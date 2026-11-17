package fmgp.yallmo.tool.scalamode

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Instant
import fmgp.rpc.{Rpc, RpcToken, RpcWebSocket}
import zio.*
import zio.json.*

/** JVM-only transport bootstrap and bounded file logging for the trusted runner. */
private[scalamode] trait ScalaToolRuntimePlatform {

  /** Optional host timing signal, independent of stdout/log budgets. Repeated evaluation keeps the first marker; absent
    * configuration or a failed write must not change the submitted program's behavior.
    */
  protected def markExecutionStarted: UIO[Unit] = ZIO.attemptBlocking {
    Option(java.lang.System.getProperty(ScalaToolRuntime.ExecutionMarkerProperty)).foreach { path =>
      Files.createFile(Path.of(path))
    }
  }.ignore

  /** Compiled bindings select their concrete contract, not a registry or reflective service type. Connection and
    * credentials remain local to this unsafe runner; scope exit closes the connection on every outcome.
    */
  def runRpc[API, E, A](contract: Rpc[API])(evaluate: API => ZIO[Any, E, A]): ZIO[Any, E | Throwable, Unit] =
    ZIO.scoped {
      for {
        endpoint <- Console.readLine
        secret <- Console.readLine
        token <- ZIO.fromEither(RpcToken.parse(secret))
        connection <- RpcWebSocket.connect(endpoint, token)
        value <- ZIO.suspendSucceed(evaluate(contract.client(connection)))
        _ <- Console.printLine(value)
      } yield ()
    }

  /** ZIOApp supplies the positive byte limit as its sole argument. No endpoint or secret is logged here. */
  val bootstrap: ZLayer[ZIOAppArgs, Throwable, Unit] =
    Runtime.removeDefaultLoggers >>> ZLayer.scoped {
      for {
        args <- ZIOAppArgs.getArgs
        limit <- ZIO
          .fromOption(args.headOption.flatMap(_.toIntOption).filter(_ > 0))
          .orElseFail(new IllegalArgumentException("Expected a positive runtime log byte limit"))
        _ <- fileLogger(Path.of(ScalaToolRuntime.LogFile), limit).build
      } yield ()
    }

  private final case class LogRecord(
      timestamp: String,
      level: String,
      fiber: String,
      message: String,
      cause: Option[String],
      annotations: Map[String, String]
  )
  private object LogRecord {
    given JsonEncoder[LogRecord] = DeriveJsonEncoder.gen[LogRecord]
  }

  /** Keeps a byte prefix plus one overflow byte so the host can detect truncation without another marker file. A
    * truncated final JSON record may be partial. Writes are synchronous, serialized, and bounded even if a snippet logs
    * forever; the scoped stream is closed when ZIOApp shuts down.
    */
  private[scalamode] def fileLogger(path: Path, maxBytes: Int): ZLayer[Any, Throwable, Unit] =
    ZLayer.scoped {
      for {
        _ <- ZIO.fail(new IllegalArgumentException("Log byte limit must be positive")).when(maxBytes <= 0)
        output <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.newOutputStream(path)))(stream =>
          ZIO.attemptBlocking(stream.close()).orDie
        )
        logger <- ZIO.succeed {
          var remaining = maxBytes.toLong + 1
          val lock = new Object
          val logger: ZLogger[String, Unit] = (_, fiber, level, message, cause, _, _, annotations) =>
            lock.synchronized {
              if remaining > 0 then {
                val text = message()
                val record = LogRecord(
                  Instant.now().toString,
                  level.label,
                  fiber.threadName,
                  if text.isEmpty && !cause.isEmpty then "Scala execution failed" else text,
                  Option.unless(cause.isEmpty)(cause.prettyPrint),
                  annotations
                )
                val bytes = (record.toJson + "\n").getBytes(UTF_8)
                val count = math.min(remaining, bytes.length.toLong).toInt
                output.write(bytes, 0, count)
                remaining -= count
              }
            }
          logger.filterLogLevel(_ >= LogLevel.Info)
        }
        _ <- Runtime.addLogger(logger).build
      } yield ()
    }
}
