package fmgp.yallmo.provider.oai

import fmgp.yallmo.LlmProviderPayloadDebug
import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.*
import munit.FunSuite
import zio.*
import zio.http.*

import java.nio.file.Files
import java.util.Base64
import scala.compiletime.testing.typeCheckErrors

final class OAIAudioTurnSuite extends FunSuite {
  private val audio = AudioInput(Chunk[Byte](12, 34, 56, 78), AudioFormat.Wav, 4L, Some(1.25), Some("LOCAL_ONLY"))
  private val encoded = Base64.getEncoder.encodeToString(audio.bytes.toArray)
  private val request = AgentTurnRequest(
    "explicit-audio-model",
    Chunk(MessagePayload.User("Describe the sound"), audio, MessagePayload.Instructions("Be helpful")),
    tools = Chunk(FunctionToolDefinition("inspect", "Inspect", JsonSchema.ObjectSchema(Map.empty))),
    toolChoice = ToolChoice.Required,
    parallelToolCalls = true,
    options = ProviderGenerateOptions(temperature = Some(0.3), maxOutputTokens = Some(50))
  )

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def fakeClient(respond: Request => Task[Response]): Client =
    Client.fromDriver(new ZClient.Driver[Any, Scope, Throwable] {
      override def request(
          version: Version,
          method: Method,
          url: URL,
          headers: Headers,
          body: Body,
          sslConfig: Option[ClientSSLConfig],
          proxy: Option[Proxy]
      )(using Trace): Task[Response] = {
        val _ = (sslConfig, proxy)
        respond(Request(version = version, method = method, url = url, headers = headers, body = body))
      }
      override def socket[Env1 <: Any](
          version: Version,
          url: URL,
          headers: Headers,
          app: WebSocketApp[Env1]
      )(using Trace, Scope =:= Scope): ZIO[Env1, Throwable, Response] = {
        val _ = (version, url, headers, app)
        ZIO.dieMessage("Unexpected WebSocket request")
      }
    })

  private def provider(client: Client): OAILlmProvider =
    OAILlmProvider(client, OAIConfig("http://offline.invalid/v1", "default-model"))

