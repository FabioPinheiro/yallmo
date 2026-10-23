package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.*
import fmgp.yallmo.orchestration.config.{AgentCapabilities, HasAgentCapabilities}
import zio.*

/** The registry boundary cannot trust callers to have assembled requests through a native program. */
private[orchestration] class ConfiguredAgent(
    delegate: Agent[Any],
    val capabilities: AgentCapabilities
) extends Agent[Any]
    with HasAgentCapabilities {
  override val id: AgentId = delegate.id

  override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] = {
    val filtered = capabilities.filterRequest(request)
    delegate.turn(filtered).flatMap { response =>
      val forbidden = response.output.exists {
        case MessagePayload.FunctionCallRequest(call) =>
          capabilities.tools.isDefined && (!capabilities.allowsTool(call.name) || !filtered.tools.exists(
            _.name == call.name
          ) || filtered.toolChoice == ToolChoice.None)
        case _ => false
      }
      if forbidden then
        ZIO.fail(
          YallmoError.InvalidConfig(
            s"Agent '${AgentId.value(id)}' returned a tool call forbidden by its configured capability policy"
          )
        )
      else ZIO.succeed(response)
    }
  }
}

private[orchestration] object ConfiguredAgent {
  def apply(delegate: Agent[Any], capabilities: AgentCapabilities): Agent[Any] =
    if capabilities == AgentCapabilities() then delegate
    else
      delegate match {
        case _: AudioAgent[?] => new ConfiguredAgent(delegate, capabilities) with AudioAgent[Any]
        case _                => new ConfiguredAgent(delegate, capabilities)
      }
}
