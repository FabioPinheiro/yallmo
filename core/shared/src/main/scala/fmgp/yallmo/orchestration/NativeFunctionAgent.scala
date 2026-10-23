package fmgp.yallmo.orchestration

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.orchestration.config.{AgentCapabilities, HasAgentCapabilities}
import zio.*
import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.*
import fmgp.yallmo.orchestration.{AgentTask, AgentTurn, ExternalToolCall, RunContext, StaticContext}

/** An agent that uses provider-native function calls while leaving execution to [[AgentRunner]]. */
final case class NativeFunctionAgent[R <: Agent[Any]](
    id: AgentId,
    model: String,
    options: ProviderGenerateOptions = ProviderGenerateOptions(responseMimeType = None),
    parallelToolCalls: Boolean = false,
    override val capabilities: AgentCapabilities = AgentCapabilities()
) extends AgentInstance[R] {
  override def configuredContext(staticContext: StaticContext[R]): ZIO[R, YallmoError, StaticContext[R]] =
    ZIO.serviceWithZIO[Agent[Any]](provider => ZIO.fromEither(resolveContext(provider, staticContext)))

  override def next(
      task: AgentTask,
      staticContext: StaticContext[R],
      runContext: RunContext
  ): ZIO[R, YallmoError, AgentTurn] =
    ZIO.serviceWithZIO[Agent[Any]] { provider =>
      nextWith(staticContext, runContext, provider.turn, policyFor(provider))
    }

  /** Binds a resolved runtime adapter explicitly rather than requiring a global Agent service.
    *
    * @tparam R1
    *   environment used by the enclosing runner's tools/context, not by this already-initialized adapter
    * @param provider
    *   resolved agent used for every provider-native turn in this program
    */
  def bind[R1](provider: Agent[Any], readBlob: BlobId => IO[YallmoError, Blob] = Blob.unavailable): AgentInstance[R1] =
    new AgentInstance[R1] {
      override val id: AgentId = NativeFunctionAgent.this.id
      override val capabilities: AgentCapabilities = policyFor(provider)
      override def configuredContext(context: StaticContext[R1]): IO[YallmoError, StaticContext[R1]] =
        ZIO.fromEither(resolveContext(provider, context))
      override def next(
          task: AgentTask,
          staticContext: StaticContext[R1],
          runContext: RunContext
      ): ZIO[R1, YallmoError, AgentTurn] =
        nextWith(
          staticContext,
          runContext,
          request => provider.turn(request.copy(readBlob = readBlob)),
          capabilities
        )
    }

  private def resolveContext[A](
      provider: Agent[Any],
      context: StaticContext[A]
  ): Either[YallmoError, StaticContext[A]] =
    for {
      _ <- capabilities.resolve(id, context)
      _ <- provider match {
        case configured: HasAgentCapabilities => configured.capabilities.resolve(id, context).map(_ => ())
        case _                                => Right(())
      }
      resolved <- policyFor(provider).resolve(id, context)
    } yield resolved

  private def policyFor(provider: Agent[Any]): AgentCapabilities = provider match {
    case configured: HasAgentCapabilities => capabilities.intersect(configured.capabilities)
    case _                                => capabilities
  }

  private def nextWith[R1](
      staticContext: StaticContext[R1],
      runContext: RunContext,
      send: AgentTurnRequest => ZIO[R1, YallmoError, AgentTurnResponse],
      policy: AgentCapabilities
  ): ZIO[R1, YallmoError, AgentTurn] =
    ZIO.fromEither(policy.resolve(id, staticContext)).flatMap { context =>
      val request = AgentTurnRequest(
        model = model,
        input = NativeFunctionAgent.input(runContext, context),
        tools = context.tools
          .map(definition => FunctionToolDefinition(definition.key, definition.description, definition.inputSchema)),
        parallelToolCalls = parallelToolCalls,
        options = options
      )
      send(policy.filterRequest(request))
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
}

object NativeFunctionAgent {
  private def instructions(staticContext: StaticContext[?]): String =
    Chunk(staticContext.systemPrompt, staticContext.skills.map(_.instructions).mkString("\n\n"))
      .filter(_.nonEmpty)
      .mkString("\n\n")

  private def input(runContext: RunContext, staticContext: StaticContext[?]): Chunk[MessagePayload] =
    runContext.messages.flatMap {
      case payload: MessagePayload.AudioAttachment     => Chunk(payload)
      case payload: MessagePayload.User                => Chunk(payload)
      case payload: MessagePayload.Assistant           => Chunk(payload)
      case payload: MessagePayload.FunctionCallRequest => Chunk(payload)
      case payload: MessagePayload.FunctionCallOutput  => Chunk(payload)
      case payload: MessagePayload.Reasoning           => Chunk(payload)
      case MessagePayload.ToolResult(toolKey, text) => Chunk(MessagePayload.Context(s"tool result from $toolKey", text))
      case MessagePayload.RagResult(retrieverKey, documents) =>
        val rendered = documents.map(document => s"source=${document.source}\n${document.text}").mkString("\n\n")
        Chunk(MessagePayload.Context(s"RAG result from $retrieverKey", rendered))
      case payload: MessagePayload.Context => Chunk(payload)
      case _: MessagePayload.Instructions  => Chunk.empty
    } :+ MessagePayload.Instructions(instructions(staticContext))

  private[orchestration] def reasoningMessages(output: Chunk[MessagePayload]): Chunk[MessagePayload] =
    output.collect { case reasoning: MessagePayload.Reasoning => reasoning }
}
