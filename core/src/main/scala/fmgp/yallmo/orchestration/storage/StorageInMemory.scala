package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import java.time.Instant
import zio.*

/** Process-local [[Storage]] implementation for callers that do not need durable filesystem state. */
private[orchestration] final class StorageInMemory private (
    sessions: Ref[Map[SessionId, Session]],
    blobs: Ref[Map[BlobId, Blob]]
) extends Storage {
  override def listSessions: IO[YallmoError, Chunk[SessionSummary]] =
    sessions.get.map { values =>
      Chunk.fromIterable(
        values.values.toList
          .sortBy(_.id.value)
          .map(session => SessionSummary(session.id, session.messages.size, session.agentIds))
      )
    }

  override def createSession(id: SessionId, agentIds: Chunk[AgentId]): IO[YallmoError, Session] =
    ZIO.fromEither(validateAgentIds(id, agentIds)) *> sessions
      .modify { current =>
        current.get(id) match
          case Some(_) => (Left(YallmoError.SessionAlreadyExists(id)), current)
          case None    =>
            val session = Session(id, agentIds, Chunk.empty)
            (Right(session), current.updated(id, session))
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

  override def append(
      id: SessionId,
      agentId: AgentId,
      messages: Chunk[MessagePayload]
  ): IO[YallmoError, Session] =
    if messages.isEmpty then ZIO.fail(YallmoError.EmptyAppend(id))
    else
      sessions
        .modify { current =>
          current.get(id).toRight(YallmoError.SessionNotFound(id)).flatMap { session =>
            if !session.agentIds.contains(agentId) then Left(YallmoError.AgentNotInSession(id, agentId))
            else {
              val appended = messages.foldLeft(session.messages) { (stored, payload) =>
                val parentId = stored.lastOption.map(_.id)
                stored :+ Message(MessageData(agentId, parentId, Instant.now(), payload))
              }
              val updated = session.copy(messages = appended)
              Right(updated)
            }
          } match
            case Left(error)    => (Left(error), current)
            case Right(updated) => (Right(updated), current.updated(id, updated))
        }
        .flatMap(ZIO.fromEither)

  override def append(blob: Blob): IO[YallmoError, Unit] =
    blobs
      .modify { current =>
        if current.contains(blob.id) then (false, current)
        else (true, current.updated(blob.id, blob))
      }
      .flatMap(inserted => ZIO.fail(YallmoError.BlobAlreadyExists(blob.id)).unless(inserted).unit)

  override def readBlob(blobId: BlobId): IO[YallmoError, Blob] =
    blobs.get.flatMap(current => ZIO.fromOption(current.get(blobId)).orElseFail(YallmoError.BlobMissing(blobId)))

  override def fork(source: SessionId, target: SessionId): IO[YallmoError, Session] =
    sessions
      .modify { current =>
        if current.contains(target) then (Left(YallmoError.SessionAlreadyExists(target)), current)
        else
          current.get(source).toRight(YallmoError.SessionNotFound(source)) match
            case Left(error)    => (Left(error), current)
            case Right(session) =>
              val forked = session.copy(id = target)
              (Right(forked), current.updated(target, forked))
      }
      .flatMap(ZIO.fromEither)

  private def validateAgentIds(id: SessionId, agentIds: Chunk[AgentId]): Either[YallmoError, Unit] =
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
    blobs <- Ref.make(Map.empty[BlobId, Blob])
  } yield new StorageInMemory(sessions, blobs)
}
