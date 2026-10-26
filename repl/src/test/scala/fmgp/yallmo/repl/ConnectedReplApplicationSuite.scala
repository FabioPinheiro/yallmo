package fmgp.yallmo.repl

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.server.OrchestratorClient
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import munit.FunSuite
import zio.*
import zio.json.ast.Json

final class ConnectedReplApplicationSuite extends FunSuite {
  private val user = AgentId("human")
  private val agent = AgentId("remote")
  private val id = SessionId.unsafe("one")
  private val message =
    MessageSnapshot(MessageId.sha256("snapshot"), user, None, java.time.Instant.EPOCH, "user", "first", Chunk.empty)

  private class Reads extends OrchestratorClient {
    var ids = Chunk(agent, user)
    var snapshot = SessionSnapshot(id, Chunk(agent), Chunk(message))
    var fail = false
    var calls = Vector.empty[String]
    private def read[A](name: String)(value: => A): IO[OrchestratorClient.Error, A] =
      ZIO.suspendSucceed {
        calls :+= name
        if fail then ZIO.fail(OrchestratorClient.Error.Disconnected) else ZIO.succeed(value)
      }
    def initialize(clientName: String) = ZIO.dieMessage("application must not initialize again")
    def listSessions = read("sessions.list")(Chunk(SessionSummary(id, snapshot.messages.size.toLong, Chunk(agent))))
    def loadSession(sessionId: SessionId) = read(s"sessions.get:${sessionId.value}")(snapshot.copy(id = sessionId))
    def listAgentIds = read("agents.list")(ids)
    def listMessages = ZIO.dieMessage("unexpected messages.list")
    def listBlobIds = ZIO.dieMessage("unexpected blobs.list")
    def readBlob(id: BlobId, offset: Long, maxBytes: Int) = ZIO.dieMessage("unexpected blobs.read")
    def close = ZIO.dieMessage("application must not own supplied connection")
  }

  test("session list and history are fresh reads; resume loads before selecting, never fabricates run context") {
    val client = new Reads
    run(for {
      app <- ConnectedReplApplication.make(client, user)
      empty <- app.submit(app.initialState, "/history")
      list1 <- app.submit(empty.state, "/session list")
      resumed <- app.submit(list1.state, "/session resume one")
      _ <- ZIO.succeed { client.snapshot = client.snapshot.copy(messages = Chunk(message.copy(content = "fresh"))) }
      history <- app.submit(resumed.state, "/history")
      list2 <- app.submit(history.state, "/session list")
    } yield {
      assertEquals(empty.events, Chunk(ReplEvent.SnapshotTranscript(Chunk.empty, None)))
      assertEquals(resumed.state.sessionId, Some("one"))
      assertEquals(history.events, Chunk(ReplEvent.SnapshotTranscript(client.snapshot.messages, Some("one"))))
      assertEquals(list2.state.runContext.messages, Chunk.empty)
      assertEquals(
        client.calls,
        Vector("agents.list", "sessions.list", "sessions.get:one", "sessions.get:one", "sessions.list")
      )
    })
  }

  test("invalid and failed resume retain the selected session and preferences; failed history/list do too") {
    val client = new Reads
    val state = ReplState(sessionId = Some("old"), logLevel = LogLevel.Debug)
    run(for {
      app <- ConnectedReplApplication.make(client, user, Some(agent))
      invalid <- app.submit(state, "/session resume ../bad")
      _ <- ZIO.succeed { client.fail = true }
      failed <- app.submit(state, "/session resume other")
      history <- app.submit(state, "/history")
      list <- app.submit(state, "/session list")
    } yield {
      List(invalid, failed, history, list).foreach { result =>
        assertEquals(result.state, state)
        assert(result.events.head.isInstanceOf[ReplEvent.SessionFailed])
      }
      assertEquals(app.selectedAgentId, Some(agent))
      assert(!client.calls.exists(_.contains("bad")))
    })
  }

