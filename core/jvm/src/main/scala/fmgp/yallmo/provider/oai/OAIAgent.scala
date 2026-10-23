package fmgp.yallmo.provider.oai

import fmgp.yallmo.LlmProviderPayloadDebug
import fmgp.yallmo.model.{AgentId, AudioAgent, YallmoError, MessagePayload}
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
      resolved <- ZIO.foreach(request.input.collect { case audio: MessagePayload.AudioAttachment => audio }.distinct) {
        audio =>
          OAILlmProvider.resolveAudio(audio, request.readBlob).map(audio.blobId -> _)
      }
      turnId = LlmProviderPayloadDebug.newTurnId()
      diagnostic = OAIChatCompletionsMapper.turnRequestBody(request.model, request)
      _ <- LlmProviderPayloadDebug.captureRequest(turnId, "OpenAI-compatible endpoint", request.model, diagnostic)
      body = OAIChatCompletionsMapper.turnRequestBody(request.model, request, resolved.toMap)
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
