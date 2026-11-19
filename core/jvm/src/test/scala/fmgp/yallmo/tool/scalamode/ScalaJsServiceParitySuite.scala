package fmgp.yallmo.tool.scalamode

import fmgp.rpc.RpcWebSocket
import fmgp.yallmo.fs.FileSystem
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.scalacode.fixture.{ArithmeticRuntime, GreetingApi, GreetingRuntime}
import fmgp.yallmo.tool.*
import java.nio.file.Files
import munit.FunSuite
import zio.*

/** Real retained compiler/linker and native Node WebSocket checks, not fake process scripts. */
final class ScalaJsServiceParitySuite extends FunSuite {
  override val munitTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(15).minutes
  private def run[A](effect: Task[A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
  private def prerequisites(): Unit = assume(
    Seq(
      "YALLMO_TEST_SCALA_CLI",
      "YALLMO_TEST_NODE",
      "YALLMO_TEST_NODE_ARTIFACT_ROOT",
      "YALLMO_TEST_JS_SERVICE_ARTIFACT"
    ).forall(sys.env.contains),
    "Set pinned compiler/Node/reviewed JS root and explicit cross-published test service artifact"
  )
  private def node: ZIO[Scope, Throwable, ScalaCodeExecutor] = ScalaCodeExecutors.node(
    ScalaCodeExecutorNodeFixtures.realArtifacts,
    sys.env("YALLMO_TEST_SCALA_CLI"),
    ScalaCodeExecutorNodeFixtures.realNode
  )
  private def check(result: ExternalFunctionResult.ScalaMode, expected: String): Unit = {
    assertEquals(result.exitCode, 0, result.toString)
    assertEquals(result.output.trim, expected, result.toString)
    assert(result.executionSetupTime.nonEmpty, result.toString)
  }

  test("retained Node binds hello, exact rooted read/write views, typed errors, UTF-8 and binary streams") {
    prerequisites()
    run(ZIO.scoped {
      for {
        executor <- node
        root <- ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("js-services-")))(path =>
          ZIO.attempt {
            val files = Files.walk(path)
            try files.sorted(java.util.Comparator.reverseOrder()).forEach(p => { Files.deleteIfExists(p); () })
            finally files.close()
          }.orDie
        )
        _ <- ZIO.attempt(Files.write(root.resolve("large.bin"), Array.tabulate[Byte](100000)(n => (n % 251).toByte)))
        fs <- FileSystem.open(root.toString).mapError(error => new Exception(error.toString))
        hello <- executor.executeWithServices("hello.helloWorld", HelloWorldService, 120.seconds, 16384)
        _ <- ZIO.succeed(check(hello, "Hello world"))
        both = FilesystemService.default(fs, Some(fs))
        write <- executor.executeWithServices(
          "import fmgp.yallmo.tool.scalamode.FilesystemModes\n" +
            "for { _ <- fs.writeUtf8(\"note.txt\", \"héllo 世界\", FilesystemModes.createNew); text <- fs.readUtf8(\"note.txt\") } yield hello.helloWorld + \"|\" + text",
          both,
          120.seconds,
          16384
        )
        _ <- ZIO.succeed(check(write, "Hello world|héllo 世界"))
        read = FilesystemService.read(fs)
        binary <- executor.executeWithServices(
          "fs.readBytes(\"large.bin\").runCollect.map(bytes => bytes.size.toString + \"|\" + bytes.take(3).mkString(\",\"))",
          read,
          120.seconds,
          16384
        )
        _ <- ZIO.succeed(check(binary, "100000|0,1,2"))
        early <- executor.executeWithServices(
          "fs.readBytes(\"large.bin\").take(3).runCollect.map(_.size)",
          read,
          120.seconds,
          16384
        )
        _ <- ZIO.succeed(check(early, "3"))
        missing <- executor.executeWithServices(
          "fs.readUtf8(\"missing\").either.map(_.isLeft)",
          read,
          120.seconds,
          16384
        )
        _ <- ZIO.succeed(check(missing, "true"))
        traversal <- executor.executeWithServices(
          "fs.readUtf8(\"../outside\").either.map(_.isLeft)",
          read,
          120.seconds,
          16384
        )
        _ <- ZIO.succeed(check(traversal, "true"))
        _ <- ZIO.foreachDiscard(List("fs.deleteFile(\"note.txt\")", "fs.writeUtf8(\"note.txt\", \"bad\", null)")) {
          code =>
            executor
              .executeWithServices(code, read, 120.seconds, 16384)
              .map(result => assert(result.result.isInstanceOf[ScalaModeResult.CompilationError], result.toString))
        }
        writeOnly = FilesystemService.write(fs)
        deniedRead <- executor.executeWithServices("fs.readUtf8(\"note.txt\")", writeOnly, 120.seconds, 16384)
        _ <- ZIO.succeed(assert(deniedRead.result.isInstanceOf[ScalaModeResult.CompilationError], deniedRead.toString))
        deleted <- executor.executeWithServices("fs.deleteFile(\"note.txt\")", writeOnly, 120.seconds, 16384)
        _ <- ZIO.succeed {
          assertEquals(deleted.exitCode, 0, deleted.toString); assert(!Files.exists(root.resolve("note.txt")))
        }
        // Return values resembling generated diagnostics must remain values, on the service path too.
        literal <- executor.executeWithServices("\"Main.scala:3:1\\n3 |hello\"", HelloWorldService, 120.seconds, 16384)
        _ <- ZIO.succeed(check(literal, "Main.scala:3:1\n3 |hello"))
      } yield ()
    })
  }

