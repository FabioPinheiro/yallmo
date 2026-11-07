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
      val confirmed = events.collect { case SequencedEventUpdate(_, EventUpdate.Confirmed(_, _, sessions), _) =>
        sessions.head
      }
      assertEquals(confirmed.size, 3)
      assert(confirmed.map(_.revision).sliding(2).forall(pair => pair.head < pair.last))
      assertEquals(confirmed(0).head, confirmed(1).head) // Conflict reports only the winner's canonical state.
      assertEquals(confirmed.last.head, Some(child.id))
      assert(
        events
          .collect { case SequencedEventUpdate(_, EventUpdate.Pending(_, messages), _) => messages }
          .forall(_.isEmpty)
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

  test("session scope follows shared ancestry, CAS conflicts, A to B to A and participant changes without false gaps") {
    val shared = SessionId.unsafe("shared")
    val unrelated = SessionId.unsafe("unrelated")
    run(for {
      base <- StorageInMemory.make
      _ <- base.createSession(id, Chunk(author))
      _ <- base.putMessagesAndUpdateSession(id, None, Chunk(root, child))
      updates <- OrchestrationEvents.make
      scoped <- updates.subscribe(EventScope.Session(shared))
      storage = new StorageEvents(base, updates)
      _ <- storage.createSession(unrelated, Chunk(author))
      _ <- storage.putMessage(Message(root.data.copy(payload = MessagePayload.User("standalone"))))
      _ <- storage.fork(id, shared)
      _ <- storage.addSessionAgent(id, AgentId("not-shared"))
      _ <- storage.compareAndSetSessionHead(shared, Some(child.id), Some(root.id))
      conflict <- storage.compareAndSetSessionHead(shared, Some(child.id), None).either
      _ <- storage.compareAndSetSessionHead(shared, Some(root.id), Some(child.id))
      _ <- storage.addSessionAgent(shared, AgentId("participant"))
      events <- ZIO.collectAll(List.fill(10)(scoped.take))
      _ <- scoped.close
    } yield {
      assert(conflict.isLeft)
      assertEquals(events.map(_.sequence), (scoped.startSequence + 1 to scoped.startSequence + 10).toList)
      val canonical = events.collect {
        case event if event.change.isInstanceOf[EventUpdate.Confirmed] =>
          event.change.asInstanceOf[EventUpdate.Confirmed]
      }
      assertEquals(
        canonical.map(_.messages.map(_.id)),
        List(
          Chunk(root.id, child.id),
          Chunk(root.id),
          Chunk(root.id),
          Chunk(root.id, child.id),
          Chunk(root.id, child.id)
        )
      )
      assert(canonical.forall(_.sessions.map(_.id) == Chunk(shared)))
      assert(canonical.map(_.sessions.head.revision).sliding(2).forall(pair => pair.head < pair.last))
      assert(canonical.last.sessions.head.agentIds.contains(AgentId("participant")))
      assert(events.forall(_.origin.exists(_.sessionId == shared)))
      assert(canonical.last.sessions.head.revision > events.last.sequence)
    })
  }

  test("scoped pre-registration pending captures only its concurrent execution context and reconciles uncertainty") {
    val other = SessionId.unsafe("other-context")
    run(for {
      base <- StorageInMemory.make
      updates <- OrchestrationEvents.make
      entered <- Queue.unbounded[Unit]
      release <- Promise.make[Nothing, Unit]
      storage = new StorageEvents(
        new StorageProbe(base) {
          override def putMessage(message: Message) = entered.offer(()) *> release.await *> ZIO.fail(problem)
        },
        updates
      )
      first <- updates.executionOrigin
        .locally(Some(EventOrigin(id, Some("turn-one"))))(storage.putMessage(root))
        .either
        .fork
      second <- updates.executionOrigin
        .locally(Some(EventOrigin(other, Some("turn-two"))))(storage.putMessage(root))
        .either
        .fork
      _ <- entered.take *> entered.take
      scoped <- updates.subscribe(EventScope.Session(id))
      bootstrap <- scoped.takePendingObservations
      context <- updates.executionOrigin.get
      _ <- release.succeed(())
      _ <- first.join *> second.join
      event <- scoped.take
      terminal <- event.change match {
        case _: EventUpdate.Pending => scoped.take
        case _                      => ZIO.succeed(event)
      }
      _ <- scoped.close
      fresh <- updates.subscribe(EventScope.Session(id))
      empty <- fresh.takePendingObservations
      _ <- fresh.close
    } yield {
      assertEquals(context, None)
      assertEquals(bootstrap.map(_.origin), Chunk(Some(EventOrigin(id, Some("turn-one")))))
      assertEquals(bootstrap.head.change.messages, Chunk(EventMessage.from(root)))
      assertEquals(terminal.change, EventUpdate.Uncertain(bootstrap.head.change.operationId))
      assertEquals(terminal.origin, bootstrap.head.origin)
      assert(empty.isEmpty)
    })
  }

  test(
    "managed writer fibers stream real prompt, call, output and reply before terminal completion with isolated origins"
  ) {
    val other = SessionId.unsafe("second-turn")
    val key = ToolKey.unsafe("event-probe")
    def throughReply(subscription: OrchestrationEvents.Subscription): IO[String, List[SequencedEventUpdate]] = {
      def loop(events: List[SequencedEventUpdate]): IO[String, List[SequencedEventUpdate]] = subscription.take.flatMap {
        next =>
          val all = events :+ next
          next.change match {
            case EventUpdate.Confirmed(_, messages, sessions)
                if messages.exists(_.role == "assistant") && sessions.exists(_.messageCount == 4L) =>
              ZIO.succeed(all)
            case _ => loop(all)
          }
      }
      loop(Nil)
    }
    run(for {
      registry <- RuntimeInstances.fromAgents(Chunk(new Agent[Any] {
        val id = author
        def turn(request: fmgp.yallmo.model.llm.AgentTurnRequest) =
          ZIO.dieMessage("No provider calls in event tests")
      }))
      live <- OrchestratorLive.inMemory(registry)
      firstSub <- live.events.subscribe(EventScope.Session(id))
      secondSub <- live.events.subscribe(EventScope.Session(other))
      _ <- live.createSession(id, Chunk(author)) *> live.createSession(other, Chunk(author))
      execution = new AgentExecution[Any] {
        val agentId = author
        private[orchestration] def run(task: AgentTask, history: ManagedHistory) = for {
          initial <- history.messages
          origin <- live.events.executionOrigin.get
          saved <- live.loadSession(origin.get.sessionId)
          _ <- ZIO.succeed(assertEquals(saved.messages, initial))
          _ <- live.invoke(
            Message(MessageData(author, None, Instant.EPOCH, MessagePayload.User("independent-" + task.prompt))),
            new AgentInvocation[Any] {
              val agentId = author
              private[orchestration] def invoke(input: MessagePayload) =
                ZIO.succeed(MessagePayload.Assistant(input.contentStr))
            }
          )
          _ <- history.append(
            Chunk(MessagePayload.ToolCall(FunctionCall(FunctionCallId.unsafe(task.prompt), key, "{}")))
          )
          _ <- history.append(
            Chunk(
              MessagePayload.ToolOutput(FunctionGenericOutput(FunctionCallId.unsafe(task.prompt), key, task.prompt))
            )
          )
          _ <- history.append(Chunk(MessagePayload.Assistant(task.prompt)))
          result <- ZIO.never
        } yield result
      }
      first <- live.submit(id, author, AgentTask("first"), execution).fork
      second <- live.submit(other, author, AgentTask("second"), execution).fork
      observations <- throughReply(firstSub).zipPar(throughReply(secondSub))
      firstTerminal <- first.poll
      secondTerminal <- second.poll
      _ <- first.interrupt *> second.interrupt
      _ <- firstSub.close *> secondSub.close
    } yield {
      assertEquals(firstTerminal, None)
      assertEquals(secondTerminal, None)
      List((id, "first", observations._1), (other, "second", observations._2)).foreach { (session, text, events) =>
        val pending = events.collect {
          case event if event.change.isInstanceOf[EventUpdate.Pending] =>
            event.change.asInstanceOf[EventUpdate.Pending].messages
        }.flatten
        assertEquals(pending.map(_.role), List("user", "tool_call", "tool_result", "assistant"))
        assertEquals(pending.filter(m => Set("user", "assistant")(m.role)).map(_.content), List(text, text))
        assert(pending.forall(m => !m.content.startsWith("independent-")))
        assertEquals(pending.map(_.id).distinct.size, 4)
        assert(events.forall(_.origin.exists(_.sessionId == session)))
        assertEquals(events.map(_.sequence), (events.head.sequence to events.last.sequence).toList)
        assertEquals(events.flatMap(_.origin.flatMap(_.turnId)).distinct.size, 1)
      }
      assertNotEquals(observations._1.last.origin.get.turnId, observations._2.last.origin.get.turnId)
    })
  }

  test("origin metadata participates in retained-pending and subscriber byte limits") {
    run(for {
      updates <- OrchestrationEvents.make
      scoped <- updates.subscribe(EventScope.Session(id))
      origin = Some(EventOrigin(id, Some("x" * 400000)))
      operation <- updates.begin(Chunk(root), origin)
      rejected <- updates.subscribe(EventScope.Session(id)).either
      _ <- updates.pending(operation, origin)
      overflow <- scoped.check.either
      _ <- updates.complete(operation, false, Chunk.empty, origin)
      _ <- scoped.close
      fresh <- updates.subscribe(EventScope.Session(id))
      pending <- fresh.takePendingObservations
      _ <- fresh.close
    } yield {
      assert(rejected.isLeft)
      assert(overflow.isLeft)
      assert(pending.isEmpty)
    })
  }

  test("a diverged managed turn confirms stored side branches without claiming they are canonical") {
    run(for {
      registry <- RuntimeInstances.fromAgents(Chunk(new Agent[Any] {
        val id = author
        def turn(request: fmgp.yallmo.model.llm.AgentTurnRequest) = ZIO.dieMessage("No providers")
      }))
      live <- OrchestratorLive.inMemory(registry)
      _ <- live.createSession(id, Chunk(author))
      scoped <- live.events.subscribe(EventScope.Session(id))
      execution = new AgentExecution[Any] {
        val agentId = author
        private[orchestration] def run(task: AgentTask, history: ManagedHistory) =
          live.append(id, author, Chunk(MessagePayload.User("competitor"))) *>
            history.append(Chunk(MessagePayload.Assistant("side-branch"))) *> ZIO.never
      }
      turn <- live.submit(id, author, AgentTask("prompt"), execution).fork
      observed <- {
        def loop(events: List[SequencedEventUpdate], sideBranchSeen: Boolean): IO[String, List[SequencedEventUpdate]] =
          scoped.take.flatMap { event =>
            val all = events :+ event
            event.change match {
              case EventUpdate.Confirmed(_, messages, sessions) =>
                if sideBranchSeen && sessions.nonEmpty then ZIO.succeed(all)
                else loop(all, sideBranchSeen || (messages.exists(_.content == "side-branch") && sessions.isEmpty))
              case _ => loop(all, sideBranchSeen)
            }
          }
        loop(Nil, false)
      }
      canonical <- live.loadSession(id)
      _ <- turn.interrupt *> scoped.close
    } yield {
      val objectConfirmation = observed.collectFirst {
        case SequencedEventUpdate(_, confirmation: EventUpdate.Confirmed, _)
            if confirmation.messages.exists(_.content == "side-branch") =>
          confirmation
      }.get
      assert(objectConfirmation.sessions.isEmpty)
      val pointer = observed.last.change.asInstanceOf[EventUpdate.Confirmed]
      assertEquals(pointer.sessions.head.head, canonical.messages.lastOption.map(_.id))
      assertEquals(pointer.messages.map(_.id), canonical.messages.map(_.id))
      assertEquals(pointer.messages.map(_.content), Chunk("prompt", "competitor"))
      assert(!pointer.messages.exists(_.content == "side-branch"))
    })
  }

  test("scoped rebase publication carries previously unseen copied ancestors and missing targets never become heads") {
    run(for {
      base <- StorageInMemory.make
      registry <- RuntimeInstances.fromAgents(Chunk(new Agent[Any] {
        val id = author
        def turn(request: fmgp.yallmo.model.llm.AgentTurnRequest) = ZIO.dieMessage("No providers")
      }))
      live <- OrchestratorLive.make(base, registry)
      _ <- live.createSession(id, Chunk(author))
      _ <- live.storage.putMessagesAndUpdateSession(id, None, Chunk(root, child))
      scoped <- live.events.subscribe(EventScope.Session(id))
      copies <- live.rebase(root.id, child.id, None)
      _ <- live.storage.compareAndSetSessionHead(id, Some(child.id), Some(copies.last.id))
      missing <- live.storage
        .compareAndSetSessionHead(id, Some(copies.last.id), Some(MessageId.sha256("absent")))
        .either
      events <- ZIO.collectAll(List.fill(4)(scoped.take))
      _ <- scoped.close
    } yield {
      assert(missing.isLeft)
      val confirmed = events.collect { case SequencedEventUpdate(_, change: EventUpdate.Confirmed, _) => change }
      assertEquals(copies.size, 1)
      assertEquals(copies.head.parentId, None)
      assertNotEquals(copies.head.id, child.id)
      assertEquals(confirmed.map(_.messages.map(_.id)), List(copies.map(_.id), copies.map(_.id)))
      assert(confirmed.forall(_.sessions.head.head.contains(copies.last.id)))
      assertEquals(events.map(_.sequence), (scoped.startSequence + 1 to scoped.startSequence + 4).toList)
    })
  }

  test("scoped canonical ancestry is included in queue byte accounting without rejecting reliable publication") {
    run(for {
      base <- StorageInMemory.make
      _ <- base.createSession(id, Chunk(author))
      huge = Message(root.data.copy(payload = MessagePayload.User("x" * 400000)))
      _ <- base.putMessage(huge)
      updates <- OrchestrationEvents.make
      scoped <- updates.subscribe(EventScope.Session(id))
      storage = new StorageEvents(base, updates)
      saved <- storage.compareAndSetSessionHead(id, None, Some(huge.id))
      overflow <- scoped.check.either
      _ <- scoped.close
    } yield {
      assertEquals(saved.messages, Chunk(huge))
      assert(overflow.isLeft)
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
