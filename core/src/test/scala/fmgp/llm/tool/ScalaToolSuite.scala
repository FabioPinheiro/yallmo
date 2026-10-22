package fmgp.yallmo.tool

import fmgp.yallmo.orchestration.ExternalToolCall
import munit.FunSuite
import zio.*

final class ScalaToolSuite extends FunSuite {
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private val executor = ZLayer.succeed(new ScalaCodeExecutor {
    def isAvailable: UIO[Boolean] = ZIO.succeed(true)
    def execute(code: String, timeout: Duration, maxOutputBytes: Int): Task[CommandOutput] = {
      assertEquals(code, "(1 to 10).sum")
      assertEquals(timeout, 45.seconds)
      assert(timeout < fmgp.yallmo.orchestration.AgentBudget.default.timeout && maxOutputBytes > 0)
      ZIO.succeed(CommandOutput(0, false, "55\n", false))
    }
  })

  test("pure code is forwarded and its result is returned") {
    val result =
      run(new ScalaTool().execute(ExternalToolCall("scala", """{"code":"(1 to 10).sum"}""")).provide(executor))
    val json = ujson.read(result.text)
    assertEquals(json("output").str, "55\n")
    assertEquals(json("exitCode").num.toInt, 0)
    assert(!json("timedOut").bool && !json("truncated").bool)
  }

  test("invalid arguments are rejected before executing anything") {
    for arguments <- List("{}", "[]", """{"code":1}""", """{"code":""}""", """{"code":"1", "scala":"3"}""") do
      assert(run(new ScalaTool().execute(ExternalToolCall("scala", arguments)).either.provide(executor)).isLeft)
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
    assert(ScalaCodeExecutor.snippet("42").startsWith("import scala.language.experimental.safe\n"))
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
      def execute(code: String, timeout: Duration, maxOutputBytes: Int): Task[CommandOutput] = {
        val _ = (code, timeout, maxOutputBytes)
        ZIO.succeed(CommandOutput(1, false, "Compilation failed", false))
      }
    })
    val result = run(new ScalaTool().execute(ExternalToolCall("scala", """{"code":"unknown"}""")).provide(errors))
    assertEquals(ujson.read(result.text)("exitCode").num.toInt, 1)
    assertEquals(ujson.read(result.text)("output").str, "Compilation failed")
  }
}
