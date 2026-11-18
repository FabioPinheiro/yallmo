package fmgp.yallmo.tool.scalamode

import fmgp.rpc.Rpc
import zio.*
import zio.json.*
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

/** Node-only, explicitly enabled trusted runner infrastructure. None of these hooks are safe snippet capabilities.
  * Browser bootstrap and RPC remain unsupported. Node itself is not an OS sandbox.
  */
private[scalamode] trait ScalaToolRuntimePlatform {
  private var nodeEnabled = false

  protected def markExecutionStarted: UIO[Unit] = ZIO.attempt {
    if nodeEnabled then
      NodeRequire("node:fs").writeFileSync(ScalaToolRuntime.ExecutionStartedFile, "", js.Dynamic.literal(flag = "wx"))
    ()
  }.ignore

  @scala.annotation.nowarn("msg=unused explicit parameter")
  def runRpc[API, E, A](contract: Rpc[API])(evaluate: API => ZIO[Any, E, A]): ZIO[Any, E | Throwable, Unit] =
    ZIO.fail(new UnsupportedOperationException("Scalamode RPC runner is not implemented on Scala.js"))

  /** Only the host's fresh CommonJS child receives the opt-in flag, followed by its positive log budget. */
  val bootstrap: ZLayer[ZIOAppArgs, Throwable, Unit] =
    Runtime.removeDefaultLoggers >>> ZLayer.scoped {
      for {
        appArgs <- ZIOAppArgs.getArgs
        // Scala.js main initializers receive no argv. Read Node argv only at this unsafe boundary; browsers do not
        // resolve require, and still fail explicitly. Tests/other hosts can supply ZIOAppArgs directly.
        args <- ZIO.attempt {
          if appArgs.isEmpty && js.typeOf(js.Dynamic.global.selectDynamic("process")) != "undefined" then
            Chunk.fromIterable(NodeRequire("node:process").argv.asInstanceOf[js.Array[String]].drop(2))
          else appArgs
        }
        _ <- ZIO
          .fail(
            new UnsupportedOperationException(
              "Scalamode runtime file logging is not implemented on Scala.js without Node opt-in"
            )
          )
          .unless(args.headOption.contains("--scalamode-node"))
        limit <- ZIO
          .fromOption(args.lift(1).flatMap(_.toIntOption).filter(_ > 0))
          .orElseFail(new IllegalArgumentException("Expected a positive runtime log byte limit"))
        _ <- nodeLogger(limit).build
        _ <- ZIO.acquireRelease(ZIO.succeed { nodeEnabled = true })(_ => ZIO.succeed { nodeEnabled = false })
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

  /** Synchronous Node writes keep at most the budget plus one overflow byte, just like the JVM logger. All handles are
    * scoped; log records never share stdout or the marker channel.
    */
  private def nodeLogger(maxBytes: Int): ZLayer[Any, Throwable, Unit] = ZLayer.scoped {
    for {
      fs <- ZIO.attempt(NodeRequire("node:fs"))
      buffer <- ZIO.attempt(NodeRequire("node:buffer").Buffer)
      fd <- ZIO.acquireRelease(ZIO.attempt(fs.openSync(ScalaToolRuntime.LogFile, "w")))(fd =>
        ZIO.attempt { fs.closeSync(fd); () }.orDie
      )
      logger <- ZIO.succeed {
        var remaining = maxBytes.toLong + 1L
        val logger: ZLogger[String, Unit] = (_, fiber, level, message, cause, _, _, annotations) => {
          if remaining > 0 then {
            val text = message()
            val record = LogRecord(
              new js.Date().toISOString(),
              level.label,
              fiber.threadName,
              if text.isEmpty && !cause.isEmpty then "Scala execution failed" else text,
              Option.unless(cause.isEmpty)(cause.prettyPrint),
              annotations
            )
            val bytes = buffer.from(record.toJson + "\n", "utf8")
            val count = math.min(remaining, bytes.length.asInstanceOf[Int].toLong).toInt
            fs.writeSync(fd, bytes.subarray(0, count))
            remaining -= count
          }
        }
        logger.filterLogLevel(_ >= LogLevel.Info)
      }
      _ <- Runtime.addLogger(logger).build
    } yield ()
  }
}

/** CommonJS lexical require is resolved only inside the explicitly enabled Node bootstrap, never at browser load. */
@js.native
@JSGlobal("require")
private object NodeRequire extends js.Object {
  def apply(module: String): js.Dynamic = js.native
}
