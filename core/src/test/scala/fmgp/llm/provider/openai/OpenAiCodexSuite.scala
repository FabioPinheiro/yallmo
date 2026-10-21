package fmgp.yallmo.provider.openai

import fmgp.yallmo.model.MessagePayload.AudioAttachment
import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.*
import munit.FunSuite
import zio.*

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.compiletime.testing.typeCheckErrors

final class OpenAiCodexSuite extends FunSuite:
  test("provider connections are not agents and expose no turn method") {
    val noTurn = typeCheckErrors("""
      def call(
          provider: fmgp.yallmo.provider.openai.OpenAiCodexClient,
          request: fmgp.yallmo.model.llm.AgentTurnRequest
      ) = provider.turn(request)
    """)
    assertEquals(noTurn.size, 1)
    assert(noTurn.head.message.contains("turn"))
    val notAnAgent = typeCheckErrors("""
      def agent(provider: fmgp.yallmo.provider.openai.OpenAiCodexClient): fmgp.yallmo.model.Agent[Any] = provider
    """)
    assertEquals(notAnAgent.size, 1)
  }

  test("native request validation rejects audio before authentication or wire submission") {
    val request = AgentTurnRequest(
      "gpt-5.4",
      Chunk(
        AudioAttachment(fmgp.yallmo.model.BlobId.fresh(), AudioFormat.Wav),
        MessagePayload.Instructions("Describe")
      )
    )
    assertEquals(OpenAiCodexTurnMapper.requestBody(request), Left("OpenAI Codex does not support audio input"))
  }

  test("direct input mapping returns an error for audio instead of throwing or returning partial input") {
    val audio = AudioAttachment(fmgp.yallmo.model.BlobId.fresh(), AudioFormat.Wav, Some("private description"))
    Seq(audio, audio.copy(blobId = fmgp.yallmo.model.BlobId.fresh())).foreach { input =>
      Seq(
        Chunk[MessagePayload](input),
        Chunk(MessagePayload.User("before"), input, MessagePayload.Assistant("after"))
      ).foreach { items =>
        assertEquals(OpenAiCodexTurnMapper.input(items), Left("OpenAI Codex does not support audio input"))
      }
    }
    assertEquals(OpenAiCodexTurnMapper.input(Chunk.empty), Right(ujson.Arr()))
  }

  test("parses a Codex response with text and a function call") {
    val response = OpenAiCodexClient.parseResponse(
      """{
        |  "id":"response-1",
        |  "status":"completed",
        |  "usage":{"input_tokens":10,"output_tokens":4,"input_tokens_details":{"cached_tokens":3}},
        |  "output": [
        |    {"type":"function_call","call_id":"call-1","name":"current_time","arguments":"{}"},
        |    {"type":"message","content":[{"type":"output_text","text":"done"}]}
        |  ]
        |}""".stripMargin
    )

    assertEquals(response.map(_.text), Right(Some("done")))
    assertEquals(response.map(_.functionCalls.map(_.name)), Right(Vector("current_time")))
    assertEquals(response.map(_.responseId), Right(Some("response-1")))
  }

  test("maps native Responses items while preserving call IDs and OpenAI continuations") {
    val call = FunctionCall(FunctionCallId.unsafe("call-1"), "read", "{\"path\":\"README.md\"}", Some("fc-1"))
    val response = OpenAiCodexResponse(
      output = Vector(
        ujson.read(
          """{"type":"reasoning","id":"rs-1","encrypted_content":"encrypted","summary":[{"type":"summary_text","text":"Inspecting files"}]}"""
        ),
        ujson.read(
          """{"type":"function_call","id":"fc-1","call_id":"call-1","name":"read","arguments":"{\"path\":\"README.md\"}"}"""
        )
      ),
      functionCalls = Vector.empty,
      text = None
    )

    val turn = OpenAiCodexTurnMapper.response(response)
    assertEquals(
      turn.map(_.output.collect { case MessagePayload.FunctionCallRequest(value) => value }),
      Right(Chunk(call))
    )
    assertEquals(turn.map(_.stopReason), Right(LlmStopReason.ToolUse))
    assertEquals(
      turn.map(_.output.collect { case MessagePayload.Reasoning(summary, continuation) => summary -> continuation }),
      Right(
        Chunk(
          Some("Inspecting files") -> ProviderContinuation(OpenAiCodexTurnMapper.providerId, Some("rs-1"), "encrypted")
        )
      )
    )

    val input = OpenAiCodexTurnMapper
      .input(
        Chunk(
          MessagePayload.FunctionCallRequest(call),
          MessagePayload.FunctionCallOutput(FunctionCallOutput(call.id, call.name, "contents"))
        )
      )
      .toOption
      .get
    assertEquals(
      input.value.map(value => ujson.write(value)).toVector,
      Vector(
        """{"type":"function_call","call_id":"call-1","name":"read","arguments":"{\"path\":\"README.md\"}","id":"fc-1"}""",
        """{"type":"function_call_output","call_id":"call-1","output":"contents"}"""
      )
    )
  }

  test("parses a completed Codex response from an SSE stream") {
    val response = OpenAiCodexClient.parseSseResponse(
      """data: {"type":"response.created","response":{"output":[]}}
        |
        |data: {"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","text":"done"}]}]}}
        |
        |data: [DONE]
        |""".stripMargin
    )

    assertEquals(response.map(_.text), Right(Some("done")))
  }

  test("uses text deltas when the terminal SSE response omits output") {
    val response = OpenAiCodexClient.parseSseResponse(
      """data: {"type":"response.output_text.delta","delta":"streamed"}
        |
        |data: {"type":"response.output_text.delta","delta":" text"}
        |
        |data: {"type":"response.completed","response":{"id":"response-1"}}
        |
        |data: [DONE]
        |""".stripMargin
    )

    assertEquals(response.map(_.text), Right(Some("streamed text")))
    assertEquals(
      response.flatMap(OpenAiCodexTurnMapper.response).map(_.output),
      Right(Chunk(MessagePayload.Assistant("streamed text")))
    )
  }

  test("accepts nonblank model overrides and uses the configured subscription default") {
    assertEquals(OpenAiCodexConfig.Model, "gpt-6.1-sol")
    assertEquals(OpenAiCodexClient.validateModel(Some("other-model")), Right(()))
    assertEquals(OpenAiCodexClient.validateModel(None), Right(()))
    assertEquals(OpenAiCodexClient.validateModel(Some(OpenAiCodexConfig.Model)), Right(()))
    Seq("", " ", "\n\t").foreach(model => assert(OpenAiCodexClient.validateModel(Some(model)).isLeft))
  }

  test("native request lifts only the final instructions and preserves earlier developer messages in order") {
    val request = AgentTurnRequest(
      model = "selected-model",
      input = Chunk(
        MessagePayload.User("first"),
        MessagePayload.Instructions("earlier instructions"),
        MessagePayload.Assistant("previous answer"),
        MessagePayload.Instructions("current instructions")
      ),
      options = ProviderGenerateOptions(systemInstruction = Some("must not replace canonical instructions"))
    )
    assert(request.validate.isRight)
    val body = OpenAiCodexTurnMapper.requestBody(request).toOption.get
    assertEquals(body("model").str, "selected-model")
    assertEquals(body("instructions").str, "current instructions")
    assertSubscriptionBody(body)
    assertEquals(body("input").arr.map(_("role").str).toVector, Vector("user", "developer", "assistant"))
    assertEquals(body("input")(1)("content")(0)("text").str, "earlier instructions")
    assertEquals(body("parallel_tool_calls").bool, false)
    assertEquals(body("tool_choice").str, "auto")
    assert(
      OpenAiCodexTurnMapper.requestBody(request.copy(input = Chunk(MessagePayload.User("no instructions")))).isLeft
    )
    assert(OpenAiCodexTurnMapper.requestBody(request.copy(input = Chunk(MessagePayload.Instructions(" ")))).isLeft)
    assert(OpenAiCodexTurnMapper.requestBody(request.copy(model = " ")).isLeft)
  }

  test("native request preserves tool calls, results and only same-provider encrypted reasoning") {
    val call = FunctionCall(FunctionCallId.unsafe("call-1"), "read", "{}", Some("fc-1"))
    val continuation = ProviderContinuation(OpenAiCodexTurnMapper.providerId, Some("rs-1"), "encrypted")
    val definition = FunctionToolDefinition(
      "read",
      "Read a file",
      JsonSchema.ObjectSchema(Map("path" -> JsonSchema.StringSchema()), Set("path"), false)
    )
    val request = AgentTurnRequest(
      model = "selected-model",
      input = Chunk(
        MessagePayload.User("read"),
        MessagePayload.Reasoning(Some("summary"), continuation),
        MessagePayload.Reasoning(None, continuation.copy(provider = ProviderId.unsafe("other-provider"))),
        MessagePayload.FunctionCallRequest(call),
        MessagePayload.FunctionCallOutput(FunctionCallOutput(call.id, call.name, "file contents")),
        MessagePayload.Instructions("Use the tools")
      ),
      tools = Chunk(definition),
      toolChoice = ToolChoice.Function("read"),
      parallelToolCalls = true
    )
    val body = OpenAiCodexTurnMapper.requestBody(request).toOption.get
    val input = body("input").arr.toVector
    assertEquals(input.size, 4)
    assertEquals(input(1)("type").str, "reasoning")
    assertEquals(input(1)("id").str, "rs-1")
    assertEquals(input(1)("encrypted_content").str, "encrypted")
    assertEquals(input(1)("summary"), ujson.Arr(ujson.Obj("type" -> "summary_text", "text" -> "summary")))
    val withoutSummary = OpenAiCodexTurnMapper.input(Chunk(MessagePayload.Reasoning(None, continuation))).toOption.get
    assertEquals(withoutSummary(0)("summary"), ujson.Arr())
    assertEquals(input(2)("call_id").str, "call-1")
    assertEquals(input(2)("id").str, "fc-1")
    assertEquals(input(3)("type").str, "function_call_output")
    assertEquals(input(3)("call_id").str, "call-1")
    assertEquals(input(3)("output").str, "file contents")
    assertEquals(body("tools")(0)("name").str, "read")
    assertEquals(body("tools")(0)("parameters")("required")(0).str, "path")
    assertEquals(body("tool_choice"), ujson.Obj("type" -> "function", "name" -> "read"))
    assert(body("parallel_tool_calls").bool)
    Seq(ToolChoice.Auto -> "auto", ToolChoice.None -> "none", ToolChoice.Required -> "required").foreach {
      case (choice, expected) =>
        assertEquals(
          OpenAiCodexTurnMapper.requestBody(request.copy(toolChoice = choice)).map(_("tool_choice").str),
          Right(expected)
        )
    }
  }

  test("legacy request honors explicit models and always includes nonblank instructions and reasoning replay") {
    val history = ujson.Arr(ujson.Obj("role" -> "user", "content" -> "hello"))
    Seq(None, Some(""), Some(" ")).foreach { instructions =>
      val body =
        OpenAiCodexClient.requestBody(history, Chunk.empty, ProviderGenerateOptions(systemInstruction = instructions))
      assertEquals(body("model").str, OpenAiCodexConfig.Model)
      assertSubscriptionBody(body)
    }
    val body = OpenAiCodexClient.requestBody(
      history,
      Chunk.empty,
      ProviderGenerateOptions(systemInstruction = Some("Legacy instructions")),
      "legacy-selected-model"
    )
    assertEquals(body("model").str, "legacy-selected-model")
    assertEquals(body("instructions").str, "Legacy instructions")
    assertEquals(body("input"), history)
    assertEquals(OpenAiCodexClient.CodexResponsesUrl, "https://chatgpt.com/backend-api/codex/responses")
  }

  test("thinking efforts are lowercase in both request paths and Minimal maps to low") {
    Seq(
      ThinkingLevel.Minimal -> "low",
      ThinkingLevel.Low -> "low",
      ThinkingLevel.Medium -> "medium",
      ThinkingLevel.High -> "high"
    ).foreach { case (level, expected) =>
      val options = ProviderGenerateOptions(thinkingLevel = Some(level))
      val native = OpenAiCodexTurnMapper
        .requestBody(
          AgentTurnRequest("model", Chunk(MessagePayload.Instructions("instructions")), options = options)
        )
        .toOption
        .get
      val legacy = OpenAiCodexClient.requestBody(ujson.Arr(), Chunk.empty, options)
      assertEquals(native("reasoning")("effort").str, expected)
      assertEquals(legacy("reasoning")("effort").str, expected)
    }
  }

  test("account ID extraction rejects missing, malformed and blank claims without exposing token data") {
    def token(claims: ujson.Value): String =
      "header." + Base64.getUrlEncoder.withoutPadding.encodeToString(
        ujson.write(claims).getBytes(StandardCharsets.UTF_8)
      ) + ".signature"
    def claims(account: String): ujson.Value =
      ujson.Obj("https://api.openai.com/auth" -> ujson.Obj("chatgpt_account_id" -> account))
    assertEquals(OpenAiCodexClient.accountId(token(claims("account-1"))), Right("account-1"))
    Seq(token(claims("")), token(claims(" ")), token(ujson.Obj()), "sensitive-invalid-token").foreach { value =>
      val error = OpenAiCodexClient.accountId(value).swap.toOption.get
      assertEquals(error, "OpenAI Codex access token does not contain a ChatGPT account id")
      assert(!error.contains("sensitive"))
    }
  }

  test("normalizes done, completed and incomplete SSE terminal variants with CRLF and multiline data") {
    Seq(
      "response.done" -> LlmStopReason.Stop,
      "response.completed" -> LlmStopReason.Stop,
      "response.incomplete" -> LlmStopReason.Length
    ).foreach { case (eventType, stopReason) =>
      val stream = s""": keepalive
          |event: $eventType
          |data: {"type":"$eventType",
          |data: "response":{"id":"response-1","output":[{"type":"message","content":[{"type":"output_text","text":"done"}]}]}}
          |
          |data: [DONE]
          |""".stripMargin.replace("\n", "\r\n")
      val response = OpenAiCodexClient.parseSseResponse(stream)
      assertEquals(response.map(_.text), Right(Some("done")))
      assertEquals(response.flatMap(OpenAiCodexTurnMapper.response).map(_.stopReason), Right(stopReason))
    }
  }

  test("terminal output is authoritative over stale item events and text deltas, without duplication") {
    val stream = sse(
      """{"type":"response.output_text.delta","output_index":0,"delta":"provisional"}""",
      """{"type":"response.output_item.done","output_index":0,"item":{"type":"message","content":[{"type":"output_text","text":"stale"}]}}""",
      """{"type":"response.done","response":{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"final"}]}]}}"""
    )
    val response = OpenAiCodexClient.parseSseResponse(stream)
    assertEquals(response.map(_.text), Right(Some("final")))
    assertEquals(
      response.flatMap(OpenAiCodexTurnMapper.response).map(_.output),
      Right(Chunk(MessagePayload.Assistant("final")))
    )
    val empty = OpenAiCodexClient.parseSseResponse(
      sse(
        """{"type":"response.output_text.delta","delta":"stale"}""",
        """{"type":"response.completed","response":{"output":[]}}"""
      )
    )
    assertEquals(empty.map(_.output), Right(Vector.empty))
  }

  test("delta-only structured responses retain ordered reasoning, tool items and assistant text") {
    val response = OpenAiCodexClient.parseSseResponse(
      sse(
        """{"type":"response.output_item.done","output_index":2,"item":{"type":"function_call","id":"fc-1","call_id":"call-1","name":"read","arguments":"{}"}}""",
        """{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"rs-1","encrypted_content":"encrypted"}}""",
        """{"type":"response.output_text.delta","output_index":1,"delta":"streamed"}""",
        """{"type":"response.output_text.delta","output_index":1,"delta":" text"}""",
        """{"type":"response.completed","response":{"id":"response-1"}}"""
      )
    )
    val turn = response.flatMap(OpenAiCodexTurnMapper.response)
    assertEquals(response.map(_.text), Right(Some("streamed text")))
    assertEquals(
      turn.map(_.output),
      Right(
        Chunk(
          MessagePayload
            .Reasoning(None, ProviderContinuation(OpenAiCodexTurnMapper.providerId, Some("rs-1"), "encrypted")),
          MessagePayload.Assistant("streamed text"),
          MessagePayload.FunctionCallRequest(FunctionCall(FunctionCallId.unsafe("call-1"), "read", "{}", Some("fc-1")))
        )
      )
    )
    assertEquals(turn.map(_.stopReason), Right(LlmStopReason.ToolUse))
  }

  test("item snapshots suppress matching deltas when terminal output is absent") {
    val response = OpenAiCodexClient.parseSseResponse(
      sse(
        """{"type":"response.output_text.delta","output_index":0,"delta":"done"}""",
        """{"type":"response.output_item.done","output_index":0,"item":{"type":"message","content":[{"type":"output_text","text":"done"}]}}""",
        """{"type":"response.completed","response":{}}"""
      )
    )
    assertEquals(
      response.flatMap(OpenAiCodexTurnMapper.response).map(_.output),
      Right(Chunk(MessagePayload.Assistant("done")))
    )
  }

  test("rejects malformed, truncated and failed SSE without leaking provider error bodies") {
    val terminal = """{"type":"response.completed","response":{}}"""
    val invalid = Seq(
      "",
      "data: [DONE]\n\n",
      sse("""{"type":"response.output_text.delta","delta":"partial"}"""),
      sse("""{"type":"response.completed","response": """),
      sse("""{"type":"response.completed"}"""),
      sse("""{"type":"response.output_text.delta","delta":42}""", terminal),
      sse("""{"type":"response.completed","response":{"output":{}}}"""),
      sse("""{"type":"response.completed","response":{"output":[{}]}}"""),
      sse("""{"type":"response.completed","response":{"output":[{"type":"message"}]}}"""),
      sse(
        """{"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","text":42}]}]}}"""
      ),
      sse("""{"type":"response.output_item.done","item":{}}""", terminal),
      sse(
        """{"type":"response.completed","response":{"output":[{"type":"function_call","call_id":" ","name":"read"}]}}"""
      ),
      sse(
        """{"type":"response.completed","response":{"output":[{"type":"function_call","call_id":"call-1","name":"read","arguments":42}]}}"""
      ),
      sse("""{"type":"error","message":"SENSITIVE provider body"}"""),
      sse("""{"type":"response.failed","response":{"error":{"message":"SENSITIVE provider body"}}}"""),
      sse("""{"type":"response.done","response":{"status":"failed","error":{"message":"SENSITIVE provider body"}}}"""),
      sse("""{"type":"response.completed","response":{"status":"cancelled"}}"""),
      sse("""{"type":"response.incomplete","response":{"status":"in_progress"}}"""),
      sse("""{"SENSITIVE":"invalid event"}""", terminal)
    )
    invalid.foreach { stream =>
      val result = OpenAiCodexClient.parseSseResponse(stream)
      assert(result.isLeft, s"Expected failure for synthetic stream: $stream")
      assertEquals(result.swap.toOption.get, "Invalid or failed OpenAI Codex response stream")
    }
    assert(OpenAiCodexClient.parseResponse("""{"status":"failed","output":[]}""").isLeft)
  }

  private def sse(events: String*): String = events.map(event => s"data: $event\n\n").mkString

  private def assertSubscriptionBody(body: ujson.Value): Unit = {
    assertEquals(body("store").bool, false)
    assertEquals(body("stream").bool, true)
    assertEquals(body("include"), ujson.Arr("reasoning.encrypted_content"))
    assert(body("instructions").str.trim.nonEmpty)
  }

  test("accepts only a browser redirect with the matching OAuth state") {
    val login = OpenAiCodexBrowserLogin("https://auth.openai.com", "expected-state", "verifier")

    assertEquals(
      OpenAiCodexAuth
        .parseBrowserRedirect(login, "http://localhost:1455/auth/callback?code=code-123&state=expected-state"),
      Right(OpenAiCodexAuthorization("code-123", "verifier"))
    )
    assert(
      OpenAiCodexAuth
        .parseBrowserRedirect(login, "http://localhost:1455/auth/callback?code=code-123&state=wrong")
        .isLeft
    )
  }

  test("parses token responses") {
    val credentials = OpenAiCodexAuth.parseCredentials(
      """{"access_token":"access","refresh_token":"refresh","expires_in":3600}"""
    )
    assertEquals(credentials.map(_.accessToken), Right("access"))
    assertEquals(credentials.map(_.refreshToken), Right("refresh"))
  }
