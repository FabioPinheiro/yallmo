package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import java.nio.file.{Files, Path}
import java.util.Comparator
import munit.FunSuite
import zio.*

final class OrchestratorSubmitSuite extends FunSuite {
  test("the core orchestrator flushes the prompt before execution and persists only the response delta") {
    withRoot { root =>
      run {
        for {
          orchestrator <- open(root)
          _ <- orchestrator.createSession(sessionId, Chunk(humanId, assistantId))
          _ <- orchestrator.append(sessionId, humanId, Chunk(MessagePayload.User("earlier")))
          _ <- orchestrator.append(sessionId, assistantId, Chunk(MessagePayload.Assistant("previous answer")))
          expected = Chunk(
            MessagePayload.User("earlier"),
            MessagePayload.Assistant("previous answer"),
            MessagePayload.User("now")
          )
          program = new AgentInstance[Any] {
            override val id: AgentId = assistantId
            override def configuredContext(context: StaticContext[Any]): IO[YallmoError, StaticContext[Any]] =
              ZIO.succeed {
                assertEquals(Files.readAllLines(root.resolve("objects.jsonl")).size(), 3)
                context
              }
            override def next(
                task: AgentTask,
                context: StaticContext[Any],
                current: RunContext
            ): IO[YallmoError, AgentTurn] =
              ZIO.succeed {
                assertEquals(current.messages, expected)
                AgentTurn(Some(MessagePayload.Assistant("answer")), AgentDecision.FinalAnswer("answer"))
              }
          }
          turn <- orchestrator.submit(sessionId, humanId, AgentTask("now"), runner.execution(program, context))
          reopened <- open(root)
          stored <- reopened.loadSession(sessionId)
        } yield {
          assertEquals(turn.session, stored)
          assertEquals(stored.messages.map(_.payload), expected :+ MessagePayload.Assistant("answer"))
          assertEquals(stored.messages.map(_.agentId), Chunk(humanId, assistantId, humanId, assistantId))
          assertEquals(turn.newMessages, Chunk(MessagePayload.User("now"), MessagePayload.Assistant("answer")))
          assertEquals(Files.readAllLines(root.resolve("objects.jsonl")).size(), 4)
        }
      }
    }
  }

  test("a real runner timeout leaves the accepted prompt available in the error and on disk") {
    withRoot { root =>
      run {
        for {
          orchestrator <- open(root)
          _ <- orchestrator.createSession(sessionId, Chunk(humanId))
          execution = runner.execution(program(_ => ZIO.never), context, AgentBudget.default.copy(timeout = 25.millis))
          failed <- orchestrator.submit(sessionId, humanId, AgentTask("keep me"), execution).either
          reopened <- open(root)
          stored <- reopened.loadSession(sessionId)
        } yield {
          val failure = failed.swap.toOption.get
          assert(failure.error.isInstanceOf[YallmoError.AgentTimedOut])
          assertEquals(failure.acceptedSession, Some(stored))
          assertEquals(stored.messages.map(_.payload), Chunk(MessagePayload.User("keep me")))
          assertEquals(stored.agentIds, Chunk(humanId))
        }
      }
    }
  }

  test("a failed prompt write prevents the agent loop from starting") {
    withRoot { root =>
      run {
        for {
          orchestrator <- open(root)
          _ <- orchestrator.createSession(sessionId, Chunk(humanId))
          called <- Ref.make(false)
          _ <- ZIO.attempt {
            Files.delete(root.resolve("objects.jsonl"))
            Files.createDirectory(root.resolve("objects.jsonl"))
          }
          execution = runner.execution(program(_ => called.set(true).as(answer)), context)
          failed <- orchestrator.submit(sessionId, humanId, AgentTask("cannot save"), execution).either
          wasCalled <- called.get
        } yield {
          assert(!wasCalled)
          assertEquals(failed.swap.toOption.get.acceptedSession, None)
          assert(failed.swap.toOption.get.error.isInstanceOf[YallmoError.StorageFailed])
        }
      }
    }
  }

