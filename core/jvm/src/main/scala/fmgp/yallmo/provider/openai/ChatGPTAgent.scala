package fmgp.yallmo.provider.openai

import fmgp.yallmo.LlmProviderPayloadDebug
import fmgp.yallmo.model.{Agent, AgentId, MessagePayload, YallmoError}
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import zio.*

/** Named ChatGPT agent over a shared subscription connection.
  *
  * Owns native turn validation, mapping and diagnostics. The provider owns OAuth and HTTP inference; the runner owns
  * tool execution and subsequent turns.
  */
final class ChatGPTAgent(val id: AgentId, provider: OpenAiCodexClient) extends Agent[Any] {
  override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
    (for {
      body <- ZIO.fromEither(OpenAiCodexTurnMapper.requestBody(request).left.map(YallmoError.InvalidConfig.apply))
      turnId = LlmProviderPayloadDebug.newTurnId()
      _ <- LlmProviderPayloadDebug.captureRequest(turnId, "OpenAI Codex", request.model, body)
      _ <- ZIO.logInfo(
        s"Calling OpenAI Codex model=${request.model} turnId=$turnId inputItems=${request.input.size} tools=${request.tools.map(_.name).mkString("[", ",", "]")}"
      )
      response <- provider.complete(body)
      turn <- ZIO.fromEither(OpenAiCodexTurnMapper.response(response).left.map(YallmoError.ProtocolFailure.apply))
      _ <- LlmProviderPayloadDebug.captureResponse(turnId, "OpenAI Codex", request.model, turn)
    } yield turn)
      .tapBoth(
        error =>
          ZIO.logError(
            s"OpenAI Codex turn failed model=${request.model}: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
          ),
        turn =>
          ZIO.logInfo(
            s"OpenAI Codex turn completed model=${request.model} stopReason=${turn.stopReason} toolCalls=${turn.output.count(_.isInstanceOf[MessagePayload.FunctionCallRequest])}"
          )
      )
}
