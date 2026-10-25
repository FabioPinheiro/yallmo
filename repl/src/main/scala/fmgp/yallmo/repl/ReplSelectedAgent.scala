package fmgp.yallmo.repl

import fmgp.yallmo.model.{Agent, AgentId}
import fmgp.yallmo.server.application.ConfiguredAgentBinding

/** Terminal feedback around the server-owned configured execution binding. */
private[repl] final case class ReplSelectedAgent(binding: ConfiguredAgentBinding) {
  def adapter: Agent[Any] = binding.adapter
  def systemPrompt: String = binding.systemPrompt

  def feedback: ReplActiveAgent = binding match {
    case ConfiguredAgentBinding.Native(_, providerId, program, _) => ReplActiveAgent.fromNative(providerId, program)
    case ConfiguredAgentBinding.Folder(adapter, _) => ReplActiveAgent(AgentId.value(adapter.id), "none", "n/a", None)
  }

}
