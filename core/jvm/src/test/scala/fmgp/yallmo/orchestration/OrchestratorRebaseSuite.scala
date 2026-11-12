package fmgp.yallmo.orchestration

import fmgp.yallmo.tool.FunctionKey

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{ProviderContinuation, ProviderId}
import fmgp.yallmo.orchestration.storage.{StorageFS, StorageInMemory}
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.Comparator
import munit.FunSuite
import zio.*
import zio.json.*

final class OrchestratorRebaseSuite extends FunSuite {
  private val human = AgentId("human")
  private val assistant = AgentId("assistant")
  private val sessionId = SessionId.unsafe("original")
  private val missing = MessageId.sha256("missing")
  private val blob = Blob(BlobId.fresh(), Chunk[Byte](0, 1, -1, 42))
  private val callId = FunctionCallId.unsafe("original-call-id")
  private val tool = FunctionKey.unsafe("never-execute")
  private val payloads = Chunk[MessagePayload](
    MessagePayload.User("unicode π\n  unchanged"),
    MessagePayload.Reasoning(
      Some("summary"),
      ProviderContinuation(ProviderId.unsafe("private"), Some("provider-item"), "opaque-ciphertext")
    ),
    MessagePayload.ToolCall(FunctionCall(callId, tool, "{ \"x\" : 1 }", Some("tool-item"))),
    MessagePayload.ToolOutput(FunctionGenericOutput(callId, tool, "original result")),
    MessagePayload.AudioAttachment(blob.id, AudioFormat.Wav, Some("unchanged bytes")),
    MessagePayload.Assistant("done")
  )
  private val prefix = Message(MessageData(human, None, Instant.ofEpochSecond(500), MessagePayload.User("prefix")))
  private val base = Message(
    MessageData(human, Some(prefix.id), Instant.ofEpochSecond(400), MessagePayload.User("base"))
  )
  // Deliberately decreasing timestamps, alternating authors, and no dependency on session membership.
  private val suffix = payloads.zipWithIndex.foldLeft(Chunk.empty[Message]) { case (chain, (payload, index)) =>
    chain :+ Message(
      MessageData(
        if index % 2 == 0 then human else assistant,
        chain.lastOption.map(_.id).orElse(Some(base.id)),
        Instant.ofEpochSecond(300 - index, index),
        payload
      )
    )
  }
  private val head = suffix.last
  private val originals = Chunk(prefix, base) ++ suffix
  private val alternate = Message(
    MessageData(assistant, None, Instant.ofEpochSecond(900), MessagePayload.User("other"))
  )
  private val failure = YallmoError.InvalidConfig("injected put failure")

