package fmgp.yallmo.repl

import fmgp.yallmo.model.{Agent, AgentId}
import fmgp.yallmo.orchestration.{ResolvedAgent, NativeFunctionAgent}

/** Terminal feedback from the same complete selection used for execution. */
private[repl] final case class ReplSelectedAgent(binding: ResolvedAgent) {
  def adapter: Agent[Any] = binding.adapter
  def systemPrompt: String = binding.systemPrompt

  def feedback: ReplActiveAgent = binding.settings match {
    case Some(settings) =>
      ReplActiveAgent.fromNative(
        binding.providerId.get,
        NativeFunctionAgent[Agent[Any]](
          binding.id,
          settings.model,
          fmgp.yallmo.model.llm
            .ProviderGenerateOptions(responseMimeType = None, thinkingLevel = settings.thinkingLevel),
          settings.parallelToolCalls
        )
      )
    case None => ReplActiveAgent(AgentId.value(binding.id), "none", "n/a", None)
  }
}
