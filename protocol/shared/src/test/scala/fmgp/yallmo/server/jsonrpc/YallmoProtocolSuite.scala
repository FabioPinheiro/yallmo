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
  test("provider login uses named parameters and exposes no credential fields") {
    assertWire(ProvidersLoginStart.Params("account"), """{"providerId":"account"}""")
    assertWire(
      ProvidersLoginComplete.Params("attempt", "private-callback"),
      """{"attemptId":"attempt","redirectUrl":"private-callback"}"""
    )
    assertWire(ProvidersLoginCancel.Params("attempt"), """{"attemptId":"attempt"}""")
    assertWire(ProvidersLoginComplete.Result(true), """{"restartRequired":true}""")
    assertWire(
      ProviderSummary("account", ProviderKind.ChatGPT, ProviderCredentialStatus.LoggedOut),
      """{"id":"account","providerType":"ChatGPT","credentials":"logged-out"}"""
    )
    assert(!ProvidersLoginComplete.Params("attempt", "private-callback").toString.contains("private-callback"))
    assert(!ProvidersLoginStart.Result("attempt", "private-url", 1L).toString.contains("private-url"))
    assert("{}".fromJson[ProvidersLoginComplete.Params].isLeft)
  }

  test("blobs.upload explicit command/result goldens and round trips") {
    val commands: List[(BlobsUpload.Params, String)] = List(
      BlobsUpload.Params.Begin(blobId, 3) -> s"""{"command":"begin","blobId":"${blobId.value}","totalBytes":3}""",
      BlobsUpload.Params.Append(
        blobId,
        0,
        "AQID"
      ) -> s"""{"command":"append","blobId":"${blobId.value}","offset":0,"dataBase64":"AQID"}""",
      BlobsUpload.Params.Complete(blobId) -> s"""{"command":"complete","blobId":"${blobId.value}"}""",
      BlobsUpload.Params.Abort(blobId) -> s"""{"command":"abort","blobId":"${blobId.value}"}"""
    )
    commands.foreach { case (value, json) => assertWire[BlobsUpload.Params](value, json) }
    val results: List[(BlobsUpload.Result, String)] = List(
      BlobsUpload.Result.Begun(blobId) -> s"""{"status":"begun","blobId":"${blobId.value}"}""",
      BlobsUpload.Result
        .Appended(blobId, 3) -> s"""{"status":"appended","blobId":"${blobId.value}","nextOffset":3}""",
      BlobsUpload.Result.Completed(blobId) -> s"""{"status":"completed","blobId":"${blobId.value}"}""",
      BlobsUpload.Result.Aborted(blobId) -> s"""{"status":"aborted","blobId":"${blobId.value}"}"""
    )
    results.foreach { case (value, json) => assertWire[BlobsUpload.Result](value, json) }
    assert(!commands(1)._1.toString.contains("AQID"))
  }

  test("blobs.upload rejects missing/unknown discriminator, malformed blob IDs and incomplete shapes") {
    List(
      """{}""",
      """{"command":"unknown"}""",
      """{"command":"begin","blobId":"bad","totalBytes":3}""",
      s"""{"command":"begin","blobId":"${blobId.value}"}""",
      """{"command":"append","blobId":"BAD","offset":0,"dataBase64":"AQID"}""",
      s"""{"command":"append","blobId":"${blobId.value}","offset":0}""",
      """{"command":"complete","blobId":null}""",
      """{"command":"abort"}"""
    ).foreach(json => assert(json.fromJson[BlobsUpload.Params].isLeft, json))
  }

  test("initialize is an ordinary JSON-RPC request with typed named params") {
    val request = JsonRpc.Request(Json.Str("r1"), Initialize.method, Initialize.Params(Version, "repl"))
    assertWire(
      request,
      """{"id":"r1","method":"initialize","params":{"protocolVersion":1,"clientName":"repl"},"jsonrpc":"2.0"}"""
    )
  }

  test("initialize optional human label roundtrips and absent/null fields preserve anonymous clients") {
    assertWire(
      Initialize.Params(Version, "repl", Some(agentId)),
      """{"protocolVersion":1,"clientName":"repl","userId":"agent1"}"""
    )
    assertEquals(
      """{"protocolVersion":1,"clientName":"browser"}""".fromJson[Initialize.Params],
      Right(Initialize.Params(Version, "browser"))
    )
    assertEquals(
      """{"protocolVersion":1,"clientName":"browser","userId":null}""".fromJson[Initialize.Params],
      Right(Initialize.Params(Version, "browser"))
    )
    assert("""{"protocolVersion":1,"clientName":"repl","userId":" "}""".fromJson[Initialize.Params].isLeft)
  }

  test("parameterless queries use an empty object, not an invented tagged command") {
    val request = JsonRpc.Request(Json.Str("r2"), SessionsList.method, Empty())
    assertWire(request, """{"id":"r2","method":"sessions.list","params":{},"jsonrpc":"2.0"}""")
    assert(!request.toJson.contains("SessionsList"))
  }

  test("agents.invoke reuses full Message input/output without session fields") {
    val input = Message(MessageData(AgentId("human"), None, Instant.EPOCH, Message.User("hello")))
    val output = Message(MessageData(agentId, Some(input.id), Instant.EPOCH, Message.Assistant("answer")))
    val params = AgentsInvoke.Params(agentId, input)
    assertWire(
      JsonRpc.Request(Json.Str("invoke"), AgentsInvoke.method, params),
      s"""{"id":"invoke","method":"agents.invoke","params":{"agentId":"agent1","input":${input.toJson}},"jsonrpc":"2.0"}"""
    )
    assertWire(
      JsonRpc.Success[AgentsInvoke.Result](Json.Str("invoke"), output),
      s"""{"id":"invoke","result":${output.toJson},"jsonrpc":"2.0"}"""
    )
    val audio = Message(input.data.copy(payload = MessagePayload.AudioAttachment(blobId, AudioFormat.Wav)))
    assertEquals(
      AgentsInvoke.Params(agentId, audio).toJson.fromJson[AgentsInvoke.Params],
      Right(AgentsInvoke.Params(agentId, audio))
    )
    assert(!params.toJson.contains("sessionId"))
  }

  test("agents.invoke rejects legacy/session controls, missing input and mismatched message hashes") {
    val input = Message(MessageData(AgentId("human"), None, Instant.EPOCH, Message.User("hello")))
    val params = AgentsInvoke.Params(agentId, input)
    List("\"sessionId\":\"s1\"", "\"expectedHead\":null", "\"resetContinuation\":false").foreach { extra =>
      val json = params.toJson.dropRight(1) + "," + extra + "}"
      assert(json.fromJson[AgentsInvoke.Params].isLeft)
    }
    assert("""{"agentId":"agent1"}""".fromJson[AgentsInvoke.Params].isLeft)
    assert(params.copy(input = input.copy(id = head)).toJson.fromJson[AgentsInvoke.Params].isLeft)
  }

  test("turn.submit uses an optional expected-head message ID") {
    val params = TurnSubmit.Params(sessionId, agentId, AgentTask("Hello"))
    assertWire(
      JsonRpc.Request(Json.Str("r3"), TurnSubmit.method, params),
      """{"id":"r3","method":"turn.submit","params":{"sessionId":"s1","agentId":"agent1","task":{"prompt":"Hello"},"resetContinuation":false},"jsonrpc":"2.0"}"""
    )
    assertWire(
      params.copy(expectedHead = Some(head)),
      s"""{"sessionId":"s1","agentId":"agent1","task":{"prompt":"Hello"},"expectedHead":"${MessageId
          .value(head)}","resetContinuation":false}"""
    )
    assertEquals(
      """{"sessionId":"s1","agentId":"agent1","task":{"prompt":"Hello"}}"""
        .fromJson[TurnSubmit.Params],
      Right(params)
    )
    assertEquals(
      """{"sessionId":"s1","agentId":"agent1","task":{"prompt":"Hello"},"expectedHead":null}"""
        .fromJson[TurnSubmit.Params],
      Right(params)
    )
  }

  test("turn.submit resetContinuation is an explicit boolean defaulting to false") {
    assertWire(
      TurnSubmit.Params(sessionId, agentId, AgentTask("Hello"), resetContinuation = true),
      """{"sessionId":"s1","agentId":"agent1","task":{"prompt":"Hello"},"resetContinuation":true}"""
    )
  }

  test("sessions.fork uses the same optional head precondition and only names a source session") {
    assertWire(SessionsFork.Params(sessionId), """{"sourceSessionId":"s1"}""")
    assertWire(
      SessionsFork.Params(sessionId, Some(SessionId.unsafe("fork")), Some(head)),
      s"""{"sourceSessionId":"s1","name":"fork","expectedHead":"${MessageId.value(head)}"}"""
    )
    assertEquals(
      """{"sourceSessionId":"s1","expectedHead":null}""".fromJson[SessionsFork.Params],
      Right(SessionsFork.Params(sessionId))
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
      SessionSnapshot(sessionId, Chunk(agentId), Chunk(MessageSnapshot.from(message))),
      AgentResult(agentId, "Hi"),
      Chunk(MessageSnapshot.from(message))
    )
    val encoded = JsonRpc.Success(Json.Str("r3"), outcome).toJson
    val decoded = encoded.fromJson[JsonRpc.Success[TurnOutcome]].fold(fail(_), identity)
    assertEquals(decoded.result, outcome)
    assert(encoded.contains("\"status\":\"succeeded\""))
    assert(!encoded.contains("Succeeded"))
    assert(!encoded.contains("\"error\":"))
    val snapshot = s"""{"id":"${MessageId.value(message.id)}","agentId":"agent1","parentId":"${MessageId.value(
        head
      )}","timestamp":"2026-10-23T12:00:00Z","role":"assistant","content":"Hi","blobIds":[]}"""
    assertEquals(
      encoded,
      s"""{"id":"r3","result":{"status":"succeeded","session":{"id":"s1","agentIds":["agent1"],"messages":[$snapshot]},"result":{"agentId":"agent1","text":"Hi"},"newMessages":[$snapshot]},"jsonrpc":"2.0"}"""
    )
    assert(!encoded.contains("runId"))
    assert(!encoded.contains("commandId"))
  }

  test("terminal failures encode sanitized acceptance and omit absent snapshots") {
    List(
      PromptAcceptance.NotAccepted -> "notAccepted",
      PromptAcceptance.Accepted -> "accepted",
      PromptAcceptance.Unknown -> "unknown"
    ).foreach { case (acceptance, wireValue) =>
      val outcome: TurnOutcome = TurnOutcome.Failed(
        acceptance,
        None,
        ApplicationError("execution_failed", "Execution failed")
      )
      assertWire(
        outcome,
        s"""{"status":"failed","acceptance":"$wireValue","error":{"code":"execution_failed","message":"Execution failed"}}"""
      )
      val withSnapshot: TurnOutcome = TurnOutcome.Failed(
        acceptance,
        Some(SessionSnapshot(sessionId, Chunk(agentId), Chunk.empty)),
        ApplicationError("execution_failed", "Execution failed")
      )
      assertEquals(withSnapshot.toJson.fromJson[TurnOutcome], Right(withSnapshot))
    }
  }

  test("pre-execution rejection uses a JSON-RPC error, not TurnOutcome") {
    assertWire(
      JsonRpc.Failure(Json.Null, JsonRpc.Error.ParseError),
      """{"id":null,"error":{"code":-32700,"message":"Parse error"},"jsonrpc":"2.0"}"""
    )
    val details = ApplicationError("head_conflict", "Session head changed").toJsonAST.fold(fail(_), identity)
    assertWire(
      JsonRpc.Failure(Json.Str("r3"), JsonRpc.Error(ErrorCode.Conflict, "Conflict", Some(details))),
      """{"id":"r3","error":{"code":-32005,"message":"Conflict","data":{"code":"head_conflict","message":"Session head changed"}},"jsonrpc":"2.0"}"""
    )
    val expired = ApplicationError("command_details_expired", "Use commands.get for the retained run ID").toJsonAST
      .fold(fail(_), identity)
    assertWire(
      JsonRpc.Failure(Json.Str("r5"), JsonRpc.Error(ErrorCode.Conflict, "Conflict", Some(expired))),
      """{"id":"r5","error":{"code":-32005,"message":"Conflict","data":{"code":"command_details_expired","message":"Use commands.get for the retained run ID"}},"jsonrpc":"2.0"}"""
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
    assertWire(RunsCancel.Result("run1", false), """{"runId":"run1","requested":false}""")
    assertWire(
      DiagnosticsMessage.Params(sessionId, head),
      s"""{"sessionId":"s1","messageId":"${MessageId.value(head)}"}"""
    )
    val session = Session(sessionId, Chunk(AgentId("human")), Chunk.empty)
    assertWire(SessionSnapshot.from(session), """{"id":"s1","agentIds":["human"],"messages":[]}""")
  }

  test("diagnostics.request accepts a session ID and returns an optional document, never a file path") {
    assertWire(
      JsonRpc.Request(Json.Str("debug"), DiagnosticsRequest.method, DiagnosticsRequest.Params(sessionId)),
      """{"id":"debug","method":"diagnostics.request","params":{"sessionId":"s1"},"jsonrpc":"2.0"}"""
    )
    assertWire(DiagnosticsRequest.Result(None), "{}")
    assertWire(
      DiagnosticsRequest.Result(Some(Json.Obj("turnId" -> Json.Str("turn"), "payload" -> Json.Obj()))),
      """{"providerRequest":{"turnId":"turn","payload":{}}}"""
    )
    assert("""{"sessionId":"../file"}""".fromJson[DiagnosticsRequest.Params].isLeft)
    assert("{}".fromJson[DiagnosticsRequest.Params].isLeft)
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

  test("roles, disabled run states and supported methods are plain JSON values") {
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
  }

  test("initialize optional configured assistant IDs are omitted for old peers and roundtrip without private config") {
    val old = Initialize.Result(Version, "instance1", Identity(agentId, Role.Viewer), Nil, Nil, MaxBlobChunkBytes)
    assert(!old.toJson.contains("configuredAgentIds"))
    val capable = old.copy(configuredAgentIds = Some(Chunk(agentId)))
    assertWire(
      capable,
      """{"protocolVersion":1,"serverInstanceId":"instance1","identity":{"id":"agent1","role":"viewer"},"methods":[],"notifications":[],"maxBlobChunkBytes":65536,"configuredAgentIds":["agent1"]}"""
    )
    assertEquals(old.toJson.fromJson[Initialize.Result], Right(old))
  }

  test("disabled commands.get draft retains codec compatibility but is not activated") {
    assertWire(
      JsonRpc.Request(Json.Str("r4"), CommandsGet.method, CommandsGet.Params("c1")),
      """{"id":"r4","method":"commands.get","params":{"commandId":"c1"},"jsonrpc":"2.0"}"""
    )
    val running = RunsGet.Result("run1", "c1", sessionId, agentId, RunStatus.Running, None)
    assertWire(
      JsonRpc.Success(Json.Str("r4"), CommandsGet.Result("run1", Some(running))),
      """{"id":"r4","result":{"runId":"run1","run":{"runId":"run1","commandId":"c1","sessionId":"s1","agentId":"agent1","status":"running"}},"jsonrpc":"2.0"}"""
    )
    assertWire(
      JsonRpc.Success(Json.Str("r4"), CommandsGet.Result("run1", None)),
      """{"id":"r4","result":{"runId":"run1"},"jsonrpc":"2.0"}"""
    )
    val outcome: TurnOutcome =
      TurnOutcome.Failed(PromptAcceptance.Unknown, None, ApplicationError("execution_unknown", "Unknown"))
    val terminal = running.copy(status = RunStatus.Cancelled, outcome = Some(outcome))
    val result = CommandsGet.Result("run1", Some(terminal))
    assertEquals(result.toJson.fromJson[CommandsGet.Result], Right(result))
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
        ToolKey.unsafe("inspect"),
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
    assert("""{"sourceSessionId":"s1","expectedHead":"sha256:bad"}""".fromJson[SessionsFork.Params].isLeft)
    assert("""{"sourceSessionId":"s1","expectedHead":{"kind":"empty"}}""".fromJson[SessionsFork.Params].isLeft)
    assert(
      """{"sessionId":"s1","agentId":"agent1","task":{"prompt":"Hello"},"expectedHead":"sha256:bad"}"""
        .fromJson[TurnSubmit.Params]
        .isLeft
    )
    assert(
      """{"sessionId":"s1","agentId":"agent1","task":{"prompt":"Hello"},"expectedHead":{"kind":"empty"}}"""
        .fromJson[TurnSubmit.Params]
        .isLeft
    )
    assert("\"maybe\"".fromJson[PromptAcceptance].isLeft)
    assert(
      """{"agentId":"agent1","task":{"prompt":"Hello"}}""".fromJson[TurnSubmit.Params].isLeft
    )
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
