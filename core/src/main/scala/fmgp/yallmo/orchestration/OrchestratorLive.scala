package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import fmgp.yallmo.orchestration.storage.{StorageFS, StorageInMemory}
import java.nio.file.Path
import zio.*

/** Default [[Orchestrator]] implementation backed by an internal [[Storage]]. */
final class OrchestratorLive[-R] private (
    storage: Storage,
    instances: RuntimeInstances[R]
) extends Orchestrator[R] {

  override def getAgent(id: AgentId): IO[YallmoError, Agent[R]] =
    instances.getAgent(id)

  override def turn(id: AgentId, request: AgentTurnRequest): ZIO[R, YallmoError, AgentTurnResponse] =
    getAgent(id).flatMap(_.turn(request.copy(readBlob = readBlob)))

  override def listSessions: IO[YallmoError, Chunk[SessionSummary]] =
    storage.listSessions.mapError(YallmoError.StorageFailed.apply)

  override def createSession(id: SessionId, agentIds: Chunk[AgentId]): IO[YallmoError, Session] =
    validateAgents(agentIds) *> storage.createSession(id, agentIds).mapError(YallmoError.StorageFailed.apply)

  override def loadSession(id: SessionId): IO[YallmoError, Session] =
    storage.loadSession(id).mapError(YallmoError.StorageFailed.apply)

  override def addSessionAgent(id: SessionId, agentId: AgentId): IO[YallmoError, Session] =
    getAgent(agentId) *> storage.addSessionAgent(id, agentId).mapError(YallmoError.StorageFailed.apply)

  override def append(
      id: SessionId,
      agentId: AgentId,
      messages: Chunk[MessagePayload]
  ): IO[YallmoError, Session] =
    getAgent(agentId) *> storage.append(id, agentId, messages).mapError(YallmoError.StorageFailed.apply)

  override def readBlob(blobId: BlobId): IO[YallmoError, Blob] = storage.readBlob(blobId)

  override def appendWithBlobWriter[R1](
      id: SessionId,
      agentId: AgentId,
      message: MessagePayload,
      produce: (Chunk[Byte] => IO[YallmoError, Unit]) => ZIO[R1, YallmoError, Unit]
  ): ZIO[R1, YallmoError, Session] =
    for {
      _ <- getAgent(agentId)
      session <- loadSession(id)
      _ <- ZIO.fail(YallmoError.AgentNotInSession(id, agentId)).unless(session.agentIds.contains(agentId))
      blobId <- message.blobIds.toList match {
        case blobId :: Nil => ZIO.succeed(blobId)
        case _             => ZIO.fail(YallmoError.InvalidConfig("Blob append requires exactly one blob ID"))
      }
      result <- ZIO.uninterruptibleMask { restore =>
        for {
          // A closed buffer also closes the scoped writer. Each write and close is atomic.
          buffer <- Ref.make[Option[Chunk[Byte]]](Some(Chunk.empty))
          write = (bytes: Chunk[Byte]) =>
            buffer
              .modify {
                case Some(current) => (Right(()), Some(current ++ bytes))
                case None          => (Left(YallmoError.BlobWriterClosed(blobId)), None)
              }
              .flatMap(ZIO.fromEither)
          exit <- (append(id, agentId, Chunk(message)) <* restore(ZIO.suspendSucceed(produce(write)))).exit
          bytes <- buffer.getAndSet(None).map(_.getOrElse(Chunk.empty))
          _ <- storage.append(Blob(blobId, bytes))
          appended <- exit
        } yield appended
      }
    } yield result

  override def fork(source: SessionId, target: SessionId): IO[YallmoError, Session] =
    storage.fork(source, target).mapError(YallmoError.StorageFailed.apply)

  private def validateAgents(agentIds: Chunk[AgentId]): IO[YallmoError, Unit] =
    ZIO.foreachDiscard(agentIds)(getAgent)
}

object OrchestratorLive {

  /** Creates an orchestrator using a shared registry and ephemeral, process-local storage. */
  def inMemory[R](instances: RuntimeInstances[R]): UIO[Orchestrator[R]] =
    StorageInMemory.make.map(storage => new OrchestratorLive(storage, instances))

  /** Opens filesystem storage using a shared registry. */
  def filesystem[R](root: Path, instances: RuntimeInstances[R]): IO[YallmoError, Orchestrator[R]] =
    StorageFS.open(root).map(storage => new OrchestratorLive(storage, instances))

}