  for backend <- List("memory", "filesystem") do {
    test(
      s"$backend: rebase exclusive base/inclusive head onto another branch preserves data and refs, including reopen"
    ) {
      withStorage(backend) { storage =>
        for {
          orchestrator <- seed(storage, session = true)
          before <- storage.listMessages
          sessions <- storage.listSessions
          savedSession <- storage.loadSession(sessionId)
          result <- orchestrator.rebase(base.id, head.id, Some(alternate.id))
          all <- storage.listMessages
          after <- storage.loadSession(sessionId)
          afterSessions <- storage.listSessions
          blobIds <- storage.listBlobIds
          bytes <- storage.getBlob(blob.id)
          reopened <- reload(storage)
          reopenedMessages <- reopened.listMessages
          reopenedSession <- reopened.loadSession(sessionId)
        } yield {
          assertRewrite(suffix, result, Some(alternate.id))
          assertEquals(all.size, before.size + suffix.size)
          assert(before.forall(all.contains))
          assertEquals(after, savedSession)
          assertEquals(afterSessions, sessions)
          assertEquals(blobIds, Chunk(blob.id))
          assertEquals(bytes, blob)
          assert(result.forall(reopenedMessages.contains))
          assert(before.forall(reopenedMessages.contains))
          assertEquals(reopenedSession, savedSession)
        }
      }
    }

    test(s"$backend: onto None creates a root, accepts isolated objects, and creates no sessions") {
      withStorage(backend) { storage =>
        for {
          orchestrator <- seed(storage)
          result <- orchestrator.rebase(base.id, head.id, None)
          sessions <- storage.listSessions
          all <- storage.listMessages
        } yield {
          assertRewrite(suffix, result, None)
          assertEquals(sessions, Chunk.empty)
          assert(result.forall(all.contains))
          assert(originals.forall(all.contains))
        }
      }
    }

    test(s"$backend: range stops at the requested inclusive head, not timestamp or session order") {
      withStorage(backend) { storage =>
        for {
          orchestrator <- seed(storage, session = true)
          result <- orchestrator.rebase(suffix(0).id, suffix(3).id, None)
        } yield assertRewrite(suffix.slice(1, 4), result, None)
      }
    }

    test(s"$backend: destination inside the source is retained original ancestry, not a cycle") {
      withStorage(backend) { storage =>
        for {
          orchestrator <- seed(storage)
          result <- orchestrator.rebase(base.id, head.id, Some(head.id))
          all <- storage.listMessages
        } yield {
          assertRewrite(suffix, result, Some(head.id))
          assert(originals.forall(all.contains))
        }
      }
    }

    test(s"$backend: base=head is empty and onto=base is idempotent, including reopen") {
      withStorage(backend) { storage =>
        for {
          orchestrator <- seed(storage)
          before <- storage.listMessages
          empty <- orchestrator.rebase(head.id, head.id, None)
          same <- orchestrator.rebase(base.id, head.id, Some(base.id))
          again <- orchestrator.rebase(base.id, head.id, Some(base.id))
          after <- storage.listMessages
          reopened <- reload(storage)
          reloaded <- reopened.listMessages
        } yield {
          assertEquals(empty, Chunk.empty)
          assertEquals(same, suffix)
          assertEquals(again, suffix)
          assertEquals(after, before)
          assertEquals(reloaded, before)
        }
      }
    }

    for reference <- List("base", "head", "onto"); empty <- List(false, true) do {
      test(s"$backend: missing $reference fails before writes (empty=$empty)") {
        withStorage(backend) { storage =>
          for {
            _ <- seed(storage)
            puts <- Ref.make(Chunk.empty[Message])
            probe = recording(storage, puts)
            orchestrator <- make(probe)
            result <- orchestrator
              .rebase(
                if reference == "base" then missing else base.id,
                if reference == "head" then missing else if empty then base.id else head.id,
                if reference == "onto" then Some(missing) else None
              )
              .either
            written <- puts.get
          } yield {
            assertInvalid(result)
            assertEquals(written, Chunk.empty)
          }
        }
      }
    }

    test(s"$backend: unrelated base is rejected before writing") {
      withStorage(backend) { storage =>
        for {
          orchestrator <- seed(storage)
          before <- storage.listMessages
          result <- orchestrator.rebase(alternate.id, head.id, None).either
          after <- storage.listMessages
        } yield {
          assertInvalid(result)
          assertEquals(after, before)
        }
      }
    }

    for broken <- List(
        "missing-intermediate",
        "missing-before-base",
        "hash-head",
        "hash-intermediate",
        "hash-base",
        "hash-onto"
      )
    do {
      test(s"$backend: $broken is rejected before any candidate writes") {
        withStorage(backend) { storage =>
          for {
            _ <- seed(storage)
            puts <- Ref.make(Chunk.empty[Message])
            probe = new StorageProbe(storage) {
              override def putMessage(message: Message) = puts.update(_ :+ message) *> super.putMessage(message)
              override def listMessages = super.listMessages.map { messages =>
                val target = broken match {
                  case "missing-intermediate" | "hash-intermediate" => suffix(3).id
                  case "missing-before-base"                        => prefix.id
                  case "hash-head"                                  => head.id
                  case "hash-base"                                  => base.id
                  case _                                            => alternate.id
                }
                if broken.startsWith("missing") then messages.filterNot(_.id == target)
                else
                  messages.map { message =>
                    if message.id == target then message.copy(data = message.data.copy(timestamp = Instant.EPOCH))
                    else message
                  }
              }
            }
            orchestrator <- make(probe)
            result <- orchestrator.rebase(base.id, head.id, Some(alternate.id)).either
            written <- puts.get
          } yield {
            assertInvalid(result)
            assertEquals(written, Chunk.empty)
          }
        }
      }
    }

    test(s"$backend: empty and unchanged ranges still validate hashes and complete ancestry") {
      withStorage(backend) { storage =>
        for {
          _ <- seed(storage)
          puts <- Ref.make(Chunk.empty[Message])
          probe = new StorageProbe(storage) {
            override def putMessage(message: Message) = puts.update(_ :+ message) *> super.putMessage(message)
            override def listMessages = super.listMessages.map(_.filterNot(_.id == prefix.id))
          }
          orchestrator <- make(probe)
          empty <- orchestrator.rebase(base.id, base.id, Some(alternate.id)).either
          unchanged <- orchestrator.rebase(base.id, head.id, Some(base.id)).either
          written <- puts.get
        } yield {
          assertInvalid(empty)
          assertInvalid(unchanged)
          assertEquals(written, Chunk.empty)
        }
      }
    }

    for destination <- List(false, true) do {
      test(s"$backend: unregistered author is rejected before writes (destination=$destination)") {
        withStorage(backend) { storage =>
          for {
            _ <- seed(storage)
            unknown = Message(
              MessageData(AgentId("unregistered"), Some(head.id), Instant.EPOCH, MessagePayload.User("unknown"))
            )
            _ <- storage.putMessage(unknown)
            puts <- Ref.make(Chunk.empty[Message])
            orchestrator <- make(recording(storage, puts))
            result <- orchestrator
              .rebase(
                base.id,
                if destination then head.id else unknown.id,
                if destination then Some(unknown.id) else None
              )
              .either
            written <- puts.get
          } yield {
            assertEquals(result, Left(YallmoError.UnknownAgent(AgentId("unregistered"))))
            assertEquals(written, Chunk.empty)
          }
        }
      }
    }

    test(s"$backend: persistence is parent-first; second put failure leaves a saved prefix and no ref changes") {
      withStorage(backend) { storage =>
        for {
          _ <- seed(storage, session = true)
          before <- storage.listMessages
          refs <- storage.listSessions
          session <- storage.loadSession(sessionId)
          puts <- Ref.make(Chunk.empty[Message])
          probe = new StorageProbe(storage) {
            override def putMessage(message: Message) =
              for {
                existing <- storage.listMessages
                _ <- ZIO.succeed(assert(message.parentId.forall(parent => existing.exists(_.id == parent))))
                attempted <- puts.updateAndGet(_ :+ message)
                saved <- if attempted.size == 2 then ZIO.fail(failure) else super.putMessage(message)
              } yield saved
          }
          orchestrator <- make(probe)
          result <- orchestrator.rebase(base.id, head.id, Some(alternate.id)).either
          attempted <- puts.get
          after <- storage.listMessages
          afterRefs <- storage.listSessions
          afterSession <- storage.loadSession(sessionId)
          reopened <- reload(storage)
          reloaded <- reopened.listMessages
        } yield {
          assertEquals(result, Left(YallmoError.StorageFailed(failure)))
          assertEquals(attempted.size, 2)
          assertEquals(after.toSet, before.toSet + attempted.head)
          assertEquals(reloaded.toSet, after.toSet)
          assertEquals(afterRefs, refs)
          assertEquals(afterSession, session)
        }
      }
    }

    test(s"$backend: interruption after a saved prefix leaves originals and refs unchanged") {
      withStorage(backend) { storage =>
        for {
          _ <- seed(storage, session = true)
          before <- storage.listMessages
          session <- storage.loadSession(sessionId)
          puts <- Ref.make(Chunk.empty[Message])
          blocked <- Promise.make[Nothing, Unit]
          probe = new StorageProbe(storage) {
            override def putMessage(message: Message) =
              puts.updateAndGet(_ :+ message).flatMap { attempted =>
                if attempted.size == 2 then blocked.succeed(()) *> ZIO.never else super.putMessage(message)
              }
          }
          orchestrator <- make(probe)
          fiber <- orchestrator.rebase(base.id, head.id, None).fork
          _ <- blocked.await
          exit <- fiber.interrupt
          attempted <- puts.get
          after <- storage.listMessages
          afterSession <- storage.loadSession(sessionId)
        } yield {
          assert(exit.isInterrupted)
          assertEquals(after.toSet, before.toSet + attempted.head)
          assertEquals(afterSession, session)
        }
      }
    }
  }

