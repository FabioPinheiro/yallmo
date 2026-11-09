package fmgp.yallmo.tool

import fmgp.yallmo.model.{FunctionCallId, ScalaModeOutput, ToolKey, ScalaModeResult}
import fmgp.yallmo.orchestration.{ExternalToolCall, ExternalToolResult}
import munit.FunSuite
import zio.*
import zio.json.*

final class ScalaToolSuite extends FunSuite {
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private val executor = ZLayer.succeed(new ScalaCodeExecutor {
    def isAvailable: UIO[Boolean] = ZIO.succeed(true)
    def execute(
        code: String,
        timeout: Duration,
        maxOutputBytes: Int,
        dependencies: Seq[String]
    ): Task[ExternalToolResult.ScalaMode] = {
      assertEquals(code, "(1 to 10).sum")
      assertEquals(timeout, 45.seconds)
      assertEquals(dependencies, Seq("dev.zio::zio:2.1.27"))
      assert(timeout < fmgp.yallmo.orchestration.AgentBudget.default.timeout && maxOutputBytes > 0)
      ZIO.succeed(ExternalToolResult.ScalaMode(ScalaModeResult.Executed("55\n"), Seq("connected", "disconnected")))
    }
  })

  test("pure code is forwarded and its result is returned") {
    val result =
      run(
        new ScalaTool().execute(ExternalToolCall(ToolKey.Scalamode, """{"code":"(1 to 10).sum"}""")).provide(executor)
      )
    val typed = result.asInstanceOf[ExternalToolResult.ScalaMode]
    assertEquals(typed.logs, Seq("connected", "disconnected"))
    assertEquals(typed.result, ScalaModeResult.Executed("55\n"))
    assertEquals(typed.exitCode, 0)
    assert(!typed.result.isInstanceOf[ScalaModeResult.TimedOut] && !typed.truncated)
  }

  test("compilation diagnostics are preserved once through result conversion and JSON serialization") {
    val diagnostics =
      "-- [E006] Not Found Error: Snippet.scala:1:1 --\n" +
        "1 |doesNotExist\n  |^^^^^^^^^^^^\n  |Not found: doesNotExist\n\n" +
        "  | longer explanation available when compiling with `-explain`\n1 error found\nCompilation failed"
    val result = ScalaCodeExecutor.result(
      CommandOutput(1, false, "", false, diagnostics.linesIterator.toVector),
      compiled = false
    )
    assertEquals(result.result, ScalaModeResult.CompilationError(diagnostics))
    assertEquals(result.output, diagnostics)
    assertEquals(result.logs, Seq.empty[String])
    assertEquals(result.exitCode, 1)
    assert(!result.result.isInstanceOf[ScalaModeResult.TimedOut] && !result.truncated)
    val output = ScalaModeOutput(
      FunctionCallId.unsafe("scala-compilation-error"),
      result.result,
      result.logs,
      result.exitCode,
      result.truncated
    )
    val json = output.toJson
    assert(json.contains("\"logs\":[]"), json)
    assertEquals(json.fromJson[ScalaModeOutput], Right(output))
  }

  test("timeouts consume logs into actionable execution diagnostics and preserve flags") {
    val result = ScalaCodeExecutor.result(
      CommandOutput(124, true, "partial value", true, Seq("checking compiler", "checking compiler")),
      compiled = false
    )
    assertEquals(
      result.result,
      ScalaModeResult.TimedOut(
        "Scala CLI exceeded its compilation/execution deadline.\npartial value\nchecking compiler\nchecking compiler"
      )
    )
    assertEquals(result.logs, Seq.empty[String])
    assertEquals(result.exitCode, 124)
    assert(result.result.isInstanceOf[ScalaModeResult.TimedOut] && result.truncated)
  }

  test("explicit compile result separates compilation diagnostics from runtime errors") {
    val command = CommandOutput(1, false, "partial value", true, Seq("[E006] Not Found Error", "missing"))
    val compilation = ScalaCodeExecutor.result(command, compiled = false)
    assertEquals(compilation.result, ScalaModeResult.CompilationError("partial value\n[E006] Not Found Error\nmissing"))
    assertEquals(compilation.logs, Seq.empty[String])
    assert(
      compilation.truncated && compilation.exitCode == 1 && !compilation.result.isInstanceOf[ScalaModeResult.TimedOut]
    )
    val runtime = ScalaCodeExecutor.result(command, compiled = true)
    assertEquals(runtime.result, ScalaModeResult.Executed(compilation.output))
    assertEquals(runtime.logs, Seq.empty[String])
    assert(runtime.truncated && runtime.exitCode == 1 && !runtime.result.isInstanceOf[ScalaModeResult.TimedOut])
    val success = ScalaCodeExecutor.result(
      CommandOutput(0, false, "42", false, Seq("connected", "42", "connected")),
      compiled = true
    )
    assertEquals(success.output, "42")
    assertEquals(success.logs, Seq("connected", "42", "connected"))
  }