  test("interrupting execution does not remove the prompt that the orchestrator already saved") {
    withRoot { root =>
      run {
        for {
          orchestrator <- open(root)
          _ <- orchestrator.createSession(sessionId, Chunk(humanId))
          started <- Promise.make[Nothing, Unit]
          execution = runner.execution(program(_ => started.succeed(()) *> ZIO.never), context)
          fiber <- orchestrator.submit(sessionId, humanId, AgentTask("cancelled"), execution).fork
          _ <- started.await
          _ <- fiber.interrupt
          stored <- orchestrator.loadSession(sessionId)
        } yield assertEquals(stored.messages.map(_.payload), Chunk(MessagePayload.User("cancelled")))
      }
    }
  }

  test("unregistered execution is rejected before accepting the prompt") {
    withRoot { root =>
      run {
        for {
          orchestrator <- open(root)
          _ <- orchestrator.createSession(sessionId, Chunk(humanId))
          invalid = new AgentExecution[Any] {
            override val agentId: AgentId = AgentId("unknown")
            override def run(task: AgentTask, acceptedContext: RunContext): IO[YallmoError, AgentTurnResult] =
              ZIO.dieMessage("Unknown execution must not run")
          }
          failed <- orchestrator.submit(sessionId, humanId, AgentTask("reject"), invalid).either
          stored <- orchestrator.loadSession(sessionId)
        } yield {
          assertEquals(failed, Left(SessionSubmitFailure(YallmoError.UnknownAgent(AgentId("unknown")), None)))
          assertEquals(stored.messages, Chunk.empty)
        }
      }
    }
  }

  test("an execution cannot replace accepted history or change its response author") {
    withRoot { root =>
      run {
        for {
          orchestrator <- open(root)
          _ <- orchestrator.createSession(sessionId, Chunk(humanId))
          invalid = new AgentExecution[Any] {
            override val agentId: AgentId = assistantId
            override def run(task: AgentTask, acceptedContext: RunContext): IO[YallmoError, AgentTurnResult] =
              ZIO.succeed(AgentTurnResult(AgentResult(assistantId, "bad"), RunContext()))
          }
          failed <- orchestrator.submit(sessionId, humanId, AgentTask("preserve"), invalid).either
          stored <- orchestrator.loadSession(sessionId)
        } yield {
          assert(failed.swap.toOption.get.error.isInstanceOf[YallmoError.InvalidAgentDecision])
          assertEquals(stored.messages.map(_.payload), Chunk(MessagePayload.User("preserve")))
        }
      }
    }
  }

  test("an empty response still returns the saved prompt without an empty append") {
    withRoot { root =>
      run {
        for {
          orchestrator <- open(root)
          _ <- orchestrator.createSession(sessionId, Chunk(humanId))
          execution = new AgentExecution[Any] {
            override val agentId: AgentId = assistantId
            override def run(task: AgentTask, acceptedContext: RunContext): IO[YallmoError, AgentTurnResult] =
              ZIO.succeed(AgentTurnResult(AgentResult(assistantId, "done"), acceptedContext))
          }
          result <- orchestrator.submit(sessionId, humanId, AgentTask("no response message"), execution)
        } yield assertEquals(result.newMessages, result.session.messages.map(_.payload))
      }
    }
  }

  private val sessionId = SessionId.unsafe("main")
  private val humanId = AgentId("human")
  private val assistantId = AgentId("assistant")
  private val context = StaticContext[Any]("Test")
  private def runner = new AgentRunner[Any](Map.empty, Map.empty)
  private def answer = AgentTurn(Some(MessagePayload.Assistant("ok")), AgentDecision.FinalAnswer("ok"))

  private def program(respond: RunContext => IO[YallmoError, AgentTurn]): AgentInstance[Any] =
    new AgentInstance[Any] {
      override val id: AgentId = assistantId
      override def next(task: AgentTask, context: StaticContext[Any], current: RunContext): IO[YallmoError, AgentTurn] =
        respond(current)
    }

  private def open(root: Path): IO[YallmoError, Orchestrator[Any]] = {
    def participant(agentId: AgentId): Agent[Any] = new Agent[Any] {
      override val id: AgentId = agentId
      override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
        ZIO.dieMessage("Only the supplied execution should be invoked")
    }
    for {
      instances <- RuntimeInstances.fromAgents(Chunk(participant(humanId), participant(assistantId)))
      orchestrator <- OrchestratorLive.filesystem(root, instances)
    } yield orchestrator
  }

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def withRoot(test: Path => Unit): Unit = {
    val root = Files.createTempDirectory("yallmo-orchestrator-submit-")
    try test(root)
    finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }
}
