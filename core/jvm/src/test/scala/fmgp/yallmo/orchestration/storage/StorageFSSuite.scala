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
            override def listMessages: UIO[Chunk[Message]] = delegate.listMessages
            override def get(id: MessageId): UIO[Option[DecodedArtifact]] = delegate.get(id)
            override def put(value: MessageData): IO[YallmoError, StoredArtifact[MessageData]] =
              for {
                stored <- delegate.put(value)
                _ <- written.succeed(())
                // Suspend after a real disk write but before StorageFS can publish the session head.
                _ <- release.await
              } yield stored
          }
          lock <- Semaphore.make(1)
          storage = new StorageFS(root, store, lock)
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
            override def listMessages: UIO[Chunk[Message]] = delegate.listMessages
            override def get(id: MessageId): UIO[Option[DecodedArtifact]] =
              ZIO.checkInterruptible(status => reads.update(_ :+ status.isInterruptible)) *> delegate.get(id)
            override def put(value: MessageData): IO[YallmoError, StoredArtifact[MessageData]] =
              ZIO.checkInterruptible(status => writes.update(_ :+ status.isInterruptible)) *> delegate.put(value)
          }
          lock <- Semaphore.make(1)
          storage = new StorageFS(root, store, lock)
          _ <- storage.append(id, replAgent, Chunk(Message.User("first"), Message.Assistant("second")))
          writeStatuses <- writes.get
          readStatuses <- reads.get
        } yield (writeStatuses, readStatuses)
      }
      assertEquals(result._1, Chunk(false, false))
      assertEquals(result._2, Chunk(true, true))
    finally deleteRecursively(root)
  }

  test("parallel membership changes and whole append batches preserve every update after reopen") {
    val root = Files.createTempDirectory("yallmo-storage-parallel-")
    try {
      val result = runZIO {
        for {
          storage <- StorageFS.open(root)
          id = SessionId.unsafe("main")
          _ <- storage.createSession(id, agents)
          start <- Promise.make[Nothing, Unit]
          workers <- ZIO.foreach(1 to 16) { i =>
            (start.await *> storage.addSessionAgent(id, AgentId(s"member-$i")) *>
              storage.append(id, replAgent, Chunk(Message.User(s"$i-first"), Message.Assistant(s"$i-second")))).fork
          }
          _ <- start.succeed(())
          _ <- ZIO.foreachDiscard(workers)(_.join)
          reopened <- StorageFS.open(root)
          loaded <- reopened.loadSession(id)
          indexed <- reopened.listMessages
        } yield (loaded, indexed)
      }
      assertEquals(result._1.agentIds.toSet, agents.toSet ++ (1 to 16).map(i => AgentId(s"member-$i")))
      assertEquals(result._1.messages.size, 32)
      assertEquals(result._1.messages.map(_.id).distinct.size, 32)
      assertEquals(result._2.map(_.id).toSet, result._1.messages.map(_.id).toSet)
      for i <- 1 to 16 do {
        val offset = result._1.messages.indexWhere(_.payload == Message.User(s"$i-first"))
        assert(offset >= 0)
        assertEquals(result._1.messages(offset + 1).payload, Message.Assistant(s"$i-second"))
      }
      result._1.messages.foreach(message => assertEquals(Message(message.data).id, message.id))
      assertEquals(Files.readAllLines(root.resolve("objects.jsonl")).size(), 32)
    } finally deleteRecursively(root)
  }

  test("parallel session create append fork and reads retain all index records and coherent histories") {
    val root = Files.createTempDirectory("yallmo-storage-sessions-")
    try {
      runZIO {
        for {
          storage <- StorageFS.open(root)
          start <- Promise.make[Nothing, Unit]
          workers <- ZIO.foreach(1 to 12) { i =>
            (for {
              _ <- start.await
              id = SessionId.unsafe(s"main-$i")
              _ <- storage.createSession(id, agents)
              _ <- storage.append(id, replAgent, Chunk(Message.User(s"prompt-$i"), Message.Assistant(s"answer-$i")))
              forked <- storage.fork(id, SessionId.unsafe(s"copy-$i"))
              _ <- storage.append(id, replAgent, Chunk(Message.Assistant(s"final-$i")))
              _ <- storage.loadSession(id)
              listed <- storage.listSessions
              indexed <- storage.listMessages
            } yield {
              assertEquals(forked.messages.size, 2)
              assert(listed.forall(summary => summary.messageCount >= 0 && summary.messageCount <= 3))
              assert(indexed.map(_.id).distinct.size == indexed.size)
            }).fork
          }
          _ <- start.succeed(())
          _ <- ZIO.foreachDiscard(workers)(_.join)
          races <- ZIO.foreachPar(1 to 12)(_ => storage.createSession(SessionId.unsafe("one-winner"), agents).either)
          forkRaces <- ZIO.foreachPar(1 to 12)(_ =>
            storage.fork(SessionId.unsafe("main-1"), SessionId.unsafe("one-fork")).either
          )
          reopened <- StorageFS.open(root)
          sessions <- reopened.listSessions
          indexed <- reopened.listMessages
          _ <- ZIO.foreachDiscard(1 to 12) { i =>
            for {
              main <- reopened.loadSession(SessionId.unsafe(s"main-$i"))
              copy <- reopened.loadSession(SessionId.unsafe(s"copy-$i"))
            } yield {
              assertEquals(main.messages.size, 3)
              assertEquals(copy.messages, main.messages.take(2))
              main.messages.foreach(message => assertEquals(Message(message.data).id, message.id))
            }
          }
        } yield {
          assertEquals(races.count(_.isRight), 1)
          assertEquals(forkRaces.count(_.isRight), 1)
          assertEquals(sessions.size, 26)
          assertEquals(indexed.size, 36)
          assertEquals(indexed.map(_.id).distinct.size, 36)
          assertEquals(Files.readAllLines(root.resolve("objects.jsonl")).size(), 36)
        }
      }
    } finally deleteRecursively(root)
  }

  test("snapshots and membership wait for the complete object batch and head publication") {
    val root = Files.createTempDirectory("yallmo-storage-publication-")
    try {
      runZIO {
        for {
          initial <- StorageFS.open(root)
          id = SessionId.unsafe("main")
          _ <- initial.createSession(id, agents)
          delegate <- StorageStore.open(root)
          written <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          store = new StorageStore {
            def listMessages: UIO[Chunk[Message]] = delegate.listMessages
            def get(id: MessageId): UIO[Option[DecodedArtifact]] = delegate.get(id)
            def put(value: MessageData): IO[YallmoError, StoredArtifact[MessageData]] =
              delegate.put(value).tap(_ => written.succeed(()) *> release.await)
          }
          lock <- Semaphore.make(1)
          storage = new StorageFS(root, store, lock)
          append <- storage.append(id, replAgent, Chunk(Message.User("first"), Message.Assistant("second"))).fork
          _ <- written.await
          started <- ZIO.foreach(1 to 4)(_ => Promise.make[Nothing, Unit])
          load <- (started(0).succeed(()) *> storage.loadSession(id)).fork
          list <- (started(1).succeed(()) *> storage.listSessions).fork
          index <- (started(2).succeed(()) *> storage.listMessages).fork
          member <- (started(3).succeed(()) *> storage.addSessionAgent(id, AgentId("new"))).fork
          _ <- ZIO.foreachDiscard(started)(_.await)
          pending <- load.poll.zip(list.poll).zip(index.poll).zip(member.poll)
          _ <- release.succeed(())
          _ <- append.join
          loaded <- load.join
          listed <- list.join
          indexed <- index.join
          updated <- member.join
        } yield {
          assertEquals(pending, (None, None, None, None))
          assertEquals(loaded.messages.size, 2)
          assertEquals(listed.map(_.messageCount), Chunk(2L))
          assertEquals(indexed.size, 2)
          assertEquals(updated.messages.size, 2)
          assert(updated.agentIds.contains(AgentId("new")))
        }
      }
    } finally deleteRecursively(root)
  }

  test("immutable history traversal releases the permit and keeps its captured head") {
    val root = Files.createTempDirectory("yallmo-storage-snapshot-")
    try {
      runZIO {
        for {
          initial <- StorageFS.open(root)
          id = SessionId.unsafe("main")
          _ <- initial.createSession(id, agents)
          before <- initial.append(id, replAgent, Chunk(Message.User("before")))
          delegate <- StorageStore.open(root)
          reading <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          first <- Ref.make(true)
          store = new StorageStore {
            def listMessages: UIO[Chunk[Message]] = delegate.listMessages
            def get(id: MessageId): UIO[Option[DecodedArtifact]] =
              first.getAndSet(false).flatMap { pause =>
                (reading.succeed(()) *> release.await).when(pause) *> delegate.get(id)
              }
            def put(value: MessageData): IO[YallmoError, StoredArtifact[MessageData]] = delegate.put(value)
          }
          lock <- Semaphore.make(1)
          storage = new StorageFS(root, store, lock)
          read <- storage.loadSession(id).fork
          _ <- reading.await
          after <- storage.append(id, replAgent, Chunk(Message.Assistant("after")))
          _ <- storage.createSession(SessionId.unsafe("independent"), agents)
          _ <- release.succeed(())
          snapshot <- read.join
        } yield {
          assertEquals(snapshot, before)
          assertEquals(after.messages.size, 2)
        }
      }
    } finally deleteRecursively(root)
  }

  test("session listing ignores abandoned atomic-publication temporary files") {
    val root = Files.createTempDirectory("yallmo-storage-temporary-")
    try {
      runZIO {
        for {
          storage <- StorageFS.open(root)
          _ <- storage.createSession(SessionId.unsafe("main"), agents)
          _ <- ZIO.attempt(Files.writeString(root.resolve("refs/sessions/.main.abandoned.tmp"), "partial"))
          listed <- storage.listSessions
        } yield assertEquals(listed.map(_.id.value), Chunk("main"))
      }
    } finally deleteRecursively(root)
  }

  private val replAgent = AgentId("repl")
  private val agents = Chunk(replAgent, AgentId("reviewer"))

  private def runZIO[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe
        .run(effect.timeoutFail(new RuntimeException("test timed out"))(20.seconds))
        .getOrThrowFiberFailure()
    }

  private def deleteRecursively(root: Path): Unit =
    val paths = Files.walk(root)
    try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
    finally paths.close()