  test(
    "exact selected native read functions, transformed text, partial grants and consecutive agents retain authority"
  ) {
    prerequisites()
    run(ZIO.scoped {
      for {
        executor <- node
        route <- RpcWebSocket.route()
        server <- zio.http.Server.defaultWith(_.binding("127.0.0.1", 0)).build
        port <- zio.http.Server.install(route.routes).provideEnvironment(server)
        calls <- Ref.make(Vector.empty[String])
        read = new AgentFunction[Any] {
          val definition = FilesystemToolDefinition.ReadFileDefinition
          def execute(call: ExternalFunctionCall) =
            calls.update(_ :+ call.argumentsJson).as(ExternalFunctionResult("transformed:selected-root"))
        }
        service = AgentToolWirings.readService(Seq(read), route, s"ws://127.0.0.1:$port/rpc")
        selected <- executor.executeWithServices("reads.readFile(\"allowed.txt\")", service, 120.seconds, 16384)
        _ <- ZIO.succeed(check(selected, "transformed:selected-root"))
        denied <- executor.executeWithServices("reads.listFiles(\".\")", service, 120.seconds, 16384)
        _ <- ZIO.succeed(assert(denied.result.isInstanceOf[ScalaModeResult.CompilationError], denied.toString))
        other = new AgentFunction[Any] {
          val definition = read.definition
          def execute(call: ExternalFunctionCall) = ZIO.succeed(ExternalFunctionResult("other-root"))
        }
        second <- executor.executeWithServices(
          "reads.readFile(\"allowed.txt\")",
          AgentToolWirings.readService(Seq(other), route, s"ws://127.0.0.1:$port/rpc"),
          120.seconds,
          16384
        )
        _ <- ZIO.succeed(check(second, "other-root"))
        list = new AgentFunction[Any] {
          val definition = FilesystemToolDefinition.ListFilesDefinition
          def execute(call: ExternalFunctionCall) =
            calls.update(_ :+ s"list:${call.argumentsJson}").as(ExternalFunctionResult("transformed:selected-list"))
        }
        listOnly = AgentToolWirings.readService(Seq(list), route, s"ws://127.0.0.1:$port/rpc")
        listed <- executor.executeWithServices("reads.listFiles(\"folder\")", listOnly, 120.seconds, 16384)
        _ <- ZIO.succeed(check(listed, "transformed:selected-list"))
        deniedRead <- executor.executeWithServices("reads.readFile(\"allowed.txt\")", listOnly, 120.seconds, 16384)
        _ <- ZIO.succeed(assert(deniedRead.result.isInstanceOf[ScalaModeResult.CompilationError], deniedRead.toString))
        noService <- executor.execute("reads.readFile(\"allowed.txt\")", 120.seconds, 16384)
        _ <- ZIO.succeed(assert(noService.result.isInstanceOf[ScalaModeResult.CompilationError], noService.toString))
        observed <- calls.get
        _ <- ZIO.succeed(assertEquals(observed, Vector("{\"path\":\"allowed.txt\"}", "list:{\"path\":\"folder\"}")))
      } yield ()
    })
  }

