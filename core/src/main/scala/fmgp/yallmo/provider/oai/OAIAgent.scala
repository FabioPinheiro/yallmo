package fmgp.yallmo.provider.oai

import fmgp.yallmo.LlmProviderPayloadDebug
import fmgp.yallmo.model.{AgentId, AudioAgent, AudioInput, YallmoError}
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import zio.*

/** Named audio-capable OAI adapter. Tools and subsequent turns remain the caller's responsibility. */
final class OAIAgent private[oai] (
    override val id: AgentId,
    provider: OAILlmProvider
) extends AudioAgent[Any] {
  override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
    for {
      _ <- ZIO.fromEither(request.validate.left.map(YallmoError.InvalidConfig.apply))
      _ <- ZIO.foreachDiscard(request.input.collect { case audio: AudioInput => audio })(audio =>
        ZIO.fromEither(OAILlmProvider.validateAudio(audio).left.map(YallmoError.InvalidConfig.apply))
      )
      turnId = LlmProviderPayloadDebug.newTurnId()
      body = OAIChatCompletionsMapper.turnRequestBody(request.model, request)
      _ <- LlmProviderPayloadDebug.captureRequest(turnId, "OpenAI-compatible endpoint", request.model, body)
      response <- provider.complete(body)
      turn <- ZIO.fromEither(
        OAIChatCompletionsMapper.agentTurnResponse(response).left.map(YallmoError.ProtocolFailure.apply)
      )
      _ <- LlmProviderPayloadDebug.captureResponse(turnId, "OpenAI-compatible endpoint", request.model, turn)
    } yield turn
}

object OAIAgent {

  /** Constructs an agent over an already initialized, potentially shared connection. */
  def make(id: AgentId, provider: OAILlmProvider): OAIAgent = new OAIAgent(id, provider)
}
