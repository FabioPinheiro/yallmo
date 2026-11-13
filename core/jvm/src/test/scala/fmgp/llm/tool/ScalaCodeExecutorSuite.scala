package fmgp.yallmo.tool

import fmgp.yallmo.model.tool.ScalamodeToolDefinition

import fmgp.yallmo.model.ScalaModeResult
import fmgp.yallmo.orchestration.ExternalFunctionResult
import fmgp.rpc.RpcWebSocket
import fmgp.yallmo.fs.{FsRead, FsError}
import java.nio.file.{Files, Path}
import munit.FunSuite
import zio.*

final class ScalaCodeExecutorSuite extends FunSuite {
  override val munitTimeout: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.DurationInt(5).minutes
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def withFakeCli(body: String, compileBody: String = "exit 0")(test: Path => Unit): Unit = {
    val executable = Files.createTempFile("yallmo-fake-scala-", ".sh")
    try {
      Files.writeString(
        executable,
        "#!/bin/sh\nif [ \"$1\" = compile ]; then\n" + compileBody + "\nexit $?\nfi\n" + body
      )
      assert(executable.toFile.setExecutable(true))
      test(executable)
    } finally Files.deleteIfExists(executable)
  }

  private def execute(
      executable: Path,
      timeout: Duration = 10.seconds,
      limit: Int = 1024
  ): ExternalFunctionResult.ScalaMode =
    run(
      ZIO
        .serviceWithZIO[ScalaCodeExecutor](_.execute("42", timeout, limit))
        .provide(ScalaCodeExecutor.layer(executable.toString))
    )

  test("timings measure compilation and execution separately in milliseconds") {
    withFakeCli("sleep 0.2; printf 42", compileBody = "sleep 0.1") { executable =>
      val result = execute(executable)
      assertEquals(result.output, "42")
      assert(result.compilationTime >= 80L, result.toString)
      assert(result.executionTime >= 180L, result.toString)
      assert(result.totalTime >= result.compilationTime + result.executionTime, result.toString)
    }
  }

  test("compilation failure records elapsed time without an execution phase") {
    withFakeCli("printf 'must not run'", compileBody = "sleep 0.1; exit 1") { executable =>
      val result = execute(executable)
      assert(result.result.isInstanceOf[ScalaModeResult.CompilationError])
      assert(result.compilationTime >= 80L, result.toString)
      assertEquals(result.executionTime, 0L)
      assert(result.totalTime >= result.compilationTime, result.toString)
    }
  }

  test("compilation timeout retains its timing and leaves execution time zero") {
    withFakeCli("printf 'must not run'", compileBody = "sleep 60") { executable =>
      val result = execute(executable, timeout = 300.millis)
      assert(result.result.isInstanceOf[ScalaModeResult.TimedOut])
      assert(result.compilationTime >= 200L, result.toString)
      assertEquals(result.executionTime, 0L)
      assert(result.totalTime >= result.compilationTime, result.toString)
    }
  }

  test("runtime failures and timeouts retain phase timings") {
    withFakeCli("sleep 0.1; exit 1", compileBody = "sleep 0.1") { executable =>
      val result = execute(executable)
      assertEquals(result.exitCode, 1)
      assert(result.compilationTime >= 80L, result.toString)
      assert(result.executionTime >= 80L, result.toString)
      assert(result.totalTime >= result.compilationTime + result.executionTime, result.toString)
    }
    withFakeCli("sleep 60") { executable =>
      val result = execute(executable, timeout = 300.millis)
      assert(result.result.isInstanceOf[ScalaModeResult.TimedOut])
      assert(result.executionTime > 0L, result.toString)
      assert(result.totalTime >= result.compilationTime + result.executionTime, result.toString)
    }
  }

  test("offline mode rejects floating Scala versions before starting the CLI") {
    for version <- List("3", "3.10", "3.nightly", "3.10.0-SNAPSHOT") do {
      val result = run(
        ZIO
          .serviceWithZIO[ScalaCodeExecutor](_.execute("2 + 3", 1.second, 1024))
          .either
          .provide(ScalaCodeExecutor.layer("must-not-start", scalaVersion = version, offline = true))
      )
      assert(result.left.toOption.exists(_.getMessage.contains("exact Scala release or full nightly version")))
    }
  }

  test("offline mode accepts exact releases and fully pinned nightlies") {
    withFakeCli("printf 5") { executable =>
      for version <- List("3.10.0", "3.10.1") do {
        val result = run(
          ZIO
            .serviceWithZIO[ScalaCodeExecutor](_.execute("2 + 3", 10.seconds, 1024))
            .provide(ScalaCodeExecutor.layer(executable.toString, scalaVersion = version, offline = true))
        )
        assertEquals(result.exitCode, 0)
        assertEquals(result.output, "5")
      }
    }
  }