  test("audio agent sends original bytes, retains general turn semantics, and captures only redacted wire JSON") {
    val client = fakeClient(incoming =>
      incoming.body.asString.flatMap(body =>
        ZIO.attempt {
          val wire = ujson.read(body)
          assertEquals(wire("model").str, request.model)
          assert(wire("messages")(1)("content")(0)("input_audio")("data").str == encoded)
          assertEquals(wire("messages")(1)("content")(0)("input_audio")("format").str, "wav")
          assertEquals(wire("messages")(0)("content").str, "Describe the sound")
          assertEquals(wire("tools")(0)("function")("name").str, "inspect")
          assertEquals(wire("tool_choice").str, "required")
          assertEquals(wire("parallel_tool_calls").bool, true)
          assertEquals(wire("temperature").num, 0.3)
          assertEquals(wire("max_tokens").num, 50.0)
          assert(!body.contains("LOCAL_ONLY"))
          assert(!body.contains("durationSeconds"))
          Response.text(
            """{"id":"audio-response","choices":[{"message":{"content":"A bell","tool_calls":[{"id":"call-1","function":{"name":"inspect","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
          )
        }
      )
    )
    val agent: AudioAgent[Any] = OAIAgent.make(AgentId("audio"), provider(client))
    val (response, path) = run(LlmProviderPayloadDebug.withRequestCapture(agent.turn(request)))
    try {
      assertEquals(response.responseId, Some("audio-response"))
      assertEquals(response.stopReason, LlmStopReason.ToolUse)
      assertEquals(response.output.head, MessagePayload.Assistant("A bell"))
      assert(response.output(1).isInstanceOf[MessagePayload.FunctionCallRequest])
      val captured = Files.readString(path.get)
      assert(!captured.contains(encoded))
      assertEquals(ujson.read(captured)("payload")("messages")(1)("content")(0)("input_audio")("data").str, "")
      assert(audio.bytes.nonEmpty)
    } finally
      path.foreach { requestPath =>
        Files.deleteIfExists(requestPath)
        Files.deleteIfExists(
          requestPath.resolveSibling(requestPath.getFileName.toString.replace("-request", "-response"))
        )
      }
  }

  test("prompt-based audio inference preserves explicit model selection without agent turns") {
    val client = fakeClient(incoming =>
      incoming.body.asString.flatMap(body =>
        ZIO.attempt {
          val wire = ujson.read(body)
          assertEquals(wire("model").str, "chosen-model")
          assertEquals(wire("tool_choice").str, "none")
          assertEquals(wire("parallel_tool_calls").bool, false)
          assert(!wire.obj.contains("tools"))
          assertEquals(wire("messages")(1)("content").str, "Describe")
          assert(wire("messages")(0)("content")(0)("input_audio")("data").str == encoded)
          Response.text("""{"choices":[{"message":{"content":"A bell"},"finish_reason":"stop"}]}""")
        }
      )
    )
    val (transcript, capture) = run(
      LlmProviderPayloadDebug.withRequestCapture(
        provider(client).inferenceWithAudio("Describe", audio, Some("chosen-model"))
      )
    )
    try {
      assertEquals(transcript, "A bell")
      val captured = Files.readString(capture.get)
      assert(!captured.contains(encoded))
      assertEquals(ujson.read(captured)("payload")("messages")(0)("content")(0)("input_audio")("data").str, "")
      assert(audio.bytes.nonEmpty)
    } finally capture.foreach(Files.deleteIfExists)
    val offline = provider(fakeClient(_ => ZIO.dieMessage("Invalid requests must not be sent")))
    assert(run(offline.inferenceWithAudio(" ", audio).either).isLeft)
    assert(run(offline.inferenceWithAudio("Describe", audio, Some(" ")).either).isLeft)
  }

  test("prompt-based audio inference rejects tool-call and empty responses") {
    val responses = Seq(
      """{"choices":[{"message":{"content":"Do not return","tool_calls":[{"id":"call-1","function":{"name":"inspect","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
      """{"choices":[{"message":{"content":"  "},"finish_reason":"stop"}]}"""
    )
    responses.foreach { response =>
      val connection = provider(fakeClient(_ => ZIO.succeed(Response.text(response))))
      assert(run(connection.inferenceWithAudio("Describe", audio).either).isLeft)
    }
  }

  test("agent and prompt-based audio inference reject restored/empty and oversized recordings before HTTP") {
    val connection = provider(fakeClient(_ => ZIO.dieMessage("Invalid audio must not be sent")))
    val agent = OAIAgent.make(AgentId("audio"), connection)
    val invalid = Seq(audio.redacted, audio.copy(bytes = Chunk.fill(OAILlmProvider.MaxAudioBytes + 1)(1.toByte)))
    invalid.foreach { input =>
      val turn = request.copy(input = Chunk(input, MessagePayload.Instructions("Describe")))
      val result = run(agent.turn(turn).either)
      assert(result.swap.toOption.exists(_.isInstanceOf[YallmoError.InvalidConfig]))
      assert(run(connection.inferenceWithAudio("Describe", input).either).isLeft)
    }
    assertEquals(
      OAILlmProvider.validateAudio(audio.copy(bytes = Chunk.fill(OAILlmProvider.MaxAudioBytes)(1.toByte))).isRight,
      true
    )
  }

  test("audio HTTP, malformed response and transport errors never echo a recording") {
    val clients = Seq(
      fakeClient(_ => ZIO.succeed(Response.text(encoded).status(Status.BadRequest))),
      fakeClient(_ => ZIO.succeed(Response.text(encoded))),
      fakeClient(_ => ZIO.fail(new IllegalStateException(encoded))),
      fakeClient(_ => ZIO.fail(YallmoError.HttpFailure(encoded)))
    )
    clients.foreach { client =>
      val connection = provider(client)
      Seq(
        OAIAgent.make(AgentId("audio"), connection).turn(request),
        connection.inferenceWithAudio("Describe", audio)
      ).foreach { effect =>
        val failure = run(effect.either).swap.toOption.get
        assert(!failure.toString.contains(encoded))
        assert(!Option(failure.getCause).exists(_.toString.contains(encoded)))
      }
    }
  }

  test("audio shared turns and legacy convenience calls retain the two-minute request timeout") {
    run {
      for {
        started <- Promise.make[Nothing, Unit]
        clock: Clock = new Clock {
          export Clock.ClockLive.{currentTime, currentDateTime, instant, javaClock, localDateTime, nanoTime, scheduler}
          override def sleep(duration: => Duration)(using Trace): UIO[Unit] =
            ZIO.succeed(assertEquals(duration, 2.minutes)) *> started.await
        }
        connection = provider(fakeClient(_ => started.succeed(()).unit *> ZIO.never))
        named <- OAIAgent.make(AgentId("audio"), connection).turn(request).withClock(clock).either
        legacy <- connection.inferenceWithAudio("Describe", audio).withClock(clock).either
      } yield Seq(named, legacy).foreach(result =>
        assert(result.swap.toOption.exists(_.getMessage.contains("timed out")))
      )
    }
  }

  test("audio provider capability exposes prompt inference, not agent turns") {
    val errors = typeCheckErrors("""
      def call(
          provider: fmgp.yallmo.model.llm.LlmProviderAudio,
          request: fmgp.yallmo.model.llm.AgentTurnRequest
      ) = provider.turn(request)
    """)
    assertEquals(errors.size, 1)
    assert(errors.head.message.contains("turn"))
    val concreteErrors = typeCheckErrors("""
      def call(
          provider: fmgp.yallmo.provider.oai.OAILlmProvider,
          request: fmgp.yallmo.model.llm.AgentTurnRequest
      ) = provider.completeTurn(request)
    """)
    assertEquals(concreteErrors.size, 1)
    assert(concreteErrors.head.message.contains("completeTurn"))
    val concreteTurnErrors = typeCheckErrors("""
      def call(
          provider: fmgp.yallmo.provider.oai.OAILlmProvider,
          request: fmgp.yallmo.model.llm.AgentTurnRequest
      ) = provider.turn(request)
    """)
    assertEquals(concreteTurnErrors.size, 1)
    assert(concreteTurnErrors.head.message.contains("turn"))
    val provider = new LlmProviderAudio {
      def inferenceWithAudio(prompt: String, audio: AudioInput, model: Option[String]): IO[YallmoError, String] =
        ZIO.succeed(prompt)
    }
    assertEquals(run(provider.inferenceWithAudio("Describe", audio)), "Describe")
  }

  test("shared audio response mapping preserves all stop reasons") {
    Seq(
      "stop" -> LlmStopReason.Stop,
      "length" -> LlmStopReason.Length,
      "content_filter" -> LlmStopReason.ContentFilter,
      "custom" -> LlmStopReason.Unknown("custom")
    ).foreach { case (reason, expected) =>
      val parsed = OAIChatCompletionsMapper
        .parseResponse(s"""{"id":"response","choices":[{"message":{"content":"sound"},"finish_reason":"$reason"}]}""")
        .flatMap(OAIChatCompletionsMapper.agentTurnResponse)
      assertEquals(parsed.map(_.stopReason), Right(expected))
      assertEquals(parsed.map(_.responseId), Right(Some("response")))
    }
  }
}
