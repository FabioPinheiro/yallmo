package fmgp.yallmo.tool

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.scalacode.{FileSystem as ScriptFileSystem, ScalaScript}
import java.nio.file.{Files, Path}
import munit.FunSuite
import zio.*

/** Public test API also visible to the external compiler. */
trait DynamicTestServices {
  def next: Task[Int]
}

final class ScalaCodeDynamicExecutorSuite extends FunSuite {
  override val munitTimeout: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.DurationInt(5).minutes

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private val environment = ZEnvironment[DynamicTestServices](new DynamicTestServices {
    override def next: Task[Int] = ZIO.succeed(42)
  })

  private def config(executable: String): ScalaCodeDynamicExecutor.Config = {
    val base = ScalaCodeDynamicExecutor.Config(executable = executable)
    val testApi = Path.of(classOf[DynamicTestServices].getProtectionDomain.getCodeSource.getLocation.toURI)
    base.copy(classpath = base.classpath + java.io.File.pathSeparator + testApi)
  }

  private def execute(
      configuration: ScalaCodeDynamicExecutor.Config,
      code: String = "ZIO.succeed(42)",
      timeout: Duration = 10.seconds,
      limit: Int = 1024
  ): Task[CommandOutput] =
    ZIO
      .serviceWithZIO[ScalaCodeDynamicExecutor](_.execute[DynamicTestServices](code, timeout, limit))
      .provideSomeLayer[DynamicTestServices](ScalaCodeDynamicExecutor.layer(configuration))
      .provideEnvironment(environment)

  private def withFakeCli(body: String)(test: Path => Unit): Unit = {
    val executable = Files.createTempFile("yallmo-fake-dynamic-scala-", ".sh")
    try {
      Files.writeString(executable, "#!/bin/sh\n" + body)
      assert(executable.toFile.setExecutable(true))
      test(executable)
    } finally Files.deleteIfExists(executable)
  }

  test("library packaging uses trusted host configuration, not a subprocess main") {
    val configuration = config("custom-scala-cli").copy(offline = true)
    val arguments = ScalaCodeDynamicExecutor.arguments(configuration)
    assertEquals(arguments.take(3), Seq("custom-scala-cli", "--power", "package"))
    assert(arguments.contains("--library"))
    assert(arguments.contains("--classpath"))
    assert(arguments.contains("--offline"))
    assert(!arguments.contains("--main-class"))
    val source = ScalaCodeDynamicExecutor.source("ZIO.succeed(42)")
    assert(source.contains("extends fmgp.yallmo.scalacode.ScalaScript {"))
    assert(source.contains("def program[R]: ZIO[R, Throwable, Any]"))
    assert(source.contains("val effect: ZIO[Nothing, Throwable, Any]"))
    assert(source.contains("ZIO.succeed(42)"))
    val multiline = "ZIO.succeed(\"\"\"\n|keep this margin\n\"\"\")"
    assert(ScalaCodeDynamicExecutor.source(multiline).contains(multiline))
  }

  test("default compiler classpath contains only the API module, not core") {
    val configuration = ScalaCodeDynamicExecutor.Config()
    val api = Path.of(classOf[ScalaScript].getProtectionDomain.getCodeSource.getLocation.toURI)
    val core = Path.of(classOf[ScalaCodeExecutor].getProtectionDomain.getCodeSource.getLocation.toURI)
    assertEquals(configuration.classpath, api.toString)
    assertNotEquals(api, core)
    assert(ScalaCodeDynamicExecutor.arguments(configuration).contains("dev.zio::zio:2.1.27"))
  }

  test("the executor and tool can be constructed without script services") {
    val configuration = config("must-not-start").copy(apiDescription = "def next: zio.Task[Int]")
    val executor = run(ZIO.service[ScalaCodeDynamicExecutor].provide(ScalaCodeDynamicExecutor.layer(configuration)))
    val tool = new ScalaDynamicTool[DynamicTestServices](configuration)
    assert(executor != null)
    assert(tool.definition.description.contains(configuration.apiDescription))
  }

  test("dynamic tools reject invalid arguments before compiling") {
    val configuration = config("must-not-start")
    val tool = new ScalaDynamicTool[DynamicTestServices](configuration)
    assertEquals(tool.definition.key, "scalaunsafe")
    for arguments <- List("{}", "[]", """{"code":1}""", """{"code":""}""", """{"code":"ZIO.unit","extra":1}""") do {
      val result = run(
        tool
          .execute(ExternalToolCall(tool.definition.key, arguments))
          .either
          .provideSomeLayer[DynamicTestServices](ScalaCodeDynamicExecutor.layer(configuration))
          .provideEnvironment(environment)
      )
      assert(result.left.toOption.exists(_.isInstanceOf[YallmoError.InvalidToolArguments]))
    }
  }

