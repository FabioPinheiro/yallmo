package fmgp.yallmo.tool

import fmgp.rpc.*
import fmgp.yallmo.model.ScalaModeResult
import fmgp.yallmo.orchestration.ExternalToolResult
import fmgp.yallmo.fs.*
import java.nio.file.{Files, Path}
import munit.FunSuite
import zio.*
import zio.http.Server

final class ScalaFilesystemRpcSuite extends FunSuite {
  override val munitTimeout: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.DurationInt(8).minutes
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  test("FsRead RPC mapper is total and sanitized for every infrastructure code") {
    for code <- RpcError.Code.values do
      assertEquals(FsReadRpc.infrastructureErrors(RpcError(code)), FsError.IoFailure("<rpc>", s"RPC $code"))
  }

  test("filesystem compiler classpath excludes application and model artifacts") {
    val paths = ScalaCodeExecutor.filesystemClasspath.split(java.io.File.pathSeparator).toSet
    assertEquals(paths.size, 3)
    for cls <- List(classOf[ScalaCodeExecutor], classOf[fmgp.yallmo.model.YallmoError]) do
      assert(!paths.contains(Path.of(cls.getProtectionDomain.getCodeSource.getLocation.toURI).toAbsolutePath.toString))
  }

  test("filesystem tool preserves typed pure and filesystem results and rejects invalid modes and extra arguments") {
    run(ZIO.scoped {
      for {
        route <- RpcWebSocket.route()
        executor = new ScalaCodeExecutor {
          def isAvailable = ZIO.succeed(true)
          def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) =
            ZIO.succeed(ExternalToolResult.ScalaMode(ScalaModeResult.Executed("pure:" + code)))
          override def executeFilesystem(code: String, endpoint: String, timeout: Duration, maxOutputBytes: Int) = {
            assertEquals(endpoint, "ws://127.0.0.1:1/rpc")
            assertEquals(timeout, 45.seconds)
            assertEquals(maxOutputBytes, 64 * 1024)
            ZIO.succeed(ExternalToolResult.ScalaMode(ScalaModeResult.Executed("filesystem:" + code), Seq("connected")))
          }
        }
        fs = new FsRead {
          def readBytes(path: String) = zio.stream.ZStream.empty
          def list(path: String) = ZIO.succeed(Chunk.empty[String])
        }
        tool = ScalaTool.filesystem("ws://127.0.0.1:1/rpc")
        results <- ZIO.foreach(List("""{"code":"42"}""", """{"code":"42","mode":"pure"}""")) { arguments =>
          tool
            .execute(fmgp.yallmo.orchestration.ExternalToolCall(fmgp.yallmo.model.ToolKey.Scalamode, arguments))
            .provideEnvironment(ZEnvironment[ScalaCodeExecutor, FsRead, RpcWebSocket.RpcRoute](executor, fs, route))
        }
        filesystem <- tool
          .execute(
            fmgp.yallmo.orchestration.ExternalToolCall(
              fmgp.yallmo.model.ToolKey.Scalamode,
              """{"code":"42","mode":"filesystem"}"""
            )
          )
          .provideEnvironment(ZEnvironment[ScalaCodeExecutor, FsRead, RpcWebSocket.RpcRoute](executor, fs, route))
        invalid <- ZIO.foreach(
          List(
            """{"code":"42","mode":"unknown"}""",
            """{"code":"42","endpoint":"ws://elsewhere"}""",
            """{"code":"42","mode":"filesystem","token":"no"}"""
          )
        ) { arguments =>
          tool
            .execute(fmgp.yallmo.orchestration.ExternalToolCall(fmgp.yallmo.model.ToolKey.Scalamode, arguments))
            .provideEnvironment(ZEnvironment[ScalaCodeExecutor, FsRead, RpcWebSocket.RpcRoute](executor, fs, route))
            .either
        }
      } yield {
        results.foreach(result => assertEquals(result.text, "pure:42"))
        assertEquals(
          filesystem,
          ExternalToolResult.ScalaMode(ScalaModeResult.Executed("filesystem:42"), Seq("connected"))
        )
        assert(invalid.forall(_.isLeft))
      }
    })
  }

  private def withExecutor[A](body: (ScalaCodeExecutor, String, FsRead, RpcWebSocket.RpcRoute) => Task[A]): A = {
    val executable = sys.env.get("YALLMO_TEST_SCALA_CLI")
    assume(executable.nonEmpty, "Set YALLMO_TEST_SCALA_CLI for real cross-JVM RPC checks")
    run(ZIO.scoped {
      for {
        root <- ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("yallmo-rpc-read-")))(path =>
          ZIO.attempt {
            Files.deleteIfExists(path.resolve("hello.txt"))
            Files.deleteIfExists(path)
          }.orDie
        )
        _ <- ZIO.attempt(Files.writeString(root.resolve("hello.txt"), "hello"))
        fs <- FileSystem.open(root.toString).mapError(error => new Exception(error.toString))
        route <- RpcWebSocket.route()
        server <- Server.defaultWith(_.binding("127.0.0.1", 0)).build
        port <- Server.install(route.routes).provideEnvironment(server)
        executor <- ZIO.service[ScalaCodeExecutor].provide(ScalaCodeExecutor.layer(executable.get))
        result <- body(executor, s"ws://127.0.0.1:$port/rpc", fs, route)
      } yield result
    })
  }

  test("pinned nightly child authenticates via stdin and reads, lists, streams and recovers domain errors") {
    withExecutor { (executor, endpoint, fs, route) =>
      executor
        .executeFilesystem(
          """for {
          |  text <- fs.readUtf8("hello.txt")
          |  entries <- fs.list(".")
          |  bytes <- fs.readBytes("hello.txt").take(3).runCollect
          |  recovered <- fs.readUtf8("missing").fold((error: FsError) => error.toString, identity)
          |} yield text + "|" + entries.mkString(",") + "|" + bytes.map(_.toInt).mkString(",") + "|" + recovered
          |""".stripMargin,
          endpoint,
          120.seconds,
          64 * 1024
        )
        .provideEnvironment(ZEnvironment[FsRead, RpcWebSocket.RpcRoute](fs, route))
        .map { result =>
          assert(!result.timedOut && !result.truncated, result.output)
          assertEquals(result.exitCode, 0, result.output)
          assertEquals(result.output, "hello|hello.txt|104,101,108|NotFound(missing)\n")
          assert(result.logs.exists(_.contains("RpcWebSocket client connected")), result.logs.mkString("\n"))
          assert(result.logs.exists(_.contains("RpcWebSocket client disconnected")), result.logs.mkString("\n"))
          assert(!result.logs.exists(_.startsWith("WARNING:")), result.logs.mkString("\n"))
        }
    }
  }

  test("ZIOApp writes structured JSON runtime logs without contaminating the returned value") {
    withExecutor { (executor, endpoint, fs, route) =>
      executor
        .executeFilesystem("fs.list(\".\").map(_.mkString(\",\"))", endpoint, 120.seconds, 64 * 1024)
        .provideEnvironment(ZEnvironment[FsRead, RpcWebSocket.RpcRoute](fs, route))
        .map { result =>
          assertEquals(result.exitCode, 0, result.output)
          assertEquals(result.output, "hello.txt\n")
          val events = result.logs.filter(_.startsWith("{")).map(ujson.read(_))
          assert(events.exists(event => event("message").str.contains("client connected")), result.logs.mkString("\n"))
          assert(
            events.exists(event => event("message").str.contains("client disconnected")),
            result.logs.mkString("\n")
          )
          assert(events.forall(event => event("timestamp").str.nonEmpty && event("level").str.nonEmpty))
          assert(!result.truncated)
        }
    }
  }

  test("filesystem compiler diagnostics point to submitted lines, not the effect wrapper") {
    withExecutor { (executor, endpoint, fs, route) =>
      executor
        .executeFilesystem("val name = \"hello.txt\"\nfs.missing(name)", endpoint, 120.seconds, 16 * 1024)
        .provideEnvironment(ZEnvironment[FsRead, RpcWebSocket.RpcRoute](fs, route))
        .map { result =>
          assert(result.result.isInstanceOf[ScalaModeResult.CompilationError])
          assert(result.output.contains("Snippet.scala:2:"), result.output)
          assert(result.output.linesIterator.exists(_.matches("\\s*2 \\|fs\\.missing\\(name\\)")), result.output)
          assertEquals(result.logs, Seq.empty[String])
        }
    }
  }

  test("filesystem syntax diagnostics hide generated braces and point to the submitted EOF") {
    withExecutor { (executor, endpoint, fs, route) =>
      val code = "fs.list(\".\""
      executor
        .executeFilesystem(code, endpoint, 120.seconds, 16 * 1024)
        .provideEnvironment(ZEnvironment[FsRead, RpcWebSocket.RpcRoute](fs, route))
        .map { result =>
          assert(result.result.isInstanceOf[ScalaModeResult.CompilationError], result.output)
          assert(result.output.contains(s"Snippet.scala:1:${code.length}"), result.output)
          assert(result.output.contains("expected at end of submitted code"), result.output)
          assert(!result.output.contains("yallmo-scala-") && !result.output.contains("def result"), result.output)
          assert(!result.output.linesIterator.exists(_.matches("\\s*\\d+ \\|\\s*}")), result.output)
          assertEquals(result.logs, Seq.empty[String])
        }
    }
  }

  test("filesystem runtime failures are typed execution attempts with actionable diagnostics") {
    withExecutor { (executor, endpoint, fs, route) =>
      ScalaTool
        .filesystem(endpoint, timeout = 120.seconds)
        .execute(
          fmgp.yallmo.orchestration.ExternalToolCall(
            fmgp.yallmo.model.ToolKey.Scalamode,
            """{"code":"fs.readUtf8(\"missing\")","mode":"filesystem"}"""
          )
        )
        .provideEnvironment(ZEnvironment[ScalaCodeExecutor, FsRead, RpcWebSocket.RpcRoute](executor, fs, route))
        .map { result =>
          val typed = result.asInstanceOf[ExternalToolResult.ScalaMode]
          assert(typed.result.isInstanceOf[ScalaModeResult.Executed])
          assertEquals(typed.exitCode, 1)
          assert(!typed.timedOut)
          assert(typed.output.contains("NotFound(missing)"), typed.output)
          assert(typed.output.contains("Scala execution failed"), typed.output)
          assertEquals(typed.logs, Seq.empty[String])
        }
    }
  }

  test("cancelling an authenticated child finalizes host work before execution returns") {
    withExecutor { (executor, endpoint, fs, route) =>
      for {
        entered <- Promise.make[Nothing, Unit]
        finalized <- Promise.make[Nothing, Unit]
        blocking = new FsRead {
          def readBytes(path: String) = fs.readBytes(path)
          def list(path: String) = fs.list(path)
          override def readUtf8(path: String): IO[FsError, String] =
            (entered.succeed(()) *> ZIO.never).ensuring(finalized.succeed(()))
        }
        fiber <- executor
          .executeFilesystem("fs.readUtf8(\"hello.txt\")", endpoint, 120.seconds, 4096)
          .provideEnvironment(ZEnvironment[FsRead, RpcWebSocket.RpcRoute](blocking, route))
          .fork
        _ <- entered.await
          .timeoutFail(new Exception("child did not invoke host"))(60.seconds)
          .onError(_ => fiber.interrupt.unit)
        exit <- fiber.interrupt
        _ <- finalized.await.timeoutFail(new Exception("host work survived cancellation"))(5.seconds)
      } yield assert(exit.isInterrupted)
    }
  }

  test("launch, compilation failure and timeout release pending execution registrations") {
    run(ZIO.scoped {
      for {
        directory <- ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("yallmo-rpc-cli-")))(path =>
          ZIO.attempt {
            Files.deleteIfExists(path.resolve("cli"))
            Files.deleteIfExists(path)
          }.orDie
        )
        route <- RpcWebSocket.route(auth = RpcWebSocket.AuthConfig(maxPendingTickets = 1))
        fs = new FsRead {
          def readBytes(path: String) = zio.stream.ZStream.empty
          def list(path: String) = ZIO.succeed(Chunk.empty[String])
        }
        _ <- ZIO.foreachDiscard(List("missing", "failure", "timeout")) { mode =>
          for {
            _ <- ZIO.attempt {
              Files.writeString(
                directory.resolve("cli"),
                "#!/bin/sh\ncat >/dev/null\n" +
                  (if mode == "timeout" then "sleep 60\n" else "exit 7\n")
              )
              assert(directory.resolve("cli").toFile.setExecutable(true))
            }
            executor <- ZIO
              .service[ScalaCodeExecutor]
              .provide(
                ScalaCodeExecutor.layer(directory.resolve(if mode == "missing" then "absent" else "cli").toString)
              )
            result <- executor
              .executeFilesystem("fs.list(\".\")", "ws://127.0.0.1:1/rpc", 300.millis, 4096)
              .provideEnvironment(ZEnvironment[FsRead, RpcWebSocket.RpcRoute](fs, route))
              .either
            _ <- ZIO.attempt {
              if mode == "missing" then assert(result.isLeft)
              else if mode == "failure" then assertEquals(result.toOption.get.exitCode, 7)
              else assert(result.toOption.get.timedOut)
            }
            // With capacity one, successful registration proves the execution released its pending ticket.
            token <- route.register(FsReadRpc.contract.bind(fs))
            _ <- route.revoke(token)
          } yield ()
        }
      } yield ()
    })
  }

  test("expanded RPC compiler classpath rejects raw capabilities, runtime and trusted entrypoint access") {
    withExecutor { (executor, endpoint, fs, route) =>
      val forbidden = List(
        "fmgp.yallmo.fs.FileSystem.open(\".\")",
        "new fmgp.yallmo.fs.FileSystem(null)",
        "fs.asInstanceOf[fmgp.yallmo.fs.FsWrite]",
        "java.nio.file.Files.readString(java.nio.file.Path.of(\"/etc/passwd\"))",
        "fmgp.rpc.RpcWebSocket.connect(1, fmgp.rpc.RpcToken.parse(\"secret\").toOption.get)",
        "new java.net.Socket(\"example.com\", 80)",
        "java.lang.Runtime.getRuntime.exec(\"true\")",
        "zio.Runtime.default",
        "ZIO.logInfo(\"not allowed\")",
        "zio.Unsafe",
        "ScalaToolMain.main(Array.empty[String])",
        "fmgp.yallmo.scalacode.ScalaToolRuntime.bootstrap",
        "ScalaToolMain.secret",
        "ScalaToolMain.fs",
        "fs.connection"
      )
      ZIO.foreachDiscard(forbidden.zipWithIndex) { (code, index) =>
        executor
          .executeFilesystem(
            "val denied = { " + code + " }; fs.readUtf8(\"hello.txt\")",
            endpoint,
            120.seconds,
            64 * 1024
          )
          .provideEnvironment(ZEnvironment[FsRead, RpcWebSocket.RpcRoute](fs, route))
          .map { result =>
            val diagnostics = result.output
            Files.writeString(Path.of(s"/tmp/yallmo-phase2-denied-$index.log"), diagnostics)
            assert(!result.timedOut, s"probe $index timed out")
            assertNotEquals(result.exitCode, 0, s"probe $index accepted: ${result.output}")
            assert(result.result.isInstanceOf[ScalaModeResult.CompilationError])
            assert(diagnostics.contains("Compilation failed"), diagnostics)
            assertEquals(result.logs, Seq.empty[String])
            assert(
              diagnostics.contains("from safe code") || diagnostics.contains("is not a member") ||
                diagnostics.contains("cannot be accessed") || diagnostics.contains("safe mode"),
              diagnostics
            )
          }
      }
    }
  }
}
