package fmgp.yallmo.server.application

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse, LlmStopReason}
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.orchestration.config.{AgentCapabilities, HasAgentCapabilities, ProviderId}
import munit.FunSuite
import zio.*

import scala.concurrent.Future

final class ConfiguredAgentBindingSuite extends FunSuite {
  private val agentId = AgentId("configured")
  private val blob = Blob(BlobId.fresh(), Chunk[Byte](1, 2, 3))
  private val input = Chunk[MessagePayload](
    MessagePayload.User("Review this"),
    MessagePayload.AudioAttachment(blob.id, AudioFormat.Wav)
  )
  private val context = StaticContext[Any](
    "System",
    tools = Chunk("shared", "program-only", "adapter-only").map(key => AgentToolDefinition(key, key))
  )
  private val readBlob: BlobId => IO[YallmoError, Blob] = id =>
    if id == blob.id then ZIO.succeed(blob) else ZIO.fail(YallmoError.BlobMissing(id))

  test("native binding retains configured capabilities and supplies the storage blob reader") {
    runZIO {
      for {
        requests <- Ref.make(Chunk.empty[AgentTurnRequest])
        adapter = new Agent[Any] with HasAgentCapabilities {
          override val id: AgentId = agentId
          override val capabilities: AgentCapabilities = AgentCapabilities(tools = Some(Set("shared", "adapter-only")))
          override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
            requests
              .update(_ :+ request)
              .as(
                AgentTurnResponse(Chunk(MessagePayload.Assistant("Native answer")), LlmStopReason.Stop)
              )
        }
        program = NativeFunctionAgent[Agent[Any]](
          agentId,
          "native-model",
          capabilities = AgentCapabilities(tools = Some(Set("shared", "program-only")))
        )
        binding = ConfiguredAgentBinding.Native(adapter, ProviderId.unsafe("local"), program, "System")
        bound = binding.bind[Any](readBlob)
        configured <- bound.configuredContext(context)
        turn <- bound.next(AgentTask("Review this"), context, RunContext(input))
        request <- requests.get.map(_.head)
        stored <- request.readBlob(blob.id)
      } yield {
        assertEquals(binding.adapter, adapter)
        assertEquals(binding.systemPrompt, "System")
        assertEquals(bound.id, agentId)
        assertEquals(bound.capabilities.tools, Some(Set("shared")))
        assertEquals(configured.tools.map(_.key), Chunk("shared"))
        assertEquals(request.tools.map(_.name), Chunk("shared"))
        assertEquals(request.model, "native-model")
        assertEquals(request.input, input :+ MessagePayload.Instructions("System"))
        assertEquals(stored, blob)
        assertEquals(turn.decision, AgentDecision.FinalAnswer("Native answer"))
      }
    }
  }

  test("folder binding retains policy and passes input and blob reader without native generation settings") {
    runZIO {
      for {
        requests <- Ref.make(Chunk.empty[AgentTurnRequest])
        adapter = new Agent[Any] {
          override val id: AgentId = agentId
          override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
            requests
              .update(_ :+ request)
              .as(
                AgentTurnResponse(
                  Chunk(MessagePayload.Assistant(" Folder"), MessagePayload.Assistant(" answer ")),
                  LlmStopReason.Stop
                )
              )
        }
        policy = AgentCapabilities(tools = Some(Set.empty))
        binding = ConfiguredAgentBinding.Folder(adapter, policy)
        bound = binding.bind[Any](readBlob)
        configured <- bound.configuredContext(context)
        turn <- bound.next(AgentTask("Review this"), context, RunContext(input))
        request <- requests.get.map(_.head)
        stored <- request.readBlob(blob.id)
      } yield {
        assertEquals(binding.systemPrompt, "")
        assertEquals(bound.id, agentId)
        assertEquals(bound.capabilities, policy)
        assertEquals(configured.tools, Chunk.empty)
        assertEquals(request.model, "")
        assertEquals(request.toolChoice, ToolChoice.None)
        assertEquals(request.tools, Chunk.empty)
        assertEquals(request.input, input)
        assertEquals(stored, blob)
        assertEquals(turn.assistant, Some(MessagePayload.Assistant(" Folder answer ")))
        assertEquals(turn.decision, AgentDecision.FinalAnswer(" Folder answer "))
      }
    }
  }

  test("folder binding rejects empty, blank, non-text and mixed output") {
    val invalid = Chunk(
      Chunk.empty[MessagePayload],
      Chunk[MessagePayload](MessagePayload.Assistant(" \n\t")),
      Chunk[MessagePayload](MessagePayload.User("Not an assistant answer")),
      Chunk[MessagePayload](MessagePayload.Assistant("Answer"), MessagePayload.Context("extra", "Not text output"))
    )
    runZIO {
      ZIO.foreachDiscard(invalid) { output =>
        val adapter = new Agent[Any] {
          override val id: AgentId = agentId
          override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
            ZIO.succeed(AgentTurnResponse(output, LlmStopReason.Stop))
        }
        ConfiguredAgentBinding
          .Folder(adapter, AgentCapabilities())
          .bind[Any](readBlob)
          .next(AgentTask("Review this"), context, RunContext(input))
          .either
          .map(result =>
            assertEquals(
              result,
              Left(YallmoError.InvalidAgentDecision("FolderStructureAgent must return a non-blank text answer"))
            )
          )
      }
    }
  }

  private def runZIO[E, A](effect: ZIO[Any, E, A]): Future[A] =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.runToFuture(effect.mapError(error => FiberFailure(Cause.fail(error))))
    }
}
