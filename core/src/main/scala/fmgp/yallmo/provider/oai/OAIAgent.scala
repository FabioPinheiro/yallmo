package fmgp.yallmo.provider.oai

import fmgp.yallmo.LlmProviderPayloadDebug
import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.{Agent, AgentId, MessagePayload}
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import zio.*

/** Provider-native OpenAI-compatible agent backed by a [[OAILlmProvider]].
  *
  * It performs one native tool-calling turn at a time; [[fmgp.yallmo.orchestration.AgentRunner]] owns tool execution
  * and subsequent turns.
  */
final class OAIAgent private[oai] (
    override val id: AgentId,
    provider: OAILlmProvider
) extends Agent[Any] {

  /** Performs one Chat Completions turn; AgentRunner remains responsible for tools and looping. */
  override def turn(turnRequest: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
    (for {
      _ <- ZIO.fromEither(turnRequest.validate.left.map(YallmoError.InvalidConfig.apply))
      turnId = LlmProviderPayloadDebug.newTurnId()
      response <- callTurn(turnRequest, turnId)
      turn <- ZIO.fromEither(
        OAIChatCompletionsMapper.agentTurnResponse(response).left.map(YallmoError.ProtocolFailure.apply)
      )
      _ <- LlmProviderPayloadDebug.captureResponse(turnId, "OpenAI-compatible endpoint", turnRequest.model, turn)
    } yield turn)
      .tapBoth(
        error =>
          ZIO.logError(
            s"OpenAI-compatible endpoint turn failed model=${turnRequest.model}: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
          ),
        turn =>
          ZIO.logInfo(
            s"OpenAI-compatible endpoint turn completed model=${turnRequest.model} stopReason=${turn.stopReason} toolCalls=${turn.output.count(_.isInstanceOf[MessagePayload.FunctionCallRequest])}"
          )
      )
      .mapError(YallmoError.fromThrowable)

  private def callTurn(turnRequest: AgentTurnRequest, turnId: String): IO[YallmoError, OAIResponse] = {
    val body = OAIChatCompletionsMapper.turnRequestBody(turnRequest.model, turnRequest)
    LlmProviderPayloadDebug.captureRequest(turnId, "OpenAI-compatible endpoint", turnRequest.model, body) *>
      ZIO.logInfo(
        s"Calling OpenAI-compatible endpoint model=${turnRequest.model} turnId=$turnId messages=${turnRequest.input.size} tools=${turnRequest.tools.map(_.name).mkString("[", ",", "]")}"
      ) *>
      provider.complete(body)
  }
}

object OAIAgent {
  def live(id: AgentId): URLayer[OAILlmProvider, OAIAgent] =
    ZLayer.fromFunction((provider: OAILlmProvider) => new OAIAgent(id, provider))
}