  test("invalid inputs fail before starting the compiler") {
    for (code, timeout, limit) <- List(
        (" ", 1.second, 10),
        ("//> using dep untrusted:artifact:1.0\nZIO.unit", 1.second, 10),
        ("x" * (64 * 1024 + 1), 1.second, 10),
        ("ZIO.unit", Duration.Zero, 10),
        ("ZIO.unit", 1.second, 0)
      )
    do {
      val result = run(execute(config("must-not-start"), code, timeout, limit).either)
      assert(result.left.toOption.exists(_.isInstanceOf[IllegalArgumentException]))
    }
  }

  test("compiler errors are returned and temporary sources are removed") {
    withFakeCli("test -f Snippet.scala || exit 8\npwd\nprintf 'Compilation failed\\n'\nexit 1\n") { executable =>
      val first = run(execute(config(executable.toString)))
      val second = run(execute(config(executable.toString)))
      for result <- List(first, second) do {
        assertEquals(result.exitCode, 1)
        assert(!result.timedOut)
        assert(result.output.contains("Compilation failed"))
        assert(!Files.exists(Path.of(result.output.linesIterator.next())))
      }
      assertNotEquals(first.output, second.output)
    }
  }

  test("classloading failures are returned and temporary files are removed") {
    withFakeCli("pwd\n") { executable =>
      val result = run(execute(config(executable.toString), limit = 8192))
      assertEquals(result.exitCode, 1)
      assert(result.output.contains("ClassNotFoundException"), result.output)
      assert(!Files.exists(Path.of(result.output.linesIterator.next())))
    }
  }

  test("compiler diagnostics exceeding pipe capacity are drained without an unbounded log file") {
    val body =
      s"test ! -e compiler.log || exit 9\ni=0; while [ $$i -lt 1024 ]; do printf '%s' '${"x" * 256}'; i=$$((i+1)); done\nexit 1\n"
    withFakeCli(body) { executable =>
      val result = run(execute(config(executable.toString), limit = 32))
      assertEquals(result.output, "x" * 32)
      assert(result.truncated)
    }
  }

  test("compiler stdout and stderr at the byte limit are not marked truncated") {
    withFakeCli("printf '1234'; printf '5678' >&2\nexit 1\n") { executable =>
      val result = run(execute(config(executable.toString), limit = 8))
      assertEquals(result.output, "12345678")
      assertEquals(result.exitCode, 1)
      assert(!result.truncated)
    }
  }

  test("compiler timeout terminates descendants and preserves diagnostics") {
    withFakeCli("sleep 60 &\necho $!\nwait\n") { executable =>
      val result = run(execute(config(executable.toString), timeout = 300.millis))
      assert(result.timedOut)
      assertEquals(result.exitCode, 124)
      assert(!isRunning(result.output.trim.toLong))
    }
  }

  test("fiber cancellation terminates the compiler and cleans its directory") {
    val marker = Files.createTempFile("yallmo-dynamic-started-", ".txt")
    try
      withFakeCli(s"printf '%s\\n%s\\n' $$$$ \"$$PWD\" > '$marker'\nwhile :; do :; done\n") { executable =>
        val (started, exit) = run {
          for {
            fiber <- execute(config(executable.toString), timeout = 60.seconds).fork
            started <- ZIO
              .attemptBlocking(Files.readString(marker).trim)
              .repeat(Schedule.spaced(10.millis) && Schedule.recurWhile[String](_.isEmpty))
              .timeoutFail(new RuntimeException("fake compiler did not start"))(5.seconds)
              .onError(_ => fiber.interrupt.unit)
            exit <- fiber.interrupt
          } yield (started._2.linesIterator.toList, exit)
        }
        assert(exit.isInterrupted)
        assert(!isRunning(started.head.toLong))
        assert(!Files.exists(Path.of(started(1))))
      }
    finally Files.deleteIfExists(marker)
  }

  private def isRunning(pid: Long): Boolean = {
    val process = ProcessHandle.of(pid)
    process.isPresent && process.get().isAlive && process.get().info().command().isPresent
  }

  // Opt in with YALLMO_TEST_SCALA_CLI=/absolute/path/to/scala-cli.
  private val realCli = sys.env.get("YALLMO_TEST_SCALA_CLI")
  private def realConfig: ScalaCodeDynamicExecutor.Config = config(realCli.get)

