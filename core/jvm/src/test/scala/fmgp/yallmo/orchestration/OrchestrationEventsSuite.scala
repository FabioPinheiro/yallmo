package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.storage.{StorageEvents, StorageInMemory}
import java.time.Instant
import munit.FunSuite
import zio.*

final class OrchestrationEventsSuite extends FunSuite {
  private val author = AgentId("graph-author")
  private val id = SessionId.unsafe("graph-session")
  private val root = Message(MessageData(author, None, Instant.EPOCH, MessagePayload.User("root")))
  private val child = Message(
    MessageData(author, Some(root.id), Instant.ofEpochSecond(1), MessagePayload.Assistant("child"))
  )
  private val problem = YallmoError.InvalidConfig("private synthetic storage failure")

  test("StorageEvents starts storage and live delivery in parallel with one immutable identity") {
    run(for {
      base <- StorageInMemory.make
      updates <- OrchestrationEvents.make
      subscriber <- updates.subscribe
      entered <- Promise.make[Nothing, Message]
      release <- Promise.make[Nothing, Unit]
      storage = new StorageProbe(base) {
        override def putMessage(message: Message) =
          entered.succeed(message) *> release.await *> base.putMessage(message)
      }
      writer <- new StorageEvents(storage, updates).putMessage(root).fork
      received <- subscriber.take
      actual <- entered.await
      before <- base.listMessages
      _ <- release.succeed(())
      result <- writer.join
      terminal <- subscriber.take
      _ <- subscriber.close
    } yield {
      assertEquals(received.change, EventUpdate.Pending(operationId(received), Chunk(EventMessage.from(root))))
      assertEquals(actual, root)
      assertEquals(before, Chunk.empty)
      assertEquals(result, root)
      assertEquals(
        terminal.change,
        EventUpdate.Confirmed(operationId(received), Chunk(EventMessage.from(root)), Chunk.empty)
      )
      assertEquals(terminal.sequence, received.sequence + 1)
    })
  }

  for afterWrite <- List(false, true) do
    test(s"StorageEvents preserves failed acknowledgement and reconciles exact stored state: afterWrite=$afterWrite") {
      run(for {
        base <- StorageInMemory.make
        updates <- OrchestrationEvents.make
        subscriber <- updates.subscribe
        storage = new StorageProbe(base) {
          override def putMessage(message: Message) =
            base.putMessage(message).when(afterWrite) *> ZIO.fail(problem)
        }
        result <- new StorageEvents(storage, updates).putMessage(root).either
        pending <- subscriber.take
        terminal <- subscriber.take
        _ <- subscriber.close
      } yield {
        assertEquals(result, Left(problem))
        if afterWrite then
          assertEquals(
            terminal.change,
            EventUpdate.Confirmed(operationId(pending), Chunk(EventMessage.from(root)), Chunk.empty)
          )
        else assertEquals(terminal.change, EventUpdate.Uncertain(operationId(pending)))
      })
    }

  test("pre-registration pending writes absent from the baseline are retained until their terminal outcome") {
    run(for {
      base <- StorageInMemory.make
      updates <- OrchestrationEvents.make
      entered <- Promise.make[Nothing, Unit]
      release <- Promise.make[Nothing, Unit]
      storage = new StorageProbe(base) {
        override def putMessage(message: Message) = entered.succeed(()) *> release.await *> base.putMessage(message)
      }
      writer <- new StorageEvents(storage, updates).putMessage(root).fork
      _ <- entered.await
      subscription <- updates.subscribe
      bootstrap <- subscription.takePending
      missing <- base.listMessages
      canonical <- updates.snapshot(base.listMessages)
      _ <- release.succeed(())
      _ <- writer.join
      // Pending delivery may have happened before registration or be buffered afterward. Terminal must remain.
      first <- subscription.take
      terminal <- first.change match {
        case _: EventUpdate.Pending => subscription.take
        case _                      => ZIO.succeed(first)
      }
      _ <- subscription.close
    } yield {
      assertEquals(missing, Chunk.empty)
      assertEquals(bootstrap.map(_.messages), Chunk(Chunk(EventMessage.from(root))))
      assertEquals(canonical._1, Chunk.empty)
      assert(terminal.change.isInstanceOf[EventUpdate.Confirmed])
      assert(canonical._2 < terminal.sequence)
    })
  }