  test("agent preference refreshes server IDs on the same client; failures do not change the old application") {
    val client = new Reads
    run(for {
      app <- ConnectedReplApplication.make(client, user, Some(agent))
      _ <- ZIO.succeed { client.ids = Chunk(user) }
      missing <- app.selectAgent(agent).either
      next <- app.selectAgent(user)
      _ <- ZIO.succeed { client.fail = true }
      failed <- next.selectAgent(agent).either
    } yield {
      assert(missing.isLeft); assert(failed.isLeft)
      assertEquals(app.selectedAgentId, Some(agent))
      assertEquals(next.selectedAgentId, Some(user))
      assertEquals(next.activeAgent, None)
      assertEquals(next.agentChoices.map(_.model), Vector(None))
      assertEquals(client.calls, Vector.fill(4)("agents.list"))
    })
  }

  test("prompts and all privileged commands are explicit unavailable events without any client invocation") {
    val client = new Reads
    run(for {
      app <- ConnectedReplApplication.make(client, user)
      results <- ZIO.foreach(
        List("hello", "//literal", "/new", "/new name", "/tools", "/debug print", "/debug export", "/debug request")
      )(app.submit(app.initialState, _))
      help <- app.submit(app.initialState, "/help")
      log <- app.submit(app.initialState, "/log info")
      exit <- app.submit(log.state, "/exit")
    } yield {
      assert(results.forall(_.events == Chunk(ReplEvent.Unavailable(ConnectedReplApplication.Unsupported))))
      assertEquals(client.calls, Vector("agents.list"))
      assertEquals(app.providerCatalog, None); assertEquals(app.providerLogin, None);
      assertEquals(app.transcriber, None)
      assert(help.events.head.toString.contains("/history"))
      assert(!help.events.head.toString.contains("/new"))
      assertEquals(log.state.logLevel, LogLevel.Info)
      assert(exit.exitsClient)
    })
  }

  test("command log thresholds use initial and /log preferences, restore ambient level and survive read failures") {
    run(ZIO.scoped {
      for {
        _ <- ZIO.logLevelScoped(LogLevel.Error)
        levels <- Ref.make(Vector.empty[LogLevel])
        client = new Reads {
          override def listSessions =
            FiberRef.currentLogLevel.get.flatMap(level => levels.update(_ :+ level)) *> super.listSessions
        }
        app <- ConnectedReplApplication.make(client, user, logLevel = LogLevel.Trace)
        selected <- app.selectAgent(agent)
        initial <- selected.submit(selected.initialState, "/session list")
        afterInitial <- FiberRef.currentLogLevel.get
        off <- selected.submit(initial.state, "/log off")
        afterLog <- FiberRef.currentLogLevel.get
        listed <- selected.submit(off.state, "/session list")
        afterList <- FiberRef.currentLogLevel.get
        _ <- ZIO.succeed { client.fail = true }
        failed <- selected.submit(listed.state, "/session list")
        afterFailure <- FiberRef.currentLogLevel.get
        observed <- levels.get
      } yield {
        assertEquals(app.initialState.logLevel, LogLevel.Trace)
        assertEquals(selected.initialState.logLevel, LogLevel.Trace)
        assertEquals(observed, Vector(LogLevel.Trace, LogLevel.None, LogLevel.None))
        assertEquals(List(afterInitial, afterLog, afterList, afterFailure), List.fill(4)(LogLevel.Error))
        assertEquals(off.state.logLevel, LogLevel.None)
        assertEquals(listed.state, off.state)
        assertEquals(failed.state, off.state)
        assert(failed.events.head.isInstanceOf[ReplEvent.SessionFailed])
      }
    })
  }

  test("startup selection validates server IDs and propagates read failures") {
    val client = new Reads
    run(for {
      unknown <- ConnectedReplApplication.make(client, user, Some(AgentId("missing"))).either
      _ <- ZIO.succeed { client.fail = true }
      failed <- ConnectedReplApplication.make(client, user).either
    } yield { assert(unknown.isLeft); assert(failed.isLeft) })
  }

