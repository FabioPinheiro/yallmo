package fmgp.yallmo.model

import fmgp.yallmo.tool.FunctionKey

import fmgp.yallmo.model.tool.BashToolDefinition

import fmgp.yallmo.model.llm.{ProviderContinuation, ProviderId}
import munit.FunSuite
import zio.Chunk
import zio.json.{DecoderOps, EncoderOps}

import java.time.Instant

final class MessagePayloadSuite extends FunSuite {
  private val callId = FunctionCallId.unsafe("call-1")
  private val fixtures: Seq[(MessagePayload, String)] = Seq(
    MessagePayload.User("hello") -> """{"User":{"text":"hello"}}""",
    MessagePayload.Assistant("answer") -> """{"Assistant":{"text":"answer"}}""",
    MessagePayload.Context("label", "context") -> """{"Context":{"label":"label","text":"context"}}""",
    MessagePayload.RagResult("docs", Chunk(RagDocument("id", "source", "text"))) ->
      """{"RagResult":{"retrieverKey":"docs","documents":[{"id":"id","source":"source","text":"text"}]}}""",
    MessagePayload.Reasoning(Some("summary"), ProviderContinuation(ProviderId.unsafe("provider"), None, "opaque")) ->
      """{"Reasoning":{"summary":"summary","continuation":{"provider":"provider","encryptedContent":"opaque"}}}""",
    MessagePayload.ToolCall(FunctionCall(callId, FunctionKey.unsafe("tool"), "{}")) ->
      """{"ToolCall":{"call":{"id":"call-1","name":"tool","argumentsJson":"{}"}}}""",
    MessagePayload.ToolOutput(FunctionGenericOutput(callId, FunctionKey.unsafe("tool"), "result")) ->
      """{"ToolOutput":{"output":{"callId":"call-1","name":"tool","output":"result"}}}""",
    MessagePayload.Instructions("system") -> """{"Instructions":{"text":"system"}}"""
  )

  test("tool message exports retain their roles and content projections") {
    val call = FunctionCall(callId, FunctionKey.unsafe("tool"), "{}")
    val output = FunctionGenericOutput(callId, FunctionKey.unsafe("tool"), "result")
    assertEquals(Message.ToolCall(call), MessagePayload.ToolCall(call))
    assertEquals(Message.ToolOutput(output), MessagePayload.ToolOutput(output))
    assertEquals(Message.ToolCall(call).role, "tool_call")
    assertEquals(Message.ToolCall(call).contentStr, "{}")
    assertEquals(Message.ToolOutput(output).role, "tool_result")
    assertEquals(Message.ToolOutput(output).contentStr, "result")
  }

  test("ScalaModeOutput round-trips flat JSON with a fixed key and no duplicated output") {
    val output = ScalaModeOutput(
      callId,
      ScalaModeResult.Executed("42"),
      Seq("connected", "disconnected"),
      compilationTime = 1200L,
      executionTime = 250L,
      totalTime = 1500L
    )
    val json = output.toJson
    assertEquals(json.fromJson[ScalaModeOutput], Right(output))
    assertEquals(json.fromJson[FunctionCallOutput], Right(output))
    assertEquals((output: FunctionCallOutput).toJson, json)
    assertEquals(Message.ToolOutput(output).contentStr, "42")
    assert(json.contains("\"name\":\"scalamode\""))
    assert(json.contains("\"compilationTime\":1200"))
    assert(json.contains("\"executionTime\":250"))
    assert(json.contains("\"totalTime\":1500"))
    assert(!json.contains("\"output\""))
    assert(!json.contains("ScalaModeOutput"))
    assertEquals(
      (Message.ToolOutput(output): MessagePayload).toJson.fromJson[MessagePayload],
      Right(Message.ToolOutput(output))
    )
  }

  test("Scala outputs without timing fields decode with zero defaults") {
    val json = """{"callId":"call-1","name":"scalamode","result":{"Executed":{"value":"42"}}}"""
    assertEquals(
      json.fromJson[FunctionCallOutput],
      Right(ScalaModeOutput(callId, ScalaModeResult.Executed("42")))
    )
  }

