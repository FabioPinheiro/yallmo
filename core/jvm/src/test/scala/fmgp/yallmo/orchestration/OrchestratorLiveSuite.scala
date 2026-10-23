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

  test("blob append validates registered authors and turns resolve stored attachments") {
    val input = MessagePayload.AudioAttachment(BlobId.fresh(), AudioFormat.Wav)
    val bytes = Chunk.fromArray("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8))
    val readerAgent = new Agent[Any] {
      val id = AgentId("reader")
      def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
        request.readBlob(request.input.head.blobIds.head).map { blob =>
          AgentTurnResponse(
            Chunk(MessagePayload.Assistant(new String(blob.bytes.toArray, java.nio.charset.StandardCharsets.UTF_8))),
            fmgp.yallmo.model.llm.LlmStopReason.Stop
          )
        }
    }
    val result = runZIO(for {
      instances <- RuntimeInstances.fromAgents(Chunk(readerAgent))
      orchestrator <- OrchestratorLive.inMemory(instances)
      id = SessionId.unsafe("blobs")
      _ <- orchestrator.createSession(id, Chunk(readerAgent.id))
      rejected <- orchestrator.appendWithBlobBytes(id, AgentId("unknown"), input, bytes).either
      _ <- orchestrator.appendWithBlobBytes(id, readerAgent.id, input, bytes)
      // Keep compatibility coverage for the deprecated blob-reader binding.
      response <- (orchestrator.turn(
        readerAgent.id,
        AgentTurnRequest("model", Chunk(input, MessagePayload.Instructions("Read")))
      ): @scala.annotation.nowarn("cat=deprecation"))
      blob <- orchestrator.readBlob(input.blobId)
    } yield (rejected, response, blob))
    assertEquals(result._1, Left(YallmoError.UnknownAgent(AgentId("unknown"))))
    assertEquals(result._2.output, Chunk(MessagePayload.Assistant("hello")))
    assertEquals(result._3.bytes, bytes)
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
