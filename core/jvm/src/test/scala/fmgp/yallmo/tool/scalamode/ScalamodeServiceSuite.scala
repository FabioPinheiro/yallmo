package fmgp.yallmo.tool.scalamode

import fmgp.rpc.*
import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.llm.JsonSchema
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.tool.scalamode.{NativeReadTools, ReadToolError}
import fmgp.yallmo.scalacode.fixture.{ArithmeticRuntime, GreetingApi, GreetingRuntime}
import fmgp.yallmo.tool.*
import fmgp.yallmo.fs.*
import java.nio.file.Files
import munit.FunSuite
import zio.*
import zio.http.Server
import zio.json.ast.Json

final class ScalamodeServiceSuite extends FunSuite {
  override val munitTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(10).minutes
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  // A custom subtype selecting a compiled pure helper/runner: no RPC, filesystem authority or application classpath.
  private object ArithmeticService extends ScalamodeService {
    val apiDocs = "math.twice(value: Int): Int doubles an integer. Plain values and ZIO[Any, E, A] are supported."
    val binding = ScalamodeService.Binding(
      parameter = "math",
      apiTypes = Seq("fmgp.yallmo.scalacode.fixture.Arithmetic.type"),
      runner = "fmgp.yallmo.scalacode.fixture.ArithmeticRuntime.run",
      products = ScalamodeService.Products(jvm = Seq(ArithmeticRuntime.getClass))
    )
  }

  private def call(code: String) =
    ExternalFunctionCall(ScalamodeToolDefinition.Execute, ujson.write(ujson.Obj("code" -> code)))
  private def native(definition0: FunctionToolDefinition, value: String): AgentFunction[Any] = new AgentFunction[Any] {
    val definition = definition0
    def execute(call: ExternalFunctionCall) =
      ZIO.succeed(ExternalFunctionResult(value + ":" + ujson.read(call.argumentsJson)("path").str))
  }
  private def catalog(functions: Chunk[AgentFunction[Any]], executor: ScalaCodeExecutor): Chunk[AgentTool[Any]] =
    AgentTool.group(
      Chunk(FilesystemToolDefinition, ScalamodeToolDefinition),
      functions :+ AgentFunction.provideEnvironment(new ScalaTool(), ZEnvironment(executor))
    )

  private def proxy(service: ScalamodeService): NativeReadTools = {
    given RpcErrorMapper[ReadToolError] = RpcErrorMapper(error => ReadToolError("RpcError", error.code.toString))
    val dispatcher = service.rpcBinding.get.dispatcher
    Rpc
      .derived[NativeReadTools]
      .client(new RpcConnection {
        def call(id: String, version: Int, method: String, arguments: Chunk[Json]) =
          dispatcher.dispatch(id, version, method, arguments)
        def close = ZIO.unit
      })
  }

  test("compiled binding accepts names only and builds a bounded reviewed artifact classpath") {
    val binding = ArithmeticService.binding
    for name <- List("math); evil()", "val", "_", "tool", "Trace", "math\nval x = 1") do
      intercept[IllegalArgumentException](binding.copy(parameter = name))
    for name <- List("example.Api; evil()", "example.`Api`", "example.class", "Api", "example..Api") do {
      intercept[IllegalArgumentException](binding.copy(apiTypes = Seq(name)))
      intercept[IllegalArgumentException](binding.copy(runner = name))
      intercept[IllegalArgumentException](binding.copy(errorType = name))
    }
    intercept[IllegalArgumentException](binding.copy(apiTypes = Seq.empty))
    def location(cls: Class[?]) =
      java.nio.file.Path.of(cls.getProtectionDomain.getCodeSource.getLocation.toURI).toAbsolutePath.toString
    val paths = ScalamodeProgram.serviceClasspath(binding).split(java.io.File.pathSeparator).toSet
    assertEquals(
      paths,
      Set(
        location(fmgp.yallmo.tool.scalamode.ScalaToolApi.getClass),
        location(classOf[FsRead]),
        location(classOf[RpcConnection]),
        location(classOf[YallmoError]),
        location(ArithmeticRuntime.getClass)
      )
    )
    assert(paths.contains(location(classOf[ScalaCodeExecutor])))
    assert(!paths.contains(location(ScalaCodeExecutors.getClass)))
    assertEquals(location(classOf[ScalaCodeExecutor]), location(classOf[YallmoError]))
    assertNotEquals(location(classOf[ScalaCodeExecutor]), location(classOf[ScalaTool]))
    for anchor <- List(classOf[ScalaTool], classOf[ScalaCodeExecutor]) do {
      val artifact = new java.net.URLClassLoader(
        Array(anchor.getProtectionDomain.getCodeSource.getLocation),
        ClassLoader.getPlatformClassLoader
      )
      try {
        assert(artifact.getResource(anchor.getName.replace('.', '/') + ".class") != null)
        for name <- List("scalamode/ScalaCodeExecutors", "LocalProcess", "CommandOutput") do
          assertEquals(artifact.getResource(s"fmgp/yallmo/tool/$name.class"), null)
      } finally artifact.close()
    }
    val main = ScalamodeProgram.serviceMain(binding)
    assert(main.contains(binding.runner))
    assert(main.contains("ScalaToolRuntime.evaluate(ScalaToolSnippet.result(math)"))
    assert(!main.contains("readLine"))
    assert(!main.contains("Rpc.derived"))
    assert(!main.contains("connect"))
  }