  private class Writes extends Reads {
    var info = Initialize.Result(
      1,
      "test",
      Identity(user, Role.Operator),
      List(SessionsCreate.method, TurnSubmit.method),
      Nil,
      MaxBlobChunkBytes,
      Some(Chunk(agent))
    )
    var creates = Vector.empty[Option[SessionId]]
    var turns = Vector.empty[(SessionId, AgentId, AgentTask, Option[MessageId], Boolean)]
    var diagnostic = DiagnosticsRequest.Result(None)
    var diagnosticError: Option[OrchestratorClient.Error] = None
    override def providerRequest(sessionId: SessionId) = ZIO.suspendSucceed {
      calls :+= s"diagnostics.request:${sessionId.value}"
      diagnosticError.fold[IO[OrchestratorClient.Error, DiagnosticsRequest.Result]](ZIO.succeed(diagnostic))(
        ZIO.fail(_)
      )
    }
    var createError: Option[OrchestratorClient.Error] = None
    var turnError: Option[OrchestratorClient.Error] = None
    val empty = SessionSnapshot(id, Chunk(user), Chunk.empty)
    val reply = message.copy(id = MessageId.sha256("answer"), agentId = agent, role = "assistant", content = "answer")
    val completed = empty.copy(messages = Chunk(message, reply))
    var outcome: TurnOutcome = TurnOutcome.Succeeded(completed, AgentResult(agent, "answer"), Chunk(message, reply))
    override def capabilities = ZIO.some(info)
    override def createSession(name: Option[SessionId]) = ZIO.suspendSucceed {
      creates :+= name
      createError.fold[IO[OrchestratorClient.Error, SessionSnapshot]](ZIO.succeed(empty))(ZIO.fail(_))
    }
    override def submitTurn(
        sessionId: SessionId,
        agentId: AgentId,
        task: AgentTask,
        expectedHead: Option[MessageId],
        resetContinuation: Boolean
    ) = ZIO.suspendSucceed {
      turns :+= ((sessionId, agentId, task, expectedHead, resetContinuation))
      turnError.fold[IO[OrchestratorClient.Error, TurnOutcome]](ZIO.succeed(outcome))(ZIO.fail(_))
    }
  }

  test("cached capabilities require Operator and both methods; configured assistants exclude humans") {
    val client = new Writes
    run(for {
      app <- ConnectedReplApplication.make(client, user, Some(agent))
      human <- app.selectAgent(user).either
      _ <- ZIO.succeed { client.info = client.info.copy(identity = Identity(user, Role.Viewer)) }
      viewer <- ConnectedReplApplication.make(client, user)
      _ <- ZIO.succeed {
        client.info = client.info.copy(identity = Identity(user, Role.Operator), methods = List(TurnSubmit.method))
      }
      partial <- ConnectedReplApplication.make(client, user)
    } yield {
      assert(app.remoteConnection); assert(app.canSubmit); assert(!app.connectedReadOnly)
      assertEquals(app.activeAgent, None)
      assertEquals(app.agentChoices.map(_.id), Vector(agent)); assert(human.isLeft)
      assert(viewer.connectedReadOnly); assert(partial.connectedReadOnly)
      assertEquals(client.calls, Vector.empty)
      assert(app.notice.contains("trusted local, unauthenticated"))
    })
  }

  test("no selected configured agent means no create and no submit; selection is per application") {
    val client = new Writes
    run(for {
      unselected <- ConnectedReplApplication.make(client, user)
      blocked <- unselected.submit(unselected.initialState, "hello")
      selected <- unselected.selectAgent(agent)
    } yield {
      assertEquals(blocked.state, unselected.initialState)
      assert(blocked.events.toString.contains("--agent"))
      assertEquals(client.creates, Vector.empty); assertEquals(client.turns, Vector.empty)
      assertEquals(selected.selectedAgentId, Some(agent)); assertEquals(unselected.selectedAgentId, None)
    })
  }

