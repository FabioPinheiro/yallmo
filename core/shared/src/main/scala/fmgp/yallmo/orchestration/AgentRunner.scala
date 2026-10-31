package fmgp.yallmo.orchestration

import fmgp.yallmo.model.YallmoError
import java.util.UUID
import zio.*
import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.{
  AgentBudget,
  AgentResult,
  AgentTask,
  AgentTurnResult,
  ExternalToolCall,
  LlmAction,
  RunContext,
  StaticContext
}

/** AgentRunner is the trusted executor around an agent
  *
  *   - The agent decides: answer, call tools, or retrieve knowledge.
  *   - The runner executes those requests, checks permissions, and enforces limits.
  *   - The orchestrator persists messages and session (pointer to messages); the runner itself does not.
  */
final class AgentRunner[R](
    tools: Map[ToolKey, AgentTool[R]],
    retrievers: Map[String, KnowledgeRetriever[R]],
    val runnerId: UUID = UUID.randomUUID() //  Identifier added to logs
) {

  /** Builds an opaque loop handle. Only Orchestrator.submit can execute it and supply transcript persistence. */
  def execution(
      agent: AgentInstance[R],
      staticContext: StaticContext[R],
      budget: AgentBudget = AgentBudget.default
  ): AgentExecution[R] =
    new AgentExecution[R] {
      override val agentId: AgentId = agent.id
      override private[orchestration] def run(
          task: AgentTask,
          acceptedContext: RunContext,
          saveTranscriptLater: RunContext => UIO[Unit]
      ): ZIO[R, YallmoError, AgentTurnResult] =
        runAccepted(agent, task, staticContext, acceptedContext, budget, saveTranscriptLater)
    }

  /** Builds a single-message recipient for Orchestrator.invoke, never a model/tool loop.
    *
    * Explicit tool-call inputs execute just that tool. Other inputs produce at most one agent turn; a decision to
    * execute actions is rejected and must instead be submitted through the session execution boundary.
    */
  def invocation(
      agent: AgentInstance[R],
      staticContext: StaticContext[R],
      budget: AgentBudget = AgentBudget.default
  ): AgentInvocation[R] =
    new AgentInvocation[R] {
      override val agentId: AgentId = agent.id
      override private[orchestration] def invoke(input: MessagePayload): ZIO[R, YallmoError, MessagePayload] =
        (for {
          _ <- ZIO.fromEither(budget.validate)
          result <- input match {
            case MessagePayload.ToolCall(call) =>
              for {
                _ <- ZIO.fromEither(call.validate.left.map(YallmoError.InvalidMessage.apply))
                context <- agent.configuredContext(staticContext)
                result <- executeAction(agent, PreparedAction.CallExternalTool(call), context)
              } yield result
            case _: MessagePayload.Reasoning =>
              ZIO.fail(
                YallmoError.InvalidMessage("Provider continuations are server-owned; submit a Context summary instead")
              )
            case _ =>
              for {
                context <- agent.configuredContext(staticContext)
                prepared <- applyExtensions(context, RunContext(Chunk(input)))
                turn <- agent.next(AgentTask(input.contentStr), context, prepared)
                result <- turn.decision match {
                  case AgentDecision.FinalAnswer(text) if text.trim.nonEmpty =>
                    ZIO.succeed(MessagePayload.Assistant(text))
                  case AgentDecision.FinalAnswer(_) =>
                    ZIO.fail(YallmoError.InvalidAgentDecision("Agent invocation must return a non-blank answer"))
                  case AgentDecision.Actions(_) =>
                    ZIO.fail(YallmoError.InvalidAgentDecision("Agent actions require Orchestrator.submit"))
                }
              } yield result
          }
        } yield result).timeoutFail(YallmoError.AgentTimedOut(budget.timeout))(budget.timeout)
    }

  private def runAccepted(
      agent: AgentInstance[R],
      task: AgentTask,
      staticContext: StaticContext[R],
      runContext: RunContext,
      budget: AgentBudget,
      saveTranscriptLater: RunContext => UIO[Unit]
  ): ZIO[R, YallmoError, AgentTurnResult] =
    ZIO.logAnnotate(AgentRunner.LogAnnotationKey, runnerId.toString) {
      val execute =
        ZIO
          .fromEither(budget.validate)
          .flatMap { _ =>
            for
              effectiveContext <- agent.configuredContext(staticContext)
              turnCount <- Ref.make(0)
              actionCount <- Ref.make(0)
              agentRun <- runLoop(
                agent,
                task,
                effectiveContext,
                runContext,
                budget,
                turnCount,
                actionCount,
                saveTranscriptLater
              )
            yield agentRun
          }
          .timeoutFail(YallmoError.AgentTimedOut(budget.timeout))(budget.timeout)

      ZIO.logDebug(s"AgentRunner started") *>
        execute.tapBoth(
          error => ZIO.logError(s"AgentRunner failed: ${error.getMessage}"),
          _ => ZIO.logDebug(s"AgentRunner completed")
        )
    }

  private def runLoop(
      agent: AgentInstance[R],
      task: AgentTask,
      staticContext: StaticContext[R],
      runContext: RunContext,
      budget: AgentBudget,
      turnCount: Ref[Int],
      actionCount: Ref[Int],
      saveTranscriptLater: RunContext => UIO[Unit]
  ): ZIO[R, YallmoError, AgentTurnResult] =
    for
      turn <- turnCount.updateAndGet(_ + 1)
      _ <- ZIO.fail(YallmoError.TurnLimitExceeded(budget.maxTurns)).when(turn > budget.maxTurns)
      preparedRunContext <- applyExtensions(staticContext, runContext)
      agentTurn <- agent.next(task, staticContext, preparedRunContext)
      runContextWithTurn = agentTurn.assistant
        .fold(preparedRunContext)(preparedRunContext.append)
        .appendAll(agentTurn.additionalMessages)
      result <- agentTurn.decision match
        case AgentDecision.FinalAnswer(text) =>
          saveTranscriptLater(runContextWithTurn).as(AgentTurnResult(AgentResult(agent.id, text), runContextWithTurn))
        case AgentDecision.Actions(actions) =>
          if actions.isEmpty then ZIO.fail(YallmoError.EmptyActionBatch(agent.id))
          else
            for
              preparedActions <- prepareActions(actions)
              runContextWithCalls = runContextWithTurn.appendAll(
                preparedActions.collect { case PreparedAction.CallExternalTool(call) =>
                  MessagePayload.ToolCall(call)
                }
              )
              _ <- saveTranscriptLater(runContextWithCalls)
              nextContext <- executeActions(
                agent,
                preparedActions,
                staticContext,
                budget,
                actionCount,
                runContextWithCalls,
                saveTranscriptLater
              )
              result <- runLoop(
                agent,
                task,
                staticContext,
                nextContext,
                budget,
                turnCount,
                actionCount,
                saveTranscriptLater
              )
            yield result
    yield result

  private def applyExtensions(
      staticContext: StaticContext[R],
      runContext: RunContext
  ): ZIO[R, YallmoError, RunContext] =
    ZIO.foldLeft(staticContext.extensions)(runContext) { (current, contextExtension) =>
      contextExtension(staticContext, current)
    }

  /** Runner-internal actions cannot contain an uncorrelated tool invocation. */
  private enum PreparedAction {
    case RetrieveRag(retrieverKey: String, query: RagQuery)
    case CallExternalTool(call: FunctionCall)
  }

  private def prepareActions(actions: Chunk[LlmAction]): UIO[Chunk[PreparedAction]] =
    ZIO.foreach(actions) {
      case LlmAction.RetrieveRag(key, query) => ZIO.succeed(PreparedAction.RetrieveRag(key, query))
      case LlmAction.CallExternalTool(call)  =>
        val callId = call.callId match {
          case Some(id) => ZIO.succeed(id)
          case None     => Random.nextUUID.map(uuid => FunctionCallId.unsafe(s"call-$uuid"))
        }
        callId.map(id => PreparedAction.CallExternalTool(FunctionCall(id, call.toolKey, call.argumentsJson)))
    }

  private def executeActions(
      agent: AgentInstance[R],
      actions: Chunk[PreparedAction],
      staticContext: StaticContext[R],
      budget: AgentBudget,
      actionCount: Ref[Int],
      runContext: RunContext,
      saveTranscriptLater: RunContext => UIO[Unit]
  ): ZIO[R, YallmoError, RunContext] =
    ZIO.foldLeft(actions)(runContext) { (current, action) =>
      for
        count <- actionCount.updateAndGet(_ + 1)
        _ <- ZIO.fail(YallmoError.ActionLimitExceeded(budget.maxActions)).when(count > budget.maxActions)
        message <- executeAction(agent, action, staticContext)
        next = current.append(message)
        _ <- saveTranscriptLater(next)
      yield next
    }

  private def executeAction(
      agent: AgentInstance[R],
      action: PreparedAction,
      staticContext: StaticContext[R]
  ): ZIO[R, YallmoError, MessagePayload] =
    action match
      case PreparedAction.RetrieveRag(retrieverKey, query) =>
        if !staticContext.retrievers.exists(_.key == retrieverKey) then
          ZIO.fail(YallmoError.RetrieverUnavailableToAgent(agent.id, retrieverKey))
        else
          ZIO
            .fromOption(retrievers.get(retrieverKey))
            .orElseFail(YallmoError.UnknownRetriever(retrieverKey))
            .flatMap(_.retrieve(query))
            .map(MessagePayload.RagResult(retrieverKey, _))

      // case PreparedAction.CallExternalTool(functionCall) => ??? // TODO scalamode
      case PreparedAction.CallExternalTool(functionCall) =>
        val call = ExternalToolCall(functionCall.name, functionCall.argumentsJson, Some(functionCall.id))
        val execute =
          if !staticContext.tools.exists(_.key == call.toolKey) then
            ZIO.fail(YallmoError.ToolUnavailableToAgent(agent.id, call.toolKey))
          else
            ZIO
              .fromOption(staticContext.tools.find(_.key == call.toolKey))
              .orElseFail(YallmoError.ToolUnavailableToAgent(agent.id, call.toolKey))
              .flatMap { definition =>
                ZIO
                  .fromEither(ToolArgumentsValidator.validate(call.argumentsJson, definition.inputSchema))
                  .mapError(YallmoError.InvalidToolArguments(call.toolKey, _))
              }
              .zipRight(
                ZIO
                  .fromOption(tools.get(call.toolKey))
                  .orElseFail(YallmoError.UnknownTool(call.toolKey))
                  .flatMap(_.execute(call))
              )

        execute.fold(
          error => toolMessage(functionCall, ExternalToolResult(toolErrorOutput(error))),
          output => toolMessage(functionCall, output)
        )

  private def toolMessage(call: FunctionCall, result: ExternalToolResult): MessagePayload = {
    val output = if call.name == ToolKey.Scalamode then
      result match {
        case value: ExternalToolResult.ScalaMode =>
          ScalaModeOutput(
            call.id,
            value.result,
            value.logs,
            value.exitCode,
            value.truncated,
            value.compilationTime,
            value.executionTime,
            value.totalTime
          )
        case ExternalToolResult.Generic(text) =>
          ScalaModeOutput(call.id, ScalaModeResult.Executed(text), exitCode = 1)
      }
    else FunctionGenericOutput(call.id, call.name, result.text)
    MessagePayload.ToolOutput(output)
  }

  private def toolErrorOutput(error: YallmoError): String =
    ujson.write(
      ujson.Obj(
        "error" -> ujson.Obj(
          "kind" -> error.getClass.getSimpleName.stripSuffix("$"),
          "message" -> error.getMessage
        )
      )
    )
}

object AgentRunner {
  val LogAnnotationKey = "agentRunnerId"
}
