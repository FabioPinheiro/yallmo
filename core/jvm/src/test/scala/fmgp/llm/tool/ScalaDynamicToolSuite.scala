package fmgp.yallmo.tool

import fmgp.yallmo.model.ToolKey
import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.orchestration.{AgentTool, ExternalToolCall}
import munit.FunSuite
import zio.*

final class ScalaDynamicToolSuite extends FunSuite {
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private val config = ScalaCodeDynamicExecutor.Config(apiDescription = "No services required")
  private val code = "ZIO.succeed(42)"
  private val call = ExternalToolCall(ToolKey.ScalaUnsafe, ujson.write(ujson.Obj("code" -> code)))

  private def executor(result: Task[CommandOutput]): ULayer[ScalaCodeDynamicExecutor] =
    ZLayer.succeed(new ScalaCodeDynamicExecutor {
      def isAvailable: UIO[Boolean] = ZIO.succeed(true)
      def execute[R: EnvironmentTag](actual: String, timeout: Duration, maxOutputBytes: Int): RIO[R, CommandOutput] = {
        assertEquals(actual, code)
        assertEquals(timeout, 45.seconds)
        assertEquals(maxOutputBytes, 64 * 1024)
        result
      }
    })

  test("dynamic and restricted tools have independent definitions and executor requirements") {
    val tool: AgentTool[ScalaCodeDynamicExecutor] = new ScalaDynamicTool[Any](config)
    assertEquals(tool.definition.key, ToolKey.ScalaUnsafe)
    assertEquals(ScalaTool.definition.key, ToolKey.Scalamode)
    assertNotEquals(tool.definition.key, ScalaTool.definition.key)
    assert(tool.definition.description.contains("ZIO effect with Throwable errors"))
    assert(tool.definition.description.contains(config.apiDescription))
    assert(tool.definition.description.contains("not a sandbox"))
    assert(!tool.definition.description.contains("offline"))
    assert(!tool.definition.description.contains("tool.api"))
    val result = run(tool.execute(call).provide(executor(ZIO.succeed(CommandOutput(0, false, "42", false)))))
    assertEquals(ujson.read(result.text)("output").str, "42")
  }

  test("both tools coexist in one catalog and call only their own executor") {
    type Environment = ScalaCodeExecutor & ScalaCodeDynamicExecutor
    val tools: List[AgentTool[Environment]] = List(new ScalaTool(), new ScalaDynamicTool[Any](config))
    val restricted = ZLayer.succeed[ScalaCodeExecutor](new ScalaCodeExecutor {
      def isAvailable: UIO[Boolean] = ZIO.succeed(true)
      def execute(
          actual: String,
          timeout: Duration,
          maxOutputBytes: Int,
          dependencies: Seq[String]
      ): Task[CommandOutput] = {
        assertEquals(actual, "6 * 7")
        assertEquals(timeout, 45.seconds)
        assertEquals(maxOutputBytes, 64 * 1024)
        ZIO.succeed(CommandOutput(0, false, "external", false))
      }
    })
    val results = run {
      ZIO
        .foreach(tools) { tool =>
          val source = if tool.definition.key == ToolKey.Scalamode then "6 * 7" else code
          tool.execute(ExternalToolCall(tool.definition.key, ujson.write(ujson.Obj("code" -> source))))
        }
        .provideLayer(restricted ++ executor(ZIO.succeed(CommandOutput(0, false, "in-process", false))))
    }
    assertEquals(results.map(result => ujson.read(result.text)("output").str), List("external", "in-process"))
  }

  test("the same tool resolves the current executor on every invocation") {
    val tool = new ScalaDynamicTool[Any](config)
    val outputs = List("first", "second").map { value =>
      val result = run(tool.execute(call).provide(executor(ZIO.succeed(CommandOutput(0, false, value, false)))))
      ujson.read(result.text)("output").str
    }
    assertEquals(outputs, List("first", "second"))
  }

  test("invalid arguments are rejected without invoking the dynamic executor") {
    val tool = new ScalaDynamicTool[Any](config)
    val mustNotExecute = executor(ZIO.dieMessage("Invalid arguments must not execute snippets"))
    for arguments <- List("{}", "[]", """{"code":1}""", """{"code":""}""", """{"code":"ZIO.unit","extra":1}""") do {
      val result = run(tool.execute(call.copy(argumentsJson = arguments)).either.provide(mustNotExecute))
      assert(result.left.toOption.exists(_.isInstanceOf[YallmoError.InvalidToolArguments]))
    }
  }

  test("infrastructure failures identify the dynamic tool") {
    val failure = new RuntimeException("compiler could not start")
    val result = run(new ScalaDynamicTool[Any](config).execute(call).either.provide(executor(ZIO.fail(failure))))
    assertEquals(result, Left(YallmoError.ToolExecutionFailed(ToolKey.ScalaUnsafe, failure.getMessage, failure)))
  }

  test("compiler failures remain command results") {
    val result = run(
      new ScalaDynamicTool[Any](config)
        .execute(call)
        .provide(executor(ZIO.succeed(CommandOutput(1, false, "Compilation failed", false))))
    )
    val output = ujson.read(result.text)
    assertEquals(output("exitCode").num.toInt, 1)
    assertEquals(output("output").str, "Compilation failed")
    assert(!output.obj.contains("hint"))
  }

  test("dynamic timeout hints describe cooperative in-process cancellation") {
    val output = ujson.read(ScalaDynamicTool.render(CommandOutput(124, true, "deadline", true)))
    assert(output("hint").str.contains("cooperative"))
    assert(!output("hint").str.contains("offline"))
    assert(output("timedOut").bool)
    assert(output("truncated").bool)
  }

  test("tool limits are validated at construction") {
    intercept[IllegalArgumentException](new ScalaDynamicTool[Any](config, timeout = Duration.Zero))
    intercept[IllegalArgumentException](new ScalaDynamicTool[Any](config, maxOutputBytes = 0))
  }
}
