package fmgp.yallmo.orchestration

import fmgp.yallmo.model.YallmoError
import zio.*
import zio.json.*
import zio.json.ast
import fmgp.yallmo.orchestration.{AgentDecision, ExternalToolCall, LlmAction, RagQuery, StaticContext}

/** Strict JSON protocol for [[LlmDecisionAgent]].
  *
  * The protocol keeps model output declarative: a model can request retrieval or a registered external tool, but it
  * cannot execute either directly.
  */
object JsonAgentDecisionCodec extends AgentDecisionCodec {
  override val instructions: String =
    """Return exactly one JSON object and no Markdown.
      |
      |To finish, return:
      |{"finalAnswer":"your answer"}
      |
      |To request actions, return:
      |{"actions":[...]}
      |
      |Each action has one of these forms:
      |{"kind":"retrieve_rag","retrieverKey":"knowledge","query":"search terms"}
      |{"kind":"call_external_tool","toolKey":"tool-name","arguments":{"key":"value"}}""".stripMargin

  override def decode(response: String, _staticContext: StaticContext[?]): IO[YallmoError, AgentDecision] =
    ZIO.fromEither(response.fromJson[DecisionPayload]).mapError(YallmoError.InvalidAgentDecision.apply).flatMap {
      case DecisionPayload(Some(answer), actions) if actions.isEmpty => ZIO.succeed(AgentDecision.FinalAnswer(answer))
      case DecisionPayload(Some(_), _)                               =>
        ZIO.fail(YallmoError.InvalidAgentDecision("finalAnswer cannot be combined with actions"))
      case DecisionPayload(None, actions) if actions.nonEmpty =>
        ZIO.foreach(actions)(toAction).map(AgentDecision.Actions.apply)
      case DecisionPayload(None, _) =>
        ZIO.fail(YallmoError.InvalidAgentDecision("response requires finalAnswer or a non-empty actions array"))
    }

  private def toAction(action: ActionPayload): IO[YallmoError, LlmAction] =
    action.kind match
      case "retrieve_rag" =>
        required(action.retrieverKey, "retrieverKey").zipWith(required(action.query, "query")) {
          (retrieverKey, query) =>
            LlmAction.RetrieveRag(retrieverKey, RagQuery(query))
        }
      case "call_external_tool" =>
        required(action.toolKey, "toolKey").map { toolKey =>
          LlmAction.CallExternalTool(ExternalToolCall(toolKey, action.arguments.fold("{}")(_.toJson)))
        }
      case unsupported =>
        ZIO.fail(YallmoError.InvalidAgentDecision(s"unsupported action kind '$unsupported'"))

  private def required(value: Option[String], field: String): IO[YallmoError, String] =
    ZIO
      .fromOption(value.map(_.trim).filter(_.nonEmpty))
      .orElseFail(YallmoError.InvalidAgentDecision(s"action requires non-empty $field"))

  private final case class DecisionPayload(
      finalAnswer: Option[String] = None,
      actions: Chunk[ActionPayload] = Chunk.empty
  ) derives JsonDecoder

  private final case class ActionPayload(
      kind: String,
      retrieverKey: Option[String] = None,
      query: Option[String] = None,
      toolKey: Option[String] = None,
      arguments: Option[ast.Json] = None
  ) derives JsonDecoder
}
