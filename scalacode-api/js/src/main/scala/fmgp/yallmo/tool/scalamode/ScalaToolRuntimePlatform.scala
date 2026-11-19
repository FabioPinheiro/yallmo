package fmgp.yallmo.tool.scalamode

import fmgp.rpc.{Rpc, RpcToken, RpcWebSocketClient}
import zio.*
import zio.json.*
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

/** Node-only, explicitly enabled trusted runner infrastructure. None of these hooks are safe snippet capabilities.
  * Browser execution remains unsupported. Node itself is not an OS sandbox.
  */
private[scalamode] trait ScalaToolRuntimePlatform {
  private var nodeEnabled = false

  protected def markExecutionStarted: UIO[Unit] = ZIO.attempt {
    if nodeEnabled then
      NodeRequire("node:fs").writeFileSync(ScalaToolRuntime.ExecutionStartedFile, "", js.Dynamic.literal(flag = "wx"))
    ()
  }.ignore

  def runRpc[API, E, A](contract: Rpc[API])(evaluate: API => ZIO[Any, E, A]): ZIO[Any, E | Throwable, Unit] =
    ZIO.scoped {
      for {
        _ <- ZIO
          .fail(new UnsupportedOperationException("Scalamode RPC requires explicit Node opt-in"))
          .unless(nodeEnabled)
        input <- readRpcBootstrap
        token <- ZIO.fromEither(RpcToken.parse(input._2))
        connection <- RpcWebSocketClient.connect(input._1, token)
        value <- ZIO.suspendSucceed(evaluate(contract.client(connection)))
        _ <- Console.printLine(value)
      } yield ()
    }

  /** stdin is the trusted host pipe, not ZIO Console on JS. Read no more than two bounded ASCII lines; no credential
    * enters argv, a workspace or an exception. The parent process deadline also covers a stalled bootstrap pipe.
    */
  private def readRpcBootstrap: Task[(String, String)] = ZIO.attempt {
    val fs = NodeRequire("node:fs")
    val buffer = NodeRequire("node:buffer").Buffer.alloc(1)
    NodeRpcBootstrap.read(() => {
      val count = fs.readSync(0, buffer, 0, 1, null).asInstanceOf[Int]
      if count == 1 then buffer.readUInt8(0).asInstanceOf[Int] else -1
    })
  }

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
            val record = ScalamodeLogRecord(
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

/** Byte-at-a-time parsing bounds stdin consumption even when the peer never sends a newline. */
private[scalamode] object NodeRpcBootstrap {
  def read(readByte: () => Int): (String, String) = {
    def line(maxBytes: Int): String = {
      val output = new StringBuilder
      var complete = false
      while !complete do {
        val byte = readByte()
        if byte < 0 then throw new IllegalArgumentException("Incomplete RPC bootstrap")
        if byte == 10 then complete = true
        else if byte < 32 || byte > 126 || output.length >= maxBytes then
          throw new IllegalArgumentException("Invalid or oversized RPC bootstrap")
        else output.append(byte.toChar)
      }
      output.toString
    }
    (line(4096), line(43))
  }
}
