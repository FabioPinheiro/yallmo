package fmgp.yallmo.orchestration

import fmgp.yallmo.model.{AgentId, Blob, BlobId, Message, MessageId, YallmoError}
import zio.{Chunk, IO}

/** Lightweight information suitable for a session picker. */
final case class SessionSummary(id: SessionId, messageCount: Long, agentIds: Chunk[AgentId])

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

  def createSession(id: SessionId, agentIds: Chunk[AgentId]): IO[YallmoError, Session]

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
      messages: Chunk[Message]
  ): IO[YallmoError, Session]

  /** Stores a full blob independently of session/message appends. No producer or stream API. */
  def putBlob(blob: Blob): IO[YallmoError, Unit]

  def getBlob(blobId: BlobId): IO[YallmoError, Blob]

  /** Creates `target` at the current head of `source`. */
  def fork(source: SessionId, target: SessionId): IO[YallmoError, Session]

}