  test("interrupted storage produces an uncertain terminal, releases the gate and never reports success") {
    run(for {
      base <- StorageInMemory.make
      updates <- OrchestrationEvents.make
      subscriber <- updates.subscribe
      entered <- Promise.make[Nothing, Unit]
      storage = new StorageProbe(base) {
        override def putMessage(message: Message) = { val _ = message; entered.succeed(()) *> ZIO.never }
      }
      writer <- new StorageEvents(storage, updates).putMessage(root).fork
      _ <- entered.await
      pending <- subscriber.take
      exit <- writer.interrupt
      terminal <- subscriber.take
      baseline <- updates.snapshot(base.listMessages)
      _ <- subscriber.close
    } yield {
      assert(exit.isInterrupted)
      assertEquals(terminal.change, EventUpdate.Uncertain(operationId(pending)))
      assertEquals(baseline._1, Chunk.empty)
    })
  }

  test("canonical CAS revisions preserve concurrent conflict and A to B to A without publishing intent") {
    run(for {
      base <- StorageInMemory.make
      _ <- base.createSession(id, Chunk(author))
      _ <- base.putMessagesAndUpdateSession(id, None, Chunk(root, child))
      updates <- OrchestrationEvents.make
      subscriber <- updates.subscribe
      storage = new StorageEvents(base, updates)
      results <- storage
        .compareAndSetSessionHead(id, Some(child.id), Some(root.id))
        .either
        .zipPar(storage.compareAndSetSessionHead(id, Some(child.id), None).either)
      canonical <- base.loadSession(id)
      _ <- storage.compareAndSetSessionHead(id, canonical.messages.lastOption.map(_.id), Some(child.id))
      events <- ZIO.collectAll(List.fill(6)(subscriber.take))
      _ <- subscriber.close
    } yield {
      assertEquals(List(results._1, results._2).count(_.isRight), 1)
      val confirmed = events.collect { case SequencedEventUpdate(_, EventUpdate.Confirmed(_, _, sessions)) =>
        sessions.head
      }
      assertEquals(confirmed.size, 3)
      assert(confirmed.map(_.revision).sliding(2).forall(pair => pair.head < pair.last))
      assertEquals(confirmed(0).head, confirmed(1).head) // Conflict reports only the winner's canonical state.
      assertEquals(confirmed.last.head, Some(child.id))
      assert(
        events.collect { case SequencedEventUpdate(_, EventUpdate.Pending(_, messages)) => messages }.forall(_.isEmpty)
      )
    })
  }

  test("a slow subscriber overflows explicitly while healthy delivery and reliable storage continue") {
    run(for {
      base <- StorageInMemory.make
      updates <- OrchestrationEvents.make
      slow <- updates.subscribe
      healthy <- updates.subscribe
      storage = new StorageEvents(base, updates)
      _ <- ZIO.foreachDiscard(1 to OrchestrationEvents.MaxQueuedEvents) { _ =>
        storage.putMessage(root) *> healthy.take *> healthy.take
      }
      failure <- slow.check.either
      saved <- base.listMessages
      _ <- slow.close *> healthy.close
      replacement <- updates.subscribe
      remaining <- replacement.takePending
      _ <- replacement.close
    } yield {
      assert(failure.left.exists(_.contains("overflow")))
      assertEquals(saved, Chunk(root))
      assert(remaining.isEmpty)
    })
  }

