package fmgp.yallmo.orchestration

import fmgp.yallmo.model.YallmoError
import zio.*
import fmgp.yallmo.orchestration.{AgentToolDefinition, ExternalToolCall, ExternalToolResult}

/** Authorizes one model-proposed external-tool call before it is executed.
  *
  * Implementations may ask a person, evaluate a policy, or reject the call. Arguments are model-produced untrusted
  * input and must be validated before an approval is granted.
  */
trait ToolApproval[R] {
  def approve(definition: AgentToolDefinition, call: ExternalToolCall): ZIO[R, YallmoError, Unit]
}

object ToolApproval {

  /** Wraps a tool so that every call is approved before its implementation runs. */
  def guard[R](tool: AgentTool[R], approval: ToolApproval[R]): AgentTool[R] = new AgentTool[R] {
    override val definition: AgentToolDefinition = tool.definition

    override def execute(call: ExternalToolCall): ZIO[R, YallmoError, ExternalToolResult] =
      approval.approve(definition, call) *> tool.execute(call)
  }
}
