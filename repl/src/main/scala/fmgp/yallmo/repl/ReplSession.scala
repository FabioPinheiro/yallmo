package fmgp.yallmo.repl

import zio.*
import java.nio.file.Path
import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.LlmProviderPayloadDebug
import fmgp.yallmo.repl.debug.ReplDebugSnapshot
import fmgp.yallmo.orchestration.{AgentResult, AgentTask, RunContext, StaticContext}

/** The immutable state retained by one interactive REPL session.
  *
  * @param continuationStart
  *   messages before this offset remain in the transcript but their opaque provider state is excluded from requests
  *   after an agent change or session resume; later continuations belong to the current runtime binding
  */
final case class ReplState(
    runContext: RunContext = RunContext(),
    logLevel: LogLevel = LogLevel.Error,
    sessionId: Option[String] = None,
    lastProviderRequest: Option[Path] = None,
    continuationStart: Int = 0 // TODO REVIEW considered for removal
)

/** The result of accepting one user prompt in a REPL session. */
final case class ReplTurn(
    state: ReplState,
    result: AgentResult,
    newMessages: Chunk[MessagePayload]
)

/** Adapts the bounded agent runner to a multi-prompt interactive session.
  *
  * Each submitted prompt starts from the previous [[RunContext]], while the [[StaticContext]] remains fixed for the
  * lifetime of this session.
  *
  * @param agent
  *   selected runner program; None represents an unselected startup and rejects prompts before running tools, changing
  *   the transcript, capturing provider requests or persisting a turn
  */
final class ReplSession[R](
    runner: AgentRunner[R],
    agent: Option[AgentInstance[R]],
    staticContext: StaticContext[R],
    afterSubmit: (ReplState, ReplTurn) => IO[YallmoError, ReplTurn] = (_, turn) => ZIO.succeed(turn)
) {

  /** Captures the current transcript without executing the agent. */
  def debugSnapshot(state: ReplState): ReplDebugSnapshot =
    ReplDebugSnapshot(state.runContext.messages)

  def submit(state: ReplState, prompt: String): ZIO[R, YallmoError, ReplTurn] =
    agent match {
      case None =>
        ZIO.fail(
          YallmoError.InvalidConfig(
            "No agent selected. Press Esc, a to select an agent, or configure defaultAgentId / --agent <id>."
          )
        )
      case Some(selected) =>
        LlmProviderPayloadDebug
          .withRequestCapture(
            runner.runWithContext(
              withCurrentContinuations(selected, state.continuationStart),
              AgentTask(prompt),
              staticContext,
              state.runContext
            )
          )
          .map { case (agentRun, requestCapture) =>
            // A run may append several messages (user prompt, assistant turns, tool calls/results, RAG results).
            // Keep only this run's append-only transcript delta; the preceding messages already belong to `state`.
            val newMessages = agentRun.runContext.messages.drop(state.runContext.messages.size)
            ReplTurn(
              state.copy(
                runContext = agentRun.runContext,
                lastProviderRequest = requestCapture.orElse(state.lastProviderRequest)
              ),
              agentRun.result,
              newMessages
            )
          }
          .flatMap(afterSubmit(state, _))
    }

  /** Filter only the request view: the runner's append-only transcript and durable history remain unchanged. */
  private def withCurrentContinuations(selected: AgentInstance[R], start: Int): AgentInstance[R] =
    if start <= 0 then selected
    else
      new AgentInstance[R] {
        override val id: AgentId = selected.id
        override def next(
            task: AgentTask,
            context: StaticContext[R],
            current: RunContext
        ): ZIO[R, YallmoError, AgentTurn] = {
          val messages = current.messages.zipWithIndex.collect {
            case (message, index) if index >= start || !message.isInstanceOf[MessagePayload.Reasoning] => message
          }
          selected.next(task, context, current.copy(messages = messages))
        }
      }
}
