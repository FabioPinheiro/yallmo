package fmgp.yallmo.server.jsonrpc

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{JsonSchema, ProviderContinuation, ProviderId}
import fmgp.yallmo.orchestration.{AgentResult, AgentTask, AgentToolDefinition, Session, SessionId, SessionSummary}
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import fmgp.yallmo.server.protocol.YallmoProtocol.given
import java.time.Instant
import munit.FunSuite
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** Golden wire examples: the same envelopes will be used by both transport adapters. */
final class YallmoProtocolSuite extends FunSuite {
  private val sessionId = SessionId.unsafe("s1")
  private val agentId = AgentId("agent1")
  private val head = MessageId.unsafe("sha256:" + "a" * 64)
  private val blobId = BlobId.from("00000000-0000-0000-0000-000000000001").fold(fail(_), identity)
  test("initialize is an ordinary JSON-RPC request with typed named params") {
    val request = JsonRpc.Request(Json.Str("r1"), Initialize.method, Initialize.Params(Version, "repl"))
    assertWire(
      request,
      """{"id":"r1","method":"initialize","params":{"protocolVersion":1,"clientName":"repl"},"jsonrpc":"2.0"}"""
    )
  }

  test("parameterless queries use an empty object, not an invented tagged command") {
    val request = JsonRpc.Request(Json.Str("r2"), SessionsList.method, Empty())
    assertWire(request, """{"id":"r2","method":"sessions.list","params":{},"jsonrpc":"2.0"}""")
    assert(!request.toJson.contains("SessionsList"))
  }

  test("turn.submit includes explicit session/agent/command identities and omits an absent precondition") {
    val params = TurnSubmit.Params("c1", sessionId, agentId, AgentTask("Hello"))
    assertWire(
      JsonRpc.Request(Json.Str("r3"), TurnSubmit.method, params),
      """{"id":"r3","method":"turn.submit","params":{"commandId":"c1","sessionId":"s1","agentId":"agent1","task":{"prompt":"Hello"}},"jsonrpc":"2.0"}"""
    )
    assertWire(
      params.copy(expectedHead = Some(head)),
      s"""{"commandId":"c1","sessionId":"s1","agentId":"agent1","task":{"prompt":"Hello"},"expectedHead":"${MessageId
          .value(head)}"}"""
    )
  }

  test("notifications have no ID and use the same concrete derived envelope") {
    assertWire(
      JsonRpc.Notification(TurnStarted.method, TurnStarted.Params(Json.Str("r3"), "c1", "run1", sessionId)),
      """{"method":"turn.started","params":{"requestId":"r3","commandId":"c1","runId":"run1","sessionId":"s1"},"jsonrpc":"2.0"}"""
    )
    assertWire(
      JsonRpc.Notification(TurnProgress.method, TurnProgress.Params("run1", "Working")),
      """{"method":"turn.progress","params":{"runId":"run1","text":"Working"},"jsonrpc":"2.0"}"""
    )
  }

  test("successful turn outcomes have an explicit application discriminator inside result only") {
    val message =
      Message(MessageData(agentId, Some(head), Instant.parse("2026-10-23T12:00:00Z"), MessagePayload.Assistant("Hi")))
    val outcome: TurnOutcome = TurnOutcome.Succeeded(
      "run1",
      sessionId,
      message.id,
      AgentResult(agentId, "Hi"),
      Chunk(MessageSnapshot.from(message))
    )
    val encoded = JsonRpc.Success(Json.Str("r3"), outcome).toJson
    val decoded = encoded.fromJson[JsonRpc.Success[TurnOutcome]].fold(fail(_), identity)
    assertEquals(decoded.result, outcome)
    assert(encoded.contains("\"status\":\"succeeded\""))
    assert(!encoded.contains("Succeeded"))
    assert(!encoded.contains("\"error\":"))
  }

