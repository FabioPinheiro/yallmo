package fmgp.yallmo.repl

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import fmgp.yallmo.orchestration.*
import java.nio.file.{Files, Path}
import java.util.Comparator
import munit.FunSuite
import zio.*

final class ReplPromptPersistenceSuite extends FunSuite {
  test("the first prompt is on disk before runner configuration and is not saved twice after success") {
    withRoot { root =>
      val app = application(
        root,
        onConfigure = ZIO.succeed {
          val lines = Files.readAllLines(root.resolve("objects.jsonl"))
          assertEquals(lines.size(), 1)
          assert(lines.get(0).contains("save me first"))
        },
        respond = context =>
          ZIO.succeed {
            assertEquals(context.messages, Chunk(MessagePayload.User("save me first")))
            answer("saved")
          }
      )
      val result = run(app.submit(ReplState(), "save me first"))
      val restored = run(app.submit(ReplState(), s"/session resume ${result.state.sessionId.get}"))
      assertEquals(
        result.state.runContext.messages,
        Chunk(MessagePayload.User("save me first"), MessagePayload.Assistant("saved"))
      )
      assertEquals(restored.state.runContext.messages, result.state.runContext.messages)
      assertEquals(Files.readAllLines(root.resolve("objects.jsonl")).size(), 2)
      assertEquals(
        result.events,
        Chunk(ReplEvent.AgentTurn(AgentResult(assistantId, "saved"), result.state.runContext.messages))
      )
    }
  }

  test("a timed-out first run keeps its saved prompt and session ID, including in subsequent requests") {
    withRoot { root =>
      val app = application(
        root,
        respond = context =>
          if context.messages.size == 1 then ZIO.fail(YallmoError.AgentTimedOut(2.minutes))
          else
            ZIO.succeed {
              assertEquals(context.messages, Chunk(MessagePayload.User("first"), MessagePayload.User("retry")))
              answer("recovered")
            }
      )
      val failed = run(app.submit(ReplState(), "first"))
      assert(failed.events.exists(_.isInstanceOf[ReplEvent.AgentFailed]))
      assert(failed.state.sessionId.nonEmpty)
      assertEquals(failed.state.runContext.messages, Chunk(MessagePayload.User("first")))
      assertEquals(Files.readAllLines(root.resolve("objects.jsonl")).size(), 1)
      val restored = run(app.submit(ReplState(), s"/session resume ${failed.state.sessionId.get}"))
      assertEquals(restored.state.runContext.messages, failed.state.runContext.messages)

      val retried = run(app.submit(failed.state, "retry"))
      assertEquals(retried.state.sessionId, failed.state.sessionId)
      assertEquals(Files.readAllLines(root.resolve("objects.jsonl")).size(), 3)
      val resumed = run(app.submit(ReplState(), s"/session resume ${failed.state.sessionId.get}"))
      assertEquals(resumed.state.runContext.messages, retried.state.runContext.messages)
    }
  }

  test("a failed write prevents even runner configuration and leaves the accepted state unchanged") {
    withRoot { root =>
      var configured = false
      val app = application(root, onConfigure = ZIO.succeed { configured = true })
      Files.delete(root.resolve("objects.jsonl"))
      Files.createDirectory(root.resolve("objects.jsonl"))
      val before = ReplState(logLevel = LogLevel.Debug, lastProviderRequest = Some(Path.of("previous-request.json")))
      val failed = run(app.submit(before, "cannot save"))
      assert(!configured)
      assertEquals(failed.state, before)
      assert(failed.events.exists(_.isInstanceOf[ReplEvent.AgentFailed]))
    }
  }

