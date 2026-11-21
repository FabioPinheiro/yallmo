package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import zio.*

/** Process-local [[Storage]] implementation for callers that do not need durable filesystem state.
  *
  * FIXME there are two separated Message sets... The message should be independent from the session!
  */
private[orchestration] final class StorageInMemory private (
    sessions: Ref[Map[SessionId, Session]],
    standalone: Ref[Map[MessageId, Message]],
    blobs: Ref[Map[BlobId, Blob]],
    uploads: BlobOpsUtils.Registry
) extends Storage {
  override def readBlob(blobId: BlobId, awaitTimeout: Duration = Duration.Zero): IO[YallmoError, Blob] =
    BlobOpsUtils.read(this, uploads, blobId, awaitTimeout)

  override def beginBlobUpload(blobId: BlobId, totalBytes: Long): IO[YallmoError, BlobUploader] =
    BlobOpsUtils.begin(this, uploads, blobId, totalBytes)

  override def listMessages: IO[YallmoError, Chunk[Message]] =
    sessions.get.zip(standalone.get).map { (values, independent) =>
      Chunk
        .fromIterable(values.valuesIterator.flatMap(_.messages).toList ++ independent.values)
        .distinctBy(_.id)
        .sortBy(message => (message.timestamp.getEpochSecond, message.timestamp.getNano, MessageId.value(message.id)))
    }

  override def putMessage(message: Message): IO[YallmoError, Message] =
    for {
      _ <- ZIO
        .fail(YallmoError.InvalidMessage("Message id does not match data"))
        .unless(message.id == MessageId.from(message.data))
      // Messages are append-only: a validated parent cannot disappear while publishing this child.
      existing <- listMessages
      _ <- ZIO
        .fail(YallmoError.InvalidMessage("Message parent is missing"))
        .unless(message.parentId.forall(parent => existing.exists(_.id == parent)))
      _ <- ZIO
        .fail(YallmoError.InvalidMessage("Conflicting message id"))
        .when(existing.exists(stored => stored.id == message.id && stored != message))
      _ <- standalone.update(_.updated(message.id, message))
    } yield message

  override def listBlobIds: IO[YallmoError, Chunk[BlobId]] =
    blobs.get.map(values => Chunk.fromIterable(values.keys.toList.sortBy(_.value)))

  override def listSessions: IO[YallmoError, Chunk[SessionSummary]] =
    sessions.get.map { values =>
      Chunk.fromIterable(
        values.values.toList
          .sortBy(_.id.value)
          .map(session => SessionSummary(session.id, session.messages.size, session.agentIds))
      )
    }

  override def createSession(id: SessionId, agentIds: Seq[AgentId]): IO[YallmoError, Session] =
    createSession(id, agentIds, None)

  override def createSession(
      id: SessionId,
      agentIds: Seq[AgentId],
      info: Option[SessionInfo]
  ): IO[YallmoError, Session] =
    for {
      _ <- ZIO.fromEither(validateAgentIds(id, agentIds))
      now <- Clock.instant
      saved <- sessions
        .modify { current =>
          val result = for {
            _ <- Either.cond(!current.contains(id), (), YallmoError.SessionAlreadyExists(id))
            _ <- info.flatMap(_.parentSessionId) match {
              case Some(parent) if parent == id =>
                Left(YallmoError.InvalidSessionInfo(id, "a session cannot be its own parent"))
              case Some(parent) if !current.contains(parent) => Left(YallmoError.SessionNotFound(parent))
              case _                                         => Right(())
            }
          } yield {
            val ref = SessionRecords.created(agentIds, info, now)
            Session(id, ref.agentIds, Seq.empty, ref.meta, ref.info)
          }
          (result, result.fold(_ => current, session => current.updated(id, session)))
        }
        .flatMap(ZIO.fromEither)
    } yield saved

  override def updateSessionInfo(
      id: SessionId,
      expectedRevision: Long,
      info: Option[SessionInfo]
  ): IO[YallmoError, Session] =
    Clock.instant.flatMap(now => updateRecord(id)(SessionRecords.updateInfo(id, _, expectedRevision, info, now)))

  override def setSessionArchived(id: SessionId, expectedRevision: Long, archived: Boolean): IO[YallmoError, Session] =
    Clock.instant.flatMap(now => updateRecord(id)(SessionRecords.setArchived(id, _, expectedRevision, archived, now)))

  private def updateRecord(
      id: SessionId
  )(change: SessionRef => Either[YallmoError, SessionRef]): IO[YallmoError, Session] =
    sessions
      .modify { current =>
        val result = current.get(id).toRight(YallmoError.SessionNotFound(id)).flatMap { session =>
          change(session.ref).map(ref => session.copy(meta = ref.meta, info = ref.info))
        }
        (result, result.fold(_ => current, session => current.updated(id, session)))
      }
      .flatMap(ZIO.fromEither)

  override def loadSession(id: SessionId): IO[YallmoError, Session] =
    sessions.get.flatMap(values => ZIO.fromOption(values.get(id)).orElseFail(YallmoError.SessionNotFound(id)))

  override def addSessionAgent(id: SessionId, agentId: AgentId): IO[YallmoError, Session] =
    sessions
      .modify { current =>
        current.get(id) match {
          case None          => (Left(YallmoError.SessionNotFound(id)), current)
          case Some(session) =>
            val updated = if session.agentIds.contains(agentId) then session
            else session.copy(agentIds = session.agentIds :+ agentId)
            (Right(updated), current.updated(id, updated))
        }
      }
      .flatMap(ZIO.fromEither)

  override def putMessagesAndUpdateSession(
      id: SessionId,
      expectedHead: Option[MessageId],
      messages: Seq[Message]
  ): IO[YallmoError, Session] =
    sessions
      .modify { current =>
        current.get(id).toRight(YallmoError.SessionNotFound(id)).flatMap { session =>
          StoreageUtils.validateMessageBatch(id, expectedHead, session.messages.lastOption.map(_.id), messages).map {
            _ =>
              session.copy(messages = session.messages ++ messages)
          }
        } match
          case Left(error)    => (Left(error), current)
          case Right(updated) => (Right(updated), current.updated(id, updated))
      }
      .flatMap(ZIO.fromEither)

  override def compareAndSetSessionHead(
      id: SessionId,
      expectedHead: Option[MessageId],
      newHead: Option[MessageId]
  ): IO[YallmoError, Session] =
    for {
      // Retain session-only originals independently BEFORE changing any references.
      originals <- loadSession(id)
      _ <- standalone.update(_ ++ originals.messages.map(m => m.id -> m))
      inventory <- listMessages.map(_.map(m => m.id -> m).toMap)
      chain <- StoreageUtils.ancestry(inventory, newHead)
      saved <- sessions
        .modify { current =>
          current.get(id).toRight(YallmoError.SessionNotFound(id)).flatMap { session =>
            val actual = session.messages.lastOption.map(_.id)
            if actual != expectedHead then Left(YallmoError.SessionHeadConflict(id, expectedHead, actual))
            else Right(session.copy(messages = chain))
          } match {
            case Left(error)    => (Left(error), current)
            case Right(updated) => (Right(updated), current.updated(id, updated))
          }
        }
        .flatMap(ZIO.fromEither)
    } yield saved

  override def putBlob(blob: Blob): IO[YallmoError, Unit] =
    blobs
      .modify { current =>
        if current.contains(blob.id) then (false, current)
        else (true, current.updated(blob.id, blob))
      }
      .flatMap(inserted => ZIO.fail(YallmoError.BlobAlreadyExists(blob.id)).unless(inserted).unit)

  override def getBlob(blobId: BlobId): IO[YallmoError, Blob] =
    blobs.get.flatMap(current => ZIO.fromOption(current.get(blobId)).orElseFail(YallmoError.BlobMissing(blobId)))

  override def fork(source: SessionId, target: SessionId): IO[YallmoError, Session] =
    Clock.instant.flatMap { now =>
      sessions
        .modify { current =>
          if current.contains(target) then (Left(YallmoError.SessionAlreadyExists(target)), current)
          else
            current.get(source).toRight(YallmoError.SessionNotFound(source)) match
              case Left(error)    => (Left(error), current)
              case Right(session) =>
                val ref = SessionRecords.forked(source, session.ref, now)
                val forked = session.copy(id = target, meta = ref.meta, info = ref.info)
                (Right(forked), current.updated(target, forked))
        }
        .flatMap(ZIO.fromEither)
    }

  private def validateAgentIds(id: SessionId, agentIds: Seq[AgentId]): Either[YallmoError, Unit] =
    agentIds.headOption match
      case None    => Left(YallmoError.EmptyAgentIds(id))
      case Some(_) =>
        agentIds
          .groupBy(identity)
          .collectFirst { case (agentId, values) if values.size > 1 => agentId }
          .fold[Either[YallmoError, Unit]](Right(()))(agentId => Left(YallmoError.DuplicateAgentId(id, agentId)))
}

object StorageInMemory {
  def make: UIO[StorageInMemory] = for {
    sessions <- Ref.make(Map.empty[SessionId, Session])
    standalone <- Ref.make(Map.empty[MessageId, Message])
    blobs <- Ref.make(Map.empty[BlobId, Blob])
    uploads <- BlobOpsUtils.registry
  } yield new StorageInMemory(sessions, standalone, blobs, uploads)
}
