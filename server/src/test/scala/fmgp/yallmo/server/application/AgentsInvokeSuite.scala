package fmgp.yallmo.server.application

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.orchestration.config.{
  AgentCapabilities,
  AgentConfig,
  Config,
  OAuthCredentialsConfig,
  ProviderConfig,
  ProviderId
}
import fmgp.yallmo.server.*
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.Comparator
import munit.FunSuite
import scala.collection.immutable.VectorMap
import zio.{Config as _, *}
import zio.http.{Version as HttpVersion, *}
import zio.json.*
import zio.json.ast.Json

final class AgentsInvokeSuite extends FunSuite {
  private val human = AgentId("human")
  private val assistant = AgentId("assistant")
  private val providerId = ProviderId.unsafe("fake")
  private val input = Message(MessageData(human, None, Instant.EPOCH, Message.User("hello")))
  private val definition = AgentConfig(
    "OAIAgent",
    Seq(providerId),
    Json.Obj("model" -> Json.Str("test"), "systemPrompt" -> Json.Str("Configured instructions")),
    capabilities = AgentCapabilities(tools = Some(Set.empty))
  )
  private val config = Config(
    agents = Map(assistant -> definition),
    providers = Map(providerId -> ProviderConfig.OAI("http://fake.invalid/v1"))
  )
  private def answer =
    Response.text("""{"choices":[{"message":{"role":"assistant","content":"answer"},"finish_reason":"stop"}]}""")

