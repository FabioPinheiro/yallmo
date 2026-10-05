package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.{AgentId, Message, MessageData, MessageId, MessagePayload}
import fmgp.yallmo.orchestration.*
import java.nio.file.Path
import java.time.Instant
import zio.*

/** Filesystem-backed [[Storage]] implementation.
  *
  * It persists agent identifiers as session metadata but has no agent definitions, runtime bindings, or execution
  * knowledge. [[Orchestrator]] is responsible for resolving those identifiers.
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
    } yield StorageFS(root, store)
}
