package fmgp.yallmo.model.llm

import zio.Chunk
import munit.FunSuite
import fmgp.yallmo.model.*

final class LlmProtocolSuite extends FunSuite:
  test("function call identifiers are opaque values with non-blank validation") {
    assertEquals(FunctionCallId.from("call-42").map(_.value), Right("call-42"))
    assertEquals(FunctionCallId.from("  "), Left("function call id must not be blank"))
  }

  test("turn response preserves ordered function calls in output") {
    val call = FunctionCall(FunctionCallId.unsafe("call-42"), "read", "{\"path\":\"README.md\"}")
    val response = AgentTurnResponse(
      output = Chunk(MessagePayload.Assistant("I will inspect the file."), MessagePayload.FunctionCallRequest(call)),
      stopReason = LlmStopReason.ToolUse
    )

    assertEquals(response.output.collect { case MessagePayload.FunctionCallRequest(value) => value }, Chunk(call))
  }

  test("turn request rejects duplicate tools and invalid nested schemas") {
    val schema: JsonSchema.ObjectSchema = JsonSchema.ObjectSchema(
      properties = Map(
        "path" -> JsonSchema.StringSchema(),
        "edits" -> JsonSchema.ArraySchema(JsonSchema.StringSchema())
      ),
      required = Set("path")
    )
    val read = FunctionToolDefinition("read", "Reads a file", schema)
    val duplicate = AgentTurnRequest(
      model = "test-model",
      input = Chunk(MessagePayload.User("Read README.md"), MessagePayload.Instructions("Use tools safely.")),
      tools = Chunk(read, read),
      options = ProviderGenerateOptions(responseMimeType = None)
    )

    assertEquals(duplicate.validate, Left("duplicate function tools: read"))
    assertEquals(
      FunctionToolDefinition(
        "broken",
        "Broken schema",
        JsonSchema.ObjectSchema(properties = Map.empty, required = Set("missing"))
      ).validate,
      Left("required properties are not declared: missing")
    )
  }

  test("provider continuations preserve scope and reject empty encrypted state") {
    val continuation = ProviderContinuation(ProviderId.unsafe("openai-codex-responses"), Some("rs-1"), "encrypted")

    assertEquals(continuation.validate, Right(continuation))
    assertEquals(
      continuation.copy(encryptedContent = "").validate,
      Left("provider continuation encrypted content must not be empty")
    )
  }
