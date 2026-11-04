package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.Comparator
import java.util.concurrent.CopyOnWriteArrayList
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import zio.*

final class StorageFSMessageMaybeSuite extends FunSuite {
  private val author = AgentId("author")
  private val sessionId = SessionId.unsafe("history")

  test("tolerant traversal preserves complete history in chronological order and an absent head is empty") {
    withHistory { (_, storage, messages) =>
      for {
        emptyId = SessionId.unsafe("empty-head")
        _ <- storage.createSession(emptyId, Chunk(author))
        empty <- storage.loadSessionParcial(emptyId)
        strict <- storage.loadSession(sessionId)
        partial <- storage.loadSessionParcial(sessionId)
      } yield {
        assertEquals(partial, SessionParcial(sessionId, strict.agentIds, messages))
        assertEquals(empty, SessionParcial(emptyId, Chunk(author), Chunk.empty))
        assertEquals(strict.messages, messages)
      }
    }
  }

  test("a missing head returns its placeholder and warns without changing storage or relaxing strict session reads") {
    val logs = new CopyOnWriteArrayList[String]()
    val logger = ZLogger.default.map(line => { logs.add(line); () })
    withHistory { (root, storage, _) =>
      ZIO.scoped {
        for {
          missing = MessageId.sha256("missing-head")
          ref = root.resolve("refs/sessions/history")
          _ <- ZIO.attempt(Files.writeString(ref, s"""{"agentIds":["author"],"head":"${MessageId.value(missing)}"}"""))
          before <- ZIO.attempt((Files.readString(ref), Files.readString(root.resolve("objects.jsonl"))))
          _ <- (Runtime.removeDefaultLoggers >>> Runtime.addLogger(logger)).build
          partial <- storage.loadSessionParcial(sessionId)
          found = partial.messages
          strict <- storage.loadSession(sessionId).either
          listed <- storage.listSessions.either
          after <- ZIO.attempt((Files.readString(ref), Files.readString(root.resolve("objects.jsonl"))))
        } yield {
          assertEquals(partial.id, sessionId)
          assertEquals(partial.agentIds, Chunk(author))
          assertEquals(found, Chunk[MessageMaybe](MessageMissing(missing)))
          assertEquals(found.last.id, missing)
          assert(strict.swap.toOption.exists(_.isInstanceOf[YallmoError.StorageFailure]))
          assert(listed.isLeft)
          assertEquals(before, after)
          assertEquals(logs.size(), 1)
          assert(logs.get(0).contains("level=WARN"))
          assert(logs.get(0).contains("sessionId=history"))
          assert(logs.get(0).contains(MessageId.value(missing)))
          assert(!logs.get(0).contains("PRIVATE_CONTENT"))
        }
      }
    }
  }

  test("a missing ancestor is followed by the readable newer tail, never by guessed older history") {
    val logs = new CopyOnWriteArrayList[String]()
    val logger = ZLogger.default.map(line => { logs.add(line); () })
    withHistory { (root, _, messages) =>
      ZIO.scoped {
        for {
          delegate <- StorageStore.open(root)
          missing = messages(1).id
          // Simulate an unavailable ancestor after startup; object-store integrity checks remain unchanged.
          filtered = new StorageStore {
            def listMessages: UIO[Chunk[Message]] = delegate.listMessages
            def get(id: MessageId): UIO[Option[DecodedArtifact]] =
              if id == missing then ZIO.none else delegate.get(id)
            def put(value: MessageData): IO[YallmoError, StoredArtifact[MessageData]] =
              ZIO.dieMessage("History traversal must not write")
          }
          lock <- Semaphore.make(1)
          uploads <- BlobOpsUtils.registry
          storage = new StorageFS(root, filtered, lock, uploads)
          _ <- (Runtime.removeDefaultLoggers >>> Runtime.addLogger(logger)).build
          partial <- storage.loadSessionParcial(sessionId)
          found = partial.messages
          strict <- storage.loadSession(sessionId).either
        } yield {
          assertEquals(partial.id, sessionId)
          assertEquals(partial.agentIds, Chunk(author))
          assertEquals(found, Chunk[MessageMaybe](MessageMissing(missing), messages.last))
          assertEquals(found.last.id, messages.last.id)
          assert(strict.isLeft)
          assertEquals(logs.size(), 1)
          assert(logs.asScala.forall(_.contains(MessageId.value(missing))))
        }
      }
    }
  }

  test("partial session loading preserves empty sessions and still rejects absent or malformed references") {
    withHistory { (root, storage, _) =>
      for {
        emptyId = SessionId.unsafe("empty")
        _ <- storage.createSession(emptyId, Chunk(author))
        empty <- storage.loadSessionParcial(emptyId)
        absentId = SessionId.unsafe("absent")
        absent <- storage.loadSessionParcial(absentId).either
        _ <- ZIO.attempt(Files.writeString(root.resolve("refs/sessions/history"), "invalid JSON"))
        malformed <- storage.loadSessionParcial(sessionId).either
      } yield {
        assertEquals(empty, SessionParcial(emptyId, Chunk(author), Chunk.empty))
        assertEquals(absent, Left(YallmoError.SessionNotFound(absentId)))
        assert(malformed.swap.toOption.exists(_.isInstanceOf[YallmoError.StorageFailure]))
      }
    }
  }

  private def withHistory(test: (Path, StorageFS, Chunk[Message]) => ZIO[Any, Any, Unit]): Unit = {
    val root = Files.createTempDirectory("yallmo-history-maybe-")
    try {
      val messages = (1 to 3).foldLeft(Chunk.empty[Message]) { (previous, index) =>
        previous :+ Message(
          MessageData(
            author,
            previous.lastOption.map(_.id),
            Instant.EPOCH.plusSeconds(index.toLong),
            MessagePayload.User(s"PRIVATE_CONTENT_$index")
          )
        )
      }
      Unsafe.unsafe { unsafe ?=>
        Runtime.default.unsafe
          .run {
            for {
              storage <- StorageFS.open(root)
              _ <- storage.createSession(sessionId, Chunk(author))
              _ <- storage.putMessagesAndUpdateSession(sessionId, None, messages)
              _ <- test(root, storage, messages)
            } yield ()
          }
          .getOrThrowFiberFailure()
      }
    } finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }
}