  test("named and resumed sessions append each prompt once and retain earlier messages after agent failure") {
    withRoot { root =>
      val app = application(
        root,
        respond = context =>
          if context.messages.last == MessagePayload.User("fail") then ZIO.fail(YallmoError.HttpFailure("offline"))
          else ZIO.succeed(answer("ok"))
      )
      val created = run(app.submit(ReplState(), "/new notes"))
      val first = run(app.submit(created.state, "hello"))
      val resumed = run(app.submit(ReplState(), "/session resume notes"))
      val before = resumed.state.copy(
        logLevel = LogLevel.Error,
        lastProviderRequest = Some(Path.of("previous-request.json"))
      )
      val failed = run(app.submit(before, "fail"))
      assertEquals(
        failed.state,
        before.copy(runContext = RunContext(first.state.runContext.messages :+ MessagePayload.User("fail")))
      )
      assertEquals(Files.readAllLines(root.resolve("objects.jsonl")).size(), 3)
      assertEquals(
        run(app.submit(ReplState(), "/session resume notes")).state.runContext.messages,
        failed.state.runContext.messages
      )
    }
  }

  test("session validation failure preserves the original state without accepting a prompt") {
    withRoot { root =>
      var configured = false
      val app = application(root, onConfigure = ZIO.succeed { configured = true })
      val before = ReplState(
        sessionId = Some("invalid/session"),
        logLevel = LogLevel.Debug,
        lastProviderRequest = Some(Path.of("previous-request.json"))
      )
      val failed = run(app.submit(before, "cannot accept"))
      assert(!configured)
      assertEquals(failed.state, before)
      assert(failed.events.exists(_.isInstanceOf[ReplEvent.AgentFailed]))
      assertEquals(Files.readAllLines(root.resolve("objects.jsonl")).size(), 0)
    }
  }

  test("interrupting a running agent does not remove the already saved prompt") {
    withRoot { root =>
      run {
        for {
          started <- Promise.make[Nothing, Unit]
          app = application(root, respond = _ => started.succeed(()) *> ZIO.never)
          fiber <- app.submit(ReplState(), "keep on cancellation").fork
          _ <- started.await
          _ <- fiber.interrupt
          sessions <- app.submit(ReplState(), "/session list")
          id = sessions.events.collect { case ReplEvent.SessionList(ids, _) => ids.head }.head
          resumed <- app.submit(ReplState(), s"/session resume $id")
        } yield assertEquals(resumed.state.runContext.messages, Chunk(MessagePayload.User("keep on cancellation")))
      }
    }
  }

  private val humanId = AgentId("operator")
  private val assistantId = AgentId("assistant")

  private def answer(text: String): AgentTurn =
    AgentTurn(Some(MessagePayload.Assistant(text)), AgentDecision.FinalAnswer(text))

  private def application(
      root: Path,
      onConfigure: UIO[Unit] = ZIO.unit,
      respond: RunContext => IO[YallmoError, AgentTurn] = _ => ZIO.succeed(answer("ok"))
  ): ReplApplication[Any] = run {
    def participant(agentId: AgentId): Agent[Any] = new Agent[Any] {
      override val id: AgentId = agentId
      override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
        ZIO.dieMessage("Only the runner program should be invoked")
    }
    val human = participant(humanId)
    val assistant = participant(assistantId)
    val program = new AgentInstance[Any] {
      override val id: AgentId = assistantId
      override def configuredContext(context: StaticContext[Any]): IO[YallmoError, StaticContext[Any]] =
        onConfigure.as(context)
      override def next(task: AgentTask, context: StaticContext[Any], current: RunContext): IO[YallmoError, AgentTurn] =
        respond(current)
    }
    for {
      instances <- RuntimeInstances.fromAgents(Chunk(human, assistant))
      storage <- OrchestratorLive.filesystem(root, instances)
      repl = ReplOrchestratorLive.make(storage, human, Some(assistant))
    } yield new ReplApplication[Any](
      new ReplSession[Any](
        new AgentRunner[Any](Map.empty, Map.empty),
        Some(program),
        StaticContext[Any]("Test")
      ),
      ReplToolAvailability(Chunk.empty, Chunk.empty),
      repl
    )
  }

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def withRoot(test: Path => Unit): Unit = {
    val root = Files.createTempDirectory("yallmo-prompt-persistence-")
    try test(root)
    finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }
}
