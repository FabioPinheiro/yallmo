package fmgp.rpc

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import zio.*

/** Linked Scala.js client -> native Node WebSocket -> actual scoped zio-http JVM host. */
class RpcNativeIntegrationSuite extends RpcReplyBeforeCloseSuite {
  protected def closeHost: ZIO[Scope, Throwable, (String, RpcToken)] = host("reply-before-close")
  private object Adapter extends RpcWebSocketClientPlatform {
    def socket(endpoint: String, token: RpcToken): ZIO[Scope, RpcError, RpcSocket] =
      open(endpoint, token, RpcWebSocketClient.Limits(maxFrameBytes = 128))
  }
  private def host(mode: String = ""): ZIO[Scope, Throwable, (String, RpcToken)] = for {
    child <- ZIO.acquireRelease(ZIO.attempt {
      val process = TestRequire("node:process")
      val env = js.Dynamic.global.Object.assign(js.Dynamic.literal(), process.env)
      js.Dynamic.global.Reflect.deleteProperty(env, "JAVA_TOOL_OPTIONS")
      js.Dynamic.global.Reflect.deleteProperty(env, "JDK_JAVA_OPTIONS")
      js.Dynamic.global.Reflect.deleteProperty(env, "_JAVA_OPTIONS")
      val classpath = env.YALLMO_RPC_TEST_CLASSPATH.asInstanceOf[String]
      if js.isUndefined(classpath) then throw new IllegalStateException("Missing sbt native RPC test host classpath")
      TestRequire("node:child_process").spawn(
        "java",
        js.Array("-cp", classpath, "fmgp.rpc.RpcNativeTestHost", mode),
        js.Dynamic.literal(env = env, stdio = js.Array("pipe", "pipe", "ignore"))
      )
    })(child => ZIO.succeed { child.stdin.end(); child.kill(); () })
    input <- ZIO
      .asyncInterrupt[Any, Throwable, (String, RpcToken)] { callback =>
        var text = ""
        var done = false
        def fail(): Unit = if !done then {
          done = true
          callback(ZIO.fail(new IllegalStateException("Native RPC test host bootstrap failed")))
        }
        val onData: js.Function1[js.Dynamic, Unit] = chunk =>
          if !done then {
            text += chunk.toString
            if text.length > 8192 then fail()
            else if text.count(_ == '\n') >= 2 then {
              val lines = text.split("\n")
              RpcToken.parse(lines(1)) match {
                case Right(token) => done = true; callback(ZIO.succeed((lines(0), token)))
                case Left(_)      => fail()
              }
              text = ""
            }
          }
        val onExit: js.Function0[Unit] = () => fail()
        child.stdout.on("data", onData)
        child.on("exit", onExit)
        child.on("error", onExit)
        Left(ZIO.succeed {
          child.stdout.removeListener("data", onData)
          child.removeListener("exit", onExit)
          child.removeListener("error", onExit)
          text = ""
        })
      }
      .timeoutFail(new IllegalStateException("Native RPC test host deadline"))(10.seconds)
  } yield input

  testZ("native adapter rejects absent or unoffered protocol echoes from an actual peer") {
    ZIO
      .foreachDiscard(List("absent-protocol", "wrong-protocol")) { mode =>
        ZIO.scoped {
          for {
            bootstrap <- host(mode)
            (endpoint, token) = bootstrap
            rejected <- RpcWebSocketClient.connect(endpoint, token).either
          } yield assertEquals(rejected.left.map(_.code), Left(RpcError.Code.Disconnected))
        }
      }
      .timeoutFail(new IllegalStateException("Protocol-echo deadline"))(20.seconds)
  }

  testZ("native adapter enforces incoming text and binary message byte limits from an actual peer") {
    ZIO
      .foreachDiscard(List("oversized-message", "oversized-binary")) { mode =>
        ZIO.scoped {
          for {
            bootstrap <- host(mode)
            (endpoint, token) = bootstrap
            connection <- RpcWebSocketClient.connect(endpoint, token, RpcWebSocketClient.Limits(maxFrameBytes = 128))
            result <- connection.call("test", 1, "ping", Chunk.empty).either
          } yield assertEquals(result.left.map(_.code), Left(RpcError.Code.LimitExceeded))
        }
      }
      .timeoutFail(new IllegalStateException("Oversized-message deadline"))(20.seconds)
  }

  testZ("native adapter bounds actual outbound bufferedAmount across consecutive sends") {
    ZIO
      .scoped {
        for {
          bootstrap <- host("reply-before-close")
          (endpoint, token) = bootstrap
          socket <- Adapter.socket(endpoint, token)
          result <- (socket.sendText("x" * 100) *> socket.sendText("x" * 100)).either
          afterOverflow <- socket.receive.either
        } yield {
          assertEquals(result, Left(RpcError(RpcError.Code.LimitExceeded)))
          assertEquals(afterOverflow, Left(RpcError(RpcError.Code.LimitExceeded)))
        }
      }
      .timeoutFail(new IllegalStateException("Outbound-buffer deadline"))(15.seconds)
  }

  testZ("native rejected upgrade terminates without recursively closing from its error callback") {
    ZIO
      .scoped {
        for {
          bootstrap <- host()
          (endpoint, _) = bootstrap
          unknown <- ZIO.fromEither(RpcToken.parse("A" * 43))
          rejected <- RpcWebSocketClient.connect(endpoint, unknown).either
        } yield assertEquals(rejected.left.map(_.code), Left(RpcError.Code.Disconnected))
      }
      .timeoutFail(new IllegalStateException("Rejected-upgrade deadline"))(15.seconds)
  }

  testZ(
    "native Scala.js client supports scoped unary, typed errors, binary streams, lazy cancellation and ticket replay rejection"
  ) {
    ZIO
      .scoped {
        for {
          bootstrap <- host()
          (endpoint, token) = bootstrap
          connection <- RpcWebSocketClient.connect(endpoint, token)
          client = RpcStreamFixtures.rpc.client(connection)
          echo <- client.ping("hé🙂")
          typed <- client.fail().runDrain.either
          // Each materialization has a fresh ID; stopping early sends cancellation and preserves other work.
          bytes = client.readBytes("x")
          first <- bytes.take(3).runCollect
          second <- bytes.runCollect
          entries <- client.entries().runCollect
          replay <- RpcWebSocketClient.connect(endpoint, token).either
          afterReplay <- client.ping("still connected")
          _ <- connection.close
          afterClose <- client.ping("closed").either
        } yield {
          assertEquals(echo, "hé🙂")
          assertEquals(typed, Left(RpcStreamFixtures.FsError.Missing("first")))
          assertEquals(first, Chunk[Byte](-128, -127, -126))
          assertEquals(second, Chunk.fromIterable((-128 to 127).map(_.toByte)))
          assertEquals(entries.map(_.name), Chunk("hello", "🙂"))
          assertEquals(replay.left.map(_.code), Left(RpcError.Code.Disconnected))
          assertEquals(afterReplay, "still connected")
          assertEquals(afterClose, Left(RpcStreamFixtures.FsError.Infra(RpcError.Code.Disconnected)))
        }
      }
      .timeoutFail(new IllegalStateException("Native integration deadline"))(25.seconds)
  }
}

@js.native
@JSGlobal("require")
private object TestRequire extends js.Object {
  def apply(module: String): js.Dynamic = js.native
}
