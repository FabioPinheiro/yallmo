package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import munit.FunSuite
import zio.*

final class OrchestratorLiveSuite extends FunSuite {
  test("rejects unregistered session agents before creating a session") {
    val known = agentId
    val unknown = AgentId("unknown")

    val result = runZIO {
      for {
        instances <- RuntimeInstances.fromAgents(Chunk(agent))
        orchestrator <- OrchestratorLive.inMemory(instances)
        result <- orchestrator.createSession(SessionId.unsafe("main"), Chunk(known, unknown)).either
      } yield result
    }

    assertEquals(result, Left(YallmoError.UnknownAgent(unknown)))
  }

  test("persists messages through the application-facing orchestrator API") {
    val session = runZIO {
      for {
        instances <- RuntimeInstances.fromAgents(Chunk(agent))
        orchestrator <- OrchestratorLive.inMemory(instances)
        _ <- orchestrator.createSession(SessionId.unsafe("main"), Chunk(agentId))
        appended <- orchestrator.append(SessionId.unsafe("main"), agentId, Chunk(Message.User("hello")))
      } yield appended
    }

    assertEquals(session.agentIds, Chunk(agentId))
    assertEquals(session.messages.map(_.payload), Chunk(Message.User("hello")))
  }

  test("explicit runtime registration rejects duplicate IDs before orchestrator creation") {
    val result = runZIO {
      (for {
        instances <- RuntimeInstances.fromAgents(Chunk(agent, agent))
        orchestrator <- OrchestratorLive.inMemory(instances)
      } yield orchestrator).either
    }
    assert(result.swap.toOption.exists(_.isInstanceOf[YallmoError.InvalidConfig]))
  }

  private val agentId = AgentId("agent")
  private val agent = new Agent[Any] {
    override val id: AgentId = agentId

    override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
      ZIO.die(new AssertionError("The storage test agent must not be invoked."))
  }

  private def runZIO[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
