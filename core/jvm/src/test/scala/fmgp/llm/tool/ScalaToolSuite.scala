package fmgp.yallmo.tool

import fmgp.yallmo.model.{ToolKey, ScalaModeResult}
import fmgp.yallmo.orchestration.{ExternalToolCall, ExternalToolResult}
import munit.FunSuite
import zio.*

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
    assert(!typed.timedOut && !typed.truncated)
  }

  test("timeouts are actionable execution attempts, not compilation errors") {
    val result = ScalaCodeExecutor.result(CommandOutput(124, true, "deadline", false), started = false)
    assert(result.result.isInstanceOf[ScalaModeResult.Executed])
    assert(result.output.contains("deadline"))
    assert(result.timedOut)
  }

  test("trusted start boundary separates compilation diagnostics from runtime errors") {
    val command = CommandOutput(1, false, "partial value", true, Seq("[E006] Not Found Error", "missing"))
    val compilation = ScalaCodeExecutor.result(command, started = false)
    assertEquals(compilation.result, ScalaModeResult.CompilationError("partial value\n[E006] Not Found Error\nmissing"))
    val runtime = ScalaCodeExecutor.result(command, started = true)
    assertEquals(runtime.result, ScalaModeResult.Executed(compilation.output))
    assert(runtime.truncated && runtime.exitCode == 1)
    val success = ScalaCodeExecutor.result(CommandOutput(0, false, "42\n", false, Seq("connected")), started = true)
    assertEquals(success.output, "42\n")
    assertEquals(success.logs, Seq("connected"))
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
    "command defaults to scala-cli with the verified nightly offline, no build server, and an explicit project root"
  ) {
    val arguments = ScalaCodeExecutor.arguments()
    assertEquals(arguments.take(3), Seq("scala-cli", "run", "."))
    assertEquals(arguments(arguments.indexOf("--scala") + 1), "3.10.2-RC1-bin-20261021-a98b104-NIGHTLY")
    assert(arguments.contains("--power") && arguments.contains("--offline"))
    assert(arguments.contains("--server=false"))
    assert(arguments.contains("--java-opt=--enable-native-access=ALL-UNNAMED"))
    assert(arguments.contains("--java-opt=--sun-misc-unsafe-memory-access=allow"))
    assert(ScalaCodeExecutor.snippet("42").startsWith("import scala.language.experimental.safe\n"))
  }

  test("runtime API is imported and only its public contract is advertised") {
    assert(ScalaCodeExecutor.snippet("api").contains("import tool.*"))
    assert(ScalaTool.definition.description.contains("def api: Unit"))
    assert(ScalaTool.definition.description.contains("tool.api"))
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
            Seq("Compilation failed"),
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
    assertEquals(typed.logs, Seq("Compilation failed"))
  }
}
