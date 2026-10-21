package fmgp.yallmo.repl

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import fmgp.yallmo.orchestration.*
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
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
    userAgent: Agent[R],
    assistantAgent: Option[Agent[R]]
) extends ReplOrchestrator[R] { // TODO rename/merge with ReplOrchestrator

  export orchestrator.{getAgent, submit, listSessions, createSession, loadSession, append, fork}

  override def addSessionAgent(id: SessionId, agentId: AgentId): IO[YallmoError, Session] =
    orchestrator.addSessionAgent(id, agentId)

  override def readBlob(blobId: BlobId): IO[YallmoError, Blob] = orchestrator.readBlob(blobId)

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
    sessionId(name).flatMap { id =>
      createSession(id, agentIds).as(ReplState(sessionId = Some(id.value)))
    }

  override private[repl] def submitPrompt[R1](
      previous: ReplState,
      prompt: String,
      execution: AgentExecution[R1]
  ): ZIO[R1, SessionSubmitFailure, ReplTurn] =
    for {
      _ <- ZIO
        .fail(SessionSubmitFailure(YallmoError.InvalidConfig("No matching assistant selected"), None))
        .unless(assistantAgent.exists(_.id == execution.agentId))
      id <- (previous.sessionId match {
        case Some(value) => sessionId(Some(value))
        case None        => sessionId(None).tap(createSession(_, agentIds))
      }).mapError(SessionSubmitFailure(_, None))
      turn <- submit(id, userAgent.id, AgentTask(prompt), execution)
    } yield ReplTurn(previous.withSession(turn.session), turn.result, turn.newMessages)

  override def resumeReplSession(id: String): IO[YallmoError, ReplState] =
    for {
      sessionId <- sessionId(Some(id))
      session <- loadSession(sessionId)
    } yield ReplState(
      RunContext(session.messages.map(_.payload)),
      sessionId = Some(session.id.value),
      continuationStart = session.messages.size
    )

  private val agentIds = Chunk(userAgent.id) ++ Chunk.fromIterable(assistantAgent.map(_.id))

  private def sessionId(name: Option[String]): IO[YallmoError, SessionId] =
    name match
      case Some(value) => ZIO.fromEither(SessionId.from(value).left.map(YallmoError.InvalidSessionId(value, _)))
      case None        => nextTimestampedSessionId

  private def nextTimestampedSessionId: IO[YallmoError, SessionId] = {
    def next: IO[YallmoError, SessionId] =
      val id = SessionId.unsafe(ReplOrchestratorLive.SessionNameFormatter.format(Instant.now()))
      loadSession(id).either.flatMap {
        case Left(YallmoError.StorageFailed(YallmoError.SessionNotFound(_))) => ZIO.succeed(id)
        case Right(_)                                                        => ZIO.sleep(1.second) *> next
        case Left(error)                                                     => ZIO.fail(error)
      }
    next
  }
}

object ReplOrchestratorLive {
  private[repl] val SessionNameFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("'main-'uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC)

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
    new ReplOrchestratorLive(orchestrator, userAgent, assistantAgent)
}
