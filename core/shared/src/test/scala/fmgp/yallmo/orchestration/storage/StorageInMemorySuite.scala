package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.{SessionId, SessionParcial}
import munit.FunSuite
import scala.concurrent.Future
import zio.*

/** Exercises shared storage on both JVM and JS, including hashing, time and UUID generation. */
final class StorageInMemorySuite extends FunSuite {
  test("appending and forking preserve authored messages and content addresses") {
    run {
      val human = AgentId("operator")
      val assistant = AgentId("assistant")
      val source = SessionId.unsafe("source")
      val target = SessionId.unsafe("fork")
      for {
        storage <- StorageInMemory.make
        _ <- storage.createSession(source, Chunk(human))
        _ <- StorageTestMessages.persistTurn(storage, source, human, Chunk(MessagePayload.User("hello")))
        _ <- storage.addSessionAgent(source, assistant)
        saved <- StorageTestMessages.persistTurn(storage, source, assistant, Chunk(MessagePayload.Assistant("reply")))
        forked <- storage.fork(source, target)
        listed <- storage.listMessages
      } yield {
        assertEquals(saved.messages.map(_.agentId), Chunk(human, assistant))
        assertEquals(saved.messages(1).parentId, Some(saved.messages.head.id))
        saved.messages.foreach(message => assertEquals(message.id, Message(message.data).id))
        assertEquals(forked.id, target)
        assertEquals(forked.messages, saved.messages)
        assertEquals(listed.size, 2)
      }
    }
  }

  test("partial session reads preserve complete histories, metadata and missing-session errors") {
    run {
      val author = AgentId("author")
      val id = SessionId.unsafe("partial")
      val absentId = SessionId.unsafe("absent")
      for {
        storage <- StorageInMemory.make
        _ <- storage.createSession(id, Chunk(author))
        empty <- storage.loadSessionParcial(id)
        strict <- StorageTestMessages.persistTurn(storage, id, author, Chunk(MessagePayload.User("hello")))
        partial <- storage.loadSessionParcial(id)
        absent <- storage.loadSessionParcial(absentId).either
        unchanged <- storage.loadSession(id)
      } yield {
        assertEquals(empty, SessionParcial(id, Chunk(author), Chunk.empty))
        assertEquals(partial, SessionParcial(id, strict.agentIds, strict.messages))
        assertEquals(absent, Left(YallmoError.SessionNotFound(absentId)))
        assertEquals(unchanged, strict)
      }
    }
  }

  test("blob identity is generated securely and duplicate writes preserve existing bytes") {
    run {
      val blob = Blob(BlobId.fresh(), Chunk[Byte](0, 1, -1))
      for {
        storage <- StorageInMemory.make
        _ <- storage.putBlob(blob)
        duplicate <- storage.putBlob(blob.copy(bytes = Chunk.empty)).either
        loaded <- storage.getBlob(blob.id)
        ids <- storage.listBlobIds
      } yield {
        assertEquals(BlobId.from(blob.id.value), Right(blob.id))
        assertEquals(duplicate, Left(YallmoError.BlobAlreadyExists(blob.id)))
        assertEquals(loaded, blob)
        assertEquals(ids, Chunk(blob.id))
      }
    }
  }

  test("standalone messages preserve content addresses and parents without creating sessions") {
    run {
      val parent = Message(MessageData(AgentId("human"), None, java.time.Instant.EPOCH, Message.User("input")))
      val child =
        Message(MessageData(AgentId("agent"), Some(parent.id), java.time.Instant.EPOCH, Message.Assistant("output")))
      for {
        storage <- StorageInMemory.make
        orphan <- storage.putMessage(child).either
        _ <- storage.putMessage(parent)
        stored <- storage.putMessage(child)
        _ <- storage.putMessage(child)
        wrongHash <- storage.putMessage(child.copy(id = parent.id)).either
        listed <- storage.listMessages
        sessions <- storage.listSessions
      } yield {
        assert(orphan.isLeft)
        assert(wrongHash.isLeft)
        assertEquals(stored, child)
        assertEquals(listed.map(_.id).toSet, Set(parent.id, child.id))
        assertEquals(listed.size, 2)
        assertEquals(sessions, Chunk.empty)
      }
    }
  }

  private def run[E, A](effect: ZIO[Any, E, A]): Future[A] =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.runToFuture(effect.mapError(error => FiberFailure(Cause.fail(error))))
    }
}
