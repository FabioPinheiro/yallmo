package fmgp.yallmo.repl

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import fmgp.yallmo.orchestration.*
import java.nio.file.{Files, Path}
import java.util.Comparator
import munit.FunSuite
import zio.*

final class ReplOrchestratorSuite extends FunSuite {
  test("a named /new session is durable before its first turn and can be resumed") {
    val root = Files.createTempDirectory("yallmo-repl-storage-")
    try {
      val result = runZIO {
        for {
          replOrchestrator <- replOrchestratorAt(root)
          created <- replOrchestrator.newReplSession(Some("research"))
          persisted <- replOrchestrator.submitPrompt(
            created,
            "first",
            execution(gammaId, Chunk(Message.Assistant("answer")))
          )
          listed <- replOrchestrator.listReplSessionIds
          loaded <- replOrchestrator.loadSession(SessionId.unsafe("research"))
          resumed <- replOrchestrator.resumeReplSession("research")
        } yield (created, persisted, listed, loaded, resumed)
      }

      assertEquals(result._1.sessionId, Some("research"))
      assertEquals(result._2.state.sessionId, Some("research"))
      assertEquals(result._3, Chunk("research"))
      assertEquals(result._4.agentIds, Chunk(bobId, gammaId))
      assertEquals(result._4.messages.map(_.agentId), Chunk(bobId, gammaId))
      assertEquals(
        result._5.runContext.messages,
        Chunk(Message.User("first"), Message.Assistant("answer"))
      )
      assertEquals(result._5.sessionId, Some("research"))
    } finally deleteRecursively(root)
  }

  test("resume is independent of the selected agent; new authors join only when messages are persisted") {
    val root = Files.createTempDirectory("yallmo-repl-storage-")
    try {
      val result = runZIO {
        for {
          bob = agent(bobId)
          gamma = agent(gammaId)
          other = agent(otherAgent)
          instances <- RuntimeInstances.fromAgents(Chunk(bob, gamma, other))
          orchestrator <- OrchestratorLive.filesystem(root, instances)
          _ <- orchestrator.createSession(SessionId.unsafe("research"), Chunk(otherAgent))
          replOrchestrator = ReplOrchestratorLive.make(orchestrator, bob, Some(gamma))
          resumed <- replOrchestrator.resumeReplSession("research")
          before <- orchestrator.loadSession(SessionId.unsafe("research"))
          persisted <- replOrchestrator.submitPrompt(
            resumed,
            "next",
            execution(gammaId, Chunk(Message.Assistant("answer")))
          )
          after <- orchestrator.loadSession(SessionId.unsafe("research"))
        } yield (resumed, before, persisted, after)
      }

      assertEquals(result._1.sessionId, Some("research"))
      assertEquals(result._2.agentIds, Chunk(otherAgent), "resuming must not change session participants")
      assertEquals(result._3.state.sessionId, Some("research"))
      assertEquals(result._4.agentIds, Chunk(otherAgent, bobId, gammaId))
      assertEquals(result._4.messages.map(_.agentId), Chunk(bobId, gammaId))
    } finally deleteRecursively(root)
  }

  test("in-memory sessions admit registered authors idempotently without changing earlier authors or session IDs") {
    val result = runZIO {
      for {
        human = agent(bobId)
        first = agent(gammaId)
        next = agent(otherAgent)
        instances <- RuntimeInstances.fromAgents(Chunk(human, first, next))
        orchestrator <- OrchestratorLive.inMemory(instances)
        original = ReplOrchestratorLive.make(orchestrator, human, Some(first))
        created <- original.newReplSession(Some("shared"))
        firstTurn <- original.submitPrompt(created, "first", execution(gammaId, Chunk(Message.Assistant("answer"))))
        switched = ReplOrchestratorLive.make(orchestrator, human, Some(next))
        _ <- switched.submitPrompt(firstTurn.state, "next", execution(otherAgent, Chunk(Message.Assistant("reply"))))
        _ <- orchestrator.addSessionAgent(SessionId.unsafe("shared"), otherAgent)
        rejected <- orchestrator.addSessionAgent(SessionId.unsafe("shared"), AgentId("unknown")).either
        loaded <- orchestrator.loadSession(SessionId.unsafe("shared"))
        sessions <- orchestrator.listSessions
      } yield (loaded, rejected, sessions)
    }
    assertEquals(result._1.id, SessionId.unsafe("shared"))
    assertEquals(result._1.agentIds, Chunk(bobId, gammaId, otherAgent))
    assertEquals(result._1.messages.map(_.agentId), Chunk(bobId, gammaId, bobId, otherAgent))
    assertEquals(result._2, Left(YallmoError.UnknownAgent(AgentId("unknown"))))
    assertEquals(result._3.map(_.id.value), Chunk("shared"))
  }

  test("the first turn creates a timestamped durable session when /new was not used") {
    val root = Files.createTempDirectory("yallmo-repl-storage-")
    try {
      val result = runZIO {
        for {
          replOrchestrator <- replOrchestratorAt(root)
          persisted <- replOrchestrator.submitPrompt(ReplState(), "first", execution(gammaId, Chunk.empty))
          id <- ZIO.fromOption(persisted.state.sessionId).orElseFail(new AssertionError("missing session ID"))
          resumed <- replOrchestrator.resumeReplSession(id)
        } yield (id, resumed)
      }

      assert(result._1.matches("main-\\d{8}-\\d{6}"))
      assertEquals(result._2.runContext.messages, Chunk(Message.User("first")))
    } finally deleteRecursively(root)
  }

  test("unselected submission rejects execution before creating or partially writing a session") {
    val result = runZIO {
      for {
        human = agent(bobId)
        instances <- RuntimeInstances.fromAgents(Chunk(human))
        orchestrator <- OrchestratorLive.inMemory(instances)
        repl = ReplOrchestratorLive.make(orchestrator, human, None)
        rejected <- repl
          .submitPrompt(ReplState(), "hello", execution(gammaId, Chunk(Message.Assistant("not selected"))))
          .either
        sessions <- repl.listSessions
      } yield (rejected, sessions)
    }
    assert(result._1.swap.toOption.exists(_.error.isInstanceOf[YallmoError.InvalidConfig]))
    assertEquals(result._1.swap.toOption.flatMap(_.acceptedSession), None)
    assertEquals(result._2, Chunk.empty)
  }

  private def replOrchestratorAt(root: Path): IO[YallmoError, ReplOrchestrator[Any]] = {
    val bob = agent(bobId)
    val gamma = agent(gammaId)
    for {
      instances <- RuntimeInstances.fromAgents(Chunk(bob, gamma))
      orchestrator <- OrchestratorLive.filesystem(root, instances)
    } yield ReplOrchestratorLive.make(orchestrator, bob, Some(gamma))
  }

  private def execution(id: AgentId, response: Chunk[MessagePayload]): AgentExecution[Any] =
    new AgentExecution[Any] {
      override val agentId: AgentId = id
      override def run(task: AgentTask, acceptedContext: RunContext): IO[YallmoError, AgentTurnResult] =
        ZIO.succeed(AgentTurnResult(AgentResult(id, "result"), acceptedContext.appendAll(response)))
    }

  private val bobId = AgentId("bob")
  private val gammaId = AgentId("Gamma")
  private val otherAgent = AgentId("other")

  private def agent(agentId: AgentId): Agent[Any] = new Agent[Any] {
    override val id: AgentId = agentId

    override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
      ZIO.die(new AssertionError("The persistence test agent must not be invoked."))
  }

  private def runZIO[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def deleteRecursively(root: Path): Unit =
    val paths = Files.walk(root)
    try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
    finally paths.close()
}
