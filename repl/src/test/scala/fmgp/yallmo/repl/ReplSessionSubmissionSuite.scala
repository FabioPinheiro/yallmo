package fmgp.yallmo.repl

import fmgp.yallmo.tool.{FunctionKey, FunctionToolDefinition}

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse, ProviderContinuation, ProviderId}
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.orchestration.config.AgentCapabilities
import munit.FunSuite
import scala.concurrent.Future
import zio.*

final class ReplSessionSubmissionSuite extends FunSuite {
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
        selected = configuredSessions(master, userId, runner, Some(agent), context)
        unselected = configuredSessions(master, userId, runner, None, context)
        missing <- unselected.submit(None, "no selection").either
        mismatch <- sessionFixture(master, userId, None)
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
        sessions = configuredSessions(master, userId, runner, Some(agent), context)
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
        failed <- configuredSessions(master, userId, runner, Some(failing), context)
          .submit(None, "keep")
          .either
        accepted <- ZIO.fromOption(failed.swap.toOption.flatMap(_.acceptedSession))
        recovered <- configuredSessions(
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
        sessions = configuredSessions(master, userId, runner, Some(agent), context)
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
        canonical <- Ref.make(Chunk.empty[Chunk[Message]])
        configured <- Ref.make(0)
        agent = new AgentInstance[Any] {
          override val id: AgentId = assistantId
          override val capabilities: AgentCapabilities = AgentCapabilities(functions = Some(Set.empty))
          override def configuredContext(available: StaticContext[Any]): IO[YallmoError, StaticContext[Any]] =
            configured.update(_ + 1) *> super.configuredContext(available)
          override def next(
              task: AgentTask,
              available: StaticContext[Any],
              current: RunContext
          ): IO[YallmoError, AgentTurn] =
            ZIO.dieMessage("Continuation wrapper must preserve the managed entry point")
          override def nextManaged(
              task: AgentTask,
              available: StaticContext[Any],
              current: RunContext,
              history: ManagedHistory
          ): IO[YallmoError, AgentTurn] =
            history.messages.flatMap(messages => canonical.update(_ :+ messages)) *>
              seen.updateAndGet(_ :+ current).map { history =>
                assertEquals(available.functions, Chunk.empty)
                val continuation =
                  ProviderContinuation(ProviderId.unsafe("test"), None, s"continuation-${history.size}")
                answer("ok").copy(additionalMessages = Chunk(MessagePayload.Reasoning(None, continuation)))
              }
        }
        sessions = configuredSessions(
          master,
          userId,
          runner,
          Some(agent),
          context.copy(
            functions = Chunk(
              FunctionToolDefinition(
                FunctionKey.unsafe("unavailable"),
                "Must be filtered"
              )
            )
          )
        )
        first <- sessions.submit(None, "first")
        boundary = first.session.messages.size
        second <- sessions.submit(Some(first.session.id.value), "after reset", boundary)
        third <- sessions.submit(Some(first.session.id.value), "continue", boundary)
        requests <- seen.get
        histories <- canonical.get
        configurations <- configured.get
      } yield {
        def reasoning(messages: Chunk[MessagePayload]) = messages.collect {
          case MessagePayload.Reasoning(_, continuation) => continuation.encryptedContent
        }
        assertEquals(reasoning(requests(1).messages), Chunk.empty)
        assertEquals(reasoning(requests(2).messages), Chunk("continuation-2"))
        assertEquals(reasoning(second.session.messages.map(_.payload)), Chunk("continuation-1", "continuation-2"))
        assertEquals(reasoning(third.session.messages.map(_.payload)).size, 3)
        assertEquals(histories.size, 3)
        assertEquals(reasoning(histories(1).map(_.payload)), Chunk("continuation-1"))
        assertEquals(reasoning(histories(2).map(_.payload)), Chunk("continuation-1", "continuation-2"))
        assertEquals(histories(2), third.session.messages.dropRight(2))
        assertEquals(configurations, 3)
      }
    }
  }

  test("reused concurrent reset execution preserves fresh continuations after shortening rebase and tool loop") {
    run {
      val key = FunctionKey.unsafe("probe")
      def continuation(value: String) = ProviderContinuation(ProviderId.unsafe("test"), None, value)
      def old(prompt: String) = continuation(s"old-$prompt")
      // For A, the fresh continuation is B's excluded value: reset state must not leak between submissions.
      def fresh(prompt: String) = if prompt == "A" then old("B") else continuation(s"fresh-$prompt")
      def reasoning(value: ProviderContinuation, summary: String) = MessagePayload.Reasoning(Some(summary), value)
      for {
        master <- orchestrator
        requests <- Ref.make(Map.empty[String, Chunk[AgentTurnRequest]])
        origins <- Ref.make(Map.empty[String, Chunk[Message]])
        enteredA <- Promise.make[Nothing, Unit]
        enteredB <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        provider = new Agent[Any] {
          val id = assistantId
          def turn(request: AgentTurnRequest) = {
            val prompt = request.input.collect { case MessagePayload.User(text) => text }.last
            requests.update(_.updatedWith(prompt)(previous => Some(previous.getOrElse(Chunk.empty) :+ request))) *>
              ZIO.succeed {
                assertEquals(request.tools.map(_.name), Chunk(key))
                if request.input.exists(_.isInstanceOf[MessagePayload.ToolOutput]) then
                  AgentTurnResponse(
                    Chunk(MessagePayload.Assistant(s"done-$prompt")),
                    fmgp.yallmo.model.llm.LlmStopReason.Stop
                  )
                else
                  AgentTurnResponse(
                    Chunk(
                      reasoning(old(prompt), "changed summary"),
                      reasoning(fresh(prompt), "fresh"),
                      MessagePayload.ToolCall(FunctionCall(FunctionCallId.unsafe(s"call-$prompt"), key, "{}"))
                    ),
                    fmgp.yallmo.model.llm.LlmStopReason.ToolUse
                  )
              }
          }
        }
        native = NativeFunctionAgent[Agent[Any]](
          assistantId,
          "recording",
          capabilities = AgentCapabilities(functions = Some(Set(key)))
        ).bind[Any](provider)
        agent = new AgentInstance[Any] {
          val id = assistantId
          override val capabilities = native.capabilities
          override def configuredContext(context: StaticContext[Any]) = native.configuredContext(context)
          def next(task: AgentTask, context: StaticContext[Any], current: RunContext) =
            ZIO.dieMessage("Managed entry point required")
          override def nextManaged(
              task: AgentTask,
              context: StaticContext[Any],
              current: RunContext,
              history: ManagedHistory
          ) =
            if current.messages.exists(_.isInstanceOf[MessagePayload.ToolOutput]) then
              native.nextManaged(task, context, current, history)
            else
              for {
                initial <- history.initialMessages
                _ <- origins.update(_.updated(task.prompt, initial))
                _ <- ZIO.succeed {
                  assertEquals(
                    current.messages.collect { case MessagePayload.Reasoning(_, value) => value },
                    Chunk.empty
                  )
                  assertEquals(initial.size, 11)
                  assertEquals(initial(1).payload, reasoning(old(task.prompt), "old"))
                  assertEquals(context.functions.map(_.name), Chunk(key))
                }
                _ <- (if task.prompt == "A" then enteredA.succeed(()) else enteredB.succeed(())).unit
                _ <- release.await
                _ <- history.rebase(initial(initial.size - 2).id, initial.last.id, None)
                rebuilt <- history.messages
                turn <- native.nextManaged(task, context, RunContext(rebuilt.map(_.payload)), history)
              } yield turn
        }
        effects <- Ref.make(0)
        tool = new AgentFunction[Any] {
          val definition = FunctionToolDefinition(key, "Synthetic probe")
          def execute(call: ExternalFunctionCall) = effects.update(_ + 1).as(ExternalFunctionResult("tool-result"))
        }
        available = context.copy(functions =
          Chunk(
            tool.definition,
            FunctionToolDefinition(
              FunctionKey.unsafe("unavailable"),
              "Excluded"
            )
          )
        )
        configured = configuredExecution(
          new AgentRunner[Any](Map(key -> tool), Map.empty),
          agent,
          available,
          10
        )
        submissions = sessionFixture(master, userId, Some(assistantId))
        seeds <- ZIO
          .foreach(List("A", "B", "C")) { prompt =>
            for {
              session <- master.createSession(SessionId.unsafe(s"reset-$prompt"), Chunk(userId))
              seed <- master.append(
                session.id,
                userId,
                Chunk(MessagePayload.User("seed"), reasoning(old(prompt), "old")) ++
                  Chunk.fromIterable((1 to 8).map(i => MessagePayload.Context("temporary", s"note-$i")))
              )
            } yield prompt -> seed
          }
          .map(_.toMap)
        a <- submissions.submitExecution(Some(seeds("A").id.value), "A", configured).fork
        _ <- enteredA.await
        b <- submissions.submitExecution(Some(seeds("B").id.value), "B", configured).fork
        _ <- enteredB.await
        _ <- release.succeed(())
        first <- a.join
        second <- b.join
        third <- submissions.submitExecution(Some(seeds("C").id.value), "C", configured)
        seen <- requests.get
        accepted <- origins.get
        inventory <- master.listMessages
        count <- effects.get
      } yield {
        assertEquals(count, 3)
        for (prompt, turn) <- List("A" -> first, "B" -> second, "C" -> third) do {
          val call = MessagePayload.ToolCall(FunctionCall(FunctionCallId.unsafe(s"call-$prompt"), key, "{}"))
          val output =
            MessagePayload.ToolOutput(FunctionGenericOutput(FunctionCallId.unsafe(s"call-$prompt"), key, "tool-result"))
          val initialRequest = Chunk(MessagePayload.User(prompt), MessagePayload.Instructions(context.systemPrompt))
          assertEquals(seen(prompt).size, 2)
          assertEquals(seen(prompt)(0).input, initialRequest)
          assertEquals(
            seen(prompt)(1).input,
            Chunk(
              MessagePayload.User(prompt),
              reasoning(fresh(prompt), "fresh"),
              call,
              output,
              MessagePayload.Instructions(context.systemPrompt)
            )
          )
          val expected = Chunk(
            MessagePayload.User(prompt),
            reasoning(old(prompt), "changed summary"),
            reasoning(fresh(prompt), "fresh"),
            call,
            output,
            MessagePayload.Assistant(s"done-$prompt")
          )
          assertEquals(turn.session.messages.map(_.payload), expected)
          assertEquals(turn.newMessages, expected)
          assert(accepted(prompt).forall(inventory.contains))
          assert(seeds(prompt).messages.forall(inventory.contains))
          assertEquals(
            turn.session.messages.drop(1).map(_.parentId),
            turn.session.messages.dropRight(1).map(m => Some(m.id))
          )
        }
      }
    }
  }

  // Adapt the retained local APIs to concise assertions; session/execution policy is implemented only in production REPL/core.
  private final class SessionFixture(
      master: Orchestrator[Any],
      author: AgentId,
      selected: Option[AgentId],
      configured: Option[(AgentRunner[Any], Option[AgentInstance[Any]], StaticContext[Any])] = None
  ) {
    private val local = ReplOrchestratorLive.make(master, participant(author), selected.map(participant))
    def create(name: Option[String]): IO[YallmoError, Session] =
      local.newReplSession(name).flatMap(state => load(state.sessionId.get))
    def load(id: String): IO[YallmoError, Session] =
      ZIO.fromEither(SessionId.from(id).left.map(YallmoError.InvalidSessionId(id, _))).flatMap(master.loadSession)
    def submit(previous: Option[String], prompt: String, start: Int = 0): IO[SessionSubmitFailure, SessionTurn] =
      configured match {
        case Some((runner, agent, context)) =>
          new ReplSession(runner, agent, context)
            .submit(ReplState(sessionId = previous, continuationStart = start), prompt, local)
            .flatMap(saved)
        case None => ZIO.fail(SessionSubmitFailure(YallmoError.InvalidConfig("No agent selected"), None))
      }
    def submitExecution(
        previous: Option[String],
        prompt: String,
        execution: AgentExecution[Any]
    ): IO[SessionSubmitFailure, SessionTurn] =
      local.submitPrompt(ReplState(sessionId = previous), prompt, execution).flatMap(saved)
    private def saved(turn: ReplTurn): IO[SessionSubmitFailure, SessionTurn] =
      load(turn.state.sessionId.get)
        .map(session => SessionTurn(session, turn.result, turn.newMessages))
        .mapError(SessionSubmitFailure(_, None))
  }
  private def participant(agentId: AgentId): Agent[Any] = new Agent[Any] {
    val id = agentId
    def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] = ZIO.dieMessage("Program only")
  }
  private def configuredSessions(
      master: Orchestrator[Any],
      author: AgentId,
      runner: AgentRunner[Any],
      agent: Option[AgentInstance[Any]],
      context: StaticContext[Any]
  ): SessionFixture =
    new SessionFixture(master, author, agent.map(_.id), Some((runner, agent, context)))
  private def sessionFixture(master: Orchestrator[Any], author: AgentId, selected: Option[AgentId]): SessionFixture =
    new SessionFixture(master, author, selected)
  private def configuredExecution(
      runner: AgentRunner[Any],
      agent: AgentInstance[Any],
      context: StaticContext[Any],
      start: Int
  ): AgentExecution[Any] =
    runner.execution(AgentContinuations.withCurrentContinuations(agent, start), context)

  private def orchestrator: IO[YallmoError, OrchestratorLive[Any]] = {
    def participant(agentId: AgentId): Agent[Any] = new Agent[Any] {
      override val id: AgentId = agentId
      override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
        ZIO.dieMessage("Only the configured program should run")
    }
    AgentCatalog
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
