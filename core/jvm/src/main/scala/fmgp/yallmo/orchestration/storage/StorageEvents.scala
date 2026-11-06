package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import zio.*

/** All orchestration mutation paths share this decoration, including CAS and acknowledgement recovery. */
private[orchestration] final class StorageEvents(base: Storage, updates: OrchestrationEvents) extends Storage {
  private val RecoveryTimeout = 2.seconds
  private def dispatch[A](messages: Chunk[Message], sessionId: Option[SessionId], successSessions: A => Chunk[Session])(
      storage: IO[YallmoError, A]
  ): IO[YallmoError, A] =
    ZIO.uninterruptibleMask { restore =>
      for {
        observation <- updates.begin(messages)
        // Intentionally parallel: pending live delivery is not persistence. Replace zipPar with zip to sequence
        // these handlers; keep the Exit/terminal reconciliation, including failures and interruption.
        delivery <- restore(storage.exit.zipPar(updates.pending(observation))).exit
        exit: Exit[YallmoError, A] = delivery match {
          case Exit.Success(value) => value
          case Exit.Failure(cause) => Exit.failCause(cause)
        }
        // Recovery is read-only and bounded. Cancellation must not wait for another storage read or imply rollback.
        recovery <- (exit match {
          case Exit.Success(value)     => ZIO.succeed(Some((true, successSessions(value))))
          case _ if exit.isInterrupted => ZIO.succeed(None)
          case Exit.Failure(_)         =>
            (for {
              sessions <- ZIO.foreach(sessionId)(base.loadSession(_))
              saved <-
                if messages.nonEmpty then base.listMessages.map(inventory => messages.forall(inventory.contains))
                else ZIO.succeed(sessionId.nonEmpty)
            } yield (saved, Chunk.fromIterable(sessions))).interruptible.timeout(RecoveryTimeout).orElseSucceed(None)
        }).exit
        canonical = recovery.toEither.toOption.flatten
        _ <- updates.complete(
          observation,
          canonical.exists(_._1),
          canonical.fold(Chunk.empty[Session])(_._2)
        )
        result <- exit
      } yield result
    }

  private def session(id: SessionId)(effect: IO[YallmoError, Session]): IO[YallmoError, Session] =
    updates.mutationGate.withPermit(dispatch[Session](Chunk.empty, Some(id), saved => Chunk(saved))(effect))

  override def listMessages = base.listMessages
  override def listBlobIds = base.listBlobIds
  override def listSessions = base.listSessions
  override def loadSession(id: SessionId) = base.loadSession(id)
  override def loadSessionParcial(id: SessionId) = base.loadSessionParcial(id)

  override def putMessage(message: Message): IO[YallmoError, Message] =
    for {
      inventory <- base.listMessages
      _ <- ZIO
        .fail(YallmoError.InvalidMessage("Invalid immutable message"))
        .unless(
          message.id == MessageId.from(message.data) && message.parentId.forall(id => inventory.exists(_.id == id)) &&
            !inventory.exists(stored => stored.id == message.id && stored != message)
        )
      saved <- dispatch[Message](Chunk(message), None, _ => Chunk.empty)(base.putMessage(message))
    } yield saved

  override def createSession(id: SessionId, agentIds: Chunk[AgentId]) = session(id)(base.createSession(id, agentIds))
  override def addSessionAgent(id: SessionId, agentId: AgentId) = session(id)(base.addSessionAgent(id, agentId))
  override def compareAndSetSessionHead(id: SessionId, expectedHead: Option[MessageId], newHead: Option[MessageId]) =
    session(id)(base.compareAndSetSessionHead(id, expectedHead, newHead))
  override def fork(source: SessionId, target: SessionId) = session(target)(base.fork(source, target))

  override def putMessagesAndUpdateSession(
      id: SessionId,
      expectedHead: Option[MessageId],
      messages: Chunk[Message]
  ): IO[YallmoError, Session] = updates.mutationGate.withPermit {
    for {
      previous <- base.loadSession(id)
      _ <- ZIO.fromEither(
        StoreageUtils.validateMessageBatch(id, expectedHead, previous.messages.lastOption.map(_.id), messages)
      )
      saved <- dispatch[Session](messages, Some(id), saved => Chunk(saved))(
        base.putMessagesAndUpdateSession(id, expectedHead, messages)
      )
    } yield saved
  }

  override def putBlob(blob: Blob) = base.putBlob(blob)
  override def getBlob(blobId: BlobId) = base.getBlob(blobId)
  override def readBlob(blobId: BlobId, awaitTimeout: Duration = Duration.Zero) = base.readBlob(blobId, awaitTimeout)
  override def beginBlobUpload(blobId: BlobId, totalBytes: Long) = base.beginBlobUpload(blobId, totalBytes)
}
