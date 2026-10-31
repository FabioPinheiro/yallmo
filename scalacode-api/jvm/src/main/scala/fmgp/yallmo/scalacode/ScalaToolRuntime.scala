package fmgp.yallmo.scalacode

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Instant
import zio.*
import zio.json.*

/** Trusted JVM runner infrastructure, deliberately not marked safe or exposed as a snippet capability. */
object ScalaToolRuntime {
  val LogFile: String = "runtime.jsonl"

  /** ZIOApp supplies the positive byte limit as its sole argument. No endpoint or secret is logged here. */
  val bootstrap: ZLayer[ZIOAppArgs, Throwable, Unit] =
    Runtime.removeDefaultLoggers >>> ZLayer.scoped {
      for {
        args <- ZIOAppArgs.getArgs
        limit <- ZIO
          .fromOption(args.headOption.flatMap(_.toIntOption).filter(_ > 0))
          .orElseFail(new IllegalArgumentException("Expected a positive runtime log byte limit"))
        _ <- fileLogger(Path.of(LogFile), limit).build
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
  private[scalacode] def fileLogger(path: Path, maxBytes: Int): ZLayer[Any, Throwable, Unit] =
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
