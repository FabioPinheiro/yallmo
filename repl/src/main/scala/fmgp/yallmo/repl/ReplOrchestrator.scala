package fmgp.yallmo.repl

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.server.application.SessionSubmission
import zio.*

/** REPL-facing extension of [[Orchestrator]]. */
trait ReplOrchestrator[-R] extends Orchestrator[R] {
  def listReplSessionIds: IO[YallmoError, Chunk[String]]

  def newReplSession(name: Option[String]): IO[YallmoError, ReplState]

  /** Submits a REPL prompt, adapting session setup and client state around [[Orchestrator.submit]].
    *
    * The flow is:
    *   1. Reject execution unless its agent matches the selected assistant, before creating or writing a session.
    *   2. Validate the session ID in `previous`, or create a timestamp-named session if no ID is present.
    *   3. Delegate to `submit` with the configured human author and `AgentTask(prompt)`. The core saves the prompt,
    *      runs execution against the stored transcript, then saves newly generated messages. Callers must not append
    *      the prompt themselves; `previous.runContext` is not the source of execution history.
    *   4. Return a [[ReplTurn]] with the saved session's ID and transcript, preserving the other REPL state fields.
    *
    * Selection and session-setup failures have no accepted session. Core failures after prompt acceptance expose it
    * through [[SessionSubmitFailure.acceptedSession]], allowing the caller to refresh its state even when execution
    * fails. Accepted prompts are not rolled back on failure or interruption; a newly created session may also remain if
    * submission fails before prompt acceptance.
    *
    * @tparam R1
    *   environment required by the configured execution
    * @param previous
    *   current REPL state, supplying the optional session ID and client preferences to retain
    * @param prompt
    *   user text to persist exactly once before execution
    * @param execution
    *   configured agent loop for the selected assistant
    * @return
    *   updated REPL state, agent result and new messages (prompt plus generated responses), or a submission failure
    */
  private[repl] def submitPrompt[R1](
      previous: ReplState,
      prompt: String,
      execution: AgentExecution[R1]
  ): ZIO[R1, SessionSubmitFailure, ReplTurn]

  def resumeReplSession(id: String): IO[YallmoError, ReplState]
}

/** Adapts the application-facing orchestrator API to REPL state and session conventions. */
final class ReplOrchestratorLive[-R] private (
    orchestrator: Orchestrator[R],
    sessions: SessionSubmission[?]
) extends ReplOrchestrator[R] {

  export orchestrator.{getAgent, submit, listSessions, createSession, loadSession, loadSessionParcial, append, fork}

  override def addSessionAgent(id: SessionId, agentId: AgentId): IO[YallmoError, Session] =
    orchestrator.addSessionAgent(id, agentId)

  override def beginBlobUpload(blobId: BlobId, totalBytes: Long): IO[YallmoError, BlobUploader] =
    orchestrator.beginBlobUpload(blobId, totalBytes)

  override def readBlob(blobId: BlobId, awaitTimeout: Duration = Duration.Zero): IO[YallmoError, Blob] =
    orchestrator.readBlob(blobId, awaitTimeout)

  override def appendWithBlobWriter[R1](
      id: SessionId,
      agentId: AgentId,
      message: MessagePayload,
      produce: (Chunk[Byte] => IO[YallmoError, Unit]) => ZIO[R1, YallmoError, Unit]
  ): ZIO[R1, YallmoError, Session] =
    orchestrator.appendWithBlobWriter(id, agentId, message, produce)

  @deprecated("Use submit for session-aware agent execution and persistence.", "2.0.0")
  override def turn(id: AgentId, request: AgentTurnRequest): ZIO[R, YallmoError, AgentTurnResponse] =
    orchestrator.turn(id, request)

  override def listReplSessionIds: IO[YallmoError, Chunk[String]] =
    listSessions.map(_.map(_.id.value))

  override def newReplSession(name: Option[String]): IO[YallmoError, ReplState] =
    sessions.create(name).map(session => ReplState().withSession(session))

  override private[repl] def submitPrompt[R1](
      previous: ReplState,
      prompt: String,
      execution: AgentExecution[R1]
  ): ZIO[R1, SessionSubmitFailure, ReplTurn] =
    sessions.submitExecution(previous.sessionId, prompt, execution).map { turn =>
      ReplTurn(previous.withSession(turn.session), turn.result, turn.newMessages)
    }

  override def resumeReplSession(id: String): IO[YallmoError, ReplState] =
    sessions.load(id).map { session =>
      ReplState(continuationStart = session.messages.size).withSession(session)
    }

}

object ReplOrchestratorLive {

  /** Adapts storage/session conventions for one selection; switching constructs another adapter over shared storage.
    *
    * @param assistantAgent
    *   optional selected assistant; unselected sessions contain only the human participant and cannot persist assistant
    *   messages. Resuming is independent of selection; authors join an existing session when their messages are saved
    */
  def make[R](
      orchestrator: Orchestrator[R],
      userAgent: Agent[R],
      assistantAgent: Option[Agent[R]]
  ): ReplOrchestrator[R] =
    new ReplOrchestratorLive(
      orchestrator,
      SessionSubmission.make(orchestrator, userAgent.id, assistantAgent.map(_.id))
    )

  private[repl] def fromApplication[R](
      orchestrator: Orchestrator[R],
      sessions: SessionSubmission[R]
  ): ReplOrchestrator[R] =
    new ReplOrchestratorLive(orchestrator, sessions)
}