  test("first create then single submit returns durable delta and head; subsequent prompt reuses session") {
    val client = new Writes
    run(for {
      app <- ConnectedReplApplication.make(client, user, Some(agent))
      first <- app.submit(app.initialState, "hello")
      next = client.reply.copy(id = MessageId.sha256("next answer"), content = "next answer")
      _ <- ZIO.succeed {
        client.outcome = TurnOutcome.Succeeded(
          client.completed.copy(messages = client.completed.messages :+ next),
          AgentResult(agent, "next answer"),
          Chunk(next)
        )
      }
      second <- app.submit(first.state, "again")
      help <- app.submit(second.state, "/help")
    } yield {
      assertEquals(client.creates, Vector(None))
      assertEquals(client.turns.map(_._3), Vector(AgentTask("hello"), AgentTask("again")))
      assertEquals(client.turns.map(_._4), Vector(None, Some(client.reply.id)))
      assertEquals(first.events, Chunk(ReplEvent.SnapshotDelta(Chunk(message, client.reply))))
      assertEquals(second.events, Chunk(ReplEvent.SnapshotDelta(Chunk(next))))
      assertEquals(second.state.sessionId, Some("one")); assertEquals(second.state.remoteHeadMessageId, Some(next.id))
      assertEquals(second.state.runContext, RunContext())
      assert(help.events.toString.contains("/new")); assert(!help.events.toString.contains("/tools"))
    })
  }

  test("create failure leaves original state and never submits; explicit new resets only remote session flags") {
    val client = new Writes
    val original = ReplState(
      sessionId = Some("old"),
      logLevel = LogLevel.Debug,
      remoteHeadMessageId = Some(message.id),
      remoteResetContinuation = true
    )
    run(for {
      app <- ConnectedReplApplication.make(client, user, Some(agent))
      _ <- ZIO.succeed { client.createError = Some(OrchestratorClient.Error.Disconnected) }
      failed <- app.submit(app.initialState, "hello")
      failedNew <- app.submit(original, "/new named")
      _ <- ZIO.succeed { client.createError = None }
      created <- app.submit(original, "/new named")
    } yield {
      assertEquals(failed.state, app.initialState); assertEquals(failedNew.state, original)
      assert(failed.events.toString.contains("/session list"));
      assert(failedNew.events.toString.contains("/session list"))
      assertEquals(client.turns, Vector.empty)
      assertEquals(client.creates, Vector(None, Some(SessionId.unsafe("named")), Some(SessionId.unsafe("named"))))
      assertEquals(
        created.state,
        original.copy(sessionId = Some("one"), remoteHeadMessageId = None, remoteResetContinuation = false)
      )
    })
  }

  test("accepted failure retains returned snapshot, session and head without leaking error contents") {
    val client = new Writes
    client.outcome = TurnOutcome.Failed(
      PromptAcceptance.Accepted,
      Some(client.completed.copy(messages = Chunk(message))),
      ApplicationError("private", "PRIVATE path/credentials")
    )
    run(for {
      app <- ConnectedReplApplication.make(client, user, Some(agent))
      failed <- app.submit(app.initialState, "keep")
    } yield {
      assertEquals(failed.state.sessionId, Some("one"));
      assertEquals(failed.state.remoteHeadMessageId, Some(message.id))
      assertEquals(failed.state.runContext, RunContext())
      assertEquals(failed.events.head, ReplEvent.SnapshotTranscript(Chunk(message), Some("one")))
      assert(failed.events.last.toString.contains("prompt remains"));
      assert(!failed.events.toString.contains("PRIVATE"))
      assertEquals(client.creates.size, 1); assertEquals(client.turns.size, 1)
    })
  }

