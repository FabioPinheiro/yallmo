package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import fmgp.yallmo.orchestration.storage.{StorageFS, StorageInMemory}
import java.nio.file.Path
import zio.*

/** Default [[Orchestrator]] implementation backed by an internal [[Storage]]. */
final class OrchestratorLive[-R] private (
    val storage: Storage,
    val instances: RuntimeInstances[R]
) extends Orchestrator[R] {

  override def getAgent(id: AgentId): IO[YallmoError, Agent[R]] =
    instances.getAgent(id)

  def listAgentIds: IO[YallmoError, Chunk[AgentId]] =
    instances.listAgentIds

  def listMessages: IO[YallmoError, Chunk[Message]] =
    storage.listMessages.mapError(YallmoError.StorageFailed.apply)

  def listBlobIds: IO[YallmoError, Chunk[BlobId]] =
    storage.listBlobIds.mapError(YallmoError.StorageFailed.apply)

  override def listSessions: IO[YallmoError, Chunk[SessionSummary]] =
    storage.listSessions.mapError(YallmoError.StorageFailed.apply)

  @deprecated("Use submit for session-aware agent execution and persistence.", "2.0.0")
  override def turn(id: AgentId, request: AgentTurnRequest): ZIO[R, YallmoError, AgentTurnResponse] =
    getAgent(id).flatMap(_.turn(request.copy(readBlob = id => readBlob(id))))

  override def submit[R1](
      id: SessionId,
      userId: AgentId,
      task: AgentTask,
      execution: AgentExecution[R1]
  ): ZIO[R1, SessionSubmitFailure, SessionTurn] =
    for {
      // Await prompt persistence before execution; Storage owns interruption safety for its critical writes.
      accepted <- (for {
        _ <- getAgent(execution.agentId)
        _ <- addSessionAgent(id, userId)
        session <- append(id, userId, Chunk(MessagePayload.User(task.prompt)))
      } yield session).mapError(SessionSubmitFailure(_, None))
      context = RunContext(accepted.messages.map(_.payload))
      submitted <- ZIO.scoped {
        for {
          persisted <- Ref.make((context, accepted))
          latest <- Ref.make(context)
          // One writer preserves checkpoint order without making model/tool work wait for storage.
          // Runner budgets bound the number of queued snapshots.
          queue <- Queue.unbounded[Option[RunContext]]
          persist = (next: RunContext) =>
            for {
              current <- persisted.get
              _ <- ZIO
                .fail(YallmoError.InvalidAgentDecision("Agent checkpoint must preserve the persisted transcript"))
                .unless(next.messages.take(current._1.messages.size) == current._1.messages)
              delta = next.messages.drop(current._1.messages.size)
              saved <-
                if delta.isEmpty then ZIO.succeed(current._2)
                else addSessionAgent(id, execution.agentId) *> append(id, execution.agentId, delta)
              _ <- persisted.set((next, saved))
            } yield ()
          writer <- queue.take
            .flatMap {
              case Some(next) =>
                persist(next)
                  .catchAllCause(cause =>
                    ZIO.logErrorCause(s"Session ${id.value} checkpoint persistence failed", cause)
                  )
                  .as(true)
              case None => ZIO.succeed(false)
            }
            .repeatWhile(identity)
            .forkScoped
          // Cancellation must not split bookkeeping from enqueueing; this never waits for storage.
          saveTranscriptLater = (next: RunContext) => (latest.set(next) *> queue.offer(Some(next)).unit).uninterruptible
          // Drain even on failure/cancellation, before the scoped writer is interrupted.
          // Later snapshots retry any suffix that an earlier checkpoint failed to save.
          finish = queue.offer(None) *> writer.join *> latest.get.zip(persisted.get).flatMap {
            case (requested, (stored, _)) =>
              val missing = requested.messages.size - stored.messages.size
              ZIO
                .logWarning(s"Session ${id.value} submission has $missing generated message(s) not stored")
                .when(missing > 0)
                .unit
          }
          turn <- (for {
            turn <- execution.run(task, context, saveTranscriptLater)
            _ <- ZIO
              .fail(
                YallmoError.InvalidAgentDecision("Agent execution must preserve the accepted transcript and agent ID")
              )
              .unless(
                turn.result.agentId == execution.agentId &&
                  turn.runContext.messages.take(context.messages.size) == context.messages
              )
            _ <- saveTranscriptLater(turn.runContext)
          } yield turn)
            .ensuring(finish)
            .catchAll(error =>
              persisted.get.flatMap { case (_, saved) => ZIO.fail(SessionSubmitFailure(error, Some(saved))) }
            )
          saved <- persisted.get.map(_._2)
          response = turn.runContext.messages.drop(context.messages.size)
        } yield SessionTurn(saved, turn.result, Chunk(MessagePayload.User(task.prompt)) ++ response)
      }
    } yield submitted

  private def persistMessage(message: Message): IO[YallmoError, Message] =
    instances.validateAuthor(message.agentId) *>
      storage.putMessage(message).mapError(YallmoError.StorageFailed.apply)

  override def invoke[R1](input: Message, execution: AgentInvocation[R1]): ZIO[R1, YallmoError, Message] =
    for {
      _ <- ZIO
        .fail(YallmoError.InvalidMessage("Message id does not match data"))
        .unless(input.id == MessageId.from(input.data))
      _ <- getAgent(execution.agentId)
      accepted <- persistMessage(input)
      _ <- ZIO.foreachDiscard(accepted.payload.blobIds.distinct)(id => readBlob(id, BlobUploadLimits.AwaitTimeout))
      payload <- execution.invoke(accepted.payload)
      timestamp <- Clock.instant
      response <- persistMessage(
        Message(
          MessageData(
            execution.agentId,
            Some(accepted.id),
            timestamp,
            payload
          )
        )
      )
    } yield response

  override def createSession(id: SessionId, agentIds: Chunk[AgentId]): IO[YallmoError, Session] =
    validateAuthors(agentIds) *> storage.createSession(id, agentIds).mapError(YallmoError.StorageFailed.apply)

  override def loadSession(id: SessionId): IO[YallmoError, Session] =
    storage.loadSession(id).mapError(YallmoError.StorageFailed.apply)

  override def loadSessionParcial(id: SessionId): IO[YallmoError, SessionParcial] =
    storage.loadSessionParcial(id).mapError(YallmoError.StorageFailed.apply)

  override def addSessionAgent(id: SessionId, agentId: AgentId): IO[YallmoError, Session] =
    instances.validateAuthor(agentId) *> storage.addSessionAgent(id, agentId).mapError(YallmoError.StorageFailed.apply)

  override def append(
      id: SessionId,
      agentId: AgentId,
      messages: Chunk[MessagePayload]
  ): IO[YallmoError, Session] =
    instances.validateAuthor(agentId) *>
      (if messages.isEmpty then ZIO.fail(YallmoError.EmptyAppend(id))
       else appendToLatestSession(id, agentId, messages)).mapError(YallmoError.StorageFailed.apply)

  private def appendToLatestSession(
      id: SessionId,
      agentId: AgentId,
      payloads: Chunk[MessagePayload]
  ): IO[YallmoError, Session] =
    (for {
      session <- storage.loadSession(id)
      _ <- ZIO.fail(YallmoError.AgentNotInSession(id, agentId)).unless(session.agentIds.contains(agentId))
      expectedHead = session.messages.lastOption.map(_.id)
      messages <- ZIO.foldLeft(payloads)(Chunk.empty[Message]) { (batch, payload) =>
        Clock.instant.map { timestamp =>
          val parent = batch.lastOption.map(_.id).orElse(expectedHead)
          batch :+ Message(MessageData(agentId, parent, timestamp, payload))
        }
      }
      saved <- storage.putMessagesAndUpdateSession(id, expectedHead, messages)
    } yield saved).catchSome {
      // Only this pre-write rejection is safe to retry. Rebuild timestamps/parents from the latest snapshot.
      case _: YallmoError.SessionHeadConflict => ZIO.yieldNow *> appendToLatestSession(id, agentId, payloads)
    }

  override def readBlob(blobId: BlobId, awaitTimeout: Duration = Duration.Zero): IO[YallmoError, Blob] =
    storage.readBlob(blobId, awaitTimeout)

  override def beginBlobUpload(blobId: BlobId, totalBytes: Long): IO[YallmoError, BlobUploader] =
    storage.beginBlobUpload(blobId, totalBytes)

  override def appendWithBlobWriter[R1](
      id: SessionId,
      agentId: AgentId,
      message: MessagePayload,
      produce: (Chunk[Byte] => IO[YallmoError, Unit]) => ZIO[R1, YallmoError, Unit]
  ): ZIO[R1, YallmoError, Session] =
    for {
      _ <- instances.validateAuthor(agentId)
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
          _ <- storage.putBlob(Blob(blobId, bytes))
          appended <- exit
        } yield appended
      }
    } yield result

  override def fork(source: SessionId, target: SessionId): IO[YallmoError, Session] =
    storage.fork(source, target).mapError(YallmoError.StorageFailed.apply)

  private def validateAuthors(agentIds: Chunk[AgentId]): IO[YallmoError, Unit] =
    ZIO.foreachDiscard(agentIds)(instances.validateAuthor)
}

object OrchestratorLive {

  /** Creates an orchestrator using a shared registry and ephemeral, process-local storage. */
  def inMemory[R](
      instances: RuntimeInstances[R]
  ): UIO[OrchestratorLive[R]] =
    for {
      storage <- StorageInMemory.make
    } yield new OrchestratorLive(storage, instances)

  /** Opens filesystem storage using a shared registry. */
  def filesystem[R](
      root: Path,
      instances: RuntimeInstances[R]
  ): IO[YallmoError, OrchestratorLive[R]] =
    for {
      storage <- StorageFS.open(root)
    } yield new OrchestratorLive(storage, instances)

}
