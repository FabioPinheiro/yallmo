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
    MessagePayload.ToolResult("tool", "result") -> """{"ToolResult":{"toolKey":"tool","text":"result"}}""",
    MessagePayload.Reasoning(Some("summary"), ProviderContinuation(ProviderId.unsafe("provider"), None, "opaque")) ->
      """{"Reasoning":{"summary":"summary","continuation":{"provider":"provider","encryptedContent":"opaque"}}}""",
    MessagePayload.FunctionCallRequest(FunctionCall(callId, "tool", "{}")) ->
      """{"FunctionCallRequest":{"call":{"id":"call-1","name":"tool","argumentsJson":"{}"}}}""",
    MessagePayload.FunctionCallOutput(FunctionCallOutput(callId, "tool", "result")) ->
      """{"FunctionCallOutput":{"output":{"callId":"call-1","name":"tool","output":"result"}}}""",
    MessagePayload.Instructions("system") -> """{"Instructions":{"text":"system"}}"""
  )

  fixtures.foreach { case (payload, json) =>
    test(s"${payload.getClass.getSimpleName} preserves existing JSON") {
      assertEquals(payload.toJson, json)
      assertEquals(json.fromJson[MessagePayload], Right(payload))
      val data = MessageData(AgentId("agent"), None, Instant.parse("2026-01-01T00:00:00Z"), payload)
      assertEquals(data.toJson.fromJson[MessageData], Right(data))
    }
  }
}
