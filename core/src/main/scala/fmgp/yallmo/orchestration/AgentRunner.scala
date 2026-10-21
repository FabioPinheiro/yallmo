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
  *   - The orchestrator persists session messages; the runner itself does not.
  */
final class AgentRunner[R](
    tools: Map[String, AgentTool[R]],
    retrievers: Map[String, KnowledgeRetriever[R]],
    val runnerId: UUID = UUID.randomUUID() //  Identifier added to logs
) {

  /** Binds a program and its capabilities for session-aware orchestration; constructing it performs no effects. */
  def execution(
      agent: AgentInstance[R],
      staticContext: StaticContext[R],
      budget: AgentBudget = AgentBudget.default
  ): AgentExecution[R] =
    new AgentExecution[R] {
      override val agentId: AgentId = agent.id
      override def run(task: AgentTask, acceptedContext: RunContext): ZIO[R, YallmoError, AgentTurnResult] =
        runAccepted(agent, task, staticContext, acceptedContext, budget)
    }

  /** Starts a new run with an empty transcript.
    *
    * Its a standalone execution. Use when you need the answer, but not conversation history.
    */
  def run(
      agent: AgentInstance[R],
      task: AgentTask,
      staticContext: StaticContext[R],
      budget: AgentBudget = AgentBudget.default // TODO REVIEW considered for removal
  ): ZIO[R, YallmoError, AgentResult] =
    runWithContext(agent, task, staticContext, RunContext(), budget).map(_.result)

  /** Runs one user task from an existing transcript and returns the transcript for the next task.
    *
    * {{{
    *    for
    *     first <- runner.runWithContext(
    *       agent, AgentTask("Explain Scala"), context, RunContext()
    *     )
    *     second <- runner.runWithContext(
    *       agent, AgentTask("Show an example"), context, first.runContext
    *     )
    *   yield second.result.text
    * }}}
    */
  def runWithContext(
      agent: AgentInstance[R],
      task: AgentTask,
      staticContext: StaticContext[R],
      runContext: RunContext,
      budget: AgentBudget = AgentBudget.default // TODO REVIEW considered for removal
  ): ZIO[R, YallmoError, AgentTurnResult] =
    runAccepted(agent, task, staticContext, runContext.append(MessagePayload.User(task.prompt)), budget)

  private def runAccepted(
      agent: AgentInstance[R],
      task: AgentTask,
      staticContext: StaticContext[R],
      runContext: RunContext,
      budget: AgentBudget
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
                actionCount
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
      actionCount: Ref[Int]
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
          ZIO.succeed(AgentTurnResult(AgentResult(agent.id, text), runContextWithTurn))
        case AgentDecision.Actions(actions) =>
          if actions.isEmpty then ZIO.fail(YallmoError.EmptyActionBatch(agent.id))
          else
            val runContextWithCalls = runContextWithTurn.appendAll(
              actions.collect {
                case LlmAction.CallExternalTool(call) if call.callId.nonEmpty =>
                  MessagePayload.FunctionCallRequest(
                    FunctionCall(call.callId.get, call.toolKey, call.argumentsJson)
                  )
              }
            )
            executeActions(agent, actions, staticContext, budget, actionCount)
              .flatMap(messages =>
                runLoop(
                  agent,
                  task,
                  staticContext,
                  runContextWithCalls.appendAll(messages),
                  budget,
                  turnCount,
                  actionCount
                )
              )
    yield result

  private def applyExtensions(
      staticContext: StaticContext[R],
      runContext: RunContext
  ): ZIO[R, YallmoError, RunContext] =
    ZIO.foldLeft(staticContext.extensions)(runContext) { (current, contextExtension) =>
      contextExtension(staticContext, current)
    }

  private def executeActions(
      agent: AgentInstance[R],
      actions: Chunk[LlmAction],
      staticContext: StaticContext[R],
      budget: AgentBudget,
      actionCount: Ref[Int]
  ): ZIO[R, YallmoError, Chunk[MessagePayload]] =
    ZIO.foreach(actions) { action =>
      for
        count <- actionCount.updateAndGet(_ + 1)
        _ <- ZIO.fail(YallmoError.ActionLimitExceeded(budget.maxActions)).when(count > budget.maxActions)
        message <- executeAction(agent, action, staticContext)
      yield message
    }

  private def executeAction(
      agent: AgentInstance[R],
      action: LlmAction,
      staticContext: StaticContext[R]
  ): ZIO[R, YallmoError, MessagePayload] =
    action match
      case LlmAction.RetrieveRag(retrieverKey, query) =>
        if !staticContext.retrievers.exists(_.key == retrieverKey) then
          ZIO.fail(YallmoError.RetrieverUnavailableToAgent(agent.id, retrieverKey))
        else
          ZIO
            .fromOption(retrievers.get(retrieverKey))
            .orElseFail(YallmoError.UnknownRetriever(retrieverKey))
            .flatMap(_.retrieve(query))
            .map(MessagePayload.RagResult(retrieverKey, _))

      case LlmAction.CallExternalTool(call) =>
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
                  .map(_.text)
              )

        execute.fold(
          error => toolMessage(call, toolErrorOutput(error)),
          output => toolMessage(call, output)
        )

  private def toolMessage(call: ExternalToolCall, output: String): MessagePayload =
    call.callId match
      case Some(id) =>
        MessagePayload.FunctionCallOutput(FunctionCallOutput(id, call.toolKey, output))
      case None => MessagePayload.ToolResult(call.toolKey, output)

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
