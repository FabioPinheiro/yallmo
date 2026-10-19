package fmgp.yallmo.repl.voice

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
  private val audio = AudioInput(Chunk[Byte](1, 2, 3), AudioFormat.Wav, 3L, Some(0.25), Some("local description"))

  private final class Fixture(response: AgentTurnResponse, registerHuman: Boolean = true) {
    val requests = scala.collection.mutable.ListBuffer.empty[AgentTurnRequest]
    val agent = new AudioAgent[Any] {
      val id = AgentId("configured-voice")
      def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] = ZIO.succeed {
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
    val secondAudio = audio.copy(bytes = Chunk[Byte](4, 5), sizeBytes = 2L)
    assertEquals(run(fixture.voice.transcribe(audio)), "transcript")
    assertEquals(run(fixture.voice.transcribe(secondAudio)), "transcript")
    assertEquals(
      fixture.requests.toList,
      List(audio, secondAudio).map(value =>
        AgentTurnRequest(
          settings.model,
          Chunk(value, MessagePayload.Instructions(settings.systemPrompt)),
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
    sessions.foreach { summary =>
      assert(summary.id.value.matches("transcription-[0-9a-f-]{36}"))
      val exchange = run(fixture.store.loadSession(summary.id))
      assertEquals(exchange.messages.map(_.agentId), Chunk(human, fixture.agent.id))
      assertEquals(exchange.messages.head.parentId, None)
      assertEquals(exchange.messages.last.parentId, Some(exchange.messages.head.id))
      assertEquals(exchange.messages.last.payload, MessagePayload.Assistant("transcript"))
      assert(exchange.messages.head.payload.asInstanceOf[AudioInput].bytes.isEmpty)
    }
    assertEquals(audio.bytes.size, 3)
  }

  test("calls, blank, unsupported payloads and non-stop results fail without another turn or retained result") {
    val call = MessagePayload.FunctionCallRequest(FunctionCall(FunctionCallId.unsafe("call"), "forbidden", "{}"))
    val responses = List(
      AgentTurnResponse(Chunk(call), LlmStopReason.ToolUse),
      AgentTurnResponse(Chunk(MessagePayload.Assistant("text"), call), LlmStopReason.Stop),
      AgentTurnResponse(Chunk(MessagePayload.Assistant("  ")), LlmStopReason.Stop),
      AgentTurnResponse(Chunk.empty, LlmStopReason.Stop),
      AgentTurnResponse(Chunk(MessagePayload.User("wrong role")), LlmStopReason.Stop),
      AgentTurnResponse(Chunk(audio), LlmStopReason.Stop),
      AgentTurnResponse(Chunk(MessagePayload.Assistant("partial")), LlmStopReason.Length),
      AgentTurnResponse(Chunk(MessagePayload.Assistant("blocked")), LlmStopReason.ContentFilter)
    )
    responses.foreach { response =>
      val fixture = new Fixture(response)
      assert(run(fixture.voice.transcribe(audio).either).isLeft)
      assertEquals(fixture.requests.size, 1)
      assertEquals(run(fixture.store.listSessions).size, 0)
    }
  }

  test("retention failure is explicit and never offers the transcript to the draft") {
    val fixture = new Fixture(
      AgentTurnResponse(Chunk(MessagePayload.Assistant("private transcript")), LlmStopReason.Stop),
      registerHuman = false
    )
    val result = run(fixture.voice.transcribe(audio).either)
    assert(result.left.toOption.exists(_.getMessage.contains("could not be retained")))
    assert(!result.left.toOption.get.getMessage.contains("private transcript"))
    assertEquals(fixture.requests.size, 1)
    assertEquals(run(fixture.store.listSessions).size, 0)
  }
}
