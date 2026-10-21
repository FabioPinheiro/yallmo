package fmgp.yallmo.provider.oai

import fmgp.yallmo.model.MessagePayload.AudioAttachment
import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.ProviderGenerateOptions
import fmgp.yallmo.model.llm.*
import munit.FunSuite
import zio.Chunk

final class OAISuite extends FunSuite:
  test("builds an OpenAI-compatible chat-completions request") {
    val tool = LlmTool[Any](
      LlmToolDefinition(
        "current_time",
        "Get the current time",
        Chunk(LlmToolParameter("timezone", "IANA timezone", ToolSchemaType.String, required = true))
      ),
      _ => throw AssertionError("The request builder must not run tools")
    )
    val options = ProviderGenerateOptions(
      systemInstruction = Some("Return JSON."),
      temperature = Some(0.2),
      topP = Some(0.9),
      maxOutputTokens = Some(64),
      stopSequences = Chunk("END"),
      responseMimeType = Some("application/json"),
      seed = Some(42)
    )

    val body = OAIChatCompletionsMapper.requestBody(
      "ai/gemma4:E4B",
      OAIChatCompletionsMapper.initialMessages("What time is it?", options),
      Chunk(tool),
      options
    )

    assertEquals(body("model").str, "ai/gemma4:E4B")
    assertEquals(body("messages").arr.length, 2)
    assertEquals(body("response_format")("type").str, "json_object")
    assertEquals(body("tools")(0)("function")("name").str, "current_time")
    assertEquals(body("tools")(0)("function")("parameters")("required")(0).str, "timezone")
  }

  test("parses text and OpenAI tool calls") {
    val response = OAIChatCompletionsMapper.parseResponse(
      """{
        |  "id": "chatcmpl-1",
        |  "usage": {"prompt_tokens": 8, "completion_tokens": 3, "prompt_tokens_details": {"cached_tokens": 2}},
        |  "choices": [{
        |    "message": {
        |      "role": "assistant",
        |      "content": "working",
        |      "tool_calls": [{
        |        "id": "call-1",
        |        "type": "function",
        |        "function": {"name": "current_time", "arguments": "{}"}
        |      }]
        |    },
        |    "finish_reason": "tool_calls"
        |  }]
        |}""".stripMargin
    )

    assertEquals(response.map(_.content), Right(Some("working")))
    assertEquals(response.map(_.responseId), Right(Some("chatcmpl-1")))
    assertEquals(
      response.toOption.flatMap(OAIChatCompletionsMapper.agentTurnResponse(_).toOption.map(_.stopReason)),
      Some(LlmStopReason.ToolUse)
    )
    assertEquals(response.map(_.functionCalls), Right(Vector(OAIFunctionCall("call-1", "current_time", "{}"))))
  }

  test("maps native calls, outputs, tool choice, and schemas to chat completions") {
    val call = FunctionCall(FunctionCallId.unsafe("call-1"), "current_time", "{\"timezone\":\"UTC\"}")
    val output = FunctionCallOutput(call.id, call.name, "12:00")
    val request = AgentTurnRequest(
      "test",
      Chunk(
        MessagePayload.Assistant("Checking."),
        MessagePayload.FunctionCallRequest(call),
        MessagePayload.FunctionCallOutput(output),
        MessagePayload.Instructions("System")
      ),
      tools = Chunk(
        FunctionToolDefinition(
          "current_time",
          "Get time",
          JsonSchema.ObjectSchema(Map("timezone" -> JsonSchema.StringSchema()), required = Set("timezone"))
        )
      ),
      toolChoice = ToolChoice.Function("current_time"),
      parallelToolCalls = true
    )

    val body = OAIChatCompletionsMapper.turnRequestBody("test", request)
    val messages = body("messages").arr
    assertEquals(messages(0)("role").str, "assistant")
    assertEquals(messages(0)("tool_calls")(0)("id").str, "call-1")
    assertEquals(messages(1)("role").str, "tool")
    assertEquals(messages(1)("tool_call_id").str, "call-1")
    assertEquals(messages(2)("role").str, "system")
    assertEquals(body("tool_choice")("function")("name").str, "current_time")
    assertEquals(body("parallel_tool_calls").bool, true)
    assertEquals(body("tools")(0)("function")("parameters")("required")(0).str, "timezone")
  }

  test("maps audio in ordered shared turns without sending local metadata") {
    val audio = AudioAttachment(fmgp.yallmo.model.BlobId.fresh(), AudioFormat.Wav, Some("LOCAL_ONLY"))
    val request = AgentTurnRequest(
      "audio-model",
      Chunk(
        MessagePayload.User("Identify the sound"),
        audio,
        MessagePayload.Instructions("Describe it.")
      ),
      toolChoice = ToolChoice.None
    )
    val body = OAIChatCompletionsMapper.turnRequestBody(request.model, request, Map(audio.blobId -> "AQID"))
    val content = body("messages")(1)("content").arr

    assertEquals(body("model").str, "audio-model")
    assertEquals(body("messages")(0)("content").str, "Identify the sound")
    assertEquals(body("messages")(2)("content").str, "Describe it.")
    assertEquals(body("tool_choice").str, "none")
    val serialized = ujson.write(body)
    Seq("LOCAL_ONLY", "999", "12.5", "sizeBytes", "durationSeconds", "description").foreach(value =>
      assert(!serialized.contains(value))
    )
    assertEquals(content(0)("type").str, "input_audio")
    assertEquals(content(0)("input_audio")("format").str, "wav")
    assertEquals(content(0)("input_audio")("data").str, "AQID")
  }

  test("validates audio transcription input and response text") {
    val bytes = Chunk[Byte](1)
    assertEquals(OAILlmProvider.validateAudio(bytes), Right(bytes))
    assert(OAILlmProvider.validateAudio(Chunk.empty).isLeft)
    assert(
      OAILlmProvider
        .validateAudio(Chunk.fill(OAILlmProvider.MaxAudioBytes + 1)(1.toByte))
        .isLeft
    )
    assertEquals(
      OAIChatCompletionsMapper
        .parseResponse("""{"choices":[{"message":{"content":"Olá"}}]}""")
        .flatMap(_.content.toRight("OpenAI-compatible endpoint returned no transcription text")),
      Right("Olá")
    )
    assert(OAIChatCompletionsMapper.parseResponse("""{"choices":[]}""").isLeft)
    assert(
      OAIChatCompletionsMapper
        .parseResponse("""{"choices":[{"message":{"content":" "}}]}""")
        .flatMap(_.content.toRight("OpenAI-compatible endpoint returned no transcription text"))
        .isLeft
    )
  }

  test("normalizes a trailing slash in the configured endpoint") {
    assertEquals(
      OAIConfig.from("http://model-runner.docker.internal/v1/", " ai/gemma4:E4B "),
      Right(OAIConfig("http://model-runner.docker.internal/v1", "ai/gemma4:E4B"))
    )
  }
