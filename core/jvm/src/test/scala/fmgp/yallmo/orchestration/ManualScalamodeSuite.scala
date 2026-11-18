package fmgp.yallmo.orchestration

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.tool.*
import fmgp.yallmo.tool.scalamode.*
import munit.FunSuite
import zio.*

final class ManualScalamodeSuite extends FunSuite {
  private def run[A](effect: ZIO[Any, Any, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
  private val executor = new ScalaCodeExecutor {
    def isAvailable = ZIO.succeed(true)
    def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) =
      ZIO.dieMessage("Approval must prevent execution")
  }
  private def tool(key: ToolKey, function: AgentFunction[Any]) =
    AgentTool(ToolDefinition(key, Set(function.definition)), Chunk(function))
  test("default wiring retains approval around a built-in after rebinding") {
    val approval = new FunctionApproval[ScalaCodeExecutor] {
      def approve(definition: FunctionToolDefinition, call: ExternalFunctionCall) =
        ZIO.fail(YallmoError.InvalidToolArguments(call.functionKey, "approval denied"))
    }
    val guarded = FunctionApproval.guard(new ScalaTool(), approval)
    val bound = AgentFunction.provideEnvironment(guarded, ZEnvironment(executor))
    val wired = run(AgentToolWirings.withDefaults(executor)(Seq(tool(ToolKey.Scalamode, bound))))
    assertEquals(wired.head.functions.head.definition.scalamode.get.services, List(HelloWorldService.inspection))
    val result =
      run(wired.head.functions.head.execute(ExternalFunctionCall(FunctionKey.scalamode, """{"code":"42"}""")).either)
    assert(result.left.toOption.exists(_.getMessage.contains("approval denied")))
    val manualResult = run(
      ScalamodeFunction
        .from(wired.head.functions.head)
        .get
        .manualScalamode
        .get
        .execute(ExternalFunctionCall(FunctionKey.scalamode, """{"code":"42"}"""), executor)
        .either
    )
    assert(manualResult.left.toOption.exists(_.getMessage.contains("approval denied")))
  }

  test("approval outside an environment wrapper survives rebinding on both execution paths") {
    val bound = AgentFunction.provideEnvironment(new ScalaTool(), ZEnvironment(executor))
    val approval = new FunctionApproval[Any] {
      def approve(definition: FunctionToolDefinition, call: ExternalFunctionCall) =
        ZIO.fail(YallmoError.InvalidToolArguments(call.functionKey, "approval denied"))
    }
    val guarded = FunctionApproval.guard(bound, approval)
    val wired = run(AgentToolWirings.withDefaults(executor)(Seq(tool(ToolKey.Scalamode, guarded))))
    val function = wired.head.functions.head
    val call = ExternalFunctionCall(FunctionKey.scalamode, """{"code":"42"}""")
    val normal = run(function.execute(call).either)
    val manual = run(ScalamodeFunction.from(function).get.manualScalamode.get.execute(call, executor).either)
    assert(normal.left.toOption.exists(_.getMessage.contains("approval denied")))
    assert(manual.left.toOption.exists(_.getMessage.contains("approval denied")))
    assert(run(AgentToolWirings.withDefaults(executor)(wired)) eq wired)
  }

  test("approval does not promote an opaque function to integrated Scalamode") {
    val opaque = new AgentFunction[Any] {
      val definition = ScalaTool.definition
      def execute(call: ExternalFunctionCall) = ZIO.succeed(ExternalFunctionResult("custom"))
    }
    val approval = new FunctionApproval[Any] {
      def approve(definition: FunctionToolDefinition, call: ExternalFunctionCall) = ZIO.unit
    }
    val guarded = FunctionApproval.guard(opaque, approval)
    val input = Seq(tool(ToolKey.Scalamode, guarded))
    assertEquals(ScalamodeFunction.from(guarded), None)
    assert(run(AgentToolWirings.withDefaults(executor)(input)) eq input)
    assertEquals(run(guarded.execute(ExternalFunctionCall(FunctionKey.scalamode, """{"code":"42"}"""))).text, "custom")
  }

  test("default filesystem opt-in pure operation retains plain tool host limits without touching service metadata") {
    val fs = run(fmgp.yallmo.fs.FileSystem.open("."))
    val builtin = AgentFunction.provideEnvironment(new ScalaTool(7.seconds, 1234, Seq.empty), ZEnvironment(executor))
    val read = new AgentFunction[Any] {
      val definition = FilesystemToolDefinition.ReadFileDefinition
      def execute(call: ExternalFunctionCall) = ZIO.dieMessage("No service execution")
    }
    val list = new AgentFunction[Any] {
      val definition = FilesystemToolDefinition.ListFilesDefinition
      def execute(call: ExternalFunctionCall) = ZIO.dieMessage("No service execution")
    }
    val input = Seq(
      tool(ToolKey.Scalamode, builtin),
      AgentTool(ToolDefinition(ToolKey.Filesystem, Set(read.definition, list.definition)), Chunk(read, list))
    )
    val wired = run(AgentToolWirings.withDefaults(executor, Some(fs), Some(fs))(input)).head.functions.head
    assertEquals(wired.definition.scalamode.get.services.map(_.parameter), List("fs", "hello"))
    val pure = new ScalaCodeExecutor {
      def isAvailable = ZIO.succeed(true)
      def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) = {
        assertEquals(code, "42")
        assertEquals(timeout, 7.seconds)
        assertEquals(maxOutputBytes, 1234)
        assertEquals(dependencies, Seq.empty[String])
        ZIO.succeed(ExternalFunctionResult.ScalaMode(ScalaModeResult.Executed("42")))
      }
    }
    val result =
      run(
        ScalamodeFunction
          .from(wired)
          .get
          .manualScalamode
          .get
          .execute(ExternalFunctionCall(FunctionKey.scalamode, """{"code":"42"}"""), pure)
      )
    assertEquals(result.text, "42")
  }
}
