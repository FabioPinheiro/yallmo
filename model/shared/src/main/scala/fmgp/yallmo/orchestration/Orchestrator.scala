package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import zio.*
import zio.stream.ZStream

/** Application-facing API for registered agents and durable sessions.
  *
  * Implementations resolve agents and delegate transcript persistence to an internal [[Storage]]. Callers do not use
  * storage directly.
  */
trait Orchestrator[-R] {
  def getAgent(id: AgentId): IO[YallmoError, Agent[R]]

  /** Invokes one provider turn without saving a prompt or response and without running the agent/tool loop.
    *
    * @deprecated
    *   Use [[submit]] for session-aware execution with prompt and response persistence. This method is retained for
    *   low-level compatibility; submit is not a drop-in rename because it requires a session and configured execution.
    */
  @deprecated("Use submit for session-aware agent execution and persistence.", "2.0.0")
  def turn(id: AgentId, request: AgentTurnRequest): ZIO[R, YallmoError, AgentTurnResponse]

  /** Submits one user task to an existing session, owning the complete persist-run-persist lifecycle.
    *
    * Validates the registered authors and saves the user prompt before invoking execution. Storage write before
    * execution starts. The execution receives history from the stored session, including the accepted prompt exactly
    * once; callers must not append it themselves. After successful execution, only newly generated assistant/tool
    * messages are saved, attributed to execution.agentId, before this effect succeeds.
    *
    * A failed prompt save prevents execution. Failures after acceptance retain the saved prompt and expose its session
    * through [[SessionSubmitFailure.acceptedSession]]. Timeout and interruption do not roll back an accepted prompt.
    * Appends are not a rollback transaction.
    *
    * @tparam R1
    *   environment required by the configured execution, including its model/tool services
    * @param id
    *   existing session whose stored transcript is the source of conversation history
    * @param authorId
    *   registered author of the prompt; added to the session's participants if needed
    * @param task
    *   user task whose prompt is accepted and saved before execution
    * @param execution
    *   configured agent loop, typically created by AgentRunner.execution; its agent must be registered and it must
    *   preserve the accepted transcript prefix and response author
    * @return
    *   the saved session, final agent result and new messages (prompt plus generated responses), or a
    *   [[SessionSubmitFailure]] containing the underlying error and the accepted session when available
    */
  def submit[R1](
      id: SessionId,
      authorId: AgentId,
      task: AgentTask,
      execution: AgentExecution[R1]
  ): ZIO[R1, SessionSubmitFailure, SessionTurn]

  def listSessions: IO[YallmoError, Chunk[SessionSummary]]

  def createSession(id: SessionId, agentIds: Chunk[AgentId]): IO[YallmoError, Session]

  def loadSession(id: SessionId): IO[YallmoError, Session]

  /** Adds a registered message author without changing the session ID or transcript. Idempotent. Fixed-membership
    * implementations retain their previous behavior unless they override this operation.
    */
  def addSessionAgent(id: SessionId, agentId: AgentId): IO[YallmoError, Session] =
    getAgent(agentId) *> loadSession(id).flatMap { session =>
      if session.agentIds.contains(agentId) then ZIO.succeed(session)
      else ZIO.fail(YallmoError.AgentNotInSession(id, agentId))
    }

  def append(id: SessionId, agentId: AgentId, messages: Chunk[MessagePayload]): IO[YallmoError, Session]

  def readBlob(blobId: BlobId): IO[YallmoError, Blob] = Blob.unavailable(blobId)

  def appendWithBlobBytes(
      id: SessionId,
      agentId: AgentId,
      message: MessagePayload,
      bytes: Chunk[Byte]
  ): IO[YallmoError, Session] =
    appendWithBlobWriter(id, agentId, message, write => write(bytes))

  /** The stream must be finite and fit in memory; Storage receives a full Blob after production. */
  def appendWithBlobStream[R1](
      id: SessionId,
      agentId: AgentId,
      message: MessagePayload,
      stream: ZStream[R1, YallmoError, Byte]
  ): ZIO[R1, YallmoError, Session] =
    appendWithBlobWriter(id, agentId, message, write => stream.chunks.runForeach(write))

  /** The effect completes after content production. Orchestrator buffers content in memory and appends a full Blob to
    * Storage after production. Failed or cancelled producers also persist the bytes collected so far, then propagate
    * their failure or interruption; stored bytes have no lifecycle status.
    */
  def appendWithBlobWriter[R1](
      id: SessionId,
      agentId: AgentId,
      message: MessagePayload,
      produce: (Chunk[Byte] => IO[YallmoError, Unit]) => ZIO[R1, YallmoError, Unit]
  ): ZIO[R1, YallmoError, Session] = {
    val _ = (id, agentId, message, produce)
    ZIO.fail(YallmoError.InvalidConfig("This orchestrator does not support blob ingestion"))
  }

  def fork(source: SessionId, target: SessionId): IO[YallmoError, Session]
}
