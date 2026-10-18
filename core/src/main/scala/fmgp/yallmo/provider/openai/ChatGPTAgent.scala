package fmgp.yallmo.provider.openai

import fmgp.yallmo.model.{Agent, AgentId, YallmoError}
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import zio.IO

/** A named ChatGPT agent over a potentially shared subscription connection.
  *
  * Agent identity is independent of the provider/account. Requests, including their model and generation options, are
  * forwarded unchanged; tool execution remains the runner's responsibility.
  */
final class ChatGPTAgent(val id: AgentId, provider: OpenAiCodexClient) extends Agent[Any] {
  override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] = provider.turn(request)
}
