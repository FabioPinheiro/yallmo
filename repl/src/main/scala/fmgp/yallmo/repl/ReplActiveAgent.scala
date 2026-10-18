package fmgp.yallmo.repl

import fmgp.yallmo.model.{Agent, AgentId}
import fmgp.yallmo.model.llm.ThinkingLevel
import fmgp.yallmo.orchestration.NativeFunctionAgent
import fmgp.yallmo.orchestration.config.ProviderId
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
      providerId: ProviderId,
      agent: NativeFunctionAgent[R]
  ): ReplActiveAgent =
    ReplActiveAgent(
      AgentId.value(agent.id),
      ProviderId.value(providerId),
      agent.model,
      agent.options.thinkingLevel
    )
}
