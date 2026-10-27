package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import java.time.Instant
import munit.FunSuite
import scala.concurrent.Future
import zio.*

/** The same persistence contract runs against both backends (including memory on JS). */
abstract class StorageBatchSuite extends FunSuite {
  protected def withStorage[A](test: Storage => IO[YallmoError, A]): Future[A]

  private val id = SessionId.unsafe("batch")
  private val author = AgentId("author")
  private val other = AgentId("other")
  private def message(text: String, parent: Option[MessageId] = None, agent: AgentId = author): Message =
    Message(MessageData(agent, parent, Instant.EPOCH, Message.User(text)))

  test("complete preconstructed multi-author batches retain timestamps, hashes and concurrent participant additions") {
    withStorage { storage =>
      val first = message("first")
      val second = message("second", Some(first.id), other)
      for {
        _ <- storage.createSession(id, Chunk(author))
        snapshot <- storage.loadSession(id)
        // Persistence does not enforce author membership, but must preserve the latest participant metadata.
        _ <- storage.addSessionAgent(id, AgentId("joined"))
        saved <- storage.putMessagesAndUpdateSession(id, snapshot.messages.lastOption.map(_.id), Chunk(first, second))
        loaded <- storage.loadSession(id)
        standalone <- storage.putMessage(second)
        all <- storage.listMessages
      } yield {
        assertEquals(saved.messages, Chunk(first, second))
        assertEquals(saved.agentIds, Chunk(author, AgentId("joined")))
        assertEquals(loaded, saved)
        assertEquals(standalone, second)
        assertEquals(all.map(_.id).toSet, Set(first.id, second.id))
        assertEquals(all.size, 2)
      }
    }
  }

  test("stale expected head rejects before storing any candidate messages or changing participants/head") {
    withStorage { storage =>
      val first = message("winner")
      val stale = message("stale")
      for {
        _ <- storage.createSession(id, Chunk(author))
        saved <- storage.putMessagesAndUpdateSession(id, None, Chunk(first))
        rejected <- storage.putMessagesAndUpdateSession(id, None, Chunk(stale)).either
        loaded <- storage.loadSession(id)
        all <- storage.listMessages
      } yield {
        assertEquals(rejected, Left(YallmoError.SessionHeadConflict(id, None, Some(first.id))))
        assertEquals(loaded, saved)
        assertEquals(all, Chunk(first))
      }
    }
  }

  test("invalid hashes and disconnected chains anywhere in the batch fail without partial writes") {
    withStorage { storage =>
      val first = message("first")
      val child = message("child", Some(first.id))
      val orphan = message("orphan", Some(MessageId.sha256("missing")))
      for {
        _ <- storage.createSession(id, Chunk(author))
        empty <- storage.putMessagesAndUpdateSession(id, None, Chunk.empty).either
        badHash <- storage.putMessagesAndUpdateSession(id, None, Chunk(first, child.copy(id = first.id))).either
        badChain <- storage.putMessagesAndUpdateSession(id, None, Chunk(first, message("disconnected"))).either
        missingParent <- storage.putMessagesAndUpdateSession(id, None, Chunk(orphan)).either
        all <- storage.listMessages
        loaded <- storage.loadSession(id)
        _ <- storage.putMessage(first)
        // An existing object is not a valid parent unless it is also this session's expected head.
        wrongHead <- storage.putMessagesAndUpdateSession(id, None, Chunk(child)).either
        saved <- storage.putMessagesAndUpdateSession(id, None, Chunk(first, child))
        // Once nonempty, a root cannot replace the current head's chain.
        root <- storage.putMessagesAndUpdateSession(id, Some(child.id), Chunk(message("root"))).either
        finalState <- storage.loadSession(id)
      } yield {
        assertEquals(empty, Left(YallmoError.EmptyAppend(id)))
        List(badHash, badChain, missingParent, wrongHead, root).foreach(result =>
          assert(result.swap.toOption.exists(_.isInstanceOf[YallmoError.InvalidMessage]))
        )
        assertEquals(all, Chunk.empty)
        assertEquals(loaded.messages, Chunk.empty)
        assertEquals(finalState, saved)
      }
    }
  }

  test("standalone immutable messages validate hashes and parents, remain idempotent, and create no session") {
    withStorage { storage =>
      val parent = message("standalone")
      val child = message("reply", Some(parent.id), other)
      for {
        orphan <- storage.putMessage(child).either
        badHash <- storage.putMessage(parent.copy(id = child.id)).either
        _ <- storage.putMessage(parent)
        stored <- storage.putMessage(child)
        _ <- storage.putMessage(child)
        all <- storage.listMessages
        sessions <- storage.listSessions
      } yield {
        assert(orphan.isLeft)
        assert(badHash.isLeft)
        assertEquals(stored, child)
        assertEquals(all.map(_.id).toSet, Set(parent.id, child.id))
        assertEquals(all.size, 2)
        assertEquals(sessions, Chunk.empty)
      }
    }
  }

  test("parallel compare-and-set batches have exactly one winner and leave no losing objects") {
    withStorage { storage =>
      val candidates = Chunk.fromIterable((1 to 16).map(i => message(s"candidate-$i")))
      for {
        _ <- storage.createSession(id, Chunk(author))
        results <- ZIO.foreachPar(candidates)(candidate =>
          storage.putMessagesAndUpdateSession(id, None, Chunk(candidate)).either
        )
        loaded <- storage.loadSession(id)
        all <- storage.listMessages
      } yield {
        assertEquals(results.count(_.isRight), 1)
        assertEquals(results.count(_.swap.toOption.exists(_.isInstanceOf[YallmoError.SessionHeadConflict])), 15)
        assertEquals(loaded.messages.size, 1)
        assertEquals(all, loaded.messages)
      }
    }
  }

  protected def runStorageTest[A](effect: IO[YallmoError, A]): Future[A] =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.runToFuture(
        effect.timeoutFail(YallmoError.InvalidConfig("storage test timed out"))(20.seconds)
      )
    }
}

final class StorageInMemoryBatchSuite extends StorageBatchSuite {
  protected def withStorage[A](test: Storage => IO[YallmoError, A]): Future[A] =
    runStorageTest(StorageInMemory.make.flatMap(test))
}
