package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{FileAlreadyExistsException, Files, LinkOption, NoSuchFileException, Path, StandardOpenOption}
import scala.jdk.CollectionConverters.*
import zio.*

/** Filesystem-backed [[Storage]] implementation.
  *
  * Exactly one Storage service must own each root, with no other process writing to it. Its root-local semaphore
  * serializes session/object mutations and snapshot acquisition across fibers, not provider/tool execution or blob
  * producers. Published histories use immutable, append-only objects and can be traversed after releasing the permit.
  * This is not cross-process coordination, a crash transaction, or rollback on failure.
  *
  * Each blob is one raw-byte file at `blobs/<BlobId>`, with no metadata or framing. Publication links a fully written,
  * forced temporary file exclusively into place; failed writes never expose partial blobs. Blob files use
  * filesystem-default permissions; the object store initializes private root permissions.
  */
private[orchestration] final class StorageFS(
    val root: Path,
    store: StorageStore,
    lock: Semaphore,
    uploads: BlobOpsUtils.Registry
) extends Storage {

  override def readBlob(blobId: BlobId, awaitTimeout: Duration = Duration.Zero): IO[YallmoError, Blob] =
    BlobOpsUtils.read(this, uploads, blobId, awaitTimeout)

  override def beginBlobUpload(blobId: BlobId, totalBytes: Long): IO[YallmoError, BlobUploader] =
    BlobOpsUtils.begin(this, uploads, blobId, totalBytes)

  override def listMessages: IO[YallmoError, Chunk[Message]] = lock.withPermit(store.listMessages)

  override def putMessage(message: Message): IO[YallmoError, Message] =
    lock.withPermit {
      ZIO
        .fail(YallmoError.InvalidMessage("Message id does not match data"))
        .unless(message.id == MessageId.from(message.data)) *>
        store.put(message.data).map(stored => Message(stored.id, stored.value)).uninterruptible
    }

  override def listBlobIds: IO[YallmoError, Chunk[BlobId]] = lock.withPermit {
    ZIO
      .attemptBlocking {
        val files = Files.list(root.resolve("blobs"))
        try
          Chunk.fromIterable(
            files.iterator.asScala
              .filter(path => Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
              .flatMap(path => BlobId.from(path.getFileName.toString).toOption)
              .toList
              .sortBy(_.value)
          )
        finally files.close()
      }
      .mapError(error => YallmoError.StorageFailure("list blobs", error))
  }

  override def listSessions: IO[YallmoError, Chunk[SessionSummary]] =
    lock
      .withPermit {
        StorageFiles.list(root).flatMap(ids => ZIO.foreach(ids)(id => readState(id).map(id -> _)))
      }
      .flatMap { states =>
        ZIO.foreach(states) { case (id, state) =>
          messageCount(state.head).map(SessionSummary(id, _, state.agentIds))
        }
      }

  override def createSession(id: SessionId, agentIds: Chunk[AgentId]): IO[YallmoError, Session] =
    lock.withPermit {
      validateAgentIds(id, agentIds) *>
        StorageFiles.exists(root, id).flatMap {
          case true  => ZIO.fail(YallmoError.SessionAlreadyExists(id))
          case false => StorageFiles.write(root, id, agentIds, None).as(Session(id, agentIds, Chunk.empty))
        }
    }

  override def loadSession(id: SessionId): IO[YallmoError, Session] =
    lock.withPermit(readState(id)).flatMap(sessionFrom(id, _))

  override def addSessionAgent(id: SessionId, agentId: AgentId): IO[YallmoError, Session] =
    lock
      .withPermit {
        for {
          state <- readState(id)
          updated = if state.agentIds.contains(agentId) then state else state.copy(agentIds = state.agentIds :+ agentId)
          _ <- StorageFiles.write(root, id, updated.agentIds, updated.head).unless(updated == state)
        } yield updated
      }
      .flatMap(sessionFrom(id, _))

  override def putMessagesAndUpdateSession(
      id: SessionId,
      expectedHead: Option[MessageId],
      messages: Chunk[Message]
  ): IO[YallmoError, Session] =
    lock
      .withPermit {
        for {
          state <- readState(id)
          _ <- ZIO.fromEither(StoreageUtils.validateMessageBatch(id, expectedHead, state.head, messages))
          // Once object writes start, finish the batch and publish its head before honoring cancellation.
          // The permit covers validation and the entire batch. I/O failure may leave orphan objects;
          // masking is not rollback/crash recovery. Stale-head and invalid-chain rejections write nothing.
          updated <- (for {
            _ <- ZIO.foreachDiscard(messages)(message => store.put(message.data))
            head = messages.lastOption.map(_.id)
            _ <- StorageFiles.write(root, id, state.agentIds, head)
          } yield state.copy(head = head)).uninterruptible
        } yield updated
      }
      .flatMap(sessionFrom(id, _))

  private def blobPath(blobId: BlobId): Path = root.resolve("blobs").resolve(blobId.value)

  override def putBlob(blob: Blob): IO[YallmoError, Unit] = lock.withPermit {
    ZIO
      .attemptBlocking {
        val path = blobPath(blob.id)
        val staged = Files.createTempFile(root.resolve("blobs"), ".upload-", ".tmp")
        try {
          val channel = FileChannel.open(staged, StandardOpenOption.WRITE)
          try {
            val buffer = ByteBuffer.wrap(blob.bytes.toArray)
            while buffer.hasRemaining do channel.write(buffer)
            channel.force(true)
          } finally channel.close()
          // A hard link is an atomic exclusive publication, unlike ATOMIC_MOVE which may replace a target.
          Files.createLink(path, staged)
          try {
            val directory = FileChannel.open(path.getParent, StandardOpenOption.READ)
            try directory.force(true)
            finally directory.close()
          } catch {
            case error: java.io.IOException =>
              try Files.delete(path)
              catch { case cleanup: java.io.IOException => error.addSuppressed(cleanup) }
              throw error
          }
        } finally {
          // A cleanup failure after publication must not report an unsuccessful append of readable content.
          try { Files.deleteIfExists(staged); () }
          catch { case _: java.io.IOException => () }
        }
      }
      .mapError {
        case _: FileAlreadyExistsException => YallmoError.BlobAlreadyExists(blob.id)
        case error                         => YallmoError.StorageFailure("append blob", error)
      }
      .uninterruptible
  }

  override def getBlob(blobId: BlobId): IO[YallmoError, Blob] = lock.withPermit {
    ZIO
      .attemptBlocking(Blob(blobId, Chunk.fromArray(Files.readAllBytes(blobPath(blobId)))))
      .mapError {
        case _: NoSuchFileException => YallmoError.BlobMissing(blobId)
        case error                  => YallmoError.StorageFailure("read blob", error)
      }
  }

  override def fork(source: SessionId, target: SessionId): IO[YallmoError, Session] =
    lock
      .withPermit {
        StorageFiles.exists(root, target).flatMap {
          case true  => ZIO.fail(YallmoError.SessionAlreadyExists(target))
          case false =>
            readState(source).flatMap { state =>
              StorageFiles.write(root, target, state.agentIds, state.head).as(state)
            }
        }
      }
      .flatMap(sessionFrom(target, _))

  private final case class SessionState(agentIds: Chunk[AgentId], head: Option[MessageId])

  private def sessionFrom(id: SessionId, state: SessionState): IO[YallmoError, Session] =
    messagesFrom(state.head).map(Session(id, state.agentIds, _))

  private def readState(id: SessionId): IO[YallmoError, SessionState] =
    StorageFiles.read(root, id).map(ref => SessionState(ref.agentIds, ref.head))

  private def messagesFrom(head: Option[MessageId]): IO[YallmoError, Chunk[Message]] =
    head.fold[IO[YallmoError, Chunk[Message]]](ZIO.succeed(Chunk.empty)) { messageId =>
      store.get(messageId).flatMap {
        case Some(DecodedArtifact.Msg(stored)) =>
          messagesFrom(stored.value.parentId).map(_ :+ Message(stored.id, stored.value))
        case None =>
          ZIO.fail(
            YallmoError.StorageFailure(
              s"session refers to missing message $messageId",
              new java.util.NoSuchElementException(MessageId.value(messageId))
            )
          )
      }
    }

  private def messageCount(head: Option[MessageId]): IO[YallmoError, Long] =
    messagesFrom(head).map(_.size.toLong)

  private def validateAgentIds(id: SessionId, agentIds: Chunk[AgentId]): IO[YallmoError, Unit] =
    agentIds.headOption match
      case None    => ZIO.fail(YallmoError.EmptyAgentIds(id))
      case Some(_) =>
        agentIds
          .groupBy(identity)
          .collectFirst { case (agentId, values) if values.size > 1 => agentId }
          .fold[IO[YallmoError, Unit]](ZIO.unit)(agentId => ZIO.fail(YallmoError.DuplicateAgentId(id, agentId)))
}

private[orchestration] object StorageFS {
  def open(root: Path): IO[YallmoError, StorageFS] =
    for {
      lock <- Semaphore.make(1)
      uploads <- BlobOpsUtils.registry
      store <- StorageStore.open(root)
      _ <- StorageFiles.ensureDirectories(root)
      _ <- ZIO
        .attemptBlocking(Files.createDirectories(root.resolve("blobs")))
        .mapError(error => YallmoError.StorageFailure("create blob directory", error))
    } yield StorageFS(root, store, lock, uploads)
}
