package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{FileAlreadyExistsException, Files, NoSuchFileException, Path, StandardOpenOption}
import java.time.Instant
import zio.*

/** Filesystem-backed [[Storage]] implementation.
  *
  * It persists agent identifiers as session metadata but has no agent definitions, runtime bindings, or execution
  * knowledge. [[Orchestrator]] is responsible for resolving those identifiers. Each blob is one raw-byte file at
  * `blobs/<BlobId>`, with no metadata or framing. Files are written directly; overlapping reads or failed writes can
  * expose partial bytes. Callers requiring all supplied bytes must wait for the blob append to finish successfully.
  * Blob files and their directory use filesystem-default permissions; root permissions are initialized by the object
  * store.
  *
  * Concurrency assumption: exactly one Storage service owns a storage root, with no other process writing to it.
  * Callers must serialize message appends and session-metadata mutations across that root, including different
  * sessions. A single service instance can still receive concurrent calls from multiple fibers; this class provides no
  * internal writer lock.
  *
  * Violating that contract can lose session-head or agent-membership updates and overwrite/interleave `objects.jsonl`
  * records. Atomic session-file replacement prevents torn files, not lost updates. Appends are not rollback
  * transactions.
  *
  * If concurrent writers are needed, introduce storage-level coordination covering all message/session mutations, not
  * just appends, and test overlapping writes. Multiple processes additionally require filesystem locking or a
  * transactional storage backend.
  */
private[orchestration] final class StorageFS private (val root: Path, store: StorageStore) extends Storage {

  override def listSessions: IO[YallmoError, Chunk[SessionSummary]] =
    StorageFiles.list(root).flatMap { ids =>
      ZIO.foreach(ids) { id =>
        readState(id).flatMap(state => messageCount(state.head).map(SessionSummary(id, _, state.agentIds)))
      }
    }

  override def createSession(id: SessionId, agentIds: Chunk[AgentId]): IO[YallmoError, Session] =
    validateAgentIds(id, agentIds) *>
      StorageFiles.exists(root, id).flatMap {
        case true  => ZIO.fail(YallmoError.SessionAlreadyExists(id))
        case false => StorageFiles.write(root, id, agentIds, None).as(Session(id, agentIds, Chunk.empty))
      }

  override def loadSession(id: SessionId): IO[YallmoError, Session] =
    for {
      state <- readState(id)
      messages <- messagesFrom(state.head)
    } yield Session(id, state.agentIds, messages)

  override def addSessionAgent(id: SessionId, agentId: AgentId): IO[YallmoError, Session] =
    for {
      state <- readState(id)
      _ <-
        if state.agentIds.contains(agentId) then ZIO.unit
        else StorageFiles.write(root, id, state.agentIds :+ agentId, state.head)
      session <- loadSession(id)
    } yield session

  override def append(
      id: SessionId,
      agentId: AgentId,
      messages: Chunk[MessagePayload]
  ): IO[YallmoError, Session] =
    if messages.isEmpty then ZIO.fail(YallmoError.EmptyAppend(id))
    else
      for {
        state <- readState(id)
        _ <- ZIO.fail(YallmoError.AgentNotInSession(id, agentId)).unless(state.agentIds.contains(agentId))
        head <- ZIO.foldLeft(messages)(state.head) { (parent, message) =>
          store.put(MessageData(agentId, parent, Instant.now(), message)).map(stored => Some(stored.id))
        }
        _ <- StorageFiles.write(root, id, state.agentIds, head)
        session <- loadSession(id)
      } yield session

  private def blobPath(blobId: BlobId): Path = root.resolve("blobs").resolve(blobId.value)

  override def append(blob: Blob): IO[YallmoError, Unit] =
    ZIO
      .attemptBlocking {
        val path = blobPath(blob.id)
        Files.createFile(path)
        val channel = FileChannel.open(path, StandardOpenOption.WRITE)
        try {
          val buffer = ByteBuffer.wrap(blob.bytes.toArray)
          while buffer.hasRemaining do channel.write(buffer)
          channel.force(true)
        } finally channel.close()
      }
      .mapError {
        case _: FileAlreadyExistsException => YallmoError.BlobAlreadyExists(blob.id)
        case error                         => YallmoError.StorageFailure("append blob", error)
      }
      .uninterruptible

  override def readBlob(blobId: BlobId): IO[YallmoError, Blob] =
    ZIO
      .attemptBlocking(Blob(blobId, Chunk.fromArray(Files.readAllBytes(blobPath(blobId)))))
      .mapError {
        case _: NoSuchFileException => YallmoError.BlobMissing(blobId)
        case error                  => YallmoError.StorageFailure("read blob", error)
      }

  override def fork(source: SessionId, target: SessionId): IO[YallmoError, Session] =
    StorageFiles.exists(root, target).flatMap {
      case true  => ZIO.fail(YallmoError.SessionAlreadyExists(target))
      case false =>
        readState(source).flatMap { state =>
          StorageFiles.write(root, target, state.agentIds, state.head) *> loadSession(target)
        }
    }

  private final case class SessionState(agentIds: Chunk[AgentId], head: Option[MessageId])

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
      store <- StorageStore.open(root)
      _ <- StorageFiles.ensureDirectories(root)
      _ <- ZIO
        .attemptBlocking(Files.createDirectories(root.resolve("blobs")))
        .mapError(error => YallmoError.StorageFailure("create blob directory", error))
    } yield StorageFS(root, store)
}
