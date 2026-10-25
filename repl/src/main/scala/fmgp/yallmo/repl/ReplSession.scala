package fmgp.yallmo.repl

import zio.*
import java.nio.file.Path
import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.LlmProviderPayloadDebug
import fmgp.yallmo.repl.debug.ReplDebugSnapshot
import fmgp.yallmo.orchestration.{AgentResult, RunContext, StaticContext}
import fmgp.yallmo.server.application.SessionSubmission

/** The immutable state retained by one interactive REPL session.
  *
  * @param logLevel
  *   terminal log threshold only; durable file logging has its own independent INFO default
  * @param continuationStart
  *   messages before this offset remain in the transcript but their opaque provider state is excluded from requests
  *   after an agent change or session resume; later continuations belong to the current runtime binding
  * @param remoteHeadMessageId
  *   last confirmed public snapshot head, used only by connected submission; None omits the precondition
  * @param remoteResetContinuation
  *   client-only reset intent for the next remote turn; never a provider continuation or local history offset
  */
final case class ReplState(
    runContext: RunContext = RunContext(),
    logLevel: LogLevel = LogLevel.Warning,
    sessionId: Option[String] = None,
    lastProviderRequest: Option[Path] = None,
    continuationStart: Int = 0, // TODO REVIEW considered for removal
    remoteHeadMessageId: Option[MessageId] = None,
    remoteResetContinuation: Boolean = false
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

/** Adapts local diagnostics and terminal state around application submission. */
final class ReplSession[R] private (
    submitTurn: Option[(ReplState, String, ReplOrchestrator[R]) => ZIO[R, SessionSubmitFailure, ReplTurn]]
) {

  /** Compatibility constructor for trusted in-process callers and test programs. */
  def this(runner: AgentRunner[R], agent: Option[AgentInstance[R]], staticContext: StaticContext[R]) =
    this(agent.map { selected => (state, prompt, orchestrator) =>
      orchestrator.submitPrompt(
        state,
        prompt,
        SessionSubmission.configuredExecution(runner, selected, staticContext, state.continuationStart)
      )
    })

  def debugSnapshot(state: ReplState): ReplDebugSnapshot =
    ReplDebugSnapshot(state.runContext.messages)

  /** The capture path is local-only. Core submission failures retain their accepted session unchanged. */
  def submit(
      state: ReplState,
      prompt: String,
      orchestrator: ReplOrchestrator[R]
  ): ZIO[R, SessionSubmitFailure, ReplTurn] =
    submitTurn match {
      case None =>
        ZIO.fail(
          SessionSubmitFailure(
            YallmoError.InvalidConfig(
              "No agent selected. Press Esc, a to select an agent, or use --agent <id>."
            ),
            None
          )
        )
      case Some(run) =>
        LlmProviderPayloadDebug.withRequestCapture(run(state, prompt, orchestrator)).map { case (turn, capture) =>
          turn.copy(state = turn.state.copy(lastProviderRequest = capture.orElse(state.lastProviderRequest)))
        }
    }
}

object ReplSession {

  /** Normal local-mode construction delegates configured execution entirely to the server application. */
  private[repl] def fromApplication[R](application: SessionSubmission[R]): ReplSession[R] =
    new ReplSession[R](Option.when(application.selectedAgentId.nonEmpty) { (state, prompt, _) =>
      application.submit(state.sessionId, prompt, state.continuationStart).map { turn =>
        ReplTurn(state.withSession(turn.session), turn.result, turn.newMessages)
      }
    })
}