  test("long source ancestry is stack-safe, including an empty range") {
    withStorage("memory") { storage =>
      val chain = (1 to 12000).foldLeft(List(prefix)) { (messages, index) =>
        Message(
          MessageData(human, Some(messages.head.id), Instant.ofEpochSecond(index), MessagePayload.User(s"$index"))
        ) :: messages
      }
      for {
        probe = new StorageProbe(storage) {
          override def listMessages = ZIO.succeed(Chunk.fromIterable(chain))
          override def putMessage(message: Message) = ZIO.dieMessage(s"Empty range must not save ${message.id}")
        }
        orchestrator <- make(probe)
        result <- orchestrator.rebase(chain.head.id, chain.head.id, None)
      } yield assertEquals(result, Chunk.empty)
    }
  }

  private def assertRewrite(source: Chunk[Message], rewritten: Chunk[Message], onto: Option[MessageId]): Unit = {
    assertEquals(rewritten.size, source.size)
    assertEquals(rewritten.head.parentId, onto)
    assertEquals(rewritten.drop(1).map(_.parentId), rewritten.dropRight(1).map(message => Some(message.id)))
    rewritten.zip(source).foreach { (copy, original) =>
      assertEquals(copy.id, MessageId.from(copy.data))
      assertEquals(copy.data.copy(parentId = original.parentId).toJson, original.data.toJson)
      assertEquals(copy.payload.toJson, original.payload.toJson)
    }
  }