  test("compiler classpath contains only the compiled API module") {
    val arguments = ScalaCodeExecutor.arguments()
    val classpath = arguments(arguments.indexOf("--classpath") + 1)
    val apiLocation =
      Path.of(fmgp.yallmo.scalacode.ScalaToolApi.getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
    val coreLocation = Path.of(classOf[ScalaCodeExecutor].getProtectionDomain.getCodeSource.getLocation.toURI)
    assertEquals(classpath, apiLocation.toAbsolutePath.toString)
    assert(Files.exists(apiLocation))
    assertNotEquals(classpath, coreLocation.toAbsolutePath.toString)
    assert(ScalaCodeExecutor.snippet("api").contains("import fmgp.yallmo.scalacode.{ScalaToolApi as tool}"))
  }

  test("CLI arguments include pinned ZIO in both offline and online modes") {
    for offline <- List(true, false) do {
      val arguments = ScalaCodeExecutor.arguments(offline = offline)
      val dependencyIndex = arguments.indexOf("--dependency")
      assert(dependencyIndex >= 0)
      assertEquals(arguments(dependencyIndex + 1), "dev.zio::zio:2.1.27")
      assertEquals(arguments.contains("--offline"), offline)
    }
  }

  test("compile and run do not suppress Scala CLI dependency-resolution diagnostics") {
    for arguments <- List(ScalaCodeExecutor.compileArguments(), ScalaCodeExecutor.arguments()) do {
      assert(!arguments.contains("--quiet"))
      assert(!arguments.contains("-q"))
    }
    // Scala CLI quiet mode suppresses its own resolution errors, unlike scalac's source diagnostics.
    withFakeCli(
      "exit 99",
      compileBody = "for arg in \"$@\"; do [ \"$arg\" = --quiet ] && exit 1; done\n" +
        "printf 'Error downloading org.scala-lang:scala3-compiler_3: pinned compiler not found in cache\\n' >&2\n" +
        "exit 1"
    ) { executable =>
      val result = execute(executable)
      assertEquals(result.exitCode, 1)
      assertEquals(
        result.result,
        ScalaModeResult.CompilationError(
          "Error downloading org.scala-lang:scala3-compiler_3: pinned compiler not found in cache"
        )
      )
      assertEquals(result.executionTime, 0L)
      assertEquals(result.logs, Seq.empty[String])
    }
  }

  test("silent compilation failure explains missing output and offline provisioning") {
    withFakeCli("exit 99", compileBody = "exit 1") { executable =>
      val result = execute(executable)
      assert(result.result.isInstanceOf[ScalaModeResult.CompilationError])
      assert(result.output.contains("No stdout/stderr was captured"))
      assert(result.output.contains("service user's Scala CLI/Coursier cache"))
      assertEquals(result.exitCode, 1)
    }
  }

  test("custom dependencies retain the mandatory ZIOApp runtime dependencies without duplicates") {
    val dependencies = Seq("dev.zio::zio-streams:2.1.27", "dev.zio::zio-json:1.1.1")
    val arguments = ScalaCodeExecutor.arguments(dependencies = dependencies)
    val actual = arguments.sliding(2).collect { case Seq("--dependency", dependency) => dependency }.toList
    assertEquals(actual, (dependencies ++ ScalaCodeExecutor.RuntimeDependencies).distinct.toList)
    assert(arguments.contains("dev.zio::zio:2.1.27"))
    assertEquals(
      ScalaCodeExecutor
        .arguments(dependencies = Seq.empty)
        .sliding(2)
        .collect { case Seq("--dependency", dependency) =>
          dependency
        }
        .toList,
      ScalaCodeExecutor.RuntimeDependencies.toList
    )
  }

  test("ScalaTool forwards additional dependencies and always includes the trusted runtime libraries") {
    withFakeCli("printf '%s\\n' \"$@\"\n") { executable =>
      for dependencies <- List(ScalaTool.DefaultDependencies, Seq("dev.zio::zio-json:1.1.1"), Seq.empty) do {
        val result = run(
          new ScalaTool(dependencies = dependencies)
            .execute(
              fmgp.yallmo.orchestration.ExternalFunctionCall(ScalamodeToolDefinition.Execute, """{"code":"42"}""")
            )
            .provide(ScalaCodeExecutor.layer(executable.toString))
        )
        val arguments = result.text.linesIterator.toList
        val actual = arguments.sliding(2).collect { case List("--dependency", dependency) => dependency }.toList
        assertEquals(actual, (dependencies ++ ScalaCodeExecutor.RuntimeDependencies).distinct.toList)
      }
    }
  }

  test("isolated temporary sources are removed after execution") {
    withFakeCli(
      "test \"$1\" = run && test \"$2\" = . || exit 8\n" +
        "test -f Snippet.scala && test -f Main.scala && test ! -f Main.sc && test ! -f ScalaToolApi.scala || exit 9\n" +
        "grep -q 'scala.language.experimental.safe' Main.scala && exit 13\n" +
        "grep -q 'extends ZIOAppDefault' Main.scala || exit 14\n" +
        "grep -q 'ScalaToolRuntime.bootstrap' Main.scala || exit 15\n" +
        "grep -E 'execution-started|System.setOut|Unsafe.unsafe|Runtime.default' Main.scala && exit 16\n" +
        "grep -q 'import fmgp.yallmo.scalacode.{ScalaToolApi as tool}' Snippet.scala || exit 10\n" +
        "grep -q 'import tool.\\*' Snippet.scala || exit 11\npwd\n"
    ) { executable =>
      val first = execute(executable)
      val second = execute(executable)
      for result <- List(first, second) do {
        assertEquals(result.exitCode, 0)
        assert(!Files.exists(Path.of(result.output.trim.linesIterator.toList.last)))
      }
      assertNotEquals(first.output.trim, second.output.trim)
    }
  }

  test("filesystem mode uses a separate trusted runner and safe effect snippet") {
    withFakeCli(
      "test \"$1\" = run && test \"$2\" = . || exit 8\n" +
        "test -f Snippet.scala && test -f Main.scala && test ! -f Main.sc || exit 9\n" +
        "grep -q 'scala.language.experimental.safe' Snippet.scala || exit 11\n" +
        "grep -q 'def result(fs: FsRead)' Snippet.scala || exit 12\n" +
        "grep -q 'RpcWebSocket.connect' Main.scala || exit 13\n" +
        "grep -q 'scala.language.experimental.safe' Main.scala && exit 14\n" +
        "grep -q 'extends ZIOAppDefault' Main.scala || exit 15\n" +
        "grep -q 'ScalaToolRuntime.bootstrap' Main.scala || exit 16\n" +
        "grep -E 'execution-started|System.setOut|Unsafe.unsafe|Runtime.default' Main.scala && exit 17\n" +
        "read endpoint && read secret || exit 18\n" +
        "test \"$endpoint\" = ws://127.0.0.1:1/rpc && test -n \"$secret\" || exit 19\n" +
        "case \"$*\" in *\"$secret\"*) exit 20;; esac\n" +
        "printf 'filesystem runner ready\\n'\n",
      compileBody = "if read unexpected; then exit 21; fi\nexit 0"
    ) { executable =>
      run(ZIO.scoped {
        for {
          route <- RpcWebSocket.route()
          executor <- ZIO.service[ScalaCodeExecutor].provide(ScalaCodeExecutor.layer(executable.toString))
          result <- executor
            .executeFilesystem("fs.list(\".\")", "ws://127.0.0.1:1/rpc", 10.seconds, 1024)
            .provideEnvironment(ZEnvironment[FsRead, RpcWebSocket.RpcRoute](unusedFilesystem, route))
          _ <- ZIO.attempt {
            assertEquals(result.exitCode, 0, result.output)
            assertEquals(result.output, "filesystem runner ready\n")
          }
        } yield ()
      })
    }
  }

  test("diagnostics use submitted pure-code lines without changing columns, source text or carets") {
    val diagnostics =
      "-- [E006] Not Found Error: /tmp/Snippet.scala:7:3 --\n" +
        "7 |  missing\n  |  ^^^^^^^\n  |  Not found: missing\n"
    assertEquals(
      ScalaCodeExecutor.remapDiagnostics(diagnostics, "val number = 1\n  missing", filesystem = false),
      "-- [E006] Not Found Error: Snippet.scala:2:3 --\n" +
        "2 |  missing\n  |  ^^^^^^^\n  |  Not found: missing\n"
    )
  }

  test("filesystem diagnostics account for their own preamble and preserve gutter alignment") {
    val code = "val a = 1\nval b = 2\nmissing"
    assertEquals(
      ScalaCodeExecutor.remapDiagnostics("Snippet.scala:10:1\n10 |missing\n   |^^^^^^^", code, filesystem = true),
      "Snippet.scala:3:1\n 3 |missing\n   |^^^^^^^"
    )
  }

  test("diagnostic mapping hides generated snippet setup but preserves other sources and excerpt contents") {
    val diagnostics =
      "Snippet.scala:2:1\n2 |trusted import\n" +
        "Main.scala:7:1\n7 |trusted runner\n" +
        "Other.scala:7:1\n7 |unrelated source\n" +
        "Snippet.scala:6:1\n6 |\"Snippet.scala:6:1\"\n" +
        "Snippet.scala:999999999999:1\n"
    assertEquals(
      ScalaCodeExecutor.remapDiagnostics(diagnostics, "\"Snippet.scala:6:1\"", filesystem = false),
      "<executor setup>\n" +
        "Main.scala:7:1\n7 |trusted runner\n" +
        "Other.scala:7:1\n7 |unrelated source\n" +
        "Snippet.scala:1:1\n1 |\"Snippet.scala:6:1\"\n" +
        "<executor setup>\n"
    )
  }

  test("syntax errors in generated closing braces point to submitted EOF without displaying the braces") {
    val raw = "-- [E040] Syntax Error: /tmp/Snippet.scala:7:2 --\n7 |  }\n  |  ^\n  | ')' expected, but '}' found\n"
    assertEquals(
      ScalaCodeExecutor.remapDiagnostics(raw, "List(1, 2", filesystem = false),
      "-- [E040] Syntax Error: Snippet.scala:1:9 --\n1 |List(1, 2\n  |         ^\n" +
        "  | ')' expected at end of submitted code\n"
    )
    assertEquals(
      ScalaCodeExecutor.remapDiagnostics("Snippet.scala:8:2\n8 |  }\n  |  ^", "List(1,\n", filesystem = false),
      "Snippet.scala:2:0\n2 |\n  |^"
    )
    assertEquals(
      ScalaCodeExecutor.remapDiagnostics("Snippet.scala:7:2\n7 |  }\n  |  ^", "\tList(1, 2", filesystem = false),
      "Snippet.scala:1:10\n1 |\tList(1, 2\n  |\t         ^"
    )
  }

  test("filesystem syntax errors and final-newline errors use the end of the submitted body") {
    val raw = "Snippet.scala:12:1\n11 |}\n   | ^\n   | '}' expected, but eof found"
    assertEquals(
      ScalaCodeExecutor.remapDiagnostics(raw, "val n = 1\nif true then {\nn", filesystem = true),
      "Snippet.scala:3:1\n 3 |n\n   | ^\n   | '}' expected at end of submitted code"
    )
  }

  test("generated setup and closing-brace context is omitted from body diagnostics") {
    val raw = "Snippet.scala:6:0\n5 |  def result: Any = {\n  |  ^\n6 |missing\n  |^^^^^^^\n7 |  }\n  |  ^\n"
    assertEquals(
      ScalaCodeExecutor.remapDiagnostics(raw, "missing", filesystem = false),
      "Snippet.scala:1:0\n1 |missing\n  |^^^^^^^\n"
    )
  }

  test("temporary source paths including spaces and Windows drives are hidden") {
    for path <- List("/tmp/a b/Snippet.scala", "C:\\temp dir\\Snippet.scala", "/Snippet.scala") do
      assertEquals(
        ScalaCodeExecutor.remapDiagnostics(s"-- Error: $path:6:4 --\n6 |missing", "missing", filesystem = false),
        "-- Error: Snippet.scala:1:4 --\n1 |missing"
      )
  }

  test("formatted EOF excerpts still obey the shared result and logs byte budget") {
    val code = "(" + (" " * 1000)
    val command = CommandOutput(1, false, "", false, Seq("Snippet.scala:7:2", "7 |  }", "  |  ^"))
    val displayed =
      ScalaCodeExecutor.displayDiagnostics(command, code, filesystem = false, compiled = false, maxOutputBytes = 64)
    assert(displayed.truncated)
    assert(
      displayed.output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length +
        displayed.logs.mkString("\n").getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 64
    )
    assert(!displayed.logs.mkString("\n").contains("  }"))
  }

  test("runtime stack frames use submitted lines but unrelated frames stay unchanged") {
    val stack = "at ScalaToolSnippet$.result(Snippet.scala:6)\nat Trusted.main(Main.scala:6)\n"
    assertEquals(
      ScalaCodeExecutor.remapDiagnostics(stack, "1 / 0", filesystem = false),
      "at ScalaToolSnippet$.result(Snippet.scala:1)\nat Trusted.main(Main.scala:6)\n"
    )
  }

  test("source-position text in the returned value is not remapped") {
    withFakeCli("printf 'Snippet.scala:6:1\\n'\n") { executable =>
      assertEquals(execute(executable).output, "Snippet.scala:6:1\n")
    }
  }

  test("successful stderr logs preserve repeated events and text matching the returned value") {
    withFakeCli("printf '42'; printf 'connected\\n42\\nconnected\\n' >&2\n") { executable =>
      val result = execute(executable)
      assertEquals(result.output, "42")
      assertEquals(result.logs, Seq("connected", "42", "connected"))
      assert(!result.truncated)
    }
  }

  test("compiler stderr errors remain visible in the tool result") {
    withFakeCli(
      "exit 99",
      compileBody = "printf '[E006] Not Found Error: Snippet.scala:6:1\\nNot found: missing\\n' >&2\nexit 1\n"
    ) { executable =>
      val result = run(
        new ScalaTool()
          .execute(
            fmgp.yallmo.orchestration.ExternalFunctionCall(ScalamodeToolDefinition.Execute, """{"code":"missing"}""")
          )
          .provide(ScalaCodeExecutor.layer(executable.toString))
      )
      val typed = result.asInstanceOf[ExternalFunctionResult.ScalaMode]
      assertEquals(typed.exitCode, 1)
      assert(typed.result.isInstanceOf[ScalaModeResult.CompilationError])
      assert(typed.output.contains("[E006] Not Found Error"))
      assert(typed.output.contains("Not found: missing"))
      assertEquals(
        typed.output,
        "[E006] Not Found Error: Snippet.scala:1:1\nNot found: missing"
      )
      assertEquals(typed.logs, Seq.empty[String])
      assert(!typed.result.isInstanceOf[ScalaModeResult.TimedOut] && !typed.truncated)
    }
  }

  test("runtime failure stays an execution result even when logs are exhausted, without a marker") {
    withFakeCli("i=0; while [ $i -lt 1000 ]; do printf x >&2; i=$((i+1)); done\nexit 1\n") { executable =>
      val result = execute(executable, limit = 32)
      assert(result.result.isInstanceOf[ScalaModeResult.Executed])
      assert(result.truncated)
      assertEquals(result.output, "x" * 32)
      assertEquals(result.logs, Seq.empty[String])
      assertEquals(result.exitCode, 1)
      assert(!result.result.isInstanceOf[ScalaModeResult.TimedOut])
      assert(!result.output.contains("execution-started"))
    }
  }

  test("compile-phase failure and runtime failure have distinct typed results without a marker") {
    withFakeCli("exit 99", compileBody = "printf 'Compiler dependency unavailable' >&2\nexit 1\n") { executable =>
      assertEquals(execute(executable).result, ScalaModeResult.CompilationError("Compiler dependency unavailable"))
    }
    withFakeCli("printf 'Runtime failure' >&2\nexit 1\n") { executable =>
      assertEquals(execute(executable).result, ScalaModeResult.Executed("Runtime failure"))
    }
  }

  test("compile failure remains a compilation error after its diagnostic budget is exhausted") {
    val failure = "i=0; while [ $i -lt 1000 ]; do printf x >&2; i=$((i+1)); done\nexit 1"
    withFakeCli("printf 'must not run'", compileBody = failure) { executable =>
      val result = execute(executable, limit = 32)
      assertEquals(result.result, ScalaModeResult.CompilationError("x" * 32))
      assert(result.truncated)
      assertEquals(result.logs, Seq.empty[String])
    }
  }

  test("compile and run share one output budget and one deadline") {
    withFakeCli("printf abcdef", compileBody = "printf 123456 >&2") { executable =>
      val result = execute(executable, limit = 8)
      assertEquals(result.output, "ab")
      assertEquals(result.logs, Seq("123456"))
      assert(result.truncated)
    }
    withFakeCli("printf 'running\\n'; sleep 60", compileBody = "sleep 0.4") { executable =>
      val start = java.lang.System.nanoTime()
      val result = execute(executable, timeout = 700.millis)
      assert(result.result.isInstanceOf[ScalaModeResult.TimedOut])
      assert(result.output.contains("running"), result.output)
      assert(java.lang.System.nanoTime() - start < 1500.millis.toNanos)
    }
  }

  test("JSON runtime file logs are separate from the result and share its byte budget") {
    val json = "{\"level\":\"INFO\",\"message\":\"hello\"}"
    withFakeCli(s"printf '42\\n'\nprintf '%s\\n' '$json' > runtime.jsonl") { executable =>
      val result = execute(executable)
      assertEquals(result.output, "42\n")
      assertEquals(result.logs, Seq(json))
      assert(!result.truncated)
      val limited = execute(executable, limit = 8)
      assertEquals(limited.output, "42\n")
      assert(limited.truncated)
      assert(
        limited.output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length +
          limited.logs.mkString("\n").getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 8
      )
    }
  }

  test("stderr is drained with the same shared byte budget as the result") {
    withFakeCli("i=0; while [ $i -lt 10000 ]; do printf x >&2; i=$((i+1)); done\n") { executable =>
      val result = execute(executable, limit = 32)
      assertEquals(result.output, "")
      assertEquals(result.logs, Seq("x" * 32))
      assert(result.truncated)
    }
  }

  test("output is drained but retained bytes are bounded") {
    withFakeCli("i=0; while [ $i -lt 10000 ]; do printf x; i=$((i+1)); done\nprintf error >&2\n") { executable =>
      val result = execute(executable, limit = 32)
      assertEquals(result.exitCode, 0)
      assertEquals(result.output.length + result.logs.mkString.length, 32)
      assert(result.output.forall(_ == 'x'))
      assert(result.truncated)
    }
  }

  test("CLI exit does not wait for EOF from a descendant holding stdout open") {
    withFakeCli("sleep 10 &\necho $!\nexit 0\n") { executable =>
      val started = java.lang.System.nanoTime()
      val result = execute(executable)
      try {
        assertEquals(result.exitCode, 0)
        assert(!result.result.isInstanceOf[ScalaModeResult.TimedOut])
        assert(java.lang.System.nanoTime() - started < 2.seconds.toNanos, "output drain waited for inherited stdout")
      } finally {
        val child = ProcessHandle.of(result.output.trim.toLong)
        if child.isPresent then child.get().destroyForcibly()
      }
    }
  }

  test("tool returns a diagnostic before the outer agent deadline when CLI stalls") {
    withFakeCli("printf 'Checking compiler metadata\\n'; printf 'Compiler stalled\\n' >&2; sleep 60\n") { executable =>
      val result = run(
        new ScalaTool(timeout = 300.millis)
          .execute(
            fmgp.yallmo.orchestration.ExternalFunctionCall(ScalamodeToolDefinition.Execute, """{"code":"2 + 3"}""")
          )
          .provide(ScalaCodeExecutor.layer(executable.toString))
          .timeoutFail(new RuntimeException("tool did not return within the agent budget"))(3.seconds)
      )
      val typed = result.asInstanceOf[ExternalFunctionResult.ScalaMode]
      assert(typed.result.isInstanceOf[ScalaModeResult.TimedOut])
      assert(typed.output.contains("Checking compiler metadata"))
      assert(typed.output.contains("deadline"))
      assert(typed.output.contains("Compiler stalled"))
      assertEquals(typed.logs, Seq.empty[String])
      assertNotEquals(typed.exitCode, 0)
      assert(!typed.truncated)
    }
  }

  test("timeout terminates the CLI and its child process") {
    withFakeCli("sleep 60 &\necho $!\nwait\n") { executable =>
      val result = execute(executable, timeout = 300.millis)
      assert(result.result.isInstanceOf[ScalaModeResult.TimedOut])
      assert(!isRunning(result.output.trim.linesIterator.toList.last.toLong))
    }
  }

  private val unusedFilesystem = new FsRead {
    def readBytes(path: String): zio.stream.ZStream[Any, FsError, Byte] =
      zio.stream.ZStream.dieMessage("Unexpected read")
    def list(path: String): IO[FsError, Chunk[String]] = ZIO.dieMessage("Unexpected list")
  }

  test(
    "filesystem bootstrap rejects multiline, oversized UTF-8 and non-WebSocket endpoints before registration or launch"
  ) {
    val marker = Files.createTempFile("yallmo-bootstrap-launch-", ".txt")
    try
      withFakeCli(s"echo launched > '$marker'\nexit 9\n") { executable =>
        run(ZIO.scoped {
          for {
            route <- RpcWebSocket.route(auth = RpcWebSocket.AuthConfig(maxPendingTickets = 1))
            occupied <- route.register(FsReadRpc.contract.bind(unusedFilesystem))
            executor <- ZIO.service[ScalaCodeExecutor].provide(ScalaCodeExecutor.layer(executable.toString))
            _ <- ZIO.foreachDiscard(
              List(
                "ws://127.0.0.1/rpc\ninjected",
                "ws://127.0.0.1/rpc\rinjected",
                "ws://127.0.0.1/" + "x" * 2048,
                "ws://127.0.0.1/" + "é" * 1100,
                "http://127.0.0.1/rpc",
                "ws:///missing-host"
              )
            ) { endpoint =>
              executor
                .executeFilesystem("fs.list(\".\")", endpoint, 300.millis, 1024)
                .provideEnvironment(ZEnvironment[FsRead, RpcWebSocket.RpcRoute](unusedFilesystem, route))
                .either
                .map(result =>
                  assert(result.left.toOption.exists(_.isInstanceOf[IllegalArgumentException]), result.toString)
                )
            }
            _ <- route.revoke(occupied)
            _ <- ZIO.attempt(assertEquals(Files.size(marker), 0L, "Invalid endpoints must not launch the CLI"))
          } yield ()
        })
      }
    finally Files.deleteIfExists(marker)
  }

  test(
    "non-reading filesystem bootstrap obeys the deadline and cancellation, releasing process, sources and registration"
  ) {
    val marker = Files.createTempFile("yallmo-bootstrap-started-", ".txt")
    try
      withFakeCli(s"{ echo $$$$; pwd; } > '$marker'\npwd\nwhile :; do :; done\n") { executable =>
        run(ZIO.scoped {
          for {
            route <- RpcWebSocket.route(auth = RpcWebSocket.AuthConfig(maxPendingTickets = 1))
            executor <- ZIO.service[ScalaCodeExecutor].provide(ScalaCodeExecutor.layer(executable.toString))
            // Exercise the full accepted byte bound without relying on the child to consume any stdin.
            prefix = "ws://127.0.0.1/"
            endpoint = prefix + "x" * (2048 - prefix.length)
            execute = (timeout: Duration) =>
              executor
                .executeFilesystem("fs.list(\".\")", endpoint, timeout, 1024)
                .provideEnvironment(ZEnvironment[FsRead, RpcWebSocket.RpcRoute](unusedFilesystem, route))
            result <- execute(300.millis).timeoutFail(new Exception("bootstrap deadline was bypassed"))(10.seconds)
            _ <- ZIO.attempt {
              assert(result.result.isInstanceOf[ScalaModeResult.TimedOut])
              assert(!isRunning(Files.readString(marker).linesIterator.next().toLong))
              assert(!Files.exists(Path.of(result.output.trim.linesIterator.toList.last)))
              Files.writeString(marker, "")
            }
            token <- route.register(FsReadRpc.contract.bind(unusedFilesystem))
            _ <- route.revoke(token)
            fiber <- execute(60.seconds).fork
            _ <- ZIO
              .attempt(Files.readString(marker))
              .repeat(Schedule.spaced(10.millis) && Schedule.recurWhile[String](_.linesIterator.size < 2))
              .timeoutFail(new Exception("non-reading child did not start"))(5.seconds)
              .onError(_ => fiber.interrupt.unit)
            exit <- fiber.interrupt.timeoutFail(new Exception("bootstrap cancellation did not finalize"))(10.seconds)
            _ <- ZIO.attempt {
              assert(exit.isInterrupted)
              val started = Files.readString(marker).linesIterator.toList
              assert(!isRunning(started.head.toLong))
              assert(!Files.exists(Path.of(started(1))))
            }
            next <- route.register(FsReadRpc.contract.bind(unusedFilesystem))
            _ <- route.revoke(next)
          } yield ()
        })
      }
    finally Files.deleteIfExists(marker)
  }

  private def isRunning(pid: Long): Boolean = {
    val process = ProcessHandle.of(pid)
    // ProcessHandle.isAlive may report true for a killed zombie awaiting reaping.
    process.isPresent && process.get().isAlive && process.get().info().command().isPresent
  }

  test("cancelling compilation kills its process before removing the sources") {
    val marker = Files.createTempFile("yallmo-compile-started-", ".txt")
    try {
      withFakeCli("exit 99", compileBody = s"{ echo $$$$; pwd; } > '$marker'\nwhile :; do :; done") { executable =>
        val exit = run(for {
          fiber <- ZIO
            .serviceWithZIO[ScalaCodeExecutor](_.execute("42", 60.seconds, 1024))
            .provide(ScalaCodeExecutor.layer(executable.toString))
            .fork
          _ <- ZIO
            .attempt(Files.readString(marker))
            .repeat(Schedule.spaced(10.millis) && Schedule.recurWhile[String](_.linesIterator.size < 2))
            .timeoutFail(new RuntimeException("compiler did not start"))(5.seconds)
            .onError(_ => fiber.interrupt.unit)
          exit <- fiber.interrupt
        } yield exit)
        assert(exit.isInterrupted)
        val lines = Files.readString(marker).linesIterator.toList
        assert(!isRunning(lines.head.toLong))
        assert(!Files.exists(Path.of(lines(1))))
      }
    } finally Files.deleteIfExists(marker)
  }

  test("fiber cancellation tears down the process before cleaning temporary files") {
    val marker = Files.createTempFile("yallmo-scala-started-", ".txt")
    try
      withFakeCli(s"echo $$$$ > '$marker'\nwhile :; do :; done\n") { executable =>
        val (pid, result) = run {
          for {
            fiber <- ZIO
              .serviceWithZIO[ScalaCodeExecutor](_.execute("42", 60.seconds, 1024))
              .provide(ScalaCodeExecutor.layer(executable.toString))
              .fork
            started <- ZIO
              .attemptBlocking(Files.readString(marker).trim)
              .repeat(Schedule.spaced(10.millis) && Schedule.recurWhile[String](_.isEmpty))
              .timeoutFail(new RuntimeException("fake CLI did not start"))(5.seconds)
              .onError(_ => fiber.interrupt.unit)
            exit <- fiber.interrupt
          } yield (started._2.toLong, exit)
        }
        assert(result.isInterrupted)
        assert(!isRunning(pid))
      }
    finally Files.deleteIfExists(marker)
  }

  // Opt in with YALLMO_TEST_SCALA_CLI=/absolute/path/to/scala-cli; the pinned compiler must already be cached.
  private val realCli = sys.env.get("YALLMO_TEST_SCALA_CLI")
  private val realVersion = sys.env.getOrElse("YALLMO_TEST_SCALA_VERSION", ScalaCodeExecutor.ScalaVersion)
  private val realOffline = sys.env.getOrElse("YALLMO_TEST_SCALA_OFFLINE", "true") == "true"

  // Phase 1 only: compile against the actual filesystem artifact, but inject no host/RPC service.
  private def evaluateFsSnippet(code: String, label: String): CommandOutput = {
    assertEquals(ScalaTool.validate(code), Right(()))
    val directory = Files.createTempDirectory("yallmo-fs-feasibility-")
    val log = Path.of(s"/tmp/yallmo-phase1-$label.log")
    try {
      Files.writeString(
        directory.resolve("Snippet.scala"),
        "import scala.language.experimental.safe\n" +
          "import fmgp.yallmo.scalacode.{ScalaToolApi as tool}\n" +
          "import fmgp.yallmo.fs.{FsRead, FsError}\n" +
          "import zio.ZIO\nobject ScalaToolSnippet {\n  import tool.*\n" +
          "  def result(fs: FsRead)(using zio.Trace, zio.CanFail[FsError]): ZIO[Any, FsError, String] = {\n" +
          code + "\n  }\n}\n"
      )
      Files.writeString(directory.resolve("Main.scala"), fsFeasibilityMain)
      val arguments = ScalaCodeExecutor.arguments(
        realCli.get,
        ScalaCodeExecutor.ScalaVersion,
        offline = true,
        dependencies = Seq("dev.zio::zio-streams:2.1.27")
      )
      val filesystemClasspath =
        Path.of(classOf[fmgp.yallmo.fs.FsRead].getProtectionDomain.getCodeSource.getLocation.toURI).toAbsolutePath
      val classpathIndex = arguments.indexOf("--classpath") + 1
      val command = arguments.updated(
        classpathIndex,
        arguments(classpathIndex) + java.io.File.pathSeparator + filesystemClasspath
      )
      Files.writeString(Path.of(s"/tmp/yallmo-phase1-$label.command"), command.mkString("\n"))
      val builder = new ProcessBuilder(command*)
        .directory(directory.toFile)
        .redirectErrorStream(true)
        .redirectOutput(log.toFile)
      Seq("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH").foreach(builder.environment().remove)
      val process = builder.start()
      process.getOutputStream.close()
      val completed = process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
      if !completed then {
        val children = process.descendants()
        try children.forEach(_.destroyForcibly())
        finally children.close()
        process.destroyForcibly()
        process.waitFor()
      }
      val input = Files.newInputStream(log)
      val output = try new String(input.readNBytes(64 * 1024), java.nio.charset.StandardCharsets.UTF_8)
      finally input.close()
      val truncated = Files.size(log) > 64 * 1024
      Files.writeString(log, output)
      CommandOutput(process.exitValue(), timedOut = !completed, output, truncated)
    } finally {
      val paths = Files.walk(directory)
      try {
        import scala.jdk.CollectionConverters.*
        paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists(_))
      } finally paths.close()
    }
  }

