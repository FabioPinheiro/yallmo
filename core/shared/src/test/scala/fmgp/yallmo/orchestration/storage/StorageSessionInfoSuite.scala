package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import java.time.Instant
import munit.FunSuite
import scala.concurrent.Future
import zio.*

/** Runs the same session ownership and revision contract on memory (JVM/JS) and filesystem storage. */
abstract class StorageSessionInfoSuite extends FunSuite {
  protected def withStorage[A](test: Storage => IO[YallmoError, A]): Future[A]

  private val id = SessionId.unsafe("session-info")
  private val parent = SessionId.unsafe("parent")
  private val author = AgentId("author")
  private val agents = List(author)
  private val info = SessionInfo(Some("Research"), Some(parent), Some("Understand storage"), Some("Investigating"))

  test("creation assigns storage-owned dates and revision zero, while info stays optional") {
    withStorage { storage =>
      for {
        created <- storage.createSession(parent, agents)
        child <- storage.createSession(id, agents, Some(info))
        loaded <- storage.loadSession(id)
      } yield {
        val meta = created.meta.get
        assert(meta.createdAt.nonEmpty)
        assertEquals(meta.updatedAt, meta.createdAt)
        assertEquals(meta.archivedAt, None)
        assertEquals(meta.revision, 0L)
        assertEquals(created.info, None)
        assertEquals(child.info, Some(info))
        assertEquals(loaded, child)
      }
    }
  }

  test("session inventory includes current metadata and info without loading history") {
    withStorage { storage =>
      for {
        created <- storage.createSession(id, agents, Some(SessionInfo(title = Some("Research"))))
        initial <- storage.listSessions
        updated <- storage.updateSessionInfo(id, 0L, Some(SessionInfo(title = Some("Results"))))
        archived <- storage.setSessionArchived(id, 1L, true)
        current <- storage.listSessions
      } yield {
        assertEquals(initial.toList, List(SessionSummary(id, 0L, agents, created.meta, created.info)))
        assertEquals(updated.info.flatMap(_.title), Some("Results"))
        assertEquals(current.toList, List(SessionSummary(id, 0L, agents, archived.meta, archived.info)))
      }
    }
  }

  test("None preserves info; Some replaces the complete object and can clear descriptive fields") {
    withStorage { storage =>
      for {
        _ <- storage.createSession(parent, agents)
        created <- storage.createSession(id, agents, Some(info))
        unchanged <- storage.updateSessionInfo(id, 0L, None)
        equal <- storage.updateSessionInfo(id, 0L, Some(info))
        replacement = SessionInfo(parentSessionId = Some(parent), status = Some("Ready"))
        updated <- storage.updateSessionInfo(id, 0L, Some(replacement))
        stale <- storage.updateSessionInfo(id, 0L, None).either
        loaded <- storage.loadSession(id)
      } yield {
        assertEquals(unchanged, created)
        assertEquals(equal, created)
        assertEquals(updated.info, Some(replacement))
        assertEquals(updated.meta.map(_.revision), Some(1L))
        assertEquals(updated.meta.flatMap(_.createdAt), created.meta.flatMap(_.createdAt))
        assert(updated.meta.flatMap(_.updatedAt).nonEmpty)
        assertEquals(stale, Left(YallmoError.SessionMetadataConflict(id, 0L, 1L)))
        assertEquals(loaded, updated)
      }
    }
  }

  test("an empty info object clears all descriptions when there is no parent") {
    withStorage { storage =>
      for {
        _ <- storage.createSession(id, agents, Some(SessionInfo(title = Some("Old"), goals = Some("Goal"))))
        cleared <- storage.updateSessionInfo(id, 0L, Some(SessionInfo()))
      } yield assertEquals(cleared.info, Some(SessionInfo()))
    }
  }

  test("parents must exist at creation, cannot be self, and cannot be changed or cleared later") {
    withStorage { storage =>
      for {
        missing <- storage.createSession(id, agents, Some(info)).either
        self <- storage.createSession(id, agents, Some(SessionInfo(parentSessionId = Some(id)))).either
        before <- storage.listSessions
        _ <- storage.createSession(parent, agents)
        created <- storage.createSession(id, agents, Some(info))
        cleared <- storage.updateSessionInfo(id, 0L, Some(SessionInfo())).either
        changed <- storage.updateSessionInfo(id, 0L, Some(info.copy(parentSessionId = Some(id)))).either
        loaded <- storage.loadSession(id)
      } yield {
        assertEquals(missing, Left(YallmoError.SessionNotFound(parent)))
        assert(self.swap.toOption.exists(_.isInstanceOf[YallmoError.InvalidSessionInfo]))
        assertEquals(before, Chunk.empty)
        assert(cleared.swap.toOption.exists(_.isInstanceOf[YallmoError.InvalidSessionInfo]))
        assert(changed.swap.toOption.exists(_.isInstanceOf[YallmoError.InvalidSessionInfo]))
        assertEquals(loaded, created)
      }
    }
  }

