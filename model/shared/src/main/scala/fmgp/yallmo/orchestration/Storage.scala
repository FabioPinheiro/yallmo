package fmgp.yallmo.orchestration

import fmgp.yallmo.model.{AgentId, Blob, BlobId, Message, MessageId, YallmoError}
import zio.{Chunk, IO}

/** Lightweight session inventory, including optional descriptions and audit dates without loading history. */
final case class SessionSummary(
    id: SessionId,
    messageCount: Long,
    agentIds: Seq[AgentId],
    meta: Option[SessionMetadata] = None,
    info: Option[SessionInfo] = None
)

/** Storage for immutable messages, conversation sessions and binary blobs.
  *
  * Storage records session membership as agent IDs only; it neither resolves nor executes agents. Application code must
  * access storage through [[Orchestrator]], which validates those IDs against its registered agents.
  */
private[orchestration] trait Storage extends BlobOps {
  def listMessages: IO[YallmoError, Chunk[Message]]

  /** Stores an immutable, content-addressed message without creating or advancing a session. Identical writes are
    * idempotent; the content address and any parent reference must be valid.
    */
  def putMessage(message: Message): IO[YallmoError, Message]

  def listBlobIds: IO[YallmoError, Chunk[BlobId]]

  def listSessions: IO[YallmoError, Chunk[SessionSummary]]

  def createSession(id: SessionId, agentIds: Seq[AgentId]): IO[YallmoError, Session]

  /** Creates a session with optional descriptive info. Storage assigns metadata and validates any parent exists. */
  def createSession(id: SessionId, agentIds: Seq[AgentId], info: Option[SessionInfo]): IO[YallmoError, Session]

  /** Revision-checked whole-info replacement. None preserves existing info; Some replaces all fields. Parent lineage
    * cannot change. Legacy records start at revision 0; no-op updates do not advance it. Storage owns all timestamps.
    */
  def updateSessionInfo(id: SessionId, expectedRevision: Long, info: Option[SessionInfo]): IO[YallmoError, Session]

  /** Revision-checked organizational archive flag, independent of execution and the message head. Repeated archive
    * retains its original timestamp; unarchive clears it. Stale revisions fail even when the requested change is a
    * no-op.
    */
  def setSessionArchived(id: SessionId, expectedRevision: Long, archived: Boolean): IO[YallmoError, Session]

  def loadSession(id: SessionId): IO[YallmoError, Session]

  /** Registers another author without changing existing messages. Resolution belongs to the orchestrator. */
  def addSessionAgent(id: SessionId, agentId: AgentId): IO[YallmoError, Session]

  /** Stores a complete, preconstructed chain and advances the session only if its head equals `expectedHead`. Hashes
    * and the entire chain are validated before writing; stale heads fail without writes. Authors may differ within a
    * batch: author resolution and membership checks belong to the orchestrator. Concurrent participant additions are
    * retained. Implementations serialize publication, but need not provide rollback or crash recovery.
    */
  def putMessagesAndUpdateSession(
      id: SessionId,
      expectedHead: Option[MessageId],
      messages: Seq[Message]
  ): IO[YallmoError, Session]

  /** Replaces only an existing session pointer with validated, already stored ancestry. Atomic with session mutations;
    * retains latest participants. Never retries against a newly observed competing head.
    *
    * TODO RENAME to only setSessionHead
    */
  def compareAndSetSessionHead(
      id: SessionId,
      expectedHead: Option[MessageId], // option condition
      newHead: Option[MessageId]
  ): IO[YallmoError, Session]

  /** Stores a full blob independently of session/message appends. No producer or stream API. */
  def putBlob(blob: Blob): IO[YallmoError, Unit]

  def getBlob(blobId: BlobId): IO[YallmoError, Blob]

  /** Creates `target` at the current head of `source`. */
  def fork(source: SessionId, target: SessionId): IO[YallmoError, Session]

}
