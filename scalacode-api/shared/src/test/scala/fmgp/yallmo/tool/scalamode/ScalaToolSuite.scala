package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.tool.*

import fmgp.yallmo.orchestration.{ExternalFunctionCall, ExternalFunctionResult}
import munit.ZSuite
import zio.*

final class ScalaToolSuite extends ZSuite {
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
    val services = Vector(
      ScalamodeServiceApi(
        parameter = "math",
        apiTypes = Vector("example.MathApi"),
        description = "Arithmetic helpers",
        scalaApi = Some("trait MathApi { def twice(value: Int): Int }")
      )
    )
    val bound = ScalamodeToolDefinition.withServices(services)
    assertEquals(bound.inputSchema, ScalaTool.definition.inputSchema)
    assertEquals(bound.validate, Right(bound))
    assertEquals(bound.scalamode, Some(ScalamodeApi(ScalamodeToolDefinition.BuiltinApi, services)))
    assert(bound.description.contains(services.head.description))
    assert(bound.description.contains(services.head.scalaApi.get))
    assertEquals(
      ToolDefinition
        .available(zio.Chunk(ScalamodeToolDefinition), zio.Chunk(bound))
        .head
        .functions,
      Set(bound)
    )
  }

  test("service metadata preserves order and supports APIs without interface source") {
    val services = Vector(
      ScalamodeServiceApi("math", Vector("example.MathApi"), "Arithmetic helpers"),
      ScalamodeServiceApi(
        "text",
        Vector("example.TextApi"),
        "Text helpers",
        Some("trait TextApi { def upper(value: String): String }")
      )
    )
    val bound = ScalamodeToolDefinition.withServices(services)
    assertEquals(bound.scalamode.get.services, services)
    assert(
      bound.description.endsWith(
        "Arithmetic helpers\nText helpers\nScala API:\ntrait TextApi { def upper(value: String): String }"
      )
    )
    assertEquals(bound.inputSchema, ScalaTool.definition.inputSchema)
  }

  test("an empty service sequence advertises only the restricted builtins") {
    val bound = ScalamodeToolDefinition.withServices(Seq.empty)
    assertEquals(bound.scalamode, ScalaTool.definition.scalamode)
    assertEquals(bound.inputSchema, ScalaTool.definition.inputSchema)
    assertEquals(bound.validate, Right(bound))
  }

  testZ("pure code is forwarded and its result is returned")(ZIO.suspendSucceed {
    for {
      result <- new ScalaTool()
        .execute(ExternalFunctionCall(ScalamodeToolDefinition.Execute, """{"code":"(1 to 10).sum"}"""))
        .provide(executor)
    } yield {
      val typed = result.asInstanceOf[ExternalFunctionResult.ScalaMode]
      assertEquals(typed.logs, Seq("connected", "disconnected"))
      assertEquals(typed.result, ScalaModeResult.Executed("55\n"))
      assertEquals(typed.exitCode, 0)
      assert(!typed.result.isInstanceOf[ScalaModeResult.TimedOut] && !typed.truncated)
    }
  })

  testZ("invalid arguments are rejected before executing anything")(ZIO.suspendSucceed {
    ZIO.foreachDiscard(
      List(
        "{}",
        "[]",
        """{"code":"1", "mode":"pure"}""",
        """{"code":"1", "mode":"filesystem"}""",
        """{"code":1}""",
        """{"code":""}""",
        """{"code":"1", "scalamode":"3"}""",
        """{"code":"1", "dependencies":["dev.zio::zio:2.1.27"]}"""
      )
    ) { arguments =>
      for {
        result <- new ScalaTool()
          .execute(ExternalFunctionCall(ScalamodeToolDefinition.Execute, arguments))
          .either
          .provide(executor)
      } yield assert(result.isLeft)
    }
  })

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

  testZ("compilation and runtime errors remain tool results, not infrastructure failures")(ZIO.suspendSucceed {
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
    for {
      result <- new ScalaTool()
        .execute(ExternalFunctionCall(ScalamodeToolDefinition.Execute, """{"code":"unknown"}"""))
        .provide(errors)
    } yield {
      val typed = result.asInstanceOf[ExternalFunctionResult.ScalaMode]
      assertEquals(typed.exitCode, 1)
      assertEquals(typed.result, ScalaModeResult.CompilationError("Compilation failed"))
      assertEquals(typed.logs, Seq.empty[String])
    }
  })
}
