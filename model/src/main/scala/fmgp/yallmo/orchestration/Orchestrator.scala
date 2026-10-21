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

  def turn(id: AgentId, request: AgentTurnRequest): ZIO[R, YallmoError, AgentTurnResponse]

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
