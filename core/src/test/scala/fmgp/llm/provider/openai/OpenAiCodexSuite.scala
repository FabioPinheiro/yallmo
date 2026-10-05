package fmgp.yallmo.provider.openai

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.*
import munit.FunSuite
import zio.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files

final class OpenAiCodexSuite extends FunSuite:
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

    val input = OpenAiCodexTurnMapper.input(
      Chunk(
        MessagePayload.FunctionCallRequest(call),
        MessagePayload.FunctionCallOutput(FunctionCallOutput(call.id, call.name, "contents"))
      )
    )
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
  }

  test("rejects a model override because Codex model selection is fixed") {
    assertEquals(
      OpenAiCodexClient.validateModel(Some("other-model")),
      Left("OpenAI Codex model is fixed to gpt-5.6-terra")
    )
    assertEquals(OpenAiCodexClient.validateModel(Some(OpenAiCodexConfig.Model)), Right(()))
  }

  test("stores only Codex credentials while preserving unrelated dotenv entries") {
    val directory = Files.createTempDirectory("asar-codex-secrets")
    val envFile = directory.resolve(".env")
    Files.writeString(envFile, "GEMINI_API_KEY=keep-me\n# comment\n", StandardCharsets.UTF_8)
    val secrets = OpenAiCodexSecrets(envFile)
    val credentials = OpenAiCodexCredentials("access", "refresh", 1234L)

    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.run(secrets.save(credentials)).getOrThrowFiberFailure()
    }

    val content = Files.readString(envFile, StandardCharsets.UTF_8)
    assert(content.contains("GEMINI_API_KEY=keep-me"))
    assert(content.contains("OPENAI_CODEX_ACCESS_TOKEN=access"))
    assert(content.contains("OPENAI_CODEX_REFRESH_TOKEN=refresh"))
    assertEquals(
      Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(secrets.load).getOrThrowFiberFailure() },
      credentials
    )
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

  test("parses device-code and token responses") {
    assertEquals(
      OpenAiCodexAuth.parseDeviceCode("""{"device_auth_id":"device","user_code":"CODE","interval":5}"""),
      Right(OpenAiCodexDeviceCode("device", "CODE", 5.seconds))
    )
    assertEquals(
      OpenAiCodexAuth.parseDeviceCode("""{"device_auth_id":"device","user_code":"CODE","interval":"5"}"""),
      Right(OpenAiCodexDeviceCode("device", "CODE", 5.seconds))
    )
    val credentials = OpenAiCodexAuth.parseCredentials(
      """{"access_token":"access","refresh_token":"refresh","expires_in":3600}"""
    )
    assertEquals(credentials.map(_.accessToken), Right("access"))
    assertEquals(credentials.map(_.refreshToken), Right("refresh"))
  }