  test("invalid arguments are rejected before executing anything") {
    for arguments <- List(
        "{}",
        "[]",
        """{"code":1}""",
        """{"code":""}""",
        """{"code":"1", "scalamode":"3"}""",
        """{"code":"1", "dependencies":["dev.zio::zio:2.1.27"]}"""
      )
    do
      assert(
        run(new ScalaTool().execute(ExternalToolCall(ToolKey.Scalamode, arguments)).either.provide(executor)).isLeft
      )
  }

  test("compiler escape hatches and directives are rejected conservatively") {
    for code <- List(
        "//> using dep evil:evil:1\n1",
        "//> using options -Xplugin:evil.jar\n1",
        "@caps.assumeSafe object Evil",
        "import caps.unsafe.*",
        "import scala.language.experimental.safe",
        "import $file.evil",
        "val `alias` = 1",
        "\\u0040caps.assumeSafe object Evil",
        "x" * (64 * 1024 + 1)
      )
    do assert(ScalaTool.validate(code).isLeft, code.take(100))
  }

  test(
    "command runs isolated Scala source files with Scala 3.10.0 offline, no build server, and the safety flags"
  ) {
    val arguments = ScalaCodeExecutor.arguments()
    assertEquals(arguments.take(3), Seq("scala-cli", "run", "."))
    assertEquals(arguments(arguments.indexOf("--main-class") + 1), "ScalaToolMain")
    assert(!arguments.contains("--quiet"), "Scala CLI must report dependency-resolution failures")
    assertEquals(arguments(arguments.indexOf("--scala") + 1), "3.10.0")
    assert(arguments.contains("--power") && arguments.contains("--offline"))
    assertEquals(arguments(arguments.indexOf("--jvm") + 1), "system")
    assert(arguments.contains("--server=false"))
    assert(arguments.contains("--scalac-option=-language:experimental.captureChecking"))
    assert(arguments.contains("--scalac-option=-language:experimental.modularity"))
    assert(arguments.contains("--scalac-option=-color:never"))
    assert(arguments.contains("--java-opt=-Xmx128m"))
    assert(arguments.contains("--java-opt=--enable-native-access=ALL-UNNAMED"))
    assert(arguments.contains("--java-opt=--sun-misc-unsafe-memory-access=allow"))
    assert(ScalaCodeExecutor.snippet("42").startsWith("import scala.language.experimental.safe\n"))
  }

  test("runtime API is imported and only its public contract is advertised") {
    assert(ScalaCodeExecutor.snippet("api").contains("import tool.*"))
    assert(ScalaTool.definition.description.contains("def api: Unit"))
    assert(ScalaTool.definition.description.contains("tool.api"))
    assert(ScalaTool.definition.description.contains("ZIO.succeed"))
    assert(ScalaTool.definition.description.contains("Scala 3.10.0"))
    assert(!ScalaTool.definition.description.contains("nightly"))
    assert(ScalaTool.definition.description.contains("effects are executed"))
    assert(ScalaCodeExecutor.snippet("ZIO.succeed(2)").contains("def result(using zio.Trace) ="))
    assert(!ScalaCodeExecutor.filesystemSnippet("ZIO.succeed(2)").contains("import zio.ZIO"))
    assert(!ScalaTool.definition.description.contains("println(\"Yes API is available\")"))
  }

  test("trusted host configuration can select an exact cached nightly without resolution") {
    val version = "3.10.2-RC1-bin-20261021-a98b104-NIGHTLY"
    val arguments = ScalaCodeExecutor.arguments("custom-scala-cli", version, offline = true)
    assertEquals(arguments(arguments.indexOf("--scala") + 1), version)
    assert(arguments.contains("--power") && arguments.contains("--offline"))
  }

  test("compilation and runtime errors remain tool results, not infrastructure failures") {
    val errors = ZLayer.succeed(new ScalaCodeExecutor {
      def isAvailable: UIO[Boolean] = ZIO.succeed(true)
      def execute(
          code: String,
          timeout: Duration,
          maxOutputBytes: Int,
          dependencies: Seq[String]
      ): Task[ExternalToolResult.ScalaMode] = {
        val _ = (code, timeout, maxOutputBytes, dependencies)
        ZIO.succeed(
          ExternalToolResult.ScalaMode(
            ScalaModeResult.CompilationError("Compilation failed"),
            Seq.empty,
            exitCode = 1
          )
        )
      }
    })
    val result =
      run(new ScalaTool().execute(ExternalToolCall(ToolKey.Scalamode, """{"code":"unknown"}""")).provide(errors))
    val typed = result.asInstanceOf[ExternalToolResult.ScalaMode]
    assertEquals(typed.exitCode, 1)
    assertEquals(typed.result, ScalaModeResult.CompilationError("Compilation failed"))
    assertEquals(typed.logs, Seq.empty[String])
  }
}
