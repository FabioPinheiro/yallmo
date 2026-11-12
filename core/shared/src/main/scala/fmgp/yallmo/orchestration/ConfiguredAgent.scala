package fmgp.yallmo.orchestration

import fmgp.yallmo.tool.ToolDefinition

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.*
import fmgp.yallmo.orchestration.config.{AgentCapabilities, HasAgentCapabilities}
import zio.*

/** The registry boundary cannot trust callers to have assembled requests through a native program. */
private[orchestration] class ConfiguredAgent(
    delegate: Agent[Any],
    val capabilities: AgentCapabilities
) extends Agent[Any]
    with HasAgentCapabilities
    with HasLocalAgentFunctions {
  override val localFunctions: Chunk[AgentFunction[Any]] = delegate match {
    // Keep the complete private catalog until policy validation; filtering here would hide valid-but-denied IDs.
    case scoped: HasLocalAgentFunctions => scoped.localFunctions
    case _                              => Chunk.empty
  }
  override val localTools: Chunk[ToolDefinition] = delegate match {
    case scoped: HasLocalAgentFunctions => scoped.localTools
    case _                              => Chunk.empty
  }
  override val id: AgentId = delegate.id

  override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] = {
    val catalog = request.toolGroups.filterNot(tool => localTools.exists(_.name == tool.name)) ++ localTools
    val filtered = capabilities.filterRequest(request.copy(toolGroups = catalog))
    delegate.turn(filtered).flatMap { response =>
      val forbidden = response.output.exists {
        case MessagePayload.ToolCall(call) =>
          (capabilities.tools.isDefined || capabilities.functions.isDefined) && !filtered.tools.exists(
            _.name == call.name
          )
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
