package fmgp.yallmo.orchestration

import fmgp.yallmo.model.YallmoError
import zio.*
import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.*
import fmgp.yallmo.orchestration.{AgentTask, AgentTurn, ExternalToolCall, RunContext, StaticContext}

/** An agent that uses provider-native function calls while leaving execution to [[AgentRunner]]. */
final case class NativeFunctionAgent[R <: Agent[Any]](
    id: AgentId,
    model: String,
    options: ProviderGenerateOptions = ProviderGenerateOptions(responseMimeType = None),
    parallelToolCalls: Boolean = false
) extends AgentInstance[R] {
  override def next(
      task: AgentTask,
      staticContext: StaticContext[R],
      runContext: RunContext
  ): ZIO[R, YallmoError, AgentTurn] =
    nextWith(staticContext, runContext, request => ZIO.serviceWithZIO[Agent[Any]](_.turn(request)))

  /** Binds a resolved runtime adapter explicitly rather than requiring a global Agent service.
    *
    * @tparam R1
    *   environment used by the enclosing runner's tools/context, not by this already-initialized adapter
    * @param provider
    *   resolved agent used for every provider-native turn in this program
    */
  def bind[R1](provider: Agent[Any]): AgentInstance[R1] = new AgentInstance[R1] {
    override val id: AgentId = NativeFunctionAgent.this.id
    override def next(
        task: AgentTask,
        staticContext: StaticContext[R1],
        runContext: RunContext
    ): ZIO[R1, YallmoError, AgentTurn] = nextWith(staticContext, runContext, provider.turn)
  }

  private def nextWith[R1](
      staticContext: StaticContext[R1],
      runContext: RunContext,
      send: AgentTurnRequest => ZIO[R1, YallmoError, AgentTurnResponse]
  ): ZIO[R1, YallmoError, AgentTurn] =
    val request = AgentTurnRequest(
      model = model,
      input = NativeFunctionAgent.input(runContext, staticContext),
      tools = staticContext.tools.map(definition =>
        FunctionToolDefinition(definition.key, definition.description, definition.inputSchema)
      ),
      parallelToolCalls = parallelToolCalls,
      options = options
    )
    send(request)
      .mapError(error => YallmoError.AgentExecutionFailed(id, error.getMessage, error))
      .flatMap { response =>
        val toolCalls = response.output.collect { case MessagePayload.FunctionCallRequest(call) => call }
        val text = response.output.collect { case MessagePayload.Assistant(value) => value }.mkString
        val assistant = Option.when(text.nonEmpty)(MessagePayload.Assistant(text))
        if toolCalls.nonEmpty then
          ZIO.succeed(
            AgentTurn(
              assistant,
              AgentDecision.Actions(
                toolCalls.map(call =>
                  LlmAction.CallExternalTool(ExternalToolCall(call.name, call.argumentsJson, Some(call.id)))
                )
              ),
              NativeFunctionAgent.reasoningMessages(response.output)
            )
          )
        else
          ZIO
            .fromOption(Option.when(text.trim.nonEmpty)(text))
            .orElseFail(
              YallmoError.InvalidAgentDecision("provider returned neither final text nor function calls")
            )
            .map(text =>
              AgentTurn(
                Some(MessagePayload.Assistant(text)),
                AgentDecision.FinalAnswer(text),
                NativeFunctionAgent.reasoningMessages(response.output)
              )
            )
      }
}

object NativeFunctionAgent {
  private def instructions(staticContext: StaticContext[?]): String =
    Chunk(staticContext.systemPrompt, staticContext.skills.map(_.instructions).mkString("\n\n"))
      .filter(_.nonEmpty)
      .mkString("\n\n")

  private def input(runContext: RunContext, staticContext: StaticContext[?]): Chunk[MessagePayload] =
    runContext.messages.flatMap {
      case MessagePayload.User(text)                       => Chunk(MessagePayload.User(text))
      case MessagePayload.Assistant(text)                  => Chunk(MessagePayload.Assistant(text))
      case MessagePayload.FunctionCallRequest(call)        => Chunk(MessagePayload.FunctionCallRequest(call))
      case MessagePayload.FunctionCallOutput(output)       => Chunk(MessagePayload.FunctionCallOutput(output))
      case MessagePayload.Reasoning(summary, continuation) => Chunk(MessagePayload.Reasoning(summary, continuation))
      case MessagePayload.ToolResult(toolKey, text) => Chunk(MessagePayload.Context(s"tool result from $toolKey", text))
      case MessagePayload.RagResult(retrieverKey, documents) =>
        val rendered = documents.map(document => s"source=${document.source}\n${document.text}").mkString("\n\n")
        Chunk(MessagePayload.Context(s"RAG result from $retrieverKey", rendered))
      case context: MessagePayload.Context => Chunk(context)
      case _: MessagePayload.Instructions  => Chunk.empty
    } :+ MessagePayload.Instructions(instructions(staticContext))

  private[orchestration] def reasoningMessages(output: Chunk[MessagePayload]): Chunk[MessagePayload] =
    output.collect { case reasoning: MessagePayload.Reasoning => reasoning }
}
