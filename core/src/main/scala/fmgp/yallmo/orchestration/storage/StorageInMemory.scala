package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.{AgentId, Message, MessageData, MessagePayload, YallmoError}
import fmgp.yallmo.orchestration.*
import java.time.Instant
import zio.*

/** Process-local [[Storage]] implementation for callers that do not need durable filesystem state. */
private[orchestration] final class StorageInMemory private (sessions: Ref[Map[SessionId, Session]]) extends Storage {
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
  def make: UIO[StorageInMemory] = Ref.make(Map.empty[SessionId, Session]).map(new StorageInMemory(_))
}