  // All capabilities and runtime execution stay local to this trusted main, never in the safe source.
  private val fsFeasibilityMain = """import fmgp.yallmo.fs.{FsRead, FsError}
    |import zio.*
    |import zio.stream.ZStream
    |object ScalaToolMain {
    |  def main(args: Array[String]): Unit = {
    |    var reads = List.empty[String]
    |    var lists = List.empty[String]
    |    var pulled = 0
    |    val fs: FsRead = new FsRead {
    |      def readBytes(path: String): ZStream[Any, FsError, Byte] = ZStream.unwrap(ZIO.succeed {
    |        reads = reads :+ path
    |        path match {
    |          case "hello.txt" => ZStream.fromIterable("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8).toSeq)
    |          case "bytes" => ZStream.repeatZIO(ZIO.succeed { pulled += 1; pulled.toByte })
    |          case _ => ZStream.fail(FsError.NotFound(path))
    |        }
    |      })
    |      def list(path: String): IO[FsError, Chunk[String]] = ZIO.succeed {
    |        lists = lists :+ path
    |        Chunk("bytes", "hello.txt")
    |      }
    |    }
    |    val effect: ZIO[Any, FsError, String] =
    |      ScalaToolSnippet.result(fs)(using Trace.empty, summon[CanFail[FsError]])
    |    Unsafe.unsafe { implicit unsafe =>
    |      println(Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())
    |    }
    |    println(s"reads=${reads.mkString(",")};lists=${lists.mkString(",")};pulled=$pulled")
    |  }
    |}
    |""".stripMargin