  test("lost reply after create retains known session without replay; unknown and notAccepted keep honest state") {
    val client = new Writes
    run(for {
      app <- ConnectedReplApplication.make(client, user, Some(agent))
      _ <- ZIO.succeed { client.turnError = Some(OrchestratorClient.Error.Disconnected) }
      lost <- app.submit(app.initialState, "only once")
      _ <- ZIO.succeed {
        client.turnError = None;
        client.outcome = TurnOutcome.Failed(PromptAcceptance.Unknown, None, ApplicationError("unknown", "secret"))
      }
      unknown <- app.submit(lost.state, "manual next")
      _ <- ZIO.succeed {
        client.outcome =
          TurnOutcome.Failed(PromptAcceptance.NotAccepted, None, ApplicationError("notAccepted", "secret"))
      }
      rejected <- app.submit(unknown.state, "manual next")
    } yield {
      List(lost, unknown, rejected).foreach(result => assertEquals(result.state.sessionId, Some("one")))
      assertEquals(lost.state.remoteHeadMessageId, None); assertEquals(unknown.state, lost.state)
      assert(lost.events.toString.contains("Refresh /history")); assert(unknown.events.toString.contains("unknown"))
      assert(rejected.events.toString.contains("not accepted"))
      assertEquals(client.creates.size, 1); assertEquals(client.turns.size, 3)
    })
  }

  test("Busy and stale-head keep state and continuation intent with explicit refresh guidance and no retries") {
    val client = new Writes
    val state =
      ReplState(sessionId = Some("one"), remoteHeadMessageId = Some(message.id), remoteResetContinuation = true)
    run(for {
      app <- ConnectedReplApplication.make(client, user, Some(agent))
      failures <- ZIO.foreach(List(ErrorCode.Busy, ErrorCode.Conflict)) { code =>
        ZIO.succeed {
          client.turnError =
            Some(OrchestratorClient.Error.Remote(fmgp.yallmo.server.jsonrpc.JsonRpc.Error(code, "PRIVATE")))
        } *>
          app.submit(state, "must not replay")
      }
    } yield {
      failures.foreach { failure =>
        assertEquals(failure.state, state);
        assert(failure.events.toString.contains("refresh") || failure.events.toString.contains("Refresh"))
        assert(!failure.events.toString.contains("PRIVATE"))
      }
      assertEquals(client.creates, Vector.empty); assertEquals(client.turns.size, 2)
      assert(client.turns.forall(_._5))
    })
  }

  test("resume and fresh history track head, request reset once and never send local continuation offsets") {
    val client = new Writes
    run(for {
      app <- ConnectedReplApplication.make(client, user, Some(agent))
      resumed <- app.submit(app.initialState.copy(continuationStart = 42), "/session resume one")
      _ <- ZIO.succeed { client.snapshot = client.completed }
      history <- app.submit(resumed.state, "/history")
      submitted <- app.submit(history.state, "next")
      _ <- app.submit(submitted.state, "ordinary")
    } yield {
      assertEquals(resumed.state.remoteHeadMessageId, Some(message.id)); assert(resumed.state.remoteResetContinuation)
      assertEquals(history.state.remoteHeadMessageId, Some(client.reply.id));
      assert(history.state.remoteResetContinuation)
      assertEquals(client.turns.map(_._4), Vector.fill(2)(Some(client.reply.id)))
      assertEquals(client.turns.map(_._5), Vector(true, false))
      assertEquals(submitted.state.continuationStart, 42); assertEquals(submitted.state.runContext, RunContext())
      assertEquals(client.creates, Vector.empty)
    })
  }

