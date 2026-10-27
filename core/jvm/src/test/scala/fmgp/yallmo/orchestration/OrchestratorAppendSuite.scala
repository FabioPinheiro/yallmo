package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import java.nio.file.{Files, Path}
import java.time.{Instant, ZoneOffset}
import java.util.Comparator
import munit.FunSuite
import zio.*

final class OrchestratorAppendSuite extends FunSuite {
  private val author = AgentId("author")
  private val other = AgentId("other")
  private val id = SessionId.unsafe("append")
  private val timestamp = Instant.parse("2026-01-01T00:00:00Z")

  for filesystem <- List(false, true) do {
    def fixture[A](test: (OrchestratorLive[Any], RuntimeInstances[Any], Path) => IO[YallmoError, A]): A = {
      val root = Files.createTempDirectory("yallmo-orchestrator-append-")
      try
        run {
          for {
            instances <- RuntimeInstances.fromAgents[Any](Chunk.empty)
            _ <- instances.registerUser(author)
            _ <- instances.registerUser(other)
            live <-
              if filesystem then OrchestratorLive.filesystem(root, instances) else OrchestratorLive.inMemory(instances)
            _ <- live.createSession(id, Chunk(author))
            result <- test(live, instances, root)
          } yield result
        }
      finally {
        val paths = Files.walk(root)
        try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
        finally paths.close()
      }
    }
    val backend = if filesystem then "filesystem" else "memory"

    test(s"$backend: append validates authors, membership and nonempty turns without mutating storage") {
      fixture { (live, _, _) =>
        for {
          unknown <- live.append(id, AgentId("unknown"), Chunk(Message.User("unknown"))).either
          unassigned <- live.append(id, other, Chunk(Message.User("unassigned"))).either
          empty <- live.append(id, author, Chunk.empty).either
          missing <- live.append(SessionId.unsafe("missing"), author, Chunk(Message.User("missing"))).either
          session <- live.loadSession(id)
          all <- live.listMessages
        } yield {
          assertEquals(unknown, Left(YallmoError.UnknownAgent(AgentId("unknown"))))
          assertEquals(unassigned, Left(YallmoError.StorageFailed(YallmoError.AgentNotInSession(id, other))))
          assertEquals(empty, Left(YallmoError.StorageFailed(YallmoError.EmptyAppend(id))))
          assertEquals(
            missing,
            Left(YallmoError.StorageFailed(YallmoError.SessionNotFound(SessionId.unsafe("missing"))))
          )
          assertEquals(session.messages, Chunk.empty)
          assertEquals(all, Chunk.empty)
        }
      }
    }

    test(s"$backend: append constructs content addresses and parent chains using the ZIO clock") {
      fixture { (live, _, _) =>
        ZIO.withClock(Clock.ClockJava(java.time.Clock.fixed(timestamp, ZoneOffset.UTC))) {
          for {
            first <- live.append(id, author, Chunk(Message.User("first"), Message.Assistant("second")))
            saved <- live.append(id, author, Chunk(Message.User("third")))
          } yield {
            assertEquals(saved.messages.map(_.timestamp), Chunk(timestamp, timestamp, timestamp))
            assertEquals(
              saved.messages.map(_.parentId),
              Chunk(None, Some(first.messages.head.id), Some(first.messages.last.id))
            )
            saved.messages.foreach(message => assertEquals(message.id, MessageId.from(message.data)))
          }
        }
      }
    }

    test(s"$backend: colliding turns rebuild on stale heads, never interleave, and retain participant additions") {
      fixture { (live, instances, root) =>
        for {
          calls <- Ref.make(0)
          bothLoaded <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          clock = new Clock {
            export Clock.ClockLive.{currentTime, currentDateTime, javaClock, localDateTime, nanoTime, scheduler, sleep}
            def instant(using Trace): UIO[Instant] =
              for {
                call <- calls.updateAndGet(_ + 1)
                _ <- bothLoaded.succeed(()).when(call == 2)
                // The first two turns have already loaded the same empty session before either can save.
                _ <- release.await.when(call <= 2)
              } yield timestamp.plusSeconds(call.toLong)
          }
          turns <- ZIO.withClock(clock) {
            ZIO.foreach(1 to 2)(i =>
              live.append(id, author, Chunk(Message.User(s"$i-first"), Message.Assistant(s"$i-second"))).fork
            )
          }
          _ <- bothLoaded.await
          _ <- live.addSessionAgent(id, other)
          _ <- release.succeed(())
          _ <- ZIO.foreachDiscard(turns)(_.join)
          saved <- live.loadSession(id)
          all <- live.listMessages
          count <- calls.get
          reopened <-
            if filesystem then OrchestratorLive.filesystem(root, instances).flatMap(_.loadSession(id))
            else live.loadSession(id)
        } yield {
          assertEquals(count, 6, "one losing two-message batch must be rebuilt exactly once")
          assertEquals(saved.messages.size, 4)
          assertEquals(saved.agentIds, Chunk(author, other))
          assertEquals(all.map(_.id).toSet, saved.messages.map(_.id).toSet)
          assertEquals(all.size, 4, "the rejected batch must not leave orphan objects")
          assertEquals(reopened, saved)
          for i <- 1 to 2 do {
            val offset = saved.messages.indexWhere(_.payload == Message.User(s"$i-first"))
            assertEquals(saved.messages(offset + 1).payload, Message.Assistant(s"$i-second"))
          }
          saved.messages.zipWithIndex.foreach { (message, offset) =>
            assertEquals(message.id, MessageId.from(message.data))
            assertEquals(message.parentId, saved.messages.take(offset).lastOption.map(_.id))
          }
        }
      }
    }
  }

  private def run[A](effect: IO[YallmoError, A]): A =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe
        .run(effect.timeoutFail(YallmoError.InvalidConfig("append test timed out"))(20.seconds))
        .getOrThrowFiberFailure()
    }
}
