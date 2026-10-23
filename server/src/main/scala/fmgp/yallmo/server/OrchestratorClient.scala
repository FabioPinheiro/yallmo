package fmgp.yallmo.server

import fmgp.yallmo.model.{AgentId, BlobId}
import fmgp.yallmo.orchestration.{SessionId, SessionSummary}
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import zio.*

/** Transport-neutral client API for the currently implemented read-only operations. Execution and mutation are
  * deliberately absent until server-owned execution and authorization are implemented.
  */
trait OrchestratorClient {
  def initialize(clientName: String = "yallmo-jvm"): IO[OrchestratorClient.Error, Initialize.Result]
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