  test(
    "withServices captures a custom descriptor, uses code-only schema, and dispatches through the canonical executor capability"
  ) {
    val executor: ScalaCodeExecutor = new ScalaCodeExecutor with ScalaCodeExecutorServices {
      def isAvailable = ZIO.succeed(true)
      def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) =
        ZIO.dieMessage("wrong path")
      override def executeWithServices[R <: ScalamodeService](
          code: String,
          service: R,
          timeout: Duration,
          maxOutputBytes: Int,
          dependencies: Seq[String]
      ) = {
        assert(service eq ArithmeticService)
        ZIO.succeed(ExternalFunctionResult.ScalaMode(ScalaModeResult.Executed(code)))
      }
    }
    val tool = ScalaTool.withServices(ArithmeticService)
    assertEquals(tool.definition.name, ScalaTool.definition.name)
    assertEquals(tool.definition.inputSchema, ScalaTool.definition.inputSchema)
    assert(tool.definition.description.contains(ArithmeticService.apiDocs))
    assertEquals(
      run(tool.execute(call("math.twice(21)")).provideEnvironment(ZEnvironment[ScalaCodeExecutor](executor))).text,
      "math.twice(21)"
    )
    for args <- List(
        "{}",
        "{\"code\":42}",
        "{\"code\":\"42\",\"service\":{}}",
        "{\"code\":\"//> bad\"}",
        """{"code":"42","mode":"pure"}""",
        """{"code":"42","mode":"filesystem"}"""
      )
    do
      assert(
        run(
          tool
            .execute(call("42").copy(argumentsJson = args))
            .provideEnvironment(ZEnvironment[ScalaCodeExecutor](executor))
            .either
        ).isLeft
      )
    val legacy = new ScalaCodeExecutor {
      def isAvailable = ZIO.succeed(true)
      def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) =
        ZIO.succeed(ExternalFunctionResult.ScalaMode(ScalaModeResult.Executed("old")))
    }
    assert(
      run(legacy.executeWithServices("42", ArithmeticService, 1.second, 100).either).left.toOption.get
        .isInstanceOf[UnsupportedOperationException]
    )
  }

  test("read and list partial views delegate exact implementations and deny missing RPC methods even without facade") {
    run(ZIO.scoped {
      for {
        route <- RpcWebSocket.route()
        _ <- ZIO.foreachDiscard(
          List(FilesystemToolDefinition.ReadFileDefinition, FilesystemToolDefinition.ListFilesDefinition)
        ) { definition =>
          val selected = native(definition, "selected")
          val service = AgentToolWirings.readService(Chunk(selected), route, "ws://localhost:1/rpc")
          val client = proxy(service)
          val read = definition.name == FilesystemToolDefinition.ReadFile
          for {
            ok <- if read then client.readFile("a") else client.listFiles("a")
            denied <- (if read then client.listFiles("a") else client.readFile("a")).either
          } yield {
            assertEquals(ok, "selected:a")
            assertEquals(denied.left.toOption.get.kind, "Denied")
            assert(service.apiDocs.contains(if read then "reads.readFile" else "reads.listFiles"))
            assert(!service.apiDocs.contains(if read then "listFiles" else "readFile"))
            assert(service.binding.apiTypes.contains("fmgp.yallmo.tool.scalamode." + (if read then "NativeReadFile"
                                                                                      else "NativeListFiles")))
          }
        }
        empty = AgentToolWirings.readService(Chunk.empty, route, "ws://localhost:1/rpc")
      } yield assertEquals(empty.rpcBinding, None)
    })
  }

  test("generated arguments use exact selected schema and native failures/text are not reinterpreted") {
    run(ZIO.scoped {
      for {
        route <- RpcWebSocket.route()
        calls <- Ref.make(0)
        selectedDefinition = FilesystemToolDefinition.ReadFileDefinition.copy(inputSchema =
          JsonSchema.ObjectSchema(Map("path" -> JsonSchema.StringSchema(None, Chunk("allowed"))), Set("path"), false)
        )
        function = new AgentFunction[Any] {
          val definition = FilesystemToolDefinition.ReadFileDefinition
          def execute(call: ExternalFunctionCall) = calls.update(_ + 1) *> ZIO.fail(
            YallmoError.ToolExecutionFailed(definition.name, "NotFound(allowed)", new Exception("private cause"))
          )
        }
        restricted = new AgentFunction[Any] {
          val definition = selectedDefinition
          def execute(call: ExternalFunctionCall) = function.execute(call)
        }
        client = proxy(AgentToolWirings.readService(Chunk(restricted), route, "ws://localhost:1/rpc"))
        invalid <- client.readFile("denied").either
        count <- calls.get
        failed <- client.readFile("allowed").either
      } yield {
        assertEquals(count, 0)
        assertEquals(invalid.left.toOption.get.kind, "InvalidToolArguments")
        assertEquals(failed.left.toOption.get.kind, "ToolExecutionFailed")
        assert(failed.left.toOption.get.message.contains("NotFound(allowed)"))
        assert(!failed.left.toOption.get.message.contains("private cause"))
      }
    })
  }

  test("opt-in wiring replaces only existing scalamode and keeps two selected agent bindings isolated") {
    run(ZIO.scoped {
      for {
        route <- RpcWebSocket.route()
        captured <- Ref.make(List.empty[String])
        executor: ScalaCodeExecutor = new ScalaCodeExecutor with ScalaCodeExecutorServices {
          def isAvailable = ZIO.succeed(true)
          def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) =
            ZIO.dieMessage("wrong path")
          override def executeWithServices[R <: ScalamodeService](
              code: String,
              services: R,
              timeout: Duration,
              maxOutputBytes: Int,
              dependencies: Seq[String]
          ) =
            proxy(services)
              .readFile("x")
              .mapError(error => new Exception(error.message))
              .flatMap(value =>
                captured.update(_ :+ value).as(ExternalFunctionResult.ScalaMode(ScalaModeResult.Executed(value)))
              )
        }
        wire = AgentToolWirings.withFilesystem(executor, route, "ws://localhost:1/rpc")
        outputs <- ZIO.foreach(List("agent-a", "agent-b"))(label =>
          wire(catalog(Chunk(native(ReadFileTool.definition, label)), executor))
        )
        _ <- ZIO.foreachDiscard(outputs)(tools =>
          tools
            .flatMap(_.functions)
            .find(_.definition.name == ScalamodeToolDefinition.Execute)
            .get
            .execute(call("reads.readFile(\"x\")"))
        )
        values <- captured.get
        empty <- wire(Chunk.empty)
      } yield {
        assertEquals(values, List("agent-a:x", "agent-b:x"))
        assertEquals(empty, Chunk.empty)
        assert(
          outputs.forall(
            _.flatMap(_.functions).map(_.definition.name).toSet == Set(
              FilesystemToolDefinition.ReadFile,
              ScalamodeToolDefinition.Execute
            )
          )
        )
      }
    })
  }

  test("service engine remaps custom preambles, hides trusted setup diagnostics and retains bounds") {
    val executable = Files.createTempFile("scalamode-service-cli-", ".sh")
    try {
      assert(executable.toFile.setExecutable(true))
      val line = ScalamodeProgram.servicePreamble(ArithmeticService.binding).count(_ == '\n') + 2
      for setupFailure <- List(false, true) do {
        val diagnostics = if setupFailure then "Main.scala:3:1\n3 |private bootstrap implementation\n  |^"
        else s"Snippet.scala:$line:1\n$line |missing(value)\n  |^"
        Files.writeString(executable, "#!/bin/sh\ncat <<'END' >&2\n" + diagnostics + "\nEND\nexit 1\n")
        val result = run(
          ZIO
            .serviceWithZIO[ScalaCodeExecutor](
              _.executeWithServices("val value = 1\nmissing(value)", ArithmeticService, 2.seconds, 4096)
            )
            .provide(ScalaCodeExecutors.layer(executable.toString))
        )
        if setupFailure then {
          assertEquals(result.output, "Trusted Scalamode setup failed; contact the host.")
          assert(!result.output.contains("bootstrap implementation"))
        } else assert(result.output.contains("Snippet.scala:2:1"), result.output)
        assertEquals(result.executionTime, 0L)
      }
      Files.writeString(executable, "#!/bin/sh\nif [ \"$1\" = compile ]; then exit 0; fi\nprintf 123456789\n")
      val bounded = run(
        ZIO
          .serviceWithZIO[ScalaCodeExecutor](_.executeWithServices("42", ArithmeticService, 2.seconds, 4))
          .provide(ScalaCodeExecutors.layer(executable.toString))
      )
      assertEquals(bounded.output, "1234")
      assert(bounded.truncated)
      Files.writeString(
        executable,
        "#!/bin/sh\nif [ \"$1\" = compile ]; then printf 'Main.scala:3:1\\n3 |private bootstrap implementation\\n  |^\\n' >&2; exit 0; fi\nprintf 42\n"
      )
      val warning = run(
        ZIO
          .serviceWithZIO[ScalaCodeExecutor](_.executeWithServices("42", ArithmeticService, 2.seconds, 4096))
          .provide(ScalaCodeExecutors.layer(executable.toString))
      )
      assertEquals(warning.output, "42")
      assert(!warning.logs.mkString.contains("bootstrap implementation"))
      assert(warning.logs.mkString.contains("setup diagnostics were hidden"))

      val literalData = "Main.scala:3:1\n3 |literal file data\n"
      for emitWarnings <- List(false, true) do {
        val setupWarning = "printf 'Main.scala:3:1\\n3 |private bootstrap implementation\\n  |^\\n'"
        val compileWarning = if emitWarnings then setupWarning + "; " else ""
        val runtimeWarning = if emitWarnings then setupWarning + " >&2\n" else ""
        Files.writeString(
          executable,
          s"#!/bin/sh\nif [ \"$$1\" = compile ]; then ${compileWarning}exit 0; fi\n" +
            runtimeWarning + "cat <<'END'\n" + literalData + "END\n"
        )
        val literal = run(
          ZIO
            .serviceWithZIO[ScalaCodeExecutor](_.executeWithServices("42", ArithmeticService, 2.seconds, 4096))
            .provide(ScalaCodeExecutors.layer(executable.toString))
        )
        assertEquals(literal.output, literalData)
        assert(!literal.logs.mkString.contains("bootstrap implementation"))
        if emitWarnings then assert(literal.logs.mkString.contains("setup diagnostics were hidden"))
        else assert(literal.logs.isEmpty)
      }
    } finally Files.deleteIfExists(executable)
  }

  test("bootstrap is stdin-only, endpoint credentials are rejected, and full or truncated tickets never reach output") {
    val executable = Files.createTempFile("scalamode-bootstrap-cli-", ".sh")
    try {
      Files.writeString(
        executable,
        """#!/bin/sh
          |if [ "$1" = compile ]; then
          |  if read unexpected; then exit 9; fi
          |  exit 0
          |fi
          |read endpoint
          |read ticket
          |case "$*" in *"$ticket"*|*"$endpoint"*) exit 10;; esac
          |grep -F -e "$ticket" -e "$endpoint" Snippet.scala Main.scala && exit 11
          |printf '%s' "$ticket"
          |""".stripMargin
      )
      assert(executable.toFile.setExecutable(true))
      run(ZIO.scoped {
        for {
          route <- RpcWebSocket.route()
          executor <- ZIO.service[ScalaCodeExecutor].provide(ScalaCodeExecutors.layer(executable.toString))
          service = AgentToolWirings.readService(
            Chunk(native(ReadFileTool.definition, "selected")),
            route,
            "ws://localhost:1/rpc"
          )
          full <- executor.executeWithServices("42", service, 2.seconds, 1024)
          partial <- executor.executeWithServices("42", service, 2.seconds, 4)
          _ <- ZIO.foreachDiscard(
            List(
              "ws://user:secret@localhost/rpc",
              "ws://localhost/rpc?token=secret",
              "ws://localhost/rpc#secret",
              "ws://localhost/rpc\ninjected",
              "ws://localhost/rpc\rinjected",
              "ws://localhost/" + "x" * 2048,
              "ws://localhost/" + "é" * 1100,
              "http://localhost/rpc",
              "ws:///missing-host"
            )
          ) { endpoint =>
            val invalid =
              AgentToolWirings.readService(Chunk(native(ReadFileTool.definition, "selected")), route, endpoint)
            executor
              .executeWithServices("42", invalid, 2.seconds, 1024)
              .either
              .map(error => assert(error.left.toOption.exists(_.isInstanceOf[IllegalArgumentException])))
          }
        } yield {
          assertEquals(full.exitCode, 0, full.toString)
          assertEquals(full.output, "<rpc ticket>")
          assertEquals(partial.output, "<rpc")
          assert(partial.truncated)
        }
      })
    } finally Files.deleteIfExists(executable)
  }

  test("service launch errors, compiler errors, timeout and cancellation clean directories and scoped registrations") {
    val executable = Files.createTempFile("scalamode-service-cli-", ".sh")
    val location = Files.createTempFile("scalamode-service-location-", ".txt")
    try {
      assert(executable.toFile.setExecutable(true))
      run(ZIO.scoped {
        for {
          route <- RpcWebSocket.route(auth = RpcWebSocket.AuthConfig(maxPendingTickets = 1))
          service = AgentToolWirings.readService(
            Chunk(native(ReadFileTool.definition, "selected")),
            route,
            // Exercise the full bootstrap byte bound even when the child never consumes stdin.
            "ws://localhost/" + "x" * (2048 - "ws://localhost/".length)
          )
          _ <- ZIO.foreachDiscard(
            List("launch", "compile", "compile-timeout", "compile-cancel", "run-timeout", "run-cancel")
          ) { stage =>
            for {
              _ <- ZIO.attempt {
                Files.writeString(location, "")
                Files.writeString(
                  executable,
                  s"#!/bin/sh\n" +
                    (if stage.startsWith("run-") then "if [ \"$1\" = compile ]; then exit 0; fi\n" else "") +
                    s"{ echo $$$$; pwd; } > '$location'\n" + (if stage == "compile" then "exit 3\n" else "sleep 60\n")
                )
              }
              executor <- ZIO
                .service[ScalaCodeExecutor]
                .provide(ScalaCodeExecutors.layer(if stage == "launch" then executable.toString + ".missing"
                else executable.toString))
              task = executor.executeWithServices(
                "reads.readFile(\"x\")",
                service,
                if stage.endsWith("timeout") then 200.millis else 10.seconds,
                1024
              )
              _ <-
                if stage.endsWith("cancel") then
                  for {
                    fiber <- task.fork
                    _ <- ZIO
                      .attempt(Files.readString(location))
                      .repeat(Schedule.spaced(10.millis) && Schedule.recurWhile[String](_.linesIterator.size < 2))
                      .timeoutFail(new Exception("CLI not entered"))(3.seconds)
                      .onError(_ => fiber.interrupt.unit)
                    exit <- fiber.interrupt
                    _ <- ZIO.attempt(assert(exit.isInterrupted))
                  } yield ()
                else
                  task.either.flatMap(result =>
                    ZIO.attempt {
                      if stage == "launch" then assert(result.isLeft)
                      else if stage == "compile" then assertEquals(result.toOption.get.exitCode, 3)
                      else assert(result.toOption.get.result.isInstanceOf[ScalaModeResult.TimedOut])
                    }
                  )
              _ <- ZIO.attempt {
                val started = Files.readString(location).linesIterator.toList
                if started.nonEmpty then {
                  val process = ProcessHandle.of(started.head.toLong)
                  assert(!process.isPresent || !process.get().isAlive || !process.get().info().command().isPresent)
                  assert(!Files.exists(java.nio.file.Path.of(started(1))), started(1))
                }
              }
              // Capacity one proves the execution released its pending ticket on every exit path.
              token <- route.register(service.rpcBinding.get.dispatcher)
              _ <- route.revoke(token)
            } yield ()
          }
        } yield ()
      })
    } finally {
      Files.deleteIfExists(executable)
      Files.deleteIfExists(location)
    }
  }

  test("layer reuses one local listener with isolated scoped tickets across concurrent and sequential executions") {
    val directory = Files.createTempDirectory("scalamode-shared-listener-")
    val executable = directory.resolve("scala-cli.sh")
    Files.writeString(
      executable,
      s"""#!/bin/sh
         |if [ "$$1" = compile ]; then exit 0; fi
         |read endpoint
         |read ticket
         |name=$$(sed -n 's/.*listener-test-\\([a-z]*\\).*/\\1/p' Snippet.scala)
         |printf '%s\n%s\n' "$$endpoint" "$$ticket" > '$directory/'"$$name.tmp"
         |mv '$directory/'"$$name.tmp" '$directory/'"$$name"
         |while [ ! -f '$directory/'"$$name.release" ]; do sleep 0.01; done
         |printf 42
         |""".stripMargin
    )
    assert(executable.toFile.setExecutable(true))
    def service(value: String): ScalamodeService = new ScalamodeService {
      val apiDocs = "Test greeting"
      val binding = ScalamodeService.Binding(
        parameter = "greeter",
        apiTypes = Seq("fmgp.yallmo.scalacode.fixture.GreetingApi"),
        runner = "fmgp.yallmo.scalacode.fixture.GreetingRuntime.run",
        products = ScalamodeService.Products(jvm = Seq(classOf[GreetingApi]))
      )
      override val localRpcBinding = Some(GreetingRuntime.contract.bind(new GreetingApi {
        def greet(name: String) = ZIO.succeed(s"$value:$name")
      }))
    }
    def bootstrap(name: String): Task[(String, RpcToken)] =
      for {
        _ <- ZIO
          .attempt(Files.exists(directory.resolve(name)))
          .repeat(Schedule.spaced(10.millis) && Schedule.recurWhile[Boolean](!_))
        lines <- ZIO.attempt(Files.readAllLines(directory.resolve(name)))
        token <- ZIO.fromEither(RpcToken.parse(lines.get(1)))
      } yield (lines.get(0), token)
    def release(name: String): Task[Unit] = ZIO.attempt(Files.writeString(directory.resolve(s"$name.release"), "")).unit
    try {
      val endpoint = run(
        ZIO
          .scoped {
            for {
              environment <- ScalaCodeExecutors.layer(executable.toString).build
              executor = environment.get[ScalaCodeExecutor]
              first <- executor
                .executeWithServices("42 // listener-test-first", service("first"), 20.seconds, 1024)
                .forkScoped
              second <- executor
                .executeWithServices("42 // listener-test-second", service("second"), 20.seconds, 1024)
                .forkScoped
              a <- bootstrap("first")
              b <- bootstrap("second")
              _ <- ZIO.attempt {
                assertEquals(a._1, b._1)
                assert(a._2.exportSecret != b._2.exportSecret)
              }
              firstConnection <- RpcWebSocketClient.connect(a._1, a._2)
              secondConnection <- RpcWebSocketClient.connect(b._1, b._2)
              firstReply <- GreetingRuntime.contract.client(firstConnection).greet("Ada")
              secondReply <- GreetingRuntime.contract.client(secondConnection).greet("Ada")
              cancelled <- first.interrupt
              closed <- GreetingRuntime.contract.client(firstConnection).greet("closed").either
              surviving <- GreetingRuntime.contract.client(secondConnection).greet("still open")
              _ <- release("second")
              completed <- second.join
              third <- executor
                .executeWithServices("42 // listener-test-third", service("third"), 20.seconds, 1024)
                .forkScoped
              c <- bootstrap("third")
              _ <- release("third")
              last <- third.join
              denied <- RpcWebSocketClient.connect(c._1, c._2).either
              _ <- ZIO.attempt {
                assertEquals(firstReply, "first:Ada")
                assertEquals(secondReply, "second:Ada")
                assert(cancelled.isInterrupted)
                assert(closed.isLeft)
                assertEquals(surviving, "second:still open")
                assertEquals(completed.output, "42")
                assertEquals(last.output, "42")
                assertEquals(c._1, a._1)
                assert(c._2.exportSecret != a._2.exportSecret && c._2.exportSecret != b._2.exportSecret)
                assert(denied.isLeft, "an unclaimed ticket must be revoked when its execution ends")
              }
            } yield a._1
          }
          .timeoutFail(new Exception("shared listener test deadline"))(35.seconds)
      )
      val socket = new java.net.Socket()
      try {
        val uri = java.net.URI.create(endpoint)
        intercept[java.net.ConnectException](
          socket.connect(new java.net.InetSocketAddress(uri.getHost, uri.getPort), 1000)
        )
      } finally socket.close()
    } finally {
      val paths = Files.walk(directory)
      try {
        import scala.jdk.CollectionConverters.*
        paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists(_))
      } finally paths.close()
    }
  }

  test("real child constructs a custom non-filesystem RPC proxy from an explicit concrete contract") {
    withLive { executor =>
      ZIO.scoped {
        for {
          route <- RpcWebSocket.route()
          server <- Server.defaultWith(_.binding("127.0.0.1", 0)).build
          port <- Server.install(route.routes).provideEnvironment(server)
          greeting = new GreetingApi {
            def greet(name: String) = ZIO.succeed("Hello " + name)
          }
          service = new ScalamodeService {
            val apiDocs = "greeter.greet(name: String): Task[String] returns a greeting."
            override val rpcBinding = Some(
              ScalamodeService.RpcBinding(
                s"ws://127.0.0.1:$port/rpc",
                route,
                GreetingRuntime.contract.bind(greeting)
              )
            )
            val binding = ScalamodeService.Binding(
              parameter = "greeter",
              apiTypes = Seq("fmgp.yallmo.scalacode.fixture.GreetingApi"),
              runner = "fmgp.yallmo.scalacode.fixture.GreetingRuntime.run",
              products = ScalamodeService.Products(jvm = Seq(classOf[GreetingApi]))
            )
          }

          result <- executor.executeWithServices("greeter.greet(\"Ada\")", service, 120.seconds, 16384)
        } yield assertEquals(result.output, "Hello Ada\n", result.toString)
      }
    }
  }

  private def withLive[A](body: ScalaCodeExecutor => Task[A]): A = {
    val executable = sys.env.get("YALLMO_TEST_SCALA_CLI")
    assume(executable.nonEmpty, "Set YALLMO_TEST_SCALA_CLI for real pinned compiler / child RPC checks")
    run(ZIO.serviceWithZIO[ScalaCodeExecutor](body).provide(ScalaCodeExecutors.layer(executable.get)))
  }

  test("real pinned compiler runs the hello service inside Scalamode") {
    withLive { executor =>
      val original = AgentFunction.provideEnvironment(new ScalaTool(), ZEnvironment(executor))
      for {
        tools <- AgentToolWirings.withHelloWorld(executor)(Seq(AgentTool(ScalamodeToolDefinition, Chunk(original))))
        bound = tools.head.functions.head
        result <- bound.execute(call("hello.helloWorld"))
        effect <- bound.execute(call("ZIO.succeed(hello.helloWorld)"))
      } yield {
        assertEquals(result.text, "Hello world\n")
        assertEquals(effect.text, "Hello world\n")
        assertEquals(bound.definition.scalamode.get.services, List(HelloWorldService.inspection))
        assertEquals(tools.flatMap(_.functions.map(_.definition.name)), Seq(ScalamodeToolDefinition.Execute))
      }
    }
  }

  test("real pinned compiler runs custom pure helper values and typed effects without RPC") {
    withLive { executor =>
      for {
        _ <- ZIO.foreachDiscard(List("math.twice(21)", "ZIO.succeed(math.twice(21))")) { code =>
          executor
            .executeWithServices(code, ArithmeticService, 120.seconds, 16384)
            .map(result => assertEquals(result.output, "42\n", result.toString))
        }
        failed <- executor.executeWithServices("ZIO.fail(\"typed failure\")", ArithmeticService, 120.seconds, 16384)
      } yield {
        assertNotEquals(failed.exitCode, 0, failed.toString)
        assert(failed.output.contains("typed failure"), failed.toString)
        assert(!failed.result.isInstanceOf[ScalaModeResult.CompilationError], failed.toString)
      }
    }
  }

  test("real compiled service classpath rejects filesystem, model-adjacent and JVM ambient authority") {
    withLive { executor =>
      ZIO.scoped {
        for {
          route <- RpcWebSocket.route()
          service = AgentToolWirings.readService(
            Chunk(native(ReadFileTool.definition, "selected")),
            route,
            "ws://localhost:1/rpc"
          )
          _ <- ZIO.foreachDiscard(
            List(
              "zio.ZIO.service[fmgp.yallmo.tool.scalamode.ScalaCodeExecutor]",
              "summon[fmgp.yallmo.tool.scalamode.ScalaCodeExecutor]",
              "new fmgp.yallmo.tool.scalamode.ScalaTool().execute(null)",
              "fmgp.yallmo.tool.scalamode.ScalaTool.withServices(null)",
              "java.lang.System.getenv(\"HOME\")",
              "fmgp.yallmo.tool.scalamode.AgentToolWirings.withFilesystem(null, null, \"ws://localhost:1/rpc\")",
              "fmgp.yallmo.tool.scalamode.ScalamodeService.RpcBinding(\"ws://localhost:1/rpc\", null, null)",
              "fmgp.yallmo.tool.scalamode.ScalaTool.validate(\"1\")",
              "fmgp.yallmo.tool.scalamode.ScalaTool.executeWith(null, null)(_ => ZIO.succeed(42))",
              "fmgp.yallmo.tool.scalamode.ScalaCodeExecutors.layer()",
              "fmgp.yallmo.fs.FileSystem.open(\".\")",
              "new fmgp.yallmo.fs.FileSystem(null)",
              "java.nio.file.Files.readString(java.nio.file.Path.of(\"/etc/passwd\"))",
              "new java.lang.ProcessBuilder(\"true\").start()",
              "java.lang.Runtime.getRuntime.exec(\"true\")",
              "java.lang.Class.forName(\"java.lang.Runtime\")",
              "zio.Runtime.default",
              "zio.Unsafe",
              "fmgp.yallmo.model.AgentId.sha256(\"ambient\")",
              "fmgp.yallmo.tool.scalamode.ScalaToolRuntime.runPure(_ => ZIO.succeed(1))",
              "fmgp.yallmo.tool.scalamode.NativeReadToolsRuntime.contract",
              "fmgp.rpc.RpcWebSocketClient.connect(1, fmgp.rpc.RpcToken.parse(\"secret\").toOption.get)",
              "reads.asInstanceOf[fmgp.yallmo.tool.scalamode.NativeReadTools]",
              "reads.connection",
              "ScalaToolMain.main(Array.empty[String])"
            )
          ) { code =>
            assertEquals(ScalaTool.validate(code), Right(()), code)
            executor.executeWithServices(code, service, 120.seconds, 16384).map { result =>
              assert(result.result.isInstanceOf[ScalaModeResult.CompilationError], s"$code: $result")
              assert(result.output.contains("Snippet.scala:1:"), s"$code: $result")
              assert(
                result.output.contains("from safe code") || result.output.contains("is not a member") ||
                  result.output.contains("cannot be accessed") || result.output.contains("safe mode") ||
                  result.output.contains("No given instance"),
                s"$code: $result"
              )
            }
          }
          empty = AgentToolWirings.readService(Chunk.empty, route, "ws://localhost:1/rpc")
          pure <- executor.executeWithServices("ZIO.succeed(42)", empty, 120.seconds, 16384)
        } yield {
          assertEquals(empty.rpcBinding, None)
          assertEquals(pure.output, "42\n", pure.toString)
        }
      }
    }
  }

  test(
    "real child RPC delegates selected rooted native read/list, preserves domain failure and rejects partial facade expansion"
  ) {
    withLive { executor =>
      ZIO.scoped {
        for {
          root <- ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("scalamode-service-")))(path =>
            ZIO.attempt {
              Files.deleteIfExists(path.resolve("note.txt")); Files.deleteIfExists(path)
            }.orDie
          )
          _ <- ZIO.attempt(Files.writeString(root.resolve("note.txt"), "native contents"))
          fs <- FileSystem.open(root.toString).mapError(error => new Exception(error.toString))
          route <- RpcWebSocket.route()
          server <- Server.defaultWith(_.binding("127.0.0.1", 0)).build
          port <- Server.install(route.routes).provideEnvironment(server)
          reads = Chunk[AgentFunction[FsRead]](new ReadFileTool, new ListFilesTool).map { function =>
            new AgentFunction[Any] {
              val definition = function.definition
              def execute(call: ExternalFunctionCall) =
                function.execute(call).provideEnvironment(ZEnvironment[FsRead](fs))
            }
          }
          service = AgentToolWirings.readService(reads, route, s"ws://127.0.0.1:$port/rpc")
          wired <- AgentToolWirings.withFilesystem(executor, route, s"ws://127.0.0.1:$port/rpc")(
            catalog(reads, executor)
          )
          result <- wired
            .flatMap(_.functions)
            .find(_.definition.name == ScalamodeToolDefinition.Execute)
            .get
            .execute(
              call(
                "for { text <- reads.readFile(\"note.txt\"); entries <- reads.listFiles(\".\") } yield text + \"|\" + entries"
              )
            )
          failure <- executor.executeWithServices(
            "reads.readFile(\"missing\").fold(_.kind, identity)",
            service,
            120.seconds,
            16384
          )
          listing = AgentToolWirings.readService(reads.drop(1), route, s"ws://127.0.0.1:$port/rpc")
          listed <- executor.executeWithServices("reads.listFiles(\".\")", listing, 120.seconds, 16384)
          deniedRead <- executor.executeWithServices("reads.readFile(\"note.txt\")", listing, 120.seconds, 16384)
          partial = AgentToolWirings.readService(reads.take(1), route, s"ws://127.0.0.1:$port/rpc")
          denied <- executor.executeWithServices("reads.listFiles(\".\")", partial, 120.seconds, 16384)
          diagnostics <- executor.executeWithServices(
            "val value = 1\nreads.missing(value)",
            partial,
            120.seconds,
            16384
          )
          entered <- Promise.make[Nothing, Unit]
          finalized <- Promise.make[Nothing, Unit]
          blocking = new AgentFunction[Any] {
            val definition = ReadFileTool.definition
            def execute(call: ExternalFunctionCall) = (entered.succeed(()) *> ZIO.never).ensuring(finalized.succeed(()))
          }
          waiting = AgentToolWirings.readService(Chunk(blocking), route, s"ws://127.0.0.1:$port/rpc")
          fiber <- executor.executeWithServices("reads.readFile(\"note.txt\")", waiting, 120.seconds, 16384).fork
          _ <- entered.await
            .timeoutFail(new Exception("child did not invoke selected tool"))(60.seconds)
            .onError(_ => fiber.interrupt.unit)
          exit <- fiber.interrupt
          _ <- finalized.await.timeoutFail(new Exception("selected tool survived child cancellation"))(5.seconds)
        } yield {
          assert(exit.isInterrupted)
          assertEquals(result.text, "native contents|note.txt\n", result.toString)
          assertEquals(failure.output, "ToolExecutionFailed\n", failure.toString)
          assertEquals(listed.output, "note.txt\n", listed.toString)
          assert(deniedRead.result.isInstanceOf[ScalaModeResult.CompilationError], deniedRead.toString)
          assert(denied.result.isInstanceOf[ScalaModeResult.CompilationError], denied.toString)
          assert(diagnostics.output.contains("Snippet.scala:2:"), diagnostics.toString)
          assert(!diagnostics.output.contains("def result"), diagnostics.toString)
        }
      }
    }
  }
}
