package fmgp.yallmo.repl

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import fmgp.yallmo.orchestration.*
import java.nio.file.{Files, Path}
import java.util.Comparator
import munit.ZSuite
import zio.*

final class ReplOrchestratorSuite extends ZSuite {
  private val bobId = AgentId("bob")
  private val gammaId = AgentId("Gamma")
  private val otherAgent = AgentId("other")

  testZ("a named /new session is durable before its first turn and can be resumed") {
    ZIO.acquireReleaseWith(ZIO.attempt(Files.createTempDirectory("yallmo-repl-storage-")))(root =>
      ZIO.attempt(deleteRecursively(root)).orDie
    ) { root =>
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
      } yield {
        assertEquals(created.sessionId, Some("research"))
        assertEquals(persisted.state.sessionId, Some("research"))
        assertEquals(listed, Chunk("research"))
        assertEquals(loaded.agentIds, Chunk(bobId, gammaId))
        assertEquals(loaded.messages.map(_.agentId), Chunk(bobId, gammaId))
        assertEquals(
          resumed.runContext.messages,
          Chunk(Message.User("first"), Message.Assistant("answer"))
        )
        assertEquals(resumed.sessionId, Some("research"))
      }
    }
  }

  testZ("resume is independent of the selected agent; new authors join only when messages are persisted") {
    ZIO.acquireReleaseWith(ZIO.attempt(Files.createTempDirectory("yallmo-repl-storage-")))(root =>
      ZIO.attempt(deleteRecursively(root)).orDie
    ) { root =>
      val bob = agent(bobId)
      val gamma = agent(gammaId)
      val other = agent(otherAgent)
      for {
        instances <- AgentCatalog.fromAgents(Chunk(bob, gamma, other))
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
      } yield {
        assertEquals(resumed.sessionId, Some("research"))
        assertEquals(before.agentIds, Chunk(otherAgent), "resuming must not change session participants")
        assertEquals(persisted.state.sessionId, Some("research"))
        assertEquals(after.agentIds, Chunk(otherAgent, bobId, gammaId))
        assertEquals(after.messages.map(_.agentId), Chunk(bobId, gammaId))
      }
    }
  }

  testZ("in-memory sessions admit registered authors idempotently without changing earlier authors or session IDs") {
    val human = agent(bobId)
    val first = agent(gammaId)
    val next = agent(otherAgent)
    for {
      instances <- AgentCatalog.fromAgents(Chunk(human, first, next))
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
    } yield {
      assertEquals(loaded.id, SessionId.unsafe("shared"))
      assertEquals(loaded.agentIds, Chunk(bobId, gammaId, otherAgent))
      assertEquals(loaded.messages.map(_.agentId), Chunk(bobId, gammaId, bobId, otherAgent))
      assertEquals(rejected, Left(YallmoError.UnknownAgent(AgentId("unknown"))))
      assertEquals(sessions.map(_.id.value), Chunk("shared"))
    }
  }

  private def replOrchestratorAt(root: Path): IO[YallmoError, ReplOrchestrator[Any]] = {
    val bob = agent(bobId)
    val gamma = agent(gammaId)
    for {
      instances <- AgentCatalog.fromAgents(Chunk(bob, gamma))
      orchestrator <- OrchestratorLive.filesystem(root, instances)
    } yield ReplOrchestratorLive.make(orchestrator, bob, Some(gamma))
  }

  private def execution(agentId: AgentId, response: Chunk[MessagePayload]): AgentExecution[Any] =
    new AgentRunner[Any](Map.empty).execution(
      new AgentInstance[Any] {
        override val id: AgentId = agentId
        override def next(
            task: AgentTask,
            context: StaticContext[Any],
            current: RunContext
        ): IO[YallmoError, AgentTurn] =
          ZIO.succeed(AgentTurn(None, AgentDecision.FinalAnswer("result"), response))
      },
      StaticContext[Any]("Test")
    )

  private def agent(agentId: AgentId): Agent[Any] = new Agent[Any] {
    override val id: AgentId = agentId

    override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
      ZIO.die(new AssertionError("The persistence test agent must not be invoked."))
  }

  private def deleteRecursively(root: Path): Unit =
    val paths = Files.walk(root)
    try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
    finally paths.close()
}
