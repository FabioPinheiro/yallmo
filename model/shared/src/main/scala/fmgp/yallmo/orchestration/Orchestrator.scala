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
    * themselves. Generated assistant/tool messages are attributed to execution.agentId. ManagedHistory assigns IDs,
    * parents and timestamps once when appending new payloads, then queues independent immutable object puts in parent
    * order. Tools and inference do not wait for disk; later appends retry identical objects after errors. Programs may
    * inspect identities through AgentInstance.nextManaged; appending remains runner-controlled, while programs may
    * explicitly request managed rebase. Each turn renders a fresh RunContext snapshot; returned snapshots never replace
    * or append to managed history.
    *
    * Session-head advancement uses existing best-effort CAS after the objects are saved. A competing append never
    * reparents/regenerates generated messages or prevents saving their branch (inspectable through listMessages).
    * Pointer conflicts are logged separately from object failures; no session-wide execution lock or automatic rebase
    * is implied.
    *
    * Pending writes are drained before returning, including on execution failure or cancellation. Any remaining unsaved
    * generated objects produce a warning. newMessages derives from the immutable execution audit: the accepted prompt
    * once plus actual generated appends, even if later rebased out of active history. Rebase copies are not additional
    * execution. These messages may be absent from the last acknowledged session publication after pointer conflicts.
    * This is best-effort persistence, not a durability barrier or exactly-once tool execution.
    *
    * A failed prompt save prevents execution. Failures after acceptance retain saved messages and expose the last
    * acknowledged publication through [[SessionSubmitFailure.acceptedSession]]. Timeout and interruption do not roll
    * back accepted input or earlier appends. Appends are not a rollback transaction.
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
    *   opaque loop handle created by AgentRunner.execution; only orchestration submission operations can execute it,
    *   with mandatory transcript persistence; its agent must be registered and preserve the response author. Ordinary
    *   appends preserve the active prefix; an authorized managed rebase may replace that prefix without altering
    *   original objects or execution audit
    * @return
    *   the last acknowledged session publication, final agent result and execution audit (prompt plus generated
    *   appends), or a [[SessionSubmitFailure]] containing the underlying error and the last acknowledged publication
    *   when available
    */
  def submit[R1](
      id: SessionId,
      authorId: AgentId,
      task: AgentTask,
      execution: AgentExecution[R1]
  ): ZIO[R1, SessionSubmitFailure, SessionTurn]

  /** Executes against complete persisted ancestry plus one newly saved user prompt parented exactly to parentId. Never
    * creates, joins or publishes a session. The configured runner owns the normal managed tool loop. Generated writes
    * are drained and verified before success; failures can retain partial immutable branches. Repeating the request can
    * execute again: there is no automatic retry or exactly-once tool guarantee.
    */
  def submitBranch[R1](
      parentId: MessageId,
      authorId: AgentId,
      task: AgentTask,
      execution: AgentExecution[R1]
  ): ZIO[R1, BranchSubmitFailure, BranchTurn] = {
    val _ = (parentId, authorId, task, execution)
    ZIO.fail(
      BranchSubmitFailure(YallmoError.InvalidConfig("Sessionless branches unsupported"), BranchAcceptance.NotAccepted)
    )
  }

  /** Saves one message, awaits its blobs and invokes a single-message recipient, not an agent loop. AgentRunner's
    * invocation adapter rejects model-requested actions; use [[submit]] or [[submitBranch]] to execute those with
    * transcript persistence. Any payload may be submitted/returned; the recipient validates what it supports. A parent
    * must exist but does not implicitly load history. No session is created or advanced. The exact response payload is
    * saved as a direct child of input. Failure never rolls back an accepted input, and repeating an invocation may
    * execute again even if that input is already stored.
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

  /** Replays messages after exclusive `base` through inclusive `head`, attaching the first copy to `onto` (or a root
    * when None). Returns chronological, persisted immutable messages, preserving authors, timestamps and payloads; only
    * parents and derived IDs change. References, complete source/destination ancestry, hashes and registered authors
    * are validated before any writes. Missing references or an unreachable base fail even for empty/no-op ranges.
    * `base == head` returns empty; `onto == Some(base)` reproduces the original IDs with idempotent puts.
    *
    * Any existing message may be the destination, including one in the source range. Original messages, session heads
    * and active ManagedHistory are never changed. Only already-persisted history is available; this does not drain or
    * coordinate active-run checkpoints. No tools/inference run, blob bytes are not copied, and preserved provider
    * continuations are not guaranteed safe to replay. Writes are parent-first, not a batch transaction: I/O failure or
    * interruption may leave a saved prefix without updating any refs. Success means every returned object is saved.
    */
  def rebase(base: MessageId, head: MessageId, onto: Option[MessageId]): IO[YallmoError, Chunk[Message]]

  def fork(source: SessionId, target: SessionId): IO[YallmoError, Session]
}