  test("concurrent immutable puts retain both bootstrap observations without blocking a session append") {
    run(for {
      base <- StorageInMemory.make
      _ <- base.createSession(id, Chunk(author))
      updates <- OrchestrationEvents.make
      entered <- Queue.unbounded[Message]
      release <- Promise.make[Nothing, Unit]
      storage = new StorageEvents(
        new StorageProbe(base) {
          override def putMessage(message: Message) =
            entered.offer(message) *> release.await *> base.putMessage(message)
        },
        updates
      )
      other = Message(root.data.copy(timestamp = Instant.ofEpochSecond(2)))
      first <- storage.putMessage(root).fork
      second <- storage.putMessage(other).fork
      _ <- entered.take *> entered.take
      subscriber <- updates.subscribe
      bootstrap <- subscriber.takePending
      appended <- storage.putMessagesAndUpdateSession(id, None, Chunk(root))
      _ <- release.succeed(())
      _ <- first.join *> second.join
      _ <- subscriber.close
    } yield {
      assertEquals(bootstrap.flatMap(_.messages).map(_.id).toSet, Set(root.id, other.id))
      assertEquals(appended.messages, Chunk(root))
    })
  }

  test("lost session CAS acknowledgement publishes recovered canonical head with a new revision") {
    run(for {
      base <- StorageInMemory.make
      _ <- base.createSession(id, Chunk(author))
      _ <- base.putMessagesAndUpdateSession(id, None, Chunk(root, child))
      updates <- OrchestrationEvents.make
      subscriber <- updates.subscribe
      storage = new StorageEvents(
        new StorageProbe(base) {
          override def compareAndSetSessionHead(id: SessionId, expected: Option[MessageId], head: Option[MessageId]) =
            base.compareAndSetSessionHead(id, expected, head) *> ZIO.fail(problem)
        },
        updates
      )
      result <- storage.compareAndSetSessionHead(id, Some(child.id), Some(root.id)).either
      _ <- subscriber.take
      event <- subscriber.take
      _ <- subscriber.close
    } yield {
      assertEquals(result, Left(problem))
      val confirmed = event.change.asInstanceOf[EventUpdate.Confirmed]
      assertEquals(confirmed.sessions.head.head, Some(root.id))
      assertEquals(confirmed.sessions.head.revision, event.sequence)
    })
  }

  test("bounded concurrent pending state rejects bootstrap until every untracked operation settles") {
    run(for {
      updates <- OrchestrationEvents.make
      pending <- ZIO.foreach(1 to OrchestrationEvents.MaxPendingEvents + 2)(_ => updates.begin(Chunk(root)))
      rejected <- updates.subscribe.either
      _ <- updates.complete(pending.head, true, Chunk.empty)
      stillRejected <- updates.subscribe.either
      _ <- updates.complete(pending.last, true, Chunk.empty)
      oneLeft <- updates.subscribe.either
      _ <- updates.complete(pending(pending.size - 2), true, Chunk.empty)
      ready <- updates.subscribe
      bootstrap <- ready.takePending
      _ <- ready.close
    } yield {
      assert(rejected.isLeft)
      assert(stillRejected.isLeft)
      assert(oneLeft.isLeft)
      assertEquals(bootstrap.size, OrchestrationEvents.MaxPendingEvents - 1)
    })
  }

  test("large tool names count toward public-byte bounds even when arguments and content are tiny") {
    run(for {
      updates <- OrchestrationEvents.make
      subscriber <- updates.subscribe
      message = Message(
        root.data.copy(payload =
          MessagePayload.ToolCall(FunctionCall(FunctionCallId.unsafe("call"), ToolKey.unsafe("t" * 800000), "{}"))
        )
      )
      pending <- updates.begin(Chunk(message))
      _ <- updates.pending(pending)
      failure <- subscriber.check.either
      rejected <- updates.subscribe.either
      _ <- updates.complete(pending, false, Chunk.empty)
      _ <- subscriber.close
      ready <- updates.subscribe
      bootstrap <- ready.takePending
      _ <- ready.close
    } yield {
      assert(failure.isLeft)
      assert(rejected.isLeft)
      assert(bootstrap.isEmpty)
    })
  }

