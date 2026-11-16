package fmgp.yallmo.provider.openai

import fmgp.yallmo.LlmProviderPayloadDebug
import fmgp.yallmo.fs.{FsRead, FsWrite}
import fmgp.yallmo.model.{Agent, AgentId, MessagePayload, YallmoError}
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import fmgp.yallmo.orchestration.{AgentTool, ToolWiring}
import fmgp.yallmo.tool.scalamode.{AgentToolWirings, ScalaCodeExecutor, ScalaCodeExecutorLive}
import zio.*

/** Named ChatGPT agent over a shared subscription connection.
  *
  * Owns native turn validation, mapping and diagnostics. The provider owns OAuth and HTTP inference; the runner owns
  * tool execution and subsequent turns. Resolution binds hello and the permitted rooted filesystem inside an existing
  * default Scalamode function after custom wiring; no separate native tools are added. Custom service bindings and
  * missing Scalamode are preserved. The supplied executor is captured, never run during construction or resolution.
  */
final class ChatGPTAgent(
    val id: AgentId,
    provider: OpenAiCodexClient,
    toolWiring: ToolWiring = tools => ZIO.succeed(tools),
    scalaCodeExecutor: ScalaCodeExecutor = ScalaCodeExecutorLive.make(),
    filesystem: Option[FsRead] = None,
    filesystemWrite: Option[FsWrite] = None
) extends Agent[Any] {
  override def toolWirings(tools: Seq[AgentTool[Any]]): IO[YallmoError, Seq[AgentTool[Any]]] =
    for {
      customized <- toolWiring(tools)
      wired <- AgentToolWirings.withDefaults(scalaCodeExecutor, filesystem, filesystemWrite)(customized)
    } yield wired

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
            s"OpenAI Codex turn completed model=${request.model} stopReason=${turn.stopReason} toolCalls=${turn.output.count(_.isInstanceOf[MessagePayload.ToolCall])}"
          )
      )
}