  test("debug request is a fresh server read, never a client-local capture or a session mutation") {
    val client = new Writes
    client.info = client.info.copy(methods = client.info.methods :+ DiagnosticsRequest.method)
    val document = Json.Obj("turnId" -> Json.Str("provider-turn"), "payload" -> Json.Obj("model" -> Json.Str("actual")))
    client.diagnostic = DiagnosticsRequest.Result(Some(document))
    val state = ReplState(
      sessionId = Some("one"),
      logLevel = LogLevel.Debug,
      lastProviderRequest = Some(java.nio.file.Path.of("client-file-must-not-be-read")),
      remoteResetContinuation = true
    )
    run(for {
      app <- ConnectedReplApplication.make(client, user, Some(agent))
      beforeSession <- app.submit(app.initialState, "/debug request")
      first <- app.submit(state, "/debug request")
      _ <- ZIO.succeed { client.diagnostic = DiagnosticsRequest.Result(None) }
      fresh <- app.submit(state, "/debug request")
      help <- app.submit(state, "/help")
      unavailable <- app.submit(state, "/debug print")
    } yield {
      assert(app.canReadRequests)
      assertEquals(beforeSession.events, Chunk(ReplEvent.ProviderRequestDocument(None)))
      assertEquals(first.events, Chunk(ReplEvent.ProviderRequestDocument(Some(document))))
      assertEquals(fresh.events, Chunk(ReplEvent.ProviderRequestDocument(None)))
      List(first, fresh, help, unavailable).foreach(result => assertEquals(result.state, state))
      val entries = help.events.head.asInstanceOf[ReplEvent.Help].entries
      assert(entries.exists(_.usage == "/debug request"))
      assert(!entries.exists(_.usage.contains("export")))
      assertEquals(client.calls, Vector.fill(2)("diagnostics.request:one"))
      assertEquals(client.creates, Vector.empty); assertEquals(client.turns, Vector.empty)
      assert(unavailable.events.head.isInstanceOf[ReplEvent.Unavailable])
    })
  }

  test("debug request requires its negotiated capability and an operator role") {
    run(ZIO.foreach(List(false, true)) { viewer =>
      val client = new Writes
      if viewer then
        client.info = client.info
          .copy(identity = Identity(user, Role.Viewer), methods = client.info.methods :+ DiagnosticsRequest.method)
      for {
        app <- ConnectedReplApplication.make(client, user)
        blocked <- app.submit(ReplState(sessionId = Some("one")), "/debug request")
        help <- app.submit(app.initialState, "/help")
      } yield {
        assert(!app.canReadRequests)
        assert(blocked.events.head.isInstanceOf[ReplEvent.Unavailable])
        assert(!help.events.head.toString.contains("/debug"))
        assertEquals(client.calls, Vector.empty)
      }
    })
  }

  test("debug read errors and invalid session IDs keep state and never expose remote exception details") {
    val client = new Writes
    client.info = client.info.copy(methods = client.info.methods :+ DiagnosticsRequest.method)
    val state = ReplState(sessionId = Some("one"), remoteHeadMessageId = Some(message.id))
    run(for {
      app <- ConnectedReplApplication.make(client, user)
      _ <- ZIO.succeed {
        client.diagnosticError = Some(OrchestratorClient.Error.Transport(new Exception("PRIVATE /path")))
      }
      failed <- app.submit(state, "/debug request")
      _ <- ZIO.succeed {
        client.diagnosticError = Some(
          OrchestratorClient.Error.Remote(fmgp.yallmo.server.jsonrpc.JsonRpc.Error(ErrorCode.Rejected, "PRIVATE /path"))
        )
      }
      rejected <- app.submit(state, "/debug request")
      invalid <- app.submit(state.copy(sessionId = Some("../invalid")), "/debug request")
    } yield {
      assertEquals(failed.state, state); assertEquals(rejected.state, state)
      assert(failed.events.head.isInstanceOf[ReplEvent.Unavailable])
      assert(rejected.events.toString.contains("diagnostic limit"))
      assert(!failed.events.toString.contains("PRIVATE")); assert(!rejected.events.toString.contains("PRIVATE"))
      assertEquals(invalid.state.sessionId, Some("../invalid"))
      assertEquals(client.calls, Vector.fill(2)("diagnostics.request:one"))
    })
  }

  private def run[A](effect: Task[A]): A = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe.run(effect.timeoutFail(new Exception("test timeout"))(10.seconds)).getOrThrowFiberFailure()
  }
}