  test("interruption during failed-ack recovery invalidates the feed and releases the session gate") {
    run(for {
      base <- StorageInMemory.make
      _ <- base.createSession(id, Chunk(author))
      updates <- OrchestrationEvents.make
      subscriber <- updates.subscribe
      reading <- Promise.make[Nothing, Unit]
      storage = new StorageEvents(
        new StorageProbe(base) {
          override def addSessionAgent(id: SessionId, author: AgentId) = { val _ = (id, author); ZIO.fail(problem) }
          override def loadSession(id: SessionId) = { val _ = id; reading.succeed(()) *> ZIO.never }
        },
        updates
      )
      writer <- storage.addSessionAgent(id, author).fork
      _ <- reading.await
      _ <- subscriber.take
      exit <- writer.interrupt
      event <- subscriber.take
      _ <- updates.snapshot(base.listMessages)
      _ <- subscriber.close
    } yield {
      assert(exit.isInterrupted)
      assert(event.change.isInstanceOf[EventUpdate.Uncertain])
    })
  }

  test("payload overflow and closed subscriptions release waiters without affecting another master") {
    run(for {
      updates <- OrchestrationEvents.make
      other <- OrchestrationEvents.make
      subscriber <- updates.subscribe
      waiter <- subscriber.take.fork
      huge = Message(
        MessageData(author, None, Instant.EPOCH, MessagePayload.User("x" * OrchestrationEvents.MaxQueuedBytes.toInt))
      )
      pending <- updates.begin(Chunk(huge))
      rejected <- updates.subscribe.either
      _ <- updates.pending(pending)
      failure <- waiter.await
      _ <- updates.complete(pending, false, Chunk.empty)
      _ <- subscriber.close
      isolated <- other.subscribe
      clean <- isolated.check.either
      _ <- isolated.close
    } yield {
      assert(rejected.isLeft)
      assert(failure.isFailure)
      assertEquals(clean, Right(()))
      assertNotEquals(updates.streamId, other.streamId)
    })
  }

  private def operationId(event: SequencedEventUpdate): String = event.change match {
    case EventUpdate.Pending(id, _) => id
    case _                          => fail("Expected pending event")
  }

  private def run[A](effect: ZIO[Any, Any, A]): A = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(effect.timeoutFail(new AssertionError("OrchestrationEventsSuite timed out"))(10.seconds))
      .getOrThrowFiberFailure()
  }

  private class StorageProbe(base: Storage) extends Storage {
    def listMessages = base.listMessages
    def putMessage(message: Message) = base.putMessage(message)
    def listBlobIds = base.listBlobIds
    def listSessions = base.listSessions
    def createSession(id: SessionId, authors: Chunk[AgentId]) = base.createSession(id, authors)
    def loadSession(id: SessionId) = base.loadSession(id)
    def loadSessionParcial(id: SessionId) = base.loadSessionParcial(id)
    def addSessionAgent(id: SessionId, author: AgentId) = base.addSessionAgent(id, author)
    def putMessagesAndUpdateSession(id: SessionId, expected: Option[MessageId], messages: Chunk[Message]) =
      base.putMessagesAndUpdateSession(id, expected, messages)
    def compareAndSetSessionHead(id: SessionId, expected: Option[MessageId], head: Option[MessageId]) =
      base.compareAndSetSessionHead(id, expected, head)
    def fork(source: SessionId, target: SessionId) = base.fork(source, target)
    def putBlob(blob: Blob) = base.putBlob(blob)
    def getBlob(id: BlobId) = base.getBlob(id)
    def readBlob(id: BlobId, timeout: Duration) = base.readBlob(id, timeout)
    def beginBlobUpload(id: BlobId, size: Long) = base.beginBlobUpload(id, size)
  }
}
