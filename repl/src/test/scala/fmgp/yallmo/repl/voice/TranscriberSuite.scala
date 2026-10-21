package fmgp.yallmo.repl.voice

import fmgp.yallmo.model.MessagePayload.AudioAttachment
import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.*
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.orchestration.config.ConfiguredAgentSettings
import munit.FunSuite
import zio.*

final class TranscriberSuite extends FunSuite {
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private val human = AgentId("human")
  private val settings = ConfiguredAgentSettings("explicit-audio-model", "configured voice instructions", None, true)
  private val audio = CapturedAudio(
    AudioAttachment(BlobId.fresh(), AudioFormat.Wav, Some("local description")),
    Chunk[Byte](1, 2, 3)
  )

  private final class Fixture(response: AgentTurnResponse, registerHuman: Boolean = true) {
    val requests = scala.collection.mutable.ListBuffer.empty[AgentTurnRequest]
    val agent = new AudioAgent[Any] {
      val id = AgentId("configured-voice")
      def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
        request.readBlob(request.input.head.blobIds.head).map { blob =>
          assert(blob.bytes.nonEmpty)
          requests += request
          response
        }
    }
    private val user = new Agent[Any] {
      val id = human
      def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
        ZIO.dieMessage("The human does not infer")
    }
    val store = run(
      OrchestratorLive.inMemory(
        run(
          RuntimeInstances.fromAgents(
            Chunk(agent) ++ (if registerHuman then Chunk(user) else Chunk.empty)
          )
        )
      )
    )
    val voice = Transcriber.make(agent, settings, store, human)
  }

  test(
    "each invocation is exactly one fresh current-audio then instructions turn with no capabilities or continuation"
  ) {
    val fixture = new Fixture(AgentTurnResponse(Chunk(MessagePayload.Assistant("  transcript  ")), LlmStopReason.Stop))
    val secondAudio = audio.copy(input = audio.input.copy(blobId = BlobId.fresh()), bytes = Chunk[Byte](4, 5))
    assertEquals(run(fixture.voice.transcribe(audio)), "transcript")
    assertEquals(run(fixture.voice.transcribe(secondAudio)), "transcript")
    assertEquals(
      fixture.requests.toList.map(_.copy(readBlob = Blob.unavailable)),
      List(audio, secondAudio).map(value =>
        AgentTurnRequest(
          settings.model,
          Chunk(value.input, MessagePayload.Instructions(settings.systemPrompt)),
          tools = Chunk.empty,
          toolChoice = ToolChoice.None,
          parallelToolCalls = false,
          options = ProviderGenerateOptions(responseMimeType = None)
        )
      )
    )
    val sessions = run(fixture.store.listSessions)
    assertEquals(sessions.size, 2)
    assertEquals(sessions.map(_.id).distinct.size, 2)
    val expectedBytes = List(audio, secondAudio).map(value => value.input.blobId -> value.bytes).toMap
    sessions.foreach { summary =>
      assert(summary.id.value.matches("transcription-[0-9a-f-]{36}"))
      val exchange = run(fixture.store.loadSession(summary.id))
      assertEquals(exchange.messages.map(_.agentId), Chunk(human, fixture.agent.id))
      assertEquals(exchange.messages.head.parentId, None)
      assertEquals(exchange.messages.last.parentId, Some(exchange.messages.head.id))
      assertEquals(exchange.messages.last.payload, MessagePayload.Assistant("transcript"))
      assertEquals(
        run(fixture.store.readBlob(exchange.messages.head.payload.blobIds.head)).bytes,
        expectedBytes(exchange.messages.head.payload.blobIds.head)
      )
    }
    assertEquals(audio.bytes.size, 3)
  }

  test("calls, blank, unsupported payloads and non-stop results fail without another turn or assistant transcript") {
    val call = MessagePayload.FunctionCallRequest(FunctionCall(FunctionCallId.unsafe("call"), "forbidden", "{}"))
    val responses = List(
      AgentTurnResponse(Chunk(call), LlmStopReason.ToolUse),
      AgentTurnResponse(Chunk(MessagePayload.Assistant("text"), call), LlmStopReason.Stop),
      AgentTurnResponse(Chunk(MessagePayload.Assistant("  ")), LlmStopReason.Stop),
      AgentTurnResponse(Chunk.empty, LlmStopReason.Stop),
      AgentTurnResponse(Chunk(MessagePayload.User("wrong role")), LlmStopReason.Stop),
      AgentTurnResponse(Chunk(audio.input), LlmStopReason.Stop),
      AgentTurnResponse(Chunk(MessagePayload.Assistant("partial")), LlmStopReason.Length),
      AgentTurnResponse(Chunk(MessagePayload.Assistant("blocked")), LlmStopReason.ContentFilter)
    )
    responses.foreach { response =>
      val fixture = new Fixture(response)
      assert(run(fixture.voice.transcribe(audio).either).isLeft)
      assertEquals(fixture.requests.size, 1)
      val summaries = run(fixture.store.listSessions)
      assertEquals(summaries.size, 1)
      assertEquals(run(fixture.store.loadSession(summaries.head.id)).messages.map(_.payload), Chunk(audio.input))
    }
  }

  test("storage failure is explicit and never offers the transcript to the draft") {
    val fixture = new Fixture(
      AgentTurnResponse(Chunk(MessagePayload.Assistant("private transcript")), LlmStopReason.Stop),
      registerHuman = false
    )
    val result = run(fixture.voice.transcribe(audio).either)
    assert(result.left.toOption.exists(_.getMessage.contains("could not be stored")))
    assert(!result.left.toOption.get.getMessage.contains("private transcript"))
    assertEquals(fixture.requests.size, 0)
    assertEquals(run(fixture.store.listSessions).size, 0)
  }
}
