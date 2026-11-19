package fmgp.yallmo.model.scalamode

import fmgp.yallmo.orchestration.{AgentFunction, ExternalFunctionCall, ExternalFunctionResult}
import fmgp.yallmo.tool.{FunctionKey, FunctionToolDefinition, ScalaModeResult}
import fmgp.yallmo.tool.scalamode.{ScalaCodeExecutor, ScalamodeFunction}
import munit.ZSuite
import zio.*

final class ScalamodeFunctionSuite extends ZSuite {
  private case class Prefix(value: String)
  private val definition = FunctionToolDefinition(FunctionKey.scalamode, "Scalamode")
  private val call = ExternalFunctionCall(FunctionKey.scalamode, "42")
  private val executor = new ScalaCodeExecutor {
    def isAvailable = ZIO.succeed(true)
    def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) =
      ZIO.succeed(ExternalFunctionResult.ScalaMode(ScalaModeResult.Executed(code)))
  }
  private val integrated: ScalamodeFunction[Prefix] = new ScalamodeFunction[Prefix] {
    val definition = ScalamodeFunctionSuite.this.definition
    def execute(call: ExternalFunctionCall) =
      ZIO.serviceWith[Prefix](prefix => ExternalFunctionResult(prefix.value + call.argumentsJson))
    override def withExecutor(executor: ScalaCodeExecutor) = Some(new ScalamodeFunction[Prefix] {
      val definition = integrated.definition
      def execute(call: ExternalFunctionCall) =
        ZIO.serviceWithZIO[Prefix](prefix =>
          executor.execute(prefix.value + call.argumentsJson, 1.second, 100, Seq.empty).orDie
        )
    })
    override def bindDefaultScalamode(binding: ScalamodeFunction.DefaultBinding) = {
      val replacement = binding.bind(ScalamodeFunction.Settings(1.second, 100, Seq.empty))
      Some(new ScalamodeFunction[Prefix] {
        val definition = replacement.definition
        def execute(call: ExternalFunctionCall) = ZIO.serviceWithZIO[Prefix](prefix =>
          replacement.execute(call).map(output => ExternalFunctionResult(prefix.value + output.text))
        )
      })
    }
  }

  testZ("environment binding preserves both execution paths and explicit integration") {
    val bound: AgentFunction[Any] = AgentFunction.provideEnvironment(integrated, ZEnvironment(Prefix("bound-")))
    for {
      normal <- bound.execute(call)
      manual <- ScalamodeFunction.from(bound).get.withExecutor(executor).get.execute(call)
    } yield {
      assertEquals(normal.text, "bound-42")
      assertEquals(manual.text, "bound-42")
    }
  }

  testZ("rebinding an environment-bound default retains its environment") {
    val bound = AgentFunction.provideEnvironment(integrated, ZEnvironment(Prefix("bound-")))
    val replacement = new ScalamodeFunction[Any] {
      val definition = ScalamodeFunctionSuite.this.definition
      def execute(call: ExternalFunctionCall) = ZIO.succeed(ExternalFunctionResult("replacement"))
    }
    val rebound = ScalamodeFunction
      .from(bound)
      .get
      .bindDefaultScalamode(new ScalamodeFunction.DefaultBinding {
        def bind(settings: ScalamodeFunction.Settings) = replacement
      })
      .get
    rebound.execute(call).map(output => assertEquals(output.text, "bound-replacement"))
  }

  testZ("opaque wrappers do not acquire integration from their definition or captured function") {
    val opaque = new AgentFunction[Prefix] {
      val definition = integrated.definition
      def execute(call: ExternalFunctionCall) = integrated.execute(call)
    }
    val bound = AgentFunction.provideEnvironment(opaque, ZEnvironment(Prefix("opaque-")))
    bound.execute(call).map { output =>
      assertEquals(output.text, "opaque-42")
      assertEquals(ScalamodeFunction.from(opaque), None)
      assertEquals(ScalamodeFunction.from(bound), None)
    }
  }
}