  test("name exclusively dispatches subtypes and malformed scalamode never falls back") {
    val generic = FunctionGenericOutput(callId, BashToolDefinition.Execute, "42")
    assertEquals(generic.toJson.fromJson[FunctionCallOutput], Right(generic))
    assert(!generic.toJson.contains("logs"))
    val reserved = """{"callId":"call-1","name":"scalamode","output":"42"}"""
    assert(reserved.fromJson[FunctionCallOutput].isLeft)
    assert(reserved.fromJson[FunctionGenericOutput].isLeft)
    val wrongKey = ScalaModeOutput(callId, ScalaModeResult.Executed("42")).toJson.replace("scalamode", "other")
    assert(wrongKey.fromJson[ScalaModeOutput].isLeft)
    assert(wrongKey.fromJson[FunctionCallOutput].isLeft)
    for result <- List("null", "{}", "42", "{\"Executed\":{}}", "{\"TimedOut\":{}}") do
      assert(
        s"""{"callId":"call-1","name":"scalamode","output":"fallback","result":$result}"""
          .fromJson[FunctionCallOutput]
          .isLeft
      )
  }

  test("Scala timeout metadata round-trips and the reserved name is not a constructor argument") {
    val output = ScalaModeOutput(
      callId,
      ScalaModeResult.TimedOut("deadline exceeded"),
      exitCode = 137,
      compilationTime = 120L,
      executionTime = 300L,
      totalTime = 450L
    )
    assertEquals(output.output, "deadline exceeded")
    assertEquals(Message.ToolOutput(output).contentStr, "deadline exceeded")
    assert(output.toJson.contains("\"TimedOut\":{\"diagnostics\":\"deadline exceeded\"}"))
    assert(!output.toJson.contains("\"timedOut\""))
    assert(!scala.compiletime.testing.typeChecks("""fmgp.yallmo.model.ScalaModeOutput(
      fmgp.yallmo.model.FunctionCallId.unsafe("call"), fmgp.yallmo.model.ScalaModeResult.Executed("42"),
      timedOut = true)"""))
    assertEquals(output.toJson.fromJson[FunctionCallOutput], Right(output))
    assertEquals(output.toJson.fromJson[ScalaModeOutput], Right(output))
    assert(!scala.compiletime.testing.typeChecks("""fmgp.yallmo.model.ScalaModeOutput(
      fmgp.yallmo.model.FunctionCallId.unsafe("call"), fmgp.yallmo.model.ScalaModeResult.Executed("42"),
      name = fmgp.yallmo.model.tool.BashToolDefinition.Execute)"""))
  }

  test("saved timeout flags decode into the typed result but are never emitted") {
    for timedOut <- List(true, false) do {
      val json =
        s"""{"callId":"call-1","name":"scalamode","result":{"Executed":{"value":"deadline exceeded"}},"timedOut":$timedOut}"""
      val result = if timedOut then ScalaModeResult.TimedOut("deadline exceeded")
      else ScalaModeResult.Executed("deadline exceeded")
      val expected = ScalaModeOutput(callId, result)
      assertEquals(json.fromJson[FunctionCallOutput], Right(expected))
      assert(!expected.toJson.contains("\"timedOut\""))
    }
  }

  test("compilation diagnostics are the complete provider-facing output") {
    val diagnostics = "[E006] Not Found Error: Snippet.scala:6:1\nNot found: missing\nCompilation failed"
    val output = ScalaModeOutput(callId, ScalaModeResult.CompilationError(diagnostics), exitCode = 1, truncated = true)
    assertEquals(output.output, diagnostics)
    assertEquals(Message.ToolOutput(output).contentStr, diagnostics)
    assertEquals(output.toJson.fromJson[FunctionCallOutput], Right(output))
    assert(output.validate.isRight)
    assert(FunctionGenericOutput(callId, FunctionKey.unsafe(" "), "").validate.isLeft)
  }

  test("removed ToolResult JSON is rejected rather than silently reinterpreted") {
    assert("""{"ToolResult":{"functionKey":"tool","text":"result"}}""".fromJson[MessagePayload].isLeft)
  }

  fixtures.foreach { case (payload, json) =>
    test(s"${payload.getClass.getSimpleName} matches expected JSON and round-trips") {
      assertEquals(payload.toJson, json)
      assertEquals(json.fromJson[MessagePayload], Right(payload))
      val data = MessageData(AgentId("agent"), None, Instant.parse("2026-01-01T00:00:00Z"), payload)
      val storedJson = s"""{"agentId":"agent","timestamp":"2026-01-01T00:00:00Z","payload":$json}"""
      assertEquals(data.toJson, storedJson)
      assertEquals(storedJson.fromJson[MessageData], Right(data))
      val message = Message(data)
      assertEquals(message.id, MessageId.sha256(storedJson))
      assertEquals(message.toJson.fromJson[Message], Right(message))
    }
  }
}
