package fmgp.yallmo.server.application

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse, ProviderContinuation, ProviderId}
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.orchestration.config.AgentCapabilities
import munit.FunSuite
import scala.concurrent.Future
import zio.*

final class SessionSubmissionSuite extends FunSuite {
  private val userId = AgentId("operator")
  private val assistantId = AgentId("assistant")
  private val runner = new AgentRunner[Any](Map.empty, Map.empty)
  private val context = StaticContext[Any]("System")

  test("unselected, mismatched and invalid-session submissions reject before creating or executing") {
    run {
      for {
        master <- orchestrator
        configured <- Ref.make(false)
        agent = program(_ => ZIO.dieMessage("Rejected submissions must not run"), configured.set(true))
        selected = SessionSubmission.configured(master, userId, runner, Some(agent), context)
        unselected = SessionSubmission.configured(master, userId, runner, None, context)
        missing <- unselected.submit(None, "no selection").either
        mismatch <- SessionSubmission
          .make(master, userId, None)
          .submitExecution(None, "mismatch", runner.execution(agent, context))
          .either
        invalid <- selected.submit(Some("invalid/session"), "invalid").either
        sessions <- master.listSessions
        ran <- configured.get
      } yield {
        for result <- List(missing, mismatch, invalid) do {
          assert(result.isLeft)
          assertEquals(result.swap.toOption.flatMap(_.acceptedSession), None)
        }
        assertEquals(sessions, Chunk.empty)
        assert(!ran)
      }
    }
  }

  test("configured submission creates a timestamped session and persists each prompt once with bound authors") {
    run {
      for {
        master <- orchestrator
        agent = program(current => ZIO.succeed(answer(s"messages=${current.messages.size}")))
        sessions = SessionSubmission.configured(master, userId, runner, Some(agent), context)
        first <- sessions.submit(None, "first")
        second <- sessions.submit(Some(first.session.id.value), "second")
        loaded <- sessions.load(first.session.id.value)
      } yield {
        assert(first.session.id.value.matches("main-\\d{8}-\\d{6}"))
        assertEquals(second.result, AgentResult(assistantId, "messages=3"))
        assertEquals(second.newMessages, Chunk(MessagePayload.User("second"), MessagePayload.Assistant("messages=3")))
        assertEquals(loaded, second.session)
        assertEquals(loaded.messages.map(_.agentId), Chunk(userId, assistantId, userId, assistantId))
        assertEquals(loaded.messages.count(_.payload == MessagePayload.User("first")), 1)
        assertEquals(loaded.messages.count(_.payload == MessagePayload.User("second")), 1)
      }
    }
  }

  test("a failure during runner configuration exposes the already accepted prompt and permits recovery") {
    run {
      for {
        master <- orchestrator
        failing = program(_ => ZIO.dieMessage("Configuration fails first"), ZIO.fail(YallmoError.InvalidConfig("bad")))
        failed <- SessionSubmission
          .configured(master, userId, runner, Some(failing), context)
          .submit(None, "keep")
          .either
        accepted <- ZIO.fromOption(failed.swap.toOption.flatMap(_.acceptedSession))
        recovered <- SessionSubmission
          .configured(
            master,
            userId,
            runner,
            Some(program(_ => ZIO.succeed(answer("ok")))),
            context
          )
          .submit(Some(accepted.id.value), "retry")
      } yield {
        assertEquals(failed.swap.toOption.map(_.error), Some(YallmoError.InvalidConfig("bad")))
        assertEquals(accepted.messages.map(_.payload), Chunk(MessagePayload.User("keep")))
        assertEquals(
          recovered.session.messages.map(_.payload),
          Chunk(
            MessagePayload.User("keep"),
            MessagePayload.User("retry"),
            MessagePayload.Assistant("ok")
          )
        )
      }
    }
  }

