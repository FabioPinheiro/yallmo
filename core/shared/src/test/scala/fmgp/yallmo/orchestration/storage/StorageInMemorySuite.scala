package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.{Session, SessionId}
import munit.ZSuite
import zio.*

/** Exercises shared storage on both JVM and JS, including hashing, time and UUID generation. */
final class StorageInMemorySuite extends ZSuite {
  testZ("appending and forking preserve authored messages and content addresses")(ZIO.suspendSucceed {
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
  })

  testZ("session reads preserve complete histories, metadata and missing-session errors")(ZIO.suspendSucceed {
    val author = AgentId("author")
    val id = SessionId.unsafe("history")
    val absentId = SessionId.unsafe("absent")
    for {
      storage <- StorageInMemory.make
      _ <- storage.createSession(id, Chunk(author))
      empty <- storage.loadSession(id)
      saved <- StorageTestMessages.persistTurn(storage, id, author, Chunk(MessagePayload.User("hello")))
      loaded <- storage.loadSession(id)
      absent <- storage.loadSession(absentId).either
    } yield {
      assertEquals(empty, Session(id, Chunk(author), Chunk.empty, empty.meta))
      assertEquals(loaded, saved)
      assertEquals(absent, Left(YallmoError.SessionNotFound(absentId)))
    }
  })

  testZ("blob identity is generated securely and duplicate writes preserve existing bytes")(ZIO.suspendSucceed {
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
  })

  testZ("standalone messages preserve content addresses and parents without creating sessions")(ZIO.suspendSucceed {
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
  })

}