  test("custom reviewed cross-published helper and RPC artifacts are explicit, with no JVM-only fallback") {
    prerequisites()
    run(ZIO.scoped {
      for {
        executor <- node
        route <- RpcWebSocket.route()
        server <- zio.http.Server.defaultWith(_.binding("127.0.0.1", 0)).build
        port <- zio.http.Server.install(route.routes).provideEnvironment(server)
        products = ScalamodeService.Products(
          jvm = Seq(ArithmeticRuntime.getClass),
          scalaJs = Some(Seq(sys.env("YALLMO_TEST_JS_SERVICE_ARTIFACT")))
        )
        pure = new ScalamodeService {
          val apiDocs = "Reviewed custom arithmetic"
          val binding = ScalamodeService.Binding(
            "math",
            Seq("fmgp.yallmo.scalacode.fixture.Arithmetic.type"),
            "fmgp.yallmo.scalacode.fixture.ArithmeticRuntime.run",
            products = products
          )
        }
        doubled <- executor.executeWithServices("ZIO.succeed(math.twice(21))", pure, 120.seconds, 16384)
        _ <- ZIO.succeed(check(doubled, "42"))
        api = new GreetingApi { def greet(name: String) = ZIO.succeed("custom-" + name) }
        rpc = new ScalamodeService {
          val apiDocs = "Reviewed custom RPC"
          val binding = ScalamodeService.Binding(
            "greeter",
            Seq("fmgp.yallmo.scalacode.fixture.GreetingApi"),
            "fmgp.yallmo.scalacode.fixture.GreetingRuntime.run",
            products = products
          )
          override val rpcBinding =
            Some(ScalamodeService.RpcBinding(s"ws://127.0.0.1:$port/rpc", route, GreetingRuntime.contract.bind(api)))
        }
        greeted <- executor.executeWithServices("greeter.greet(\"Ada\")", rpc, 120.seconds, 16384)
        _ <- ZIO.succeed(check(greeted, "custom-Ada"))
        unsupported = new ScalamodeService {
          val apiDocs = pure.apiDocs
          val binding = pure.binding.copy(products = products.copy(scalaJs = None))
        }
        rejected <- executor.executeWithServices("42", unsupported, 120.seconds, 16384).either
        _ <- ZIO.succeed(
          assert(rejected.left.toOption.exists(_.getMessage.contains("requires reviewed Scala.js products")))
        )
      } yield ()
    })
  }
  test("Node cancellation and deadline revoke host work and finalize service authority through child exit") {
    prerequisites()
    run(ZIO.scoped {
      for {
        executor <- node
        // Warm linking separately so the short deadline measures a blocked service evaluation, not cold compilation.
        warm <- executor.execute("42", 120.seconds, 16384)
        _ <- ZIO.succeed(check(warm, "42"))
        route <- RpcWebSocket.route()
        server <- zio.http.Server.defaultWith(_.binding("127.0.0.1", 0)).build
        port <- zio.http.Server.install(route.routes).provideEnvironment(server)
        _ <- ZIO.foreachDiscard(List(false, true)) { cancel =>
          for {
            started <- Promise.make[Nothing, Unit]
            finalized <- Promise.make[Nothing, Unit]
            read = new AgentFunction[Any] {
              val definition = FilesystemToolDefinition.ReadFileDefinition
              def execute(call: ExternalFunctionCall) =
                (started.succeed(()) *> ZIO.never).ensuring(finalized.succeed(()))
            }
            service = AgentToolWirings.readService(Seq(read), route, s"ws://127.0.0.1:$port/rpc")
            fiber <- executor
              .executeWithServices(
                "reads.readFile(\"waiting\")",
                service,
                if cancel then 120.seconds else 8.seconds,
                16384
              )
              .forkScoped
            _ <- started.await.timeoutFail(new Exception("Node never reached selected service"))(30.seconds)
            _ <-
              if cancel then fiber.interrupt.unit
              else
                fiber.join.map { result =>
                  assert(result.result.isInstanceOf[ScalaModeResult.TimedOut], result.toString)
                  assert(result.executionSetupTime.nonEmpty, result.toString)
                }
            _ <- finalized.await.timeoutFail(new Exception("Invocation did not revoke host work"))(5.seconds)
          } yield ()
        }
        recovered <- executor.executeWithServices("hello.helloWorld", HelloWorldService, 120.seconds, 16384)
        _ <- ZIO.succeed(check(recovered, "Hello world"))
      } yield ()
    })
  }

}