  test("interrupting configured execution retains its accepted prompt and session") {
    run {
      for {
        master <- orchestrator
        started <- Promise.make[Nothing, Unit]
        agent = program(_ => started.succeed(()) *> ZIO.never)
        sessions = SessionSubmission.configured(master, userId, runner, Some(agent), context)
        fiber <- sessions.submit(None, "keep on cancellation").fork
        _ <- started.await
        exit <- fiber.interrupt
        ids <- master.listSessions
        loaded <- sessions.load(ids.head.id.value)
      } yield {
        assert(exit.isInterrupted)
        assertEquals(ids.size, 1)
        assertEquals(loaded.messages.map(_.payload), Chunk(MessagePayload.User("keep on cancellation")))
      }
    }
  }

  test("continuation reset filters only old reasoning and retains configured-context policy") {
    run {
      for {
        master <- orchestrator
        seen <- Ref.make(Chunk.empty[RunContext])
        configured <- Ref.make(0)
        agent = new AgentInstance[Any] {
          override val id: AgentId = assistantId
          override val capabilities: AgentCapabilities = AgentCapabilities(tools = Some(Set.empty))
          override def configuredContext(available: StaticContext[Any]): IO[YallmoError, StaticContext[Any]] =
            configured.update(_ + 1) *> super.configuredContext(available)
          override def next(
              task: AgentTask,
              available: StaticContext[Any],
              current: RunContext
          ): IO[YallmoError, AgentTurn] =
            seen.updateAndGet(_ :+ current).map { history =>
              assertEquals(available.tools, Chunk.empty)
              val continuation = ProviderContinuation(ProviderId.unsafe("test"), None, s"continuation-${history.size}")
              answer("ok").copy(additionalMessages = Chunk(MessagePayload.Reasoning(None, continuation)))
            }
        }
        sessions = SessionSubmission.configured(
          master,
          userId,
          runner,
          Some(agent),
          context.copy(
            tools = Chunk(AgentToolDefinition(ToolKey.unsafe("unavailable"), "Must be filtered"))
          )
        )
        first <- sessions.submit(None, "first")
        boundary = first.session.messages.size
        second <- sessions.submit(Some(first.session.id.value), "after reset", boundary)
        third <- sessions.submit(Some(first.session.id.value), "continue", boundary)
        requests <- seen.get
        configurations <- configured.get
      } yield {
        def reasoning(messages: Chunk[MessagePayload]) = messages.collect {
          case MessagePayload.Reasoning(_, continuation) => continuation.encryptedContent
        }
        assertEquals(reasoning(requests(1).messages), Chunk.empty)
        assertEquals(reasoning(requests(2).messages), Chunk("continuation-2"))
        assertEquals(reasoning(second.session.messages.map(_.payload)), Chunk("continuation-1", "continuation-2"))
        assertEquals(reasoning(third.session.messages.map(_.payload)).size, 3)
        assertEquals(configurations, 3)
      }
    }
  }

  private def orchestrator: IO[YallmoError, OrchestratorLive[Any]] = {
    def participant(agentId: AgentId): Agent[Any] = new Agent[Any] {
      override val id: AgentId = agentId
      override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
        ZIO.dieMessage("Only the configured program should run")
    }
    RuntimeInstances
      .fromAgents(Chunk(participant(userId), participant(assistantId)))
      .flatMap(OrchestratorLive.inMemory(_))
  }

  private def program(
      respond: RunContext => IO[YallmoError, AgentTurn],
      configure: IO[YallmoError, Unit] = ZIO.unit
  ): AgentInstance[Any] = new AgentInstance[Any] {
    override val id: AgentId = assistantId
    override def configuredContext(available: StaticContext[Any]): IO[YallmoError, StaticContext[Any]] =
      configure.as(available)
    override def next(task: AgentTask, available: StaticContext[Any], current: RunContext): IO[YallmoError, AgentTurn] =
      respond(current)
  }

  private def answer(text: String): AgentTurn =
    AgentTurn(Some(MessagePayload.Assistant(text)), AgentDecision.FinalAnswer(text))

  private def run[E, A](effect: ZIO[Any, E, A]): Future[A] =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.runToFuture(effect.mapError(error => FiberFailure(Cause.fail(error))))
    }
}
