package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import zio.*
import zio.stream.ZStream

/** Application-facing API for registered agents, immutable messages and durable sessions.
  *
  * Implementations resolve agents and delegate transcript persistence to an internal [[Storage]]. Callers do not use
  * storage directly.
  */
trait Orchestrator[-R] extends BlobOps {

  /** Resolves executable behavior; a registered user identity alone is not an agent. */
  def getAgent(id: AgentId): IO[YallmoError, Agent[R]]

  /** Invokes one provider turn without saving a prompt or response and without running the agent/tool loop.
    *
    * @deprecated
    *   Use [[submit]] for session-aware execution with prompt and response persistence. This method is retained for
    *   low-level compatibility; submit is not a drop-in rename because it requires a session and configured execution.
    */
  @deprecated("Use submit for session-aware agent execution and persistence.", "2.0.0")
  def turn(id: AgentId, request: AgentTurnRequest): ZIO[R, YallmoError, AgentTurnResponse]

  /** Submits one user task to an existing session, owning prompt and transcript persistence.
    *
    * Validates the registered authors and saves the user prompt before invoking execution. The execution receives
    * history from the stored session, including the accepted prompt exactly once; callers must not append it
    * themselves. Generated assistant/tool messages are attributed to execution.agentId and saved through ordered,
    * asynchronous checkpoints. Tools and inference do not wait for these writes; persistence failures are logged as
    * errors without stopping execution. Subsequent checkpoints retry the unsaved suffix.
    *
    * Pending checkpoints are drained before returning, including on execution failure or cancellation. Any remaining
    * unsaved generated messages produce a warning; a successful result may include newMessages absent from its saved
    * session. This is best-effort persistence, not a durability barrier or exactly-once tool execution.
    *
    * A failed prompt save prevents execution. Failures after acceptance retain saved checkpoints and expose the latest
    * saved session through [[SessionSubmitFailure.acceptedSession]]. Timeout and interruption do not roll back accepted
    * input or earlier checkpoints. Appends are not a rollback transaction.
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
    *   opaque loop handle created by AgentRunner.execution; only submit can execute it, with mandatory transcript
    *   persistence; its agent must be registered and preserve the accepted transcript prefix and response author
    * @return
    *   the saved session, final agent result and new messages (prompt plus generated responses), or a
    *   [[SessionSubmitFailure]] containing the underlying error and the latest saved session when available
    */
  def submit[R1](
      id: SessionId,
      authorId: AgentId,
      task: AgentTask,
      execution: AgentExecution[R1]
  ): ZIO[R1, SessionSubmitFailure, SessionTurn]

  /** Saves one message, awaits its blobs and invokes a single-message recipient, not an agent loop. AgentRunner's
    * invocation adapter rejects model-requested actions; use [[submit]] to execute those with transcript persistence.
    * Any payload may be submitted/returned; the recipient validates what it supports. A parent must exist but does not
    * implicitly load history. No session is created or advanced. The exact response payload is saved as a direct child
    * of input. Failure never rolls back an accepted input, and repeating an invocation may execute again even if that
    * input is already stored.
    */
  def invoke[R1](input: Message, execution: AgentInvocation[R1]): ZIO[R1, YallmoError, Message] = {
    val _ = (input, execution)
    ZIO.fail(YallmoError.InvalidConfig("Standalone invocation unsupported"))
  }

  def listSessions: IO[YallmoError, Chunk[SessionSummary]]

  def createSession(id: SessionId, agentIds: Chunk[AgentId]): IO[YallmoError, Session]

  def loadSession(id: SessionId): IO[YallmoError, Session]

  /** Loads read-only history with explicit missing-message markers. Does not repair storage or make incomplete history
    * executable; submit, append and other mutations continue to require strict [[loadSession]].
    */
  def loadSessionParcial(id: SessionId): IO[YallmoError, SessionParcial]

  /** Adds a registered message author without changing the session ID or transcript. Idempotent. Fixed-membership
    * implementations retain their previous behavior unless they override this operation.
    */
  def addSessionAgent(id: SessionId, agentId: AgentId): IO[YallmoError, Session] =
    getAgent(agentId) *> loadSession(id).flatMap { session =>
      if session.agentIds.contains(agentId) then ZIO.succeed(session)
      else ZIO.fail(YallmoError.AgentNotInSession(id, agentId))
    }

  def append(id: SessionId, agentId: AgentId, messages: Chunk[MessagePayload]): IO[YallmoError, Session]

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
