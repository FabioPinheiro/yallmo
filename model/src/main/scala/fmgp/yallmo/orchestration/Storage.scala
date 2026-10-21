package fmgp.yallmo.orchestration

import fmgp.yallmo.model.{AgentId, Blob, BlobId, MessagePayload, YallmoError}
import zio.{Chunk, IO}

/** Lightweight information suitable for a session picker. */
final case class SessionSummary(id: SessionId, messageCount: Long, agentIds: Chunk[AgentId])

/** Storage for conversation transcripts and binary blobs.
  *
  * Storage records session membership as agent IDs only; it neither resolves nor executes agents. Application code must
  * access storage through [[Orchestrator]], which validates those IDs against its registered agents.
  */
private[orchestration] trait Storage {
  def listSessions: IO[YallmoError, Chunk[SessionSummary]]

  def createSession(id: SessionId, agentIds: Chunk[AgentId]): IO[YallmoError, Session]

  def loadSession(id: SessionId): IO[YallmoError, Session]

  /** Registers another author without changing existing messages. Resolution belongs to the orchestrator. */
  def addSessionAgent(id: SessionId, agentId: AgentId): IO[YallmoError, Session]

  /** Appends one completed turn for an agent associated with the session. */
  def append(id: SessionId, agentId: AgentId, messages: Chunk[MessagePayload]): IO[YallmoError, Session]

  /** Stores a full blob independently of session/message appends. No producer or stream API. */
  def append(blob: Blob): IO[YallmoError, Unit]

  // TODO rename to retrieve
  def readBlob(blobId: BlobId): IO[YallmoError, Blob]

  /** Creates `target` at the current head of `source`. */
  def fork(source: SessionId, target: SessionId): IO[YallmoError, Session]

}
