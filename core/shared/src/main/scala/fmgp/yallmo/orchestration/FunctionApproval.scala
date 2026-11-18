package fmgp.yallmo.orchestration

import fmgp.yallmo.tool.scalamode.{ManualScalamode, ScalaCodeExecutor, ScalamodeFunction}
import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.tool.FunctionToolDefinition

import zio.*
import fmgp.yallmo.orchestration.{ExternalFunctionCall, ExternalFunctionResult}

/** Authorizes one model- or client-proposed external-tool call before it is executed.
  *
  * Implementations may ask a person, evaluate a policy, or reject the call. Arguments are caller-produced untrusted
  * input and must be validated before an approval is granted.
  */
trait FunctionApproval[R] {
  def approve(definition: FunctionToolDefinition, call: ExternalFunctionCall): ZIO[R, YallmoError, Unit]
}

object FunctionApproval {

  /** Wraps a tool so that every call is approved before its implementation runs. */
  def guard[R](tool: AgentFunction[R], approval: FunctionApproval[R]): AgentFunction[R] =
    ScalamodeFunction.from(tool) match {
      case Some(integrated) => guardScalamode(integrated, approval)
      case None             =>
        new AgentFunction[R] {
          override val definition: FunctionToolDefinition = tool.definition
          override def execute(call: ExternalFunctionCall): ZIO[R, YallmoError, ExternalFunctionResult] =
            approval.approve(definition, call) *> tool.execute(call)
        }
    }

  private def guardScalamode[R](
      tool: ScalamodeFunction[R],
      approval: FunctionApproval[R]
  ): ScalamodeFunction[R] = new ScalamodeFunction[R] {
    override val definition: FunctionToolDefinition = tool.definition

    override def bindDefaultScalamode(replacement: ScalamodeFunction[Any]): Option[ScalamodeFunction[R]] =
      tool.bindDefaultScalamode(replacement).map(guardScalamode(_, approval))

    override def manualScalamode = tool.manualScalamode.map(operation =>
      new ManualScalamode[R] {
        def execute(call: ExternalFunctionCall, executor: ScalaCodeExecutor) =
          approval.approve(definition, call) *> operation.execute(call, executor)
      }
    )

    override def execute(call: ExternalFunctionCall): ZIO[R, YallmoError, ExternalFunctionResult] =
      approval.approve(definition, call) *> tool.execute(call)
  }
}
