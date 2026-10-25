package fmgp.yallmo.server

import fmgp.yallmo.model.{AgentId, BlobId}
import fmgp.yallmo.orchestration.{SessionId, SessionSummary}
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import zio.*

/** Transport-neutral client. Optional mutations default to unavailable for existing read-only implementations. */
trait OrchestratorClient {
  def initialize(clientName: String = "yallmo-jvm"): IO[OrchestratorClient.Error, Initialize.Result]

  /** Cached negotiation only: never performs a second initialization/network request. */
  def capabilities: UIO[Option[Initialize.Result]] = ZIO.none
  @scala.annotation.nowarn("msg=unused explicit parameter")
  def createSession(name: Option[SessionId] = None): IO[OrchestratorClient.Error, SessionSnapshot] =
    ZIO.fail(OrchestratorClient.Error.Remote(JsonRpc.Error.MethodNotFound))
  @scala.annotation.nowarn("msg=unused explicit parameter")
  def submitTurn(
      sessionId: SessionId,
      agentId: AgentId,
      task: fmgp.yallmo.orchestration.AgentTask,
      expectedHead: Option[fmgp.yallmo.model.MessageId] = None,
      resetContinuation: Boolean = false
  ): IO[OrchestratorClient.Error, TurnOutcome] =
    ZIO.fail(OrchestratorClient.Error.Remote(JsonRpc.Error.MethodNotFound))
  def listSessions: IO[OrchestratorClient.Error, Chunk[SessionSummary]]
  def loadSession(id: SessionId): IO[OrchestratorClient.Error, SessionSnapshot]
  def listMessages: IO[OrchestratorClient.Error, Chunk[MessageSnapshot]]
  def listAgentIds: IO[OrchestratorClient.Error, Chunk[AgentId]]
  def listBlobIds: IO[OrchestratorClient.Error, Chunk[BlobId]]
  def readBlob(
      id: BlobId,
      offset: Long = 0L,
      maxBytes: Int = MaxBlobChunkBytes
  ): IO[OrchestratorClient.Error, BlobsRead.Result]
  def close: UIO[Unit]
}

object OrchestratorClient {
  enum Error {
    case InvalidEndpoint
    case Transport(cause: Throwable)
    case Disconnected
    case RequestTimedOut
    case TooManyPendingRequests
    case InvalidResponse
    case Remote(error: JsonRpc.Error)
  }
}