  test("execution failure after prompt acceptance is a typed result, not a protocol error") {
    val outcome: TurnOutcome = TurnOutcome.Failed(
      "run1",
      sessionId,
      true,
      Some(head),
      ApplicationError("provider_failed", "Provider unavailable")
    )
    assertWire(
      JsonRpc.Success(Json.Str("r3"), outcome),
      s"""{"id":"r3","result":{"status":"failed","runId":"run1","sessionId":"s1","accepted":true,"headMessageId":"${MessageId
          .value(head)}","error":{"code":"provider_failed","message":"Provider unavailable"}},"jsonrpc":"2.0"}"""
    )
    val cancelled: TurnOutcome = TurnOutcome.Cancelled("run1", sessionId, false, None)
    assertWire(
      cancelled,
      """{"status":"cancelled","runId":"run1","sessionId":"s1","accepted":false}"""
    )
  }

  test("standard errors and application rejections use error, never both result and error") {
    assertWire(
      JsonRpc.Failure(Json.Null, JsonRpc.Error.ParseError),
      """{"id":null,"error":{"code":-32700,"message":"Parse error"},"jsonrpc":"2.0"}"""
    )
    val details = ApplicationError("head_conflict", "Session head changed").toJsonAST.fold(fail(_), identity)
    assertWire(
      JsonRpc.Failure(Json.Str("r3"), JsonRpc.Error(ErrorCode.Conflict, "Conflict", Some(details))),
      """{"id":"r3","error":{"code":-32005,"message":"Conflict","data":{"code":"head_conflict","message":"Session head changed"}},"jsonrpc":"2.0"}"""
    )
  }

  test("explicit null results and null/numeric IDs survive derived codecs") {
    assertWire(
      JsonRpc.Success(Json.Null, Json.Null),
      """{"id":null,"result":null,"jsonrpc":"2.0"}"""
    )
    assertWire(
      JsonRpc.Success(Json.Num(BigDecimal("12345678901234567890").bigDecimal), Json.Null),
      """{"id":12345678901234567890,"result":null,"jsonrpc":"2.0"}"""
    )
    assertWire(
      JsonRpc.Success[Option[String]](Json.Str("r1"), None),
      """{"id":"r1","result":null,"jsonrpc":"2.0"}"""
    )
  }

  test("read and administrative operation params are concrete derived DTOs") {
    assertWire(SessionsGet.Params(sessionId), """{"sessionId":"s1"}""")
    assertWire(SessionsCreate.Params(), "{}")
    assertWire(SessionsCreate.Params(Some(SessionId.unsafe("new-session"))), """{"name":"new-session"}""")
    assertWire(
      SessionsFork.Params(sessionId, Some(SessionId.unsafe("fork"))),
      """{"sourceSessionId":"s1","name":"fork"}"""
    )
    assertWire(ToolsList.Params(agentId), """{"agentId":"agent1"}""")
    assertWire(RunsGet.Params("run1"), """{"runId":"run1"}""")
    assertWire(RunsCancel.Result("run1", true), """{"runId":"run1","requested":true}""")
    assertWire(
      DiagnosticsMessage.Params(sessionId, head),
      s"""{"sessionId":"s1","messageId":"${MessageId.value(head)}"}"""
    )
    val session = Session(sessionId, Chunk(AgentId("human")), Chunk.empty)
    assertWire(SessionSnapshot.from(session), """{"id":"s1","agentIds":["human"],"messages":[]}""")
  }

  test("blob reads are bounded named requests and base64 chunk results") {
    assertWire(
      BlobsRead.Params(blobId, 0),
      s"""{"blobId":"${blobId.value}","offset":0,"maxBytes":65536}"""
    )
    assertWire(
      BlobsRead.Result(blobId, 0, "AAH/", 3, true),
      s"""{"blobId":"${blobId.value}","offset":0,"dataBase64":"AAH/","totalBytes":3,"eof":true}"""
    )
  }

  test("roles, run states and supported methods are plain JSON values") {
    assertWire[Role](Role.Viewer, "\"viewer\"")
    assertWire[Role](Role.Operator, "\"operator\"")
    assertWire[RunStatus](RunStatus.Running, "\"running\"")
    assertWire(
      Initialize.Result(
        Version,
        "instance1",
        Identity(AgentId("human"), Role.Operator),
        List(SessionsList.method),
        Nil,
        MaxBlobChunkBytes
      ),
      """{"protocolVersion":1,"serverInstanceId":"instance1","identity":{"id":"human","role":"operator"},"methods":["sessions.list"],"notifications":[],"maxBlobChunkBytes":65536}"""
    )
    val outcome: TurnOutcome = TurnOutcome.Cancelled("run1", sessionId, true, Some(head))
    val result = RunsGet.Result("run1", "c1", sessionId, agentId, RunStatus.Cancelled, Some(outcome))
    assertEquals(result.toJson.fromJson[RunsGet.Result], Right(result))
  }

