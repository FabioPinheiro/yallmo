package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import java.nio.file.{Files, Path}
import java.util.Comparator
import munit.FunSuite
import zio.*

final class StorageFSSuite extends FunSuite:
  test("an empty named session persists its agent IDs across reopening") {
    val root = Files.createTempDirectory("yallmo-storage-")
    try
      val sessions = runZIO {
        for {
          storage <- StorageFS.open(root)
          created <- storage.createSession(SessionId.unsafe("research"), agents)
          reopened <- StorageFS.open(root)
          loaded <- reopened.loadSession(SessionId.unsafe("research"))
        } yield (created, loaded)
      }

      assertEquals(sessions._1, Session(SessionId.unsafe("research"), agents, Chunk.empty))
      assertEquals(sessions._2, sessions._1)
    finally deleteRecursively(root)
  }

  test("append restores message artifacts in order and fork preserves the source history") {
    val root = Files.createTempDirectory("yallmo-storage-")
    try
      val sessions = runZIO {
        for {
          storage <- StorageFS.open(root)
          _ <- storage.createSession(SessionId.unsafe("main"), agents)
          appended <- storage.append(
            SessionId.unsafe("main"),
            replAgent,
            Chunk(Message.User("research this"), Message.Assistant("starting research"))
          )
          copied <- storage.fork(SessionId.unsafe("main"), SessionId.unsafe("copy"))
          _ <- storage.append(SessionId.unsafe("main"), replAgent, Chunk(Message.Assistant("final answer")))
          original <- storage.loadSession(SessionId.unsafe("main"))
          listed <- storage.listSessions
        } yield (appended, copied, original, listed)
      }

      assertEquals(
        sessions._1.messages.map(_.payload),
        Chunk(Message.User("research this"), Message.Assistant("starting research"))
      )
      assertEquals(sessions._2.messages.map(_.payload), sessions._1.messages.map(_.payload))
      assertEquals(sessions._3.messages.lastOption.map(_.payload), Some(Message.Assistant("final answer")))
      assertEquals(sessions._4.map(_.id.value), Chunk("copy", "main"))
      assertEquals(sessions._4.map(_.messageCount), Chunk(2L, 3L))
      assertEquals(sessions._4.map(_.agentIds), Chunk(agents, agents))
    finally deleteRecursively(root)
  }

  test("storage rejects invalid session membership") {
    val root = Files.createTempDirectory("yallmo-storage-")
    try
      val result = runZIO {
        for {
          storage <- StorageFS.open(root)
          empty <- storage.createSession(SessionId.unsafe("empty"), Chunk.empty).either
          _ <- storage.createSession(SessionId.unsafe("main"), agents)
          unassigned <- storage
            .append(SessionId.unsafe("main"), AgentId("other"), Chunk(Message.User("hello")))
            .either
        } yield (empty, unassigned)
      }

      assertEquals(result._1, Left(YallmoError.EmptyAgentIds(SessionId.unsafe("empty"))))
      assertEquals(
        result._2,
        Left(YallmoError.AgentNotInSession(SessionId.unsafe("main"), AgentId("other")))
      )
    finally deleteRecursively(root)
  }

  test("cancellation after an object write still publishes the complete batch's session head") {
    val root = Files.createTempDirectory("yallmo-storage-cancellation-")
    try
      val result = runZIO {
        for {
          initialized <- StorageFS.open(root)
          id = SessionId.unsafe("main")
          _ <- initialized.createSession(id, agents)
          delegate <- StorageStore.open(root)
          written <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          store = new StorageStore {
            override def get(id: MessageId): UIO[Option[DecodedArtifact]] = delegate.get(id)
            override def put(value: MessageData): IO[YallmoError, StoredArtifact[MessageData]] =
              for {
                stored <- delegate.put(value)
                _ <- written.succeed(())
                // Suspend after a real disk write but before StorageFS can publish the session head.
                _ <- release.await
              } yield stored
          }
          storage = new StorageFS(root, store)
          messages = Chunk(Message.User("first"), Message.Assistant("second"))
          fiber <- storage.append(id, replAgent, messages).fork
          _ <- written.await
          _ <- fiber.interruptFork
          _ <- release.succeed(())
          exit <- fiber.await
          reopened <- StorageFS.open(root)
          loaded <- reopened.loadSession(id)
        } yield (exit, loaded, messages)
      }
      assert(result._1 match {
        case Exit.Failure(cause) => cause.isInterrupted
        case _                   => false
      })
      assertEquals(result._2.messages.map(_.payload), result._3)
      assertEquals(Files.readAllLines(root.resolve("objects.jsonl")).size(), 2)
    finally deleteRecursively(root)
  }

  test("append masks only its critical writes, not the final history read") {
    val root = Files.createTempDirectory("yallmo-storage-interruptibility-")
    try
      val result = runZIO {
        for {
          initialized <- StorageFS.open(root)
          id = SessionId.unsafe("main")
          _ <- initialized.createSession(id, agents)
          delegate <- StorageStore.open(root)
          writes <- Ref.make(Chunk.empty[Boolean])
          reads <- Ref.make(Chunk.empty[Boolean])
          store = new StorageStore {
            override def get(id: MessageId): UIO[Option[DecodedArtifact]] =
              ZIO.checkInterruptible(status => reads.update(_ :+ status.isInterruptible)) *> delegate.get(id)
            override def put(value: MessageData): IO[YallmoError, StoredArtifact[MessageData]] =
              ZIO.checkInterruptible(status => writes.update(_ :+ status.isInterruptible)) *> delegate.put(value)
          }
          storage = new StorageFS(root, store)
          _ <- storage.append(id, replAgent, Chunk(Message.User("first"), Message.Assistant("second")))
          writeStatuses <- writes.get
          readStatuses <- reads.get
        } yield (writeStatuses, readStatuses)
      }
      assertEquals(result._1, Chunk(false, false))
      assertEquals(result._2, Chunk(true, true))
    finally deleteRecursively(root)
  }

  private val replAgent = AgentId("repl")
  private val agents = Chunk(replAgent, AgentId("reviewer"))

  private def runZIO[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def deleteRecursively(root: Path): Unit =
    val paths = Files.walk(root)
    try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
    finally paths.close()