  test("real CLI loads fresh scripts and calls the same host service instance") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run compilation integration tests")
    val results = run {
      for {
        counter <- Ref.make(0)
        services = new DynamicTestServices {
          override def next: Task[Int] = counter.updateAndGet(_ + 1)
        }
        results <- ZIO
          .foreach(List("next", "next.map(_ + 10)")) { expression =>
            ZIO.serviceWithZIO[ScalaCodeDynamicExecutor](
              _.execute[DynamicTestServices](
                s"ZIO.serviceWithZIO[fmgp.yallmo.tool.DynamicTestServices](_.$expression)",
                120.seconds,
                4096
              )
            )
          }
          .provideLayer(ScalaCodeDynamicExecutor.layer(realConfig) ++ ZLayer.succeed[DynamicTestServices](services))
        count <- counter.get
      } yield (results, count)
    }
    for result <- results._1 do assertEquals(result.exitCode, 0, result.output + "\nclasspath=" + realConfig.classpath)
    assertEquals(results._2, 2)
    assert(results._1.head.output.endsWith("1"), results._1.head.output)
    assert(results._1(1).output.endsWith("12"), results._1(1).output)
  }

  test("real CLI shares one executor across agent calls but uses only each caller's selected environment") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run compilation integration tests")
    type AgentEnvironment = ScalaCodeDynamicExecutor & DynamicTestServices & Ref[String]
    val agent = new AgentInstance[AgentEnvironment] {
      val id: AgentId = AgentId("dynamic-environment-test")
      def next(
          task: AgentTask,
          context: StaticContext[AgentEnvironment],
          current: RunContext
      ): ZIO[AgentEnvironment, YallmoError, AgentTurn] =
        ZIO.dieMessage("Direct tool calls must not ask the model for a turn")
    }
    val results = run {
      (for {
        executor <- ZIO.service[ScalaCodeDynamicExecutor]
        tool = new ScalaDynamicTool[DynamicTestServices](realConfig, timeout = 120.seconds)
        runner = new AgentRunner[AgentEnvironment](Map(tool.definition.key -> tool), Map.empty)
        context = StaticContext[AgentEnvironment]("", tools = Chunk(tool.definition))
        privateState <- Ref.make("not exposed to scripts")
        results <- ZIO.foreachPar(List(7, 99)) { value =>
          val services = new DynamicTestServices {
            def next: Task[Int] = ZIO.succeed(value)
          }
          val code =
            "ZIO.serviceWithZIO[fmgp.yallmo.tool.DynamicTestServices](_.next).zip(ZIO.environment[Any].map(_.size))"
          val call = FunctionCall(
            FunctionCallId.unsafe(s"dynamic-$value"),
            tool.definition.key,
            ujson.write(ujson.Obj("code" -> code))
          )
          runner
            .execution(agent, context, AgentBudget(1, 1, 120.seconds))
            .invoke(MessagePayload.ToolCall(call))
            .provideEnvironment(
              ZEnvironment[DynamicTestServices](services)
                .add[Ref[String]](privateState)
                .add[ScalaCodeDynamicExecutor](executor)
            )
        }
      } yield results).provide(ScalaCodeDynamicExecutor.layer(realConfig))
    }
    val outputs = results.map {
      case MessagePayload.ToolOutput(result) => ujson.read(result.output)
      case other                             => fail(s"Expected a tool output, got $other")
    }
    outputs.foreach(output => assertEquals(output("exitCode").num.toInt, 0, output.toString))
    assertEquals(outputs.map(_("output").str), List("(7,1)", "(99,1)"))
  }

  test("real CLI supports an intersection of services selected at execution time") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run compilation integration tests")
    type ScriptEnvironment = DynamicTestServices & Ref[Int]
    val configuration = realConfig
    val result = run {
      (for {
        executor <- ZIO.service[ScalaCodeDynamicExecutor]
        state <- Ref.make(8)
        result <- executor
          .execute[ScriptEnvironment](
            "ZIO.serviceWithZIO[fmgp.yallmo.tool.DynamicTestServices](_.next).zip(ZIO.serviceWithZIO[Ref[Int]](_.get))",
            120.seconds,
            4096
          )
          .provideEnvironment(environment.add[Ref[Int]](state))
      } yield result).provide(ScalaCodeDynamicExecutor.layer(configuration))
    }
    assertEquals(result.exitCode, 0, result.output)
    assertEquals(result.output, "(42,8)")
  }

  test("real CLI checks ZIO and Throwable types before adapting the environment") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run compilation integration tests")
    for code <- List("42", """ZIO.fail("not a Throwable")""") do {
      val result = run(execute(realConfig, code, timeout = 120.seconds, limit = 8192))
      assertEquals(result.exitCode, 1, result.output)
      assert(!result.timedOut)
      assert(result.output.contains("Compilation failed"), result.output)
    }
  }

  test("real CLI uses each method-level environment and reports missing services without retaining them") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run compilation integration tests")
    val code = "ZIO.serviceWithZIO[fmgp.yallmo.tool.DynamicTestServices](_.next)"
    val (missing, supplied, missingAgain) = run {
      (for {
        executor <- ZIO.service[ScalaCodeDynamicExecutor]
        missing <- executor.execute[Any](code, 120.seconds, 8192)
        supplied <- executor.execute[DynamicTestServices](code, 120.seconds, 8192).provideEnvironment(environment)
        missingAgain <- executor.execute[Any](code, 120.seconds, 8192)
      } yield (missing, supplied, missingAgain)).provide(ScalaCodeDynamicExecutor.layer(realConfig))
    }
    for result <- List(missing, missingAgain) do {
      assertEquals(result.exitCode, 1, result.output)
      assert(!result.timedOut)
      assert(result.output.contains("DynamicTestServices"), result.output)
    }
    assertEquals(supplied.exitCode, 0, supplied.output)
    assertEquals(supplied.output, "42")
  }

  test("real CLI returns compilation failures, effect failures and defects") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run compilation integration tests")
    for (code, expected) <- List(
        ("doesNotExist", "Not found"),
        ("ZIO.fail(new RuntimeException(\"effect failed\"))", "effect failed"),
        ("ZIO.die(new RuntimeException(\"script defect\"))", "script defect")
      )
    do {
      val result = run(execute(realConfig, code, timeout = 120.seconds, limit = 8192))
      assertEquals(result.exitCode, 1, result.output)
      assert(!result.timedOut)
      assert(result.output.contains(expected), result.output)
    }
  }

  test("real CLI scalaunsafe executes its advertised host service through ScalaDynamicTool") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run compilation integration tests")
    val configuration = realConfig.copy(apiDescription = "def next: zio.Task[Int]")
    val result = run {
      val tool = new ScalaDynamicTool[DynamicTestServices](configuration, timeout = 120.seconds)
      assert(tool.definition.description.contains("def next: zio.Task[Int]"))
      tool
        .execute(
          ExternalToolCall(
            tool.definition.key,
            """{"code":"ZIO.serviceWithZIO[fmgp.yallmo.tool.DynamicTestServices](_.next)"}"""
          )
        )
        .provideLayer(ScalaCodeDynamicExecutor.layer(configuration) ++ ZLayer.succeedEnvironment(environment))
    }
    val json = ujson.read(result.text)
    assertEquals(json("exitCode").num.toInt, 0, result.text)
    assertEquals(json("output").str, "42")
  }

  test("real CLI uses the standalone API without exposing core implementation classes to the compiler") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run compilation integration tests")
    val host = new WorkspaceFileSystem {
      def isDirectory(path: Path): UIO[Boolean] = ZIO.succeed(path.isAbsolute)
      def readUtf8(path: Path): Task[String] = ZIO.succeed(s"host:$path")
      def list(path: Path): Task[Chunk[String]] = ZIO.succeed(Chunk(path.toString))
    }
    val configuration = ScalaCodeDynamicExecutor.Config(executable = realCli.get)
    val results = run {
      ZIO
        .foreach(
          List(
            "ZIO.serviceWithZIO[fmgp.yallmo.scalacode.FileSystem](_.readUtf8(\"example.txt\"))",
            "ZIO.succeed(fmgp.yallmo.tool.CommandOutput(0, false, \"\", false))"
          )
        )(code => ZIO.serviceWithZIO[ScalaCodeDynamicExecutor](_.execute[ScriptFileSystem](code, 120.seconds, 8192)))
        .provideLayer(
          ScalaCodeDynamicExecutor.layer(configuration) ++ ZLayer
            .succeed[ScriptFileSystem](new ScalaCodeFileSystem(host))
        )
    }
    assertEquals(results.head.exitCode, 0, results.head.output)
    assertEquals(results.head.output, "host:example.txt")
    assertEquals(results(1).exitCode, 1, results(1).output)
    assert(!results(1).timedOut)
    assert(results(1).output.contains("Compilation failed"), results(1).output)
  }

  test("real CLI returned values are bounded") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run compilation integration tests")
    val result = run(execute(realConfig, "ZIO.succeed(\"x\" * 10000)", timeout = 120.seconds, limit = 32))
    assertEquals(result.exitCode, 0, result.output)
    assertEquals(result.output, "x" * 32)
    assert(result.truncated)
  }

  test("real CLI execution timeout interrupts effects and runs their finalizers") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run compilation integration tests")
    val marker = Files.createTempFile("yallmo-dynamic-finalizer-", ".txt")
    try {
      val path = ujson.write(marker.toString)
      val code = s"""ZIO.never.ensuring(ZIO.attempt {
        java.nio.file.Files.writeString(java.nio.file.Path.of($path), "finalized")
      }.orDie)"""
      val result = run(execute(realConfig, code, timeout = 20.seconds))
      assert(result.timedOut, result.output)
      assertEquals(Files.readString(marker), "finalized", result.output)
    } finally Files.deleteIfExists(marker)
  }
}
