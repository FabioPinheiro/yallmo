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
          persisted <- replOrchestrator.persistTurn(
            created,
            turn(created, Chunk(Message.User("first"), Message.Assistant("answer")))
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

  test("resume rejects a session that does not include this REPL agent") {
    val root = Files.createTempDirectory("yallmo-repl-storage-")
    try {
      val result = runZIO {
        for {
          bob = agent(bobId)
          gamma = agent(gammaId)
          other = agent(otherAgent)
          orchestrator <- OrchestratorLive.filesystem(root, Chunk(bob, gamma, other))
          _ <- orchestrator.createSession(SessionId.unsafe("research"), Chunk(otherAgent))
          replOrchestrator = ReplOrchestratorLive.make(orchestrator, bob, gamma)
          resumed <- replOrchestrator.resumeReplSession("research").either
        } yield resumed
      }

      assertEquals(
        result,
        Left(YallmoError.AgentNotInSession(SessionId.unsafe("research"), gammaId))
      )
    } finally deleteRecursively(root)
  }

  test("the first turn creates a timestamped durable session when /new was not used") {
    val root = Files.createTempDirectory("yallmo-repl-storage-")
    try {
      val result = runZIO {
        for {
          replOrchestrator <- replOrchestratorAt(root)
          persisted <- replOrchestrator.persistTurn(ReplState(), turn(ReplState(), Chunk(Message.User("first"))))
          id <- ZIO.fromOption(persisted.state.sessionId).orElseFail(new AssertionError("missing session ID"))
          resumed <- replOrchestrator.resumeReplSession(id)
        } yield (id, resumed)
      }

      assert(result._1.matches("main-\\d{8}-\\d{6}"))
      assertEquals(result._2.runContext.messages, Chunk(Message.User("first")))
    } finally deleteRecursively(root)
  }

  private def replOrchestratorAt(root: Path): IO[YallmoError, ReplOrchestrator[Any]] = {
    val bob = agent(bobId)
    val gamma = agent(gammaId)
    OrchestratorLive
      .filesystem(root, Chunk(bob, gamma))
      .map(ReplOrchestratorLive.make(_, bob, gamma))
  }

  private def turn(state: ReplState, messages: Chunk[MessagePayload]): ReplTurn =
    ReplTurn(
      state.copy(runContext = RunContext(messages)),
      AgentResult(gammaId, "result"),
      messages
    )

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