  test("heterogeneous batches use ordinary JSON arrays without another document abstraction") {
    val first = JsonRpc.Request(Json.Str("r1"), SessionsList.method, Empty()).toJsonAST.fold(fail(_), identity)
    val second = JsonRpc.Request(Json.Str("r2"), AgentsList.method, Empty()).toJsonAST.fold(fail(_), identity)
    assertWire(List(first, second), s"[${first.toJson},${second.toJson}]")
  }

  test("session summaries, agent IDs, blob IDs, tasks and results reuse domain types and codecs") {
    val sessions: SessionsList.Result = Chunk(SessionSummary(sessionId, 2, Chunk(agentId)))
    val agents: AgentsList.Result = Chunk(agentId)
    val blobs: BlobsList.Result = Chunk(blobId)
    assertWire(sessions, """[{"id":"s1","messageCount":2,"agentIds":["agent1"]}]""")
    assertWire(agents, """["agent1"]""")
    assertWire(blobs, s"""["${blobId.value}"]""")
    assertWire(AgentTask("Hello"), """{"prompt":"Hello"}""")
    assertWire(AgentResult(agentId, "Hi"), """{"agentId":"agent1","text":"Hi"}""")
  }

  test("tools use AgentToolDefinition and its existing JsonSchema model, not a duplicate DTO") {
    val tools: ToolsList.Result = Chunk(
      AgentToolDefinition(
        "inspect",
        "Read",
        JsonSchema.ObjectSchema(
          Map("path" -> JsonSchema.StringSchema()),
          Set("path")
        )
      )
    )
    assertWire(
      tools,
      """[{"key":"inspect","description":"Read","inputSchema":{"properties":{"path":{"StringSchema":{"allowedValues":[]}}},"required":["path"],"additionalProperties":false}}]"""
    )
    val schema: JsonSchema = JsonSchema.ArraySchema(JsonSchema.IntegerSchema(Some("index")))
    assertEquals(schema.toJson.fromJson[JsonSchema], Right(schema))
  }

  test("domain ID validation applies to protocol params without duplicate validation logic") {
    assert("""{"sessionId":"../private"}""".fromJson[SessionsGet.Params].isLeft)
    assert("""{"agentId":" "}""".fromJson[ToolsList.Params].isLeft)
    assert("""{"blobId":"not-a-uuid","offset":0}""".fromJson[BlobsRead.Params].isLeft)
    assert("""{"sessionId":"s1","messageId":"sha256:bad"}""".fromJson[DiagnosticsMessage.Params].isLeft)
    assert("""{"name":"../private"}""".fromJson[SessionsCreate.Params].isLeft)
  }

  test("message/session snapshots retain domain IDs but never expose provider continuations") {
    val continuation = ProviderContinuation(ProviderId.unsafe("provider"), Some("private-item"), "private-ciphertext")
    val message = Message(
      MessageData(
        agentId,
        Some(head),
        Instant.parse("2026-10-23T12:00:00Z"),
        MessagePayload.Reasoning(Some("Public summary"), continuation)
      )
    )
    val session = Session(sessionId, Chunk(agentId), Chunk(message))
    val snapshot = SessionSnapshot.from(session)
    val encoded = snapshot.toJson
    assertEquals(encoded.fromJson[SessionSnapshot], Right(snapshot))
    assertEquals(snapshot.messages.head.id, message.id)
    assertEquals(snapshot.messages.head.content, "Public summary")
    assertEquals(snapshot.messages.head.role, "reasoning")
    assert(!encoded.contains("continuation"))
    assert(!encoded.contains("private-item"))
    assert(!encoded.contains("private-ciphertext"))
    assertEquals(session.messages.head.payload, MessagePayload.Reasoning(Some("Public summary"), continuation))
  }

  private def assertWire[A: JsonCodec](value: A, expected: String): Unit = {
    assertEquals(value.toJson, expected)
    assertEquals(expected.fromJson[A], Right(value))
  }
}
