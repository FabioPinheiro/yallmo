package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.Var
import fmgp.yallmo.model.*
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import java.time.Instant
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import zio.json.*
import zio.json.ast.Json

private[web] final case class ScalamodeResult(message: Message) {
  val output: String = message.payload.contentStr
  val fields: Option[ToolResultContent.Result] = ToolResultContent.parse(output)
}

private[web] enum ScalamodeTool(val key: String) {
  case Safe extends ScalamodeTool("scalamode")
  case Dynamic extends ScalamodeTool("scalaunsafe")
}

private[web] final case class ScalamodeState(
    code: String = "tool.api\nval numbers = List(1, 2, 3)\nnumbers.map(_ * 2).sum",
    tool: ScalamodeTool = ScalamodeTool.Safe,
    agents: List[AgentId] = Nil,
    enabled: Boolean = false,
    pending: Boolean = false,
    result: Option[ScalamodeResult] = None,
    notice: String = ""
) {
  def blockedReason(agent: Option[AgentId]): Option[String] =
    if !enabled then Some("Connect as an operator to a server supporting agents.invoke.")
    else if pending then Some(s"Waiting for ${tool.key}. Execution is not cancelled by leaving this page.")
    else if !agent.exists(agents.contains) then Some("Choose a configured agent in the toolbar.")
    else if code.trim.isEmpty then Some("Enter a Scala block.")
    else if code.length > 65536 then Some("Scala code exceeds 65,536 characters.")
    else None
}

/** Root-owned playground state. Requests are explicit, correlated, never retried, and survive tab navigation. Agent
  * selection is read from the shared Chat toolbar and captured at Run time, not stored independently here.
  * Disconnect/reconfiguration invalidates callbacks without claiming that admitted backend work was cancelled.
  */
private[web] final class ScalamodeModel(
    invoke: AgentsInvoke.Params => Future[Message],
    selectedAgent: () => Option[AgentId]
)(using ExecutionContext) {
  val state = Var(ScalamodeState())
  private var identity = Option.empty[AgentId]
  private var version = 0L

  def configure(info: Initialize.Result): Unit = {
    version += 1
    identity = Some(info.identity.id)
    val agents = info.configuredAgentIds.toList.flatMap(_.toList)
    state.update(s =>
      s.copy(
        agents = agents,
        enabled = info.identity.role == Role.Operator && info.methods.contains(AgentsInvoke.method),
        pending = false
      )
    )
  }

  def disable(): Unit = {
    version += 1
    identity = None
    state.update(s =>
      s.copy(
        enabled = false,
        pending = false,
        notice =
          if s.pending then "Execution outcome unknown. Reconnect and inspect All messages before a manual repeat."
          else s.notice
      )
    )
  }

  def code(value: String): Unit = state.update(_.copy(code = value))

  def run(tool: ScalamodeTool = ScalamodeTool.Safe): Unit = {
    val current = state.now()
    val selected = selectedAgent()
    current.blockedReason(selected) match {
      case Some(reason) => state.update(_.copy(notice = reason))
      case None         =>
        val agent = selected.get
        val call = FunctionCall(
          FunctionCallId.unsafe(s"${tool.key}-${UUID.randomUUID()}"),
          tool.key,
          Json.Obj("code" -> Json.Str(current.code)).toJson
        )
        val input = Message(MessageData(identity.get, None, Instant.now(), MessagePayload.ToolCall(call)))
        version += 1
        val requestVersion = version
        state.update(_.copy(tool = tool, pending = true, result = None, notice = ""))
        Try(invoke(AgentsInvoke.Params(agent, input))).fold(Future.failed, value => value).onComplete {
          case Success(message) if requestVersion == version =>
            val correlated = message.agentId == agent && message.parentId.contains(input.id) && (message.payload match {
              case MessagePayload.ToolOutput(output) => output.callId == call.id && output.name == call.name
              case _                                 => false
            })
            if correlated then state.update(_.copy(pending = false, result = Some(ScalamodeResult(message))))
            else
              state.update(
                _.copy(pending = false, notice = "Uncorrelated tool response. Inspect All messages before repeating.")
              )
          case Failure(error) if requestVersion == version =>
            state.update(
              _.copy(
                pending = false,
                notice =
                  s"${Option(error.getMessage).getOrElse("Invocation failed")}. Input may have been saved; inspect All messages before repeating."
              )
            )
          case _ => ()
        }
    }
  }
}