  test("archive is explicit, revisioned, idempotent, and does not block append or change descriptive status") {
    withStorage { storage =>
      for {
        created <- storage.createSession(id, agents, Some(SessionInfo(status = Some("In progress"))))
        archived <- storage.setSessionArchived(id, 0L, true)
        repeated <- storage.setSessionArchived(id, 1L, true)
        appended <- StorageTestMessages.persistTurn(storage, id, author, Chunk(Message.User("still writable")))
        stale <- storage.setSessionArchived(id, 0L, false).either
        restored <- storage.setSessionArchived(id, 1L, false)
        repeatedRestore <- storage.setSessionArchived(id, 2L, false)
      } yield {
        assert(archived.meta.flatMap(_.archivedAt).nonEmpty)
        assertEquals(archived.meta.map(_.revision), Some(1L))
        assertEquals(repeated, archived)
        assertEquals(appended.meta, archived.meta)
        assertEquals(appended.info, created.info)
        assertEquals(stale, Left(YallmoError.SessionMetadataConflict(id, 0L, 1L)))
        assertEquals(restored.meta.flatMap(_.archivedAt), None)
        assertEquals(restored.meta.map(_.revision), Some(2L))
        assertEquals(restored.meta.flatMap(_.createdAt), created.meta.flatMap(_.createdAt))
        assertEquals(repeatedRestore, restored)
      }
    }
  }

  test("info updates and head/participant mutations preserve each other's fields") {
    withStorage { storage =>
      val joined = AgentId("joined")
      val first = Message(MessageData(author, None, Instant.EPOCH, Message.User("first")))
      val second = Message(MessageData(author, Some(first.id), Instant.EPOCH, Message.User("second")))
      for {
        created <- storage.createSession(id, agents)
        _ <- storage.putMessagesAndUpdateSession(id, None, Vector(first))
        _ <- ZIO.collectAllPar(
          List(
            storage.updateSessionInfo(id, 0L, Some(SessionInfo(title = Some("New")))),
            storage.addSessionAgent(id, joined),
            storage.putMessagesAndUpdateSession(id, Some(first.id), List(second))
          )
        )
        loaded <- storage.loadSession(id)
        rewound <- storage.compareAndSetSessionHead(id, Some(second.id), Some(first.id))
      } yield {
        assertEquals(loaded.agentIds, Seq(author, joined))
        assertEquals(loaded.messages, Seq(first, second))
        assertEquals(loaded.info, Some(SessionInfo(title = Some("New"))))
        assertEquals(loaded.meta.map(_.revision), Some(1L))
        assertEquals(loaded.meta.flatMap(_.createdAt), created.meta.flatMap(_.createdAt))
        assertEquals(rewound.meta, loaded.meta)
        assertEquals(rewound.info, loaded.info)
        assertEquals(rewound.agentIds, loaded.agentIds)
        assertEquals(rewound.messages, Seq(first))
      }
    }
  }

  test("concurrent info/archive edits with one expected revision admit exactly one winner") {
    withStorage { storage =>
      for {
        _ <- storage.createSession(id, agents)
        results <- ZIO.collectAllPar(
          List(
            storage.updateSessionInfo(id, 0L, Some(SessionInfo(title = Some("Winner")))).either,
            storage.setSessionArchived(id, 0L, true).either
          )
        )
        loaded <- storage.loadSession(id)
      } yield {
        assertEquals(results.count(_.isRight), 1)
        assertEquals(
          results.collect { case Left(error) => error },
          List(YallmoError.SessionMetadataConflict(id, 0L, 1L))
        )
        assertEquals(loaded.meta.map(_.revision), Some(1L))
        assertEquals(loaded, results.flatMap(_.toOption).head)
      }
    }
  }

  test("fork creates fresh metadata and source lineage, without inheriting archive or descriptive state") {
    withStorage { storage =>
      val target = SessionId.unsafe("fork")
      for {
        _ <- storage.createSession(id, agents, Some(SessionInfo(title = Some("Source"), status = Some("Done"))))
        _ <- StorageTestMessages.persistTurn(storage, id, author, Chunk(Message.User("shared history")))
        source <- storage.setSessionArchived(id, 0L, true)
        forked <- storage.fork(id, target)
        loaded <- storage.loadSession(id)
      } yield {
        assertEquals(forked.id, target)
        assertEquals(forked.messages, source.messages)
        assertEquals(forked.agentIds, source.agentIds)
        assertEquals(forked.info, Some(SessionInfo(parentSessionId = Some(id))))
        assertEquals(forked.meta.map(_.revision), Some(0L))
        assertEquals(forked.meta.flatMap(_.archivedAt), None)
        assert(forked.meta.flatMap(_.createdAt).nonEmpty)
        assertEquals(loaded, source)
      }
    }
  }

  test("metadata operations on missing sessions fail without creating records") {
    withStorage { storage =>
      for {
        infoResult <- storage.updateSessionInfo(id, 0L, Some(SessionInfo())).either
        archiveResult <- storage.setSessionArchived(id, 0L, true).either
        listed <- storage.listSessions
      } yield {
        assertEquals(infoResult, Left(YallmoError.SessionNotFound(id)))
        assertEquals(archiveResult, Left(YallmoError.SessionNotFound(id)))
        assertEquals(listed, Chunk.empty)
      }
    }
  }

  protected def runStorageTest[A](effect: IO[YallmoError, A]): Future[A] =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.runToFuture(
        effect.timeoutFail(YallmoError.InvalidConfig("session info test timed out"))(20.seconds)
      )
    }
}

final class StorageInMemorySessionInfoSuite extends StorageSessionInfoSuite {
  protected def withStorage[A](test: Storage => IO[YallmoError, A]): Future[A] =
    runStorageTest(StorageInMemory.make.flatMap(test))
}
