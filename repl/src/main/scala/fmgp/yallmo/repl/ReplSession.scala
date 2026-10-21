package fmgp.yallmo.repl

import zio.*
import java.nio.file.Path
import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.LlmProviderPayloadDebug
import fmgp.yallmo.repl.debug.ReplDebugSnapshot
import fmgp.yallmo.orchestration.{AgentResult, RunContext, StaticContext}

/** The immutable state retained by one interactive REPL session.
  *
  * @param logLevel
  *   terminal log threshold only; durable file logging has its own independent INFO default
  * @param continuationStart
  *   messages before this offset remain in the transcript but their opaque provider state is excluded from requests
  *   after an agent change or session resume; later continuations belong to the current runtime binding
  */
final case class ReplState(
    runContext: RunContext = RunContext(),
    logLevel: LogLevel = LogLevel.Warning,
    sessionId: Option[String] = None,
    lastProviderRequest: Option[Path] = None,
    continuationStart: Int = 0 // TODO REVIEW considered for removal
) {

  /** Refreshes durable conversation state without changing client preferences or provider continuation boundaries. */
  def withSession(session: Session): ReplState =
    copy(sessionId = Some(session.id.value), runContext = RunContext(session.messages.map(_.payload)))
}

/** The result of accepting one user prompt in a REPL session. */
final case class ReplTurn(
    state: ReplState,
    result: AgentResult,
    newMessages: Chunk[MessagePayload]
)

/** Adapts REPL diagnostics and provider continuations to session-aware orchestration.
  *
  * @param agent
  *   selected runner program; None represents an unselected startup and rejects prompts before running tools, changing
  *   the transcript, capturing provider requests or persisting a turn
  */
final class ReplSession[R](
    runner: AgentRunner[R],
    agent: Option[AgentInstance[R]],
    staticContext: StaticContext[R],
) {

  /** Captures the current transcript without executing the agent. */
  def debugSnapshot(state: ReplState): ReplDebugSnapshot =
    ReplDebugSnapshot(state.runContext.messages)

  /** Preserves core submission failures so the client can recover the accepted session after an agent error. */
  def submit(
      state: ReplState,
      prompt: String,
      orchestrator: ReplOrchestrator[R]
  ): ZIO[R, SessionSubmitFailure, ReplTurn] =
    agent match {
      case None =>
        ZIO.fail(
          SessionSubmitFailure(
            YallmoError.InvalidConfig(
              "No agent selected. Press Esc, a to select an agent, or configure defaultAgentId / --agent <id>."
            ),
            None
          )
        )
      case Some(selected) =>
        val execution = runner.execution(withCurrentContinuations(selected, state.continuationStart), staticContext)
        LlmProviderPayloadDebug
          .withRequestCapture(orchestrator.submitPrompt(state, prompt, execution))
          .map { case (turn, requestCapture) =>
            turn.copy(state = turn.state.copy(lastProviderRequest = requestCapture.orElse(state.lastProviderRequest)))
          }
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
