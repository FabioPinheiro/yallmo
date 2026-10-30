package fmgp.yallmo.model

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
    MessagePayload.ToolCall(FunctionCall(callId, ToolKey.unsafe("tool"), "{}")) ->
      """{"ToolCall":{"call":{"id":"call-1","name":"tool","argumentsJson":"{}"}}}""",
    MessagePayload.ToolOutput(FunctionCallOutput(callId, ToolKey.unsafe("tool"), "result")) ->
      """{"ToolOutput":{"output":{"callId":"call-1","name":"tool","output":"result"}}}""",
    MessagePayload.Instructions("system") -> """{"Instructions":{"text":"system"}}"""
  )

  test("tool message exports retain their roles and content projections") {
    val call = FunctionCall(callId, ToolKey.unsafe("tool"), "{}")
    val output = FunctionCallOutput(callId, ToolKey.unsafe("tool"), "result")
    assertEquals(Message.ToolCall(call), MessagePayload.ToolCall(call))
    assertEquals(Message.ToolOutput(output), MessagePayload.ToolOutput(output))
    assertEquals(Message.ToolCall(call).role, "tool_call")
    assertEquals(Message.ToolCall(call).contentStr, "{}")
    assertEquals(Message.ToolOutput(output).role, "tool_result")
    assertEquals(Message.ToolOutput(output).contentStr, "result")
  }

  test("tool logs round-trip independently of the result and legacy outputs default to no logs") {
    val output = FunctionCallOutput(callId, ToolKey.unsafe("tool"), "42", Seq("connected", "disconnected"))
    assertEquals(output.toJson.fromJson[FunctionCallOutput], Right(output))
    assertEquals(Message.ToolOutput(output).contentStr, "42")
    assertEquals(
      """{"callId":"call-1","name":"tool","output":"42"}""".fromJson[FunctionCallOutput],
      Right(output.copy(logs = Seq.empty))
    )
  }

  test("removed ToolResult JSON is rejected rather than silently reinterpreted") {
    assert("""{"ToolResult":{"toolKey":"tool","text":"result"}}""".fromJson[MessagePayload].isLeft)
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
