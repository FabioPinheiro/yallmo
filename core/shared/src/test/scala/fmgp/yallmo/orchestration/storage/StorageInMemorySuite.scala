package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.SessionId
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
        _ <- storage.append(source, human, Chunk(MessagePayload.User("hello")))
        _ <- storage.addSessionAgent(source, assistant)
        saved <- storage.append(source, assistant, Chunk(MessagePayload.Assistant("reply")))
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

  test("blob identity is generated securely and duplicate writes preserve existing bytes") {
    run {
      val blob = Blob(BlobId.fresh(), Chunk[Byte](0, 1, -1))
      for {
        storage <- StorageInMemory.make
        _ <- storage.append(blob)
        duplicate <- storage.append(blob.copy(bytes = Chunk.empty)).either
        loaded <- storage.readBlob(blob.id)
        ids <- storage.listBlobIds
      } yield {
        assertEquals(BlobId.from(blob.id.value), Right(blob.id))
        assertEquals(duplicate, Left(YallmoError.BlobAlreadyExists(blob.id)))
        assertEquals(loaded, blob)
        assertEquals(ids, Chunk(blob.id))
      }
    }
  }

  private def run[E, A](effect: ZIO[Any, E, A]): Future[A] =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.runToFuture(effect.mapError(error => FiberFailure(Cause.fail(error))))
    }
}