  for persisted <- List(false, true) do
    test(s"${
        if persisted then "filesystem" else "memory"
      }: ordinary Message request/result, exact author/hash, no session creation or inherited history") {
      run(ZIO.scoped {
        for {
          root <- if persisted then temporaryRoot.map(Some(_)) else ZIO.none
          seen <- Ref.make(Vector.empty[String])
          fixture <- make(request => request.body.asString.flatMap(body => seen.update(_ :+ body)).as(answer), root)
          existing <- fixture.backend.create(human, Some(SessionId.unsafe("unrelated")))
          _ <- fixture.backend.submit(human, existing.id, assistant, "earlier")
          before <- fixture.live.loadSession(existing.id)
          reply <- invoke(fixture)
          output = decode[JsonRpc.Success[Message]](reply).result
          after <- fixture.live.loadSession(existing.id)
          stored <- fixture.live.listMessages
          requests <- seen.get
          sessions <- fixture.live.listSessions
          reopened <- root.fold(fixture.live.listMessages)(path =>
            OrchestratorLive.filesystem(path, fixture.runtime.instances).flatMap(_.listMessages)
          )
          info <- initialize(fixture.rpc, fixture.connection, Some(human))
        } yield {
          assertEquals(output.agentId, assistant)
          assertEquals(output.parentId, Some(input.id))
          assertEquals(output.payload, Message.Assistant("answer"))
          assertEquals(output.id, MessageId.from(output.data))
          assert(stored.contains(input))
          assert(stored.contains(output))
          assertEquals(before, after)
          assertEquals(sessions.map(_.id), Chunk(existing.id))
          assertEquals(reopened, stored)
          val sent =
            ujson.read(requests.last)("messages").arr.map(value => value("role").str -> value("content").str).toVector
          assertEquals(sent, Vector("user" -> "hello", "system" -> "Configured instructions"))
          assert(!reply.contains("sessionId"))
          assert(decode[JsonRpc.Success[Initialize.Result]](info).result.methods.contains(AgentsInvoke.method))
        }
      })
    }

  test(
    "only configured initialized operators may invoke; claimed message authors cannot override connection identity"
  ) {
    run(ZIO.scoped {
      for {
        calls <- Ref.make(0)
        fixture <- make(_ => calls.update(_ + 1).as(answer))
        uninitialized <- fixture.rpc.openConnection
        unauthorized <- call(fixture.rpc, uninitialized, AgentsInvoke.method, AgentsInvoke.Params(assistant, input))
        viewer <- fixture.rpc.openConnection
        info <- initialize(fixture.rpc, viewer, None)
        forbidden <- call(fixture.rpc, viewer, AgentsInvoke.method, AgentsInvoke.Params(assistant, input))
        readonly = new OrchestratorRpc(fixture.live)
        readonlyConnection <- readonly.openConnection
        readonlyInfo <- initialize(readonly, readonlyConnection, Some(human))
        unavailable <- call(readonly, readonlyConnection, AgentsInvoke.method, AgentsInvoke.Params(assistant, input))
        spoof = Message(input.data.copy(agentId = assistant))
        spoofed <- invoke(fixture, spoof)
        _ <- fixture.rpc.disconnect(uninitialized) *> fixture.rpc.disconnect(viewer) *> readonly.disconnect(
          readonlyConnection
        )
        count <- calls.get
        stored <- fixture.live.listMessages
      } yield {
        assertEquals(decode[JsonRpc.Failure](unauthorized).error.code, ErrorCode.Unauthorized)
        assertEquals(decode[JsonRpc.Failure](forbidden).error.code, ErrorCode.Forbidden)
        assertEquals(decode[JsonRpc.Failure](unavailable).error, JsonRpc.Error.MethodNotFound)
        assertEquals(decode[JsonRpc.Failure](spoofed).error.code, ErrorCode.Forbidden)
        List(info, readonlyInfo).foreach(value =>
          assert(!decode[JsonRpc.Success[Initialize.Result]](value).result.methods.contains(AgentsInvoke.method))
        )
        assertEquals(count, 0)
        assertEquals(stored, Chunk.empty)
      }
    })
  }

  test(
    "invalid hashes, missing parents and unknown agents cannot save input or call a provider"
  ) {
    run(ZIO.scoped {
      for {
        calls <- Ref.make(0)
        fixture <- make(_ => calls.update(_ + 1).as(answer))
        malformed <- invoke(fixture, input.copy(id = MessageId.sha256("wrong")))
        rejected <- ZIO.foreach(
          Chunk(
            Message(input.data.copy(parentId = Some(input.id)))
          )
        )(invoke(fixture, _))
        unknown <- invoke(fixture, recipient = AgentId("unknown"))
        stored <- fixture.live.listMessages
        count <- calls.get
      } yield {
        assertEquals(decode[JsonRpc.Failure](malformed).error, JsonRpc.Error.InvalidParams)
        rejected.foreach(value => assertEquals(decode[JsonRpc.Failure](value).error.code, ErrorCode.Rejected))
        assertEquals(decode[JsonRpc.Failure](unknown).error.code, ErrorCode.Rejected)
        assertEquals(stored, Chunk.empty)
        assertEquals(count, 0)
      }
    })
  }

  test("ordinary configured capabilities and the tool loop apply to independent invocations") {
    run(ZIO.scoped {
      for {
        requests <- Ref.make(Vector.empty[String])
        executions <- Ref.make(0)
        tool = new AgentTool[Any] {
          val definition = AgentToolDefinition("echo", "Echo")
          def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
            executions.update(_ + 1).as(ExternalToolResult("tool output"))
        }
        enabled = config.copy(agents =
          Map(assistant -> definition.copy(capabilities = AgentCapabilities(tools = Some(Set("echo")))))
        )
        fixture <- make(
          request =>
            request.body.asString.flatMap(body => requests.updateAndGet(_ :+ body)).map { seen =>
              if seen.size == 1 then
                Response.text(
                  """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"call-1","function":{"name":"echo","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
                )
              else answer
            },
          configuration = enabled,
          tools = Chunk(tool)
        )
        reply <- invoke(fixture)
        sent <- requests.get
        count <- executions.get
        stored <- fixture.live.listMessages
      } yield {
        assertEquals(decode[JsonRpc.Success[Message]](reply).result.payload, Message.Assistant("answer"))
        assertEquals(count, 1)
        assertEquals(sent.size, 2)
        assertEquals(ujson.read(sent.head)("tools")(0)("function")("name").str, "echo")
        assert(
          ujson
            .read(sent.last)("messages")
            .arr
            .exists(item => item("role").str == "tool" && item("content").str == "tool output")
        )
        assertEquals(stored.size, 2)
        assert(!stored.exists(_.payload.role == "tool_result"))
      }
    })
  }

  test("direct scalamode calls preserve correlation, persist ToolOutput, and never invoke the provider") {
    run(ZIO.scoped {
      for {
        providers <- Ref.make(0)
        executions <- Ref.make(Chunk.empty[ExternalToolCall])
        tool = new AgentTool[Any] {
          val definition = fmgp.yallmo.tool.ScalaTool.definition
          def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
            executions
              .update(_ :+ call)
              .as(ExternalToolResult("""{"exitCode":0,"timedOut":false,"output":"42\\n","truncated":false}"""))
        }
        allowed = config.copy(agents =
          Map(assistant -> definition.copy(capabilities = AgentCapabilities(tools = Some(Set("scalamode")))))
        )
        fixture <- make(_ => providers.update(_ + 1).as(answer), configuration = allowed, tools = Chunk(tool))
        function = FunctionCall(FunctionCallId.unsafe("browser-call"), "scalamode", """{"code":"40 + 2"}""")
        request = Message(input.data.copy(payload = Message.ToolCall(function)))
        reply <- invoke(fixture, request)
        response = decode[JsonRpc.Success[Message]](reply).result
        invalid = function.copy(id = FunctionCallId.unsafe("bad-arguments"), argumentsJson = """{"code":42}""")
        badReply <- invoke(fixture, Message(input.data.copy(payload = Message.ToolCall(invalid))))
        unavailable = function.copy(id = FunctionCallId.unsafe("denied"), name = "bash")
        deniedReply <- invoke(fixture, Message(input.data.copy(payload = Message.ToolCall(unavailable))))
        calls <- executions.get
        count <- providers.get
        stored <- fixture.live.listMessages
        sessions <- fixture.live.listSessions
      } yield {
        assertEquals(response.agentId, assistant)
        assertEquals(response.parentId, Some(request.id))
        assertEquals(
          response.payload,
          Message.ToolOutput(
            FunctionCallOutput(
              function.id,
              function.name,
              """{"exitCode":0,"timedOut":false,"output":"42\\n","truncated":false}"""
            )
          )
        )
        assertEquals(calls, Chunk(ExternalToolCall(function.name, function.argumentsJson, Some(function.id))))
        assertEquals(count, 0)
        assert(stored.contains(request) && stored.contains(response))
        assertEquals(sessions, Chunk.empty)
        List(badReply, deniedReply).foreach { reply =>
          assert(decode[JsonRpc.Success[Message]](reply).result.payload.isInstanceOf[MessagePayload.ToolOutput])
          assert(reply.contains("error"))
        }
      }
    })
  }

  test("direct tools retain configured denial and approval gates without inference") {
    run(ZIO.scoped {
      for {
        executions <- Ref.make(0)
        approvals <- Ref.make(0)
        tool = new AgentTool[Any] {
          val definition = fmgp.yallmo.tool.ScalaTool.definition
          def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
            executions.update(_ + 1).as(ExternalToolResult("must not run"))
        }
        guarded = ToolApproval.guard(
          tool,
          new ToolApproval[Any] {
            def approve(definition: AgentToolDefinition, call: ExternalToolCall): IO[YallmoError, Unit] =
              approvals.update(_ + 1) *> ZIO.fail(YallmoError.InvalidConfig("Approval denied"))
          }
        )
        allowed = config.copy(agents =
          Map(assistant -> definition.copy(capabilities = AgentCapabilities(tools = Some(Set("scalamode")))))
        )
        denied <- make(_ => ZIO.dieMessage("No provider inference"), tools = Chunk(guarded))
        permitted <- make(_ => ZIO.dieMessage("No provider inference"), configuration = allowed, tools = Chunk(guarded))
        request = Message(
          input.data.copy(payload =
            Message.ToolCall(FunctionCall(FunctionCallId.unsafe("gate"), "scalamode", """{"code":"42"}"""))
          )
        )
        deniedReply <- invoke(denied, request)
        approvedReply <- invoke(permitted, request)
        count <- executions.get
        gates <- approvals.get
      } yield {
        assertEquals(count, 0)
        assertEquals(gates, 1)
        assert(deniedReply.contains("ToolUnavailableToAgent"))
        assert(approvedReply.contains("Approval denied"))
      }
    })
  }

  test("other payloads and existing parents are accepted without inheriting parent history") {
    run(ZIO.scoped {
      for {
        fixture <- make(_ => ZIO.succeed(answer))
        _ <- invoke(fixture)
        responses <- ZIO.foreach(
          Chunk[MessagePayload](
            Message.Assistant("forwarded text"),
            Message.Context("submitted", "data"),
            Message.Instructions("not a configured system prompt"),
            Message.ToolOutput(FunctionCallOutput(FunctionCallId.unsafe("forwarded"), "other", "data"))
          )
        ) { payload =>
          invoke(fixture, Message(input.data.copy(parentId = Some(input.id), payload = payload)))
        }
      } yield responses.foreach(reply =>
        assertEquals(decode[JsonRpc.Success[Message]](reply).result.payload, Message.Assistant("answer"))
      )
    })
  }

  test("provider failures and defects expose no private details and preserve accepted input") {
    run(ZIO.scoped {
      ZIO.foreachDiscard(List(false, true)) { defect =>
        for {
          fixture <- make(_ =>
            if defect then ZIO.dieMessage("PRIVATE credentials")
            else ZIO.succeed(Response.text("PRIVATE credentials").status(Status.InternalServerError))
          )
          failed <- invoke(fixture)
          stored <- fixture.live.listMessages
          sessions <- fixture.live.listSessions
        } yield {
          assertEquals(decode[JsonRpc.Failure](failed).error, JsonRpc.Error.InternalError)
          assert(!failed.contains("PRIVATE"))
          assertEquals(stored, Chunk(input))
          assertEquals(sessions, Chunk.empty)
        }
      }
    })
  }

  test("private provider reasoning is not returned/persisted and the next invocation does not reuse continuation") {
    run(ZIO.scoped {
      for {
        seen <- Ref.make(Vector.empty[String])
        encoded = java.util.Base64.getUrlEncoder
          .withoutPadding()
          .encodeToString(
            """{"https://api.openai.com/auth":{"chatgpt_account_id":"fake"}}""".getBytes(
              java.nio.charset.StandardCharsets.UTF_8
            )
          )
        credentials = OAuthCredentialsConfig(s"header.$encoded.signature", "fake-refresh", Long.MaxValue)
        configuration = config.copy(
          agents = Map(assistant -> definition.copy(agentType = "ChatGPTAgent")),
          providers = Map(providerId -> ProviderConfig.ChatGPT(Some(credentials)))
        )
        fixture <- make(
          request =>
            request.body.asString
              .flatMap(body => seen.update(_ :+ body))
              .as(
                Response.text(
                  """data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"reasoning","encrypted_content":"PRIVATE-CONTINUATION","summary":[{"type":"summary_text","text":"summary"}]},{"type":"message","content":[{"type":"output_text","text":"answer"}]}]}}

"""
                )
              ),
          configuration = configuration
        )
        first <- invoke(fixture)
        second <- invoke(fixture, Message(input.data.copy(payload = Message.User("another"))))
        requests <- seen.get
        stored <- fixture.live.listMessages
      } yield {
        List(first, second).foreach { reply =>
          assertEquals(decode[JsonRpc.Success[Message]](reply).result.payload, Message.Assistant("answer"))
          assert(!reply.contains("PRIVATE-CONTINUATION"))
        }
        assert(requests.forall(body => !body.contains("PRIVATE-CONTINUATION")))
        assertEquals(stored.size, 4)
        assert(!stored.exists(_.payload.isInstanceOf[MessagePayload.Reasoning]))
      }
    })
  }

  test("unknown blob and upload abort fail without starting provider work") {
    run(ZIO.scoped {
      for {
        calls <- Ref.make(0)
        fixture <- make(_ => calls.update(_ + 1).as(answer))
        missing = Message(input.data.copy(payload = MessagePayload.AudioAttachment(BlobId.fresh(), AudioFormat.Wav)))
        unknown <- invoke(fixture, missing)
        id = BlobId.fresh()
        audio = Message(input.data.copy(payload = MessagePayload.AudioAttachment(id, AudioFormat.Wav)))
        handle <- fixture.live.beginBlobUpload(id, 1)
        running <- invoke(fixture, audio).fork
        _ <- fixture.live.listMessages.repeatUntil(_.exists(_.id == audio.id))
        _ <- suspended(running)
        _ <- handle.abort
        aborted <- running.join
        count <- calls.get
      } yield {
        assertEquals(decode[JsonRpc.Failure](unknown).error.code, ErrorCode.NotFound)
        assert(Set(ErrorCode.Rejected, ErrorCode.NotFound).contains(decode[JsonRpc.Failure](aborted).error.code))
        assertEquals(count, 0)
      }
    })
  }

  test("an admitted invocation belongs to the application, not the caller's interruptible wait") {
    run(ZIO.scoped {
      for {
        started <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        fixture <- make(_ => started.succeed(()) *> release.await.as(answer))
        pending <- invoke(fixture).fork
        _ <- started.await
        _ <- pending.interrupt
        _ <- fixture.rpc.disconnect(fixture.connection)
        _ <- release.succeed(())
        saved <- fixture.live.listMessages.repeatUntil(_.exists(_.parentId.contains(input.id)))
        sessions <- fixture.live.listSessions
      } yield {
        assertEquals(saved.size, 2)
        assertEquals(sessions, Chunk.empty)
      }
    })
  }

  test("audio input is rejected before persistence for a configured agent without audio capability") {
    run(ZIO.scoped {
      for {
        calls <- Ref.make(0)
        configuration = config.copy(
          agents = Map(assistant -> definition.copy(agentType = "ChatGPTAgent")),
          providers =
            Map(providerId -> ProviderConfig.ChatGPT(Some(OAuthCredentialsConfig("unused", "unused", Long.MaxValue))))
        )
        fixture <- make(_ => calls.update(_ + 1).as(answer), configuration = configuration)
        audio = Message(input.data.copy(payload = MessagePayload.AudioAttachment(BlobId.fresh(), AudioFormat.Wav)))
        reply <- invoke(fixture, audio)
        stored <- fixture.live.listMessages
        count <- calls.get
      } yield {
        assertEquals(decode[JsonRpc.Failure](reply).error.code, ErrorCode.Rejected)
        assertEquals(stored, Chunk.empty)
        assertEquals(count, 0)
      }
    })
  }

  test("application shutdown and already-closed scopes deliver terminal errors instead of hanging invocation waits") {
    run(ZIO.scoped {
      for {
        started <- Promise.make[Nothing, Unit]
        scope <- Scope.make
        fixture <- make(_ => started.succeed(()) *> ZIO.never).provideEnvironment(ZEnvironment(scope))
        running <- invoke(fixture).fork
        _ <- started.await
        _ <- scope.close(Exit.unit)
        reply <- running.join
        afterClose <- fixture.backend.invoke(human, assistant, input).either
        stored <- fixture.live.listMessages
      } yield {
        assertEquals(decode[JsonRpc.Failure](reply).error, JsonRpc.Error.InternalError)
        assertEquals(afterClose, Left(JsonRpc.Error.InternalError))
        assertEquals(stored, Chunk(input))
      }
    })
  }

  test(
    "same WebSocket accepts an invocation before upload completion, then correlates upload/read/invocation replies"
  ) {
    run(ZIO.scoped {
      for {
        calls <- Ref.make(0)
        sent <- Promise.make[Nothing, String]
        fixture <- make(request =>
          request.body.asString.flatMap(body => sent.succeed(body)) *> calls.update(_ + 1).as(answer)
        )
        port <- new OrchestratorWSServer(fixture.live, Some(fixture.backend)).start(0)
        connected <- Promise.make[Nothing, WebSocketChannel]
        replies <- Queue.unbounded[String]
        socket = Handler.webSocket { channel =>
          channel.receiveAll {
            case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
              connected.succeed(channel).unit
            case ChannelEvent.Read(WebSocketFrame.Text(value)) => replies.offer(value).unit
            case _                                             => ZIO.unit
          }
        }
        environment <- Client.default.build
        _ <- socket.connect(s"ws://127.0.0.1:$port/rpc").provideSomeEnvironment[Scope](_.add(environment.get[Client]))
        channel <- connected.await
        send = (id: String, method: String, params: Json) =>
          channel.send(
            ChannelEvent.Read(
              WebSocketFrame.Text(
                JsonRpc.Request(Json.Str(id), method, params).toJson
              )
            )
          )
        _ <- send(
          "init",
          Initialize.method,
          Initialize
            .Params(fmgp.yallmo.server.protocol.YallmoProtocol.Version, "test", Some(human))
            .toJsonAST
            .toOption
            .get
        )
        _ <- replies.take
        blob = BlobId.fresh()
        _ <- send(
          "begin",
          BlobsUpload.method,
          (BlobsUpload.Params.Begin(blob, 3): BlobsUpload.Params).toJsonAST.toOption.get
        )
        begun <- replies.take
        _ = assertEquals(decode[JsonRpc.Success[BlobsUpload.Result]](begun).result, BlobsUpload.Result.Begun(blob))
        audio = Message(input.data.copy(payload = MessagePayload.AudioAttachment(blob, AudioFormat.Wav)))
        _ <- send("invoke", AgentsInvoke.method, AgentsInvoke.Params(assistant, audio).toJsonAST.toOption.get)
        _ <- fixture.live.listMessages.repeatUntil(_.exists(_.id == audio.id))
        before <- calls.get
        _ <- send("read", MessagesList.method, Empty().toJsonAST.toOption.get)
        read <- replies.take
        _ <- send(
          "append",
          BlobsUpload.method,
          (BlobsUpload.Params.Append(blob, 0, "AQID"): BlobsUpload.Params).toJsonAST.toOption.get
        )
        appended <- replies.take
        _ <- send(
          "complete",
          BlobsUpload.method,
          (BlobsUpload.Params.Complete(blob): BlobsUpload.Params).toJsonAST.toOption.get
        )
        finished <- replies.take.zip(replies.take)
        body <- sent.await
        count <- calls.get
        sessions <- fixture.live.listSessions
        _ <- channel.shutdown
      } yield {
        assertEquals(before, 0)
        assertEquals(count, 1)
        assertEquals(decode[JsonRpc.Success[Json]](read).id, Json.Str("read"))
        assertEquals(decode[JsonRpc.Success[BlobsUpload.Result]](appended).id, Json.Str("append"))
        val results = List(finished._1, finished._2).map(value => decode[JsonRpc.Success[Json]](value))
        assertEquals(results.map(_.id).toSet, Set[Json](Json.Str("complete"), Json.Str("invoke")))
        val result = results.find(_.id == Json.Str("invoke")).get.result.as[Message].fold(fail(_), identity)
        assertEquals(result.parentId, Some(audio.id))
        assertEquals(result.payload, Message.Assistant("answer"))
        val content = ujson.read(body)("messages")(0)("content")(0)
        assertEquals(content("input_audio")("data").str, "AQID")
        assertEquals(sessions, Chunk.empty)
      }
    })
  }

  private final case class Fixture(
      runtime: ApplicationRuntime,
      backend: ServerApplication.Configured,
      rpc: OrchestratorRpc,
      connection: OrchestratorRpc.Connection
  ) {
    def live: OrchestratorLive[Any] = backend.assembly.orchestrator
  }

  private def make(
      respond: Request => Task[Response],
      root: Option[Path] = None,
      configuration: Config = config,
      tools: Chunk[AgentTool[Any]] = Chunk.empty
  ): ZIO[Scope, YallmoError, Fixture] =
    for {
      runtime <- ApplicationRuntime.make(configuration, Chunk.empty).provide(ZLayer.succeed(fakeClient(respond)))
      assembly <- runtime.open[Any](root, tools)
      scope <- ZIO.service[Scope]
      inFlight <- Ref.make(Set.empty[SessionId])
      continuations <- Ref.make(Map.empty[SessionId, (AgentId, Int)])
      captures <- Ref.make(VectorMap.empty[SessionId, Path])
      backend = new ServerApplication.Configured(
        runtime,
        assembly,
        Path.of("."),
        scope,
        inFlight,
        continuations,
        captures
      )
      rpc = new OrchestratorRpc(assembly.orchestrator, Some(backend))
      connection <- rpc.openConnection
      _ <- initialize(rpc, connection, Some(human))
      _ <- ZIO.addFinalizer(rpc.disconnect(connection))
    } yield Fixture(runtime, backend, rpc, connection)

  private def invoke(fixture: Fixture, message: Message = input, recipient: AgentId = assistant): UIO[String] =
    call(fixture.rpc, fixture.connection, AgentsInvoke.method, AgentsInvoke.Params(recipient, message))

  private def initialize(
      rpc: OrchestratorRpc,
      connection: OrchestratorRpc.Connection,
      user: Option[AgentId]
  ): UIO[String] =
    call(
      rpc,
      connection,
      Initialize.method,
      Initialize.Params(fmgp.yallmo.server.protocol.YallmoProtocol.Version, "test", user)
    )

  private def call[A: JsonCodec](
      rpc: OrchestratorRpc,
      connection: OrchestratorRpc.Connection,
      method: String,
      params: A
  ): UIO[String] =
    rpc.handle(JsonRpc.Request(Json.Str("request"), method, params).toJson, connection).map(_.get)

  private def suspended[E, A](fiber: Fiber.Runtime[E, A]): UIO[Unit] =
    (ZIO.yieldNow *> fiber.status).repeatUntil(_.isInstanceOf[Fiber.Status.Suspended]).unit

  private def decode[A: JsonDecoder](value: String): A = value.fromJson[A].fold(fail(_), identity)

  private def temporaryRoot: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("yallmo-agents-invoke-"))) { root =>
      ZIO.attemptBlocking {
        val paths = Files.walk(root)
        try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
        finally paths.close()
      }.orDie
    }

  private def fakeClient(respond: Request => Task[Response]): Client =
    Client.fromDriver(new ZClient.Driver[Any, Scope, Throwable] {
      def request(
          version: HttpVersion,
          method: Method,
          url: URL,
          headers: Headers,
          body: Body,
          sslConfig: Option[ClientSSLConfig],
          proxy: Option[Proxy]
      )(using Trace): Task[Response] = {
        val _ = (sslConfig, proxy)
        respond(Request(version = version, method = method, url = url, headers = headers, body = body))
      }
      def socket[Env1 <: Any](version: HttpVersion, url: URL, headers: Headers, app: WebSocketApp[Env1])(using
          Trace,
          Scope =:= Scope
      ): ZIO[Env1, Throwable, Response] = {
        val _ = (version, url, headers, app)
        ZIO.dieMessage("Unexpected provider WebSocket")
      }
    })

  private def run[A](effect: ZIO[Any, Any, A]): A = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(effect.timeoutFail(new AssertionError("Invoke test timed out"))(20.seconds))
      .getOrThrowFiberFailure()
  }
}
