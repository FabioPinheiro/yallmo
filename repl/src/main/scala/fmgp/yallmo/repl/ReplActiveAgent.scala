package fmgp.yallmo.repl

import fmgp.yallmo.model.{Agent, AgentId}
import fmgp.yallmo.model.llm.ThinkingLevel
import fmgp.yallmo.orchestration.NativeFunctionAgent
import java.util.Locale

/** Immutable feedback from the same native configuration used for outgoing turns, not server defaults. */
final case class ReplActiveAgent(
    agentId: String,
    provider: String,
    model: String,
    thinkingLevel: Option[ThinkingLevel]
) {
  def thinkingLabel: String = thinkingLevel.fold("default")(_.toString.toLowerCase(Locale.ROOT))
}

object ReplActiveAgent {
  private[repl] def fromNative[R <: Agent[Any]](
      selection: ReplProviderSelection,
      agent: NativeFunctionAgent[R]
  ): ReplActiveAgent =
    ReplActiveAgent(
      AgentId.value(agent.id),
      selection match {
        case ReplProviderSelection.Oai     => "oai"
        case ReplProviderSelection.ChatGpt => "openai-codex"
      },
      agent.model,
      agent.options.thinkingLevel
    )
}
