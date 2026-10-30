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

  /** Binds a program and its capabilities for orchestrator-owned execution; construction performs no effects. */
  def execution(
      agent: AgentInstance[R],
      staticContext: StaticContext[R],
      budget: AgentBudget = AgentBudget.default
  ): AgentExecution[R] =
    new AgentExecution[R] {
      override val agentId: AgentId = agent.id
      override def run(task: AgentTask, acceptedContext: RunContext): ZIO[R, YallmoError, AgentTurnResult] =
        runAccepted(agent, task, staticContext, acceptedContext, budget)

      override def invoke(input: MessagePayload): ZIO[R, YallmoError, MessagePayload] = input match {
        case MessagePayload.ToolCall(call) =>
          (for {
            _ <- ZIO.fromEither(budget.validate)
            _ <- ZIO.fromEither(call.validate.left.map(YallmoError.InvalidMessage.apply))
            context <- agent.configuredContext(staticContext)
            result <- executeAction(agent, PreparedAction.CallExternalTool(call), context)
          } yield result).timeoutFail(YallmoError.AgentTimedOut(budget.timeout))(budget.timeout)
        case _: MessagePayload.Reasoning =>
          ZIO.fail(
            YallmoError.InvalidMessage("Provider continuations are server-owned; submit a Context summary instead")
          )
        case _ => super.invoke(input)
      }
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
            for
              preparedActions <- prepareActions(actions)
              runContextWithCalls = runContextWithTurn.appendAll(
                preparedActions.collect { case PreparedAction.CallExternalTool(call) =>
                  MessagePayload.ToolCall(call)
                }
              )
              messages <- executeActions(agent, preparedActions, staticContext, budget, actionCount)
              result <- runLoop(
                agent,
                task,
                staticContext,
                runContextWithCalls.appendAll(messages),
                budget,
                turnCount,
                actionCount
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
          ScalaModeOutput(call.id, value.result, value.logs, value.exitCode, value.timedOut, value.truncated)
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