  private def assertInvalid(result: Either[YallmoError, Chunk[Message]]): Unit =
    assert(result.left.exists(_.isInstanceOf[YallmoError.InvalidMessage]), result.toString)

  private def make(storage: Storage): IO[YallmoError, Orchestrator[Any]] =
    for {
      instances <- AgentCatalog.fromAgents[Any](Chunk.empty)
      _ <- instances.registerUser(human)
      _ <- instances.registerUser(assistant)
      orchestrator <- OrchestratorLive.make(storage, instances)
    } yield orchestrator

  private def seed(storage: Storage, session: Boolean = false): IO[YallmoError, Orchestrator[Any]] =
    for {
      _ <- storage.putBlob(blob)
      _ <- ZIO.foreachDiscard(originals :+ alternate)(storage.putMessage)
      _ <- (storage.createSession(sessionId, Chunk(human)) *>
        storage.putMessagesAndUpdateSession(sessionId, None, originals)).when(session)
      orchestrator <- make(storage)
    } yield orchestrator

  private def recording(storage: Storage, puts: Ref[Chunk[Message]]): Storage = new StorageProbe(storage) {
    override def putMessage(message: Message) = puts.update(_ :+ message) *> super.putMessage(message)
  }

  private def withStorage(backend: String)(test: Storage => IO[Any, Unit]): Unit = {
    val root = Files.createTempDirectory("yallmo-rebase-")
    try {
      val storage = if backend == "filesystem" then StorageFS.open(root) else StorageInMemory.make
      Unsafe.unsafe { unsafe ?=>
        Runtime.default.unsafe.run(storage.flatMap(test).timeoutFail(failure)(30.seconds)).getOrThrowFiberFailure()
      }
    } finally deleteRoot(root)
  }

  private def reload(storage: Storage): IO[YallmoError, Storage] = storage match {
    case fs: StorageFS => StorageFS.open(fs.root)
    case _             => ZIO.succeed(storage)
  }

  private def deleteRoot(root: Path): Unit = {
    val paths = Files.walk(root)
    try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
    finally paths.close()
  }

  private class StorageProbe(delegate: Storage) extends Storage {
    def compareAndSetSessionHead(
        id: SessionId,
        expectedHead: Option[MessageId],
        newHead: Option[MessageId]
    ): IO[YallmoError, Session] =
      delegate.compareAndSetSessionHead(id, expectedHead, newHead)
    def listMessages = delegate.listMessages
    def putMessage(message: Message) = delegate.putMessage(message)
    def listBlobIds = delegate.listBlobIds
    def listSessions = delegate.listSessions
    def createSession(id: SessionId, agents: Chunk[AgentId]) = delegate.createSession(id, agents)
    def loadSession(id: SessionId) = delegate.loadSession(id)
    def loadSessionParcial(id: SessionId) = delegate.loadSessionParcial(id)
    def addSessionAgent(id: SessionId, agentId: AgentId) = delegate.addSessionAgent(id, agentId)
    def putMessagesAndUpdateSession(id: SessionId, head: Option[MessageId], messages: Chunk[Message]) =
      delegate.putMessagesAndUpdateSession(id, head, messages)
    def putBlob(blob: Blob) = delegate.putBlob(blob)
    def getBlob(id: BlobId) = delegate.getBlob(id)
    def fork(source: SessionId, target: SessionId) = delegate.fork(source, target)
    def readBlob(id: BlobId, timeout: Duration) = delegate.readBlob(id, timeout)
    def beginBlobUpload(id: BlobId, size: Long) = delegate.beginBlobUpload(id, size)
  }
}