  test("phase 1 pinned compiler executes injected FsRead effects with no ambient environment") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run filesystem feasibility")
    val result = evaluateFsSnippet(
      """for {
        |  text <- fs.readUtf8("hello.txt")
        |  entries <- fs.list(".")
        |  prefix <- fs.readBytes("bytes").take(3).runCollect.map(_.map(_.toInt).mkString(","))
        |  recovered <- fs.readUtf8("missing").fold((error: FsError) => "recovered:" + error.toString, identity)
        |} yield text + "|" + entries.mkString(",") + "|" + prefix + "|" + recovered
        |""".stripMargin,
      "effects"
    )
    assert(!result.timedOut && !result.truncated, result.output)
    assertEquals(result.exitCode, 0, result.output)
    assertEquals(
      result.output.linesIterator.toList,
      List("hello|bytes,hello.txt|1,2,3|recovered:NotFound(missing)", "reads=hello.txt,bytes,missing;lists=.;pulled=3")
    )
  }

  test("phase 1 pinned compiler preserves pure expressions and API with the filesystem classpath") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run filesystem feasibility")
    val result = evaluateFsSnippet(
      "fs.readUtf8(\"hello.txt\").map(_ => { api; tool.api; List(1, 2, 3).map(_ * 2).sum.toString })",
      "pure-api"
    )
    assertEquals(result.exitCode, 0, result.output)
    assertEquals(
      result.output.linesIterator.toList,
      List("Yes Tool API is available", "Yes Tool API is available", "12", "reads=hello.txt;lists=;pulled=0")
    )
  }

  test("phase 1 pinned compiler rejects authority expansion through the filesystem artifact and trusted main") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run filesystem feasibility")
    val forbidden = List(
      "open" -> "fmgp.yallmo.fs.FileSystem.open(\"/tmp\")",
      "constructor" -> "new fmgp.yallmo.fs.FileSystem(null)",
      "nio" -> "java.nio.file.Files.readString(java.nio.file.Path.of(\"/etc/passwd\"))",
      "network" -> "new java.net.Socket(\"example.com\", 80)",
      "runtime" -> "java.lang.Runtime.getRuntime.exec(\"true\")",
      "zio-runtime" -> "zio.Runtime.default",
      "unsafe-token" -> "zio.Unsafe",
      "main" -> "ScalaToolMain.main(Array.empty[String])",
      "main-fs" -> "ScalaToolMain.fs",
      "cast" -> "fs.asInstanceOf[fmgp.yallmo.fs.FsWrite]",
      "extractor" -> "fs.readUtf8(\"missing\").fold({ case FsError.NotFound(path) => path; case _ => \"other\" }, identity)"
    )
    for (label, code) <- forbidden do {
      val result = evaluateFsSnippet(s"val forbidden = { $code }; fs.readUtf8(\"hello.txt\")", s"reject-$label")
      assert(!result.timedOut && !result.truncated, s"$label: ${result.output}")
      assert(result.exitCode != 0 && result.output.contains("Compilation failed"), s"Accepted $code\n${result.output}")
      assert(
        result.output.contains("safe mode") || result.output.contains("from safe code") ||
          result.output.contains("cannot be accessed") ||
          result.output.contains("is not a member"),
        result.output
      )
    }
    assert(ScalaTool.validate("zio.Unsafe.unsafe { implicit u => () }").isLeft)
  }

  test("real CLI nightly resolution returns before the surrounding deadline") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run the compiler integration test")
    val result = run(
      new ScalaTool(timeout = 1.second)
        .execute(
          fmgp.yallmo.orchestration.ExternalFunctionCall(ScalamodeToolDefinition.Execute, """{"code":"2 + 3"}""")
        )
        .provide(ScalaCodeExecutor.layer(realCli.get, scalaVersion = "3.nightly", offline = false))
        .timeoutFail(new RuntimeException("Scala CLI resolution did not respect its deadline"))(5.seconds)
    )
    val typed = result.asInstanceOf[ExternalFunctionResult.ScalaMode]
    if typed.result.isInstanceOf[ScalaModeResult.TimedOut] then assert(typed.output.contains("deadline"))
  }

  test("pinned compiler returns E006 compilation diagnostics in the tool output") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run the compiler diagnostics integration test")
    val result = run(
      new ScalaTool(timeout = 120.seconds)
        .execute(
          fmgp.yallmo.orchestration.ExternalFunctionCall(ScalamodeToolDefinition.Execute, """{"code":"doesNotExist"}""")
        )
        .provide(ScalaCodeExecutor.layer(realCli.get, realVersion, realOffline))
    )
    val typed = result.asInstanceOf[ExternalFunctionResult.ScalaMode]
    assertEquals(typed.exitCode, 1)
    assert(typed.result.isInstanceOf[ScalaModeResult.CompilationError])
    assert(typed.output.contains("[E006] Not Found Error"), result.text)
    assert(typed.output.contains("Not found: doesNotExist"), result.text)
    assert(typed.output.contains("Snippet.scala:1:"), result.text)
    assert(typed.output.linesIterator.exists(_.matches("\\s*1 \\|doesNotExist")), result.text)
    assertEquals(typed.logs, Seq.empty[String])
  }

  test("pinned compiler syntax diagnostics hide the wrapper and point to the submitted EOF") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run the compiler diagnostics integration test")
    for code <- List("List(1, 2", "val n = 1\nif true then {\nn") do {
      val result = run(
        ZIO
          .serviceWithZIO[ScalaCodeExecutor](_.execute(code, 120.seconds, 16 * 1024))
          .provide(ScalaCodeExecutor.layer(realCli.get, realVersion, realOffline))
      )
      val lines = code.split("\n", -1)
      assert(result.result.isInstanceOf[ScalaModeResult.CompilationError], result.output)
      assert(result.output.contains(s"Snippet.scala:${lines.length}:${lines.last.length}"), result.output)
      assert(result.output.contains("expected at end of submitted code"), result.output)
      assert(!result.output.contains("yallmo-scala-"), result.output)
      assert(!result.output.contains("def result") && !result.output.contains("object ScalaToolSnippet"), result.output)
      assert(!result.output.linesIterator.exists(_.matches("\\s*\\d+ \\|\\s*}")), result.output)
    }
  }

  test("pinned compiler provides the trusted runtime API on every fresh invocation") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run the runtime API integration test")
    for code <- List("api; tool.api; 42", "api") do {
      val result = run(
        ZIO
          .serviceWithZIO[ScalaCodeExecutor](_.execute(code, 120.seconds, 16 * 1024))
          .provide(ScalaCodeExecutor.layer(realCli.get, realVersion, realOffline))
      )
      assertEquals(result.exitCode, 0, result.output)
      assert(!result.logs.exists(_.startsWith("WARNING:")), result.logs.mkString("\n"))
      val lines = result.output.linesIterator.toList
      if code == "api" then {
        assertEquals(lines, List("()"))
        assertEquals(result.logs, Seq("Yes Tool API is available"))
      } else {
        assertEquals(lines, List("42"))
        assertEquals(result.logs, Seq("Yes Tool API is available", "Yes Tool API is available"))
      }
    }
  }

  test("pinned compiler constructs and executes restricted ZIO effects") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run the restricted ZIO integration test")
    for (code, expected) <- List(
        "ZIO.succeed(1 + 1)" -> "2",
        "for { a <- ZIO.succeed(1); b <- ZIO.succeed(2) } yield a + b" -> "3",
        "ZIO.foreach(List(1, 2, 3))(n => ZIO.succeed(n * 2)).map(_.sum)" -> "12",
        "ZIO.fromEither(Right(7)).map(_ + 1)" -> "8",
        "ZIO.attempt(6 / 2)" -> "3",
        "ZIO.unit" -> "()"
      )
    do {
      val result = run(
        ZIO
          .serviceWithZIO[ScalaCodeExecutor](_.execute(code, 120.seconds, 16 * 1024))
          .provide(ScalaCodeExecutor.layer(realCli.get, realVersion, realOffline))
      )
      assertEquals(result.exitCode, 0, result.output)
      assertEquals(result.output.trim, expected)
    }
    val failed = run(
      ZIO
        .serviceWithZIO[ScalaCodeExecutor](_.execute("ZIO.fail(\"expected failure\")", 120.seconds, 16 * 1024))
        .provide(ScalaCodeExecutor.layer(realCli.get, realVersion, realOffline))
    )
    assert(failed.result.isInstanceOf[ScalaModeResult.Executed], failed.output)
    assertNotEquals(failed.exitCode, 0)
    assert(failed.output.contains("expected failure"), failed.output)
  }

  test("pinned compiler restricted ZIO callbacks cannot acquire ambient authority") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run the restricted ZIO integration test")
    for code <- List(
        "ZIO.succeed(java.lang.System.getenv())",
        "ZIO.attempt(java.nio.file.Files.readString(java.nio.file.Path.of(\"/etc/passwd\")))",
        "ZIO.foreach(List(1))(_ => ZIO.succeed(new java.net.Socket(\"example.com\", 80)))",
        "ZIO.service[String]",
        "zio.ZIO.succeed(1)",
        "zio.Runtime.default",
        "zio.Unsafe"
      )
    do {
      val rejected = run(
        ZIO
          .serviceWithZIO[ScalaCodeExecutor](_.execute(code, 120.seconds, 16 * 1024))
          .provide(ScalaCodeExecutor.layer(realCli.get, realVersion, realOffline))
      )
      assert(rejected.result.isInstanceOf[ScalaModeResult.CompilationError], s"$code\n${rejected.output}")
      assertNotEquals(rejected.exitCode, 0)
    }
  }

  test("pinned compiler accepts pure code and rejects ambient JVM authority") {
    assume(realCli.nonEmpty, "Set YALLMO_TEST_SCALA_CLI to run the compiler integration test")
    def evaluate(code: String, timeout: Duration = 120.seconds): ExternalFunctionResult.ScalaMode = run(
      ZIO
        .serviceWithZIO[ScalaCodeExecutor](_.execute(code, timeout, 16 * 1024))
        .provide(ScalaCodeExecutor.layer(realCli.get, realVersion, realOffline))
    )
    val small = evaluate("2 + 3")
    assertEquals(small.exitCode, 0, small.output)
    assert(small.output.linesIterator.contains("5"), small.output)
    val pure = evaluate("val values = List(1, 2, 3); values.map(_ * 2).sum")
    assertEquals(pure.exitCode, 0, pure.output)
    assert(pure.output.linesIterator.contains("12"), pure.output)

    for code <- List(
        "doesNotExist",
        "new fmgp.yallmo.tool.ScalaTool()",
        "java.nio.file.Files.readString(java.nio.file.Path.of(\"/etc/passwd\"))",
        "java.nio.file.Files.writeString(java.nio.file.Path.of(\"forbidden.txt\"), \"no\")",
        "java.lang.System.getenv()",
        "java.lang.System.exit(0)",
        "new java.lang.Thread()",
        "scala.io.Source.fromFile(\"/etc/passwd\").mkString",
        "sys.env",
        "sys.props",
        "sys.runtime",
        "java.lang.Runtime.getRuntime.exec(\"true\")",
        "new java.net.Socket(\"example.com\", 80)",
        "classOf[String].getDeclaredMethods",
        "1.asInstanceOf[String]",
        "println(42)",
        "ScalaToolMain.main(Array.empty[String])",
        "fmgp.yallmo.scalacode.ScalaToolRuntime.bootstrap"
      )
    do {
      val rejected = evaluate(code)
      assert(
        !rejected.result.isInstanceOf[ScalaModeResult.TimedOut] && rejected.exitCode != 0,
        s"Unexpectedly accepted: $code\n${rejected.output}"
      )
      assert(rejected.result.isInstanceOf[ScalaModeResult.CompilationError])
      assert(rejected.output.contains("Compilation failed"), rejected.output)
      assertEquals(rejected.logs, Seq.empty[String])
    }
    val runtimeError = evaluate("1 / 0")
    assert(runtimeError.result.isInstanceOf[ScalaModeResult.Executed])
    assert(runtimeError.output.contains("ArithmeticException"))
    assertNotEquals(runtimeError.exitCode, 0)
    assertEquals(runtimeError.logs, Seq.empty[String])
    assert(evaluate("while true do ()", 15.seconds).result.isInstanceOf[ScalaModeResult.TimedOut])
  }
}
