package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.tool.*

import fmgp.yallmo.orchestration.{ExternalFunctionCall, ExternalFunctionResult}
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
    ): Task[ExternalFunctionResult.ScalaMode] = {
      assertEquals(code, "(1 to 10).sum")
      assertEquals(timeout, 45.seconds)
      assertEquals(dependencies, Seq("dev.zio::zio:2.1.27"))
      assert(timeout < fmgp.yallmo.orchestration.AgentBudget.default.timeout && maxOutputBytes > 0)
      ZIO.succeed(ExternalFunctionResult.ScalaMode(ScalaModeResult.Executed("55\n"), Seq("connected", "disconnected")))
    }
  })

  test("tool definitions retain the shared key and expose the selected binding schema") {
    assertEquals(ScalamodeToolDefinition.Execute, FunctionKey.scalamode)
    assertEquals(ScalamodeToolDefinition.name, ToolKey.Scalamode)
    assertEquals(ScalamodeToolDefinition.functions, Set(ScalaTool.definition))
    assertEquals(ScalaTool.definition.validate, Right(ScalaTool.definition))
    val bound = ScalamodeToolDefinition.withServices("math.twice(value: Int): Int")
    assertEquals(bound.inputSchema, ScalaTool.definition.inputSchema)
    assertEquals(bound.validate, Right(bound))
    assertEquals(
      ToolDefinition
        .available(zio.Chunk(ScalamodeToolDefinition), zio.Chunk(bound))
        .head
        .functions,
      Set(bound)
    )
  }

  test("pure code is forwarded and its result is returned") {
    val result =
      run(
        new ScalaTool()
          .execute(ExternalFunctionCall(ScalamodeToolDefinition.Execute, """{"code":"(1 to 10).sum"}"""))
          .provide(executor)
      )
    val typed = result.asInstanceOf[ExternalFunctionResult.ScalaMode]
    assertEquals(typed.logs, Seq("connected", "disconnected"))
    assertEquals(typed.result, ScalaModeResult.Executed("55\n"))
    assertEquals(typed.exitCode, 0)
    assert(!typed.result.isInstanceOf[ScalaModeResult.TimedOut] && !typed.truncated)
  }

  test("invalid arguments are rejected before executing anything") {
    for arguments <- List(
        "{}",
        "[]",
        """{"code":"1", "mode":"pure"}""",
        """{"code":"1", "mode":"filesystem"}""",
        """{"code":1}""",
        """{"code":""}""",
        """{"code":"1", "scalamode":"3"}""",
        """{"code":"1", "dependencies":["dev.zio::zio:2.1.27"]}"""
      )
    do
      assert(
        run(
          new ScalaTool()
            .execute(ExternalFunctionCall(ScalamodeToolDefinition.Execute, arguments))
            .either
            .provide(executor)
        ).isLeft
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

  test("compilation and runtime errors remain tool results, not infrastructure failures") {
    val errors = ZLayer.succeed(new ScalaCodeExecutor {
      def isAvailable: UIO[Boolean] = ZIO.succeed(true)
      def execute(
          code: String,
          timeout: Duration,
          maxOutputBytes: Int,
          dependencies: Seq[String]
      ): Task[ExternalFunctionResult.ScalaMode] = {
        val _ = (code, timeout, maxOutputBytes, dependencies)
        ZIO.succeed(
          ExternalFunctionResult.ScalaMode(
            ScalaModeResult.CompilationError("Compilation failed"),
            Seq.empty,
            exitCode = 1
          )
        )
      }
    })
    val result =
      run(
        new ScalaTool()
          .execute(ExternalFunctionCall(ScalamodeToolDefinition.Execute, """{"code":"unknown"}"""))
          .provide(errors)
      )
    val typed = result.asInstanceOf[ExternalFunctionResult.ScalaMode]
    assertEquals(typed.exitCode, 1)
    assertEquals(typed.result, ScalaModeResult.CompilationError("Compilation failed"))
    assertEquals(typed.logs, Seq.empty[String])
  }
}
