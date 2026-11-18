package fmgp.yallmo.server.application

import fmgp.yallmo.tool.scalamode.ScalamodeToolDefinition

import fmgp.yallmo.tool.{
  BashToolDefinition,
  FunctionCall,
  FunctionCallId,
  FunctionGenericOutput,
  FunctionKey,
  FunctionToolDefinition,
  ScalaModeOutput,
  ScalaModeResult
}

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
  private val human = AgentId("user-human")
  private val assistant = AgentId("assistant")
  private val providerId = ProviderId.unsafe("fake")
  private val input = Message(MessageData(human, None, Instant.EPOCH, Message.User("hello")))
  private val definition = AgentConfig(
    "OAIAgent",
    Seq(providerId),
    Json.Obj("model" -> Json.Str("test"), "systemPrompt" -> Json.Str("Configured instructions")),
    capabilities = AgentCapabilities(functions = Some(Set.empty))
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
          existing <- seed(fixture.backend, human, Some(SessionId.unsafe("unrelated")))
          _ <- fixture.backend.submit(human, TurnSubmit.Target.Existing(existing.id), assistant, "earlier")
          before <- fixture.live.loadSession(existing.id)
          reply <- invoke(fixture)
          output = decode[JsonRpc.Success[Message]](reply).result
          after <- fixture.live.loadSession(existing.id)
          stored <- fixture.live.listMessages
          requests <- seen.get
          sessions <- fixture.live.listSessions
          reopened <- root.fold(fixture.live.listMessages)(path =>
            OrchestratorLive.filesystem(path, fixture.runtime.catalog).flatMap(_.listMessages)
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
        unauthorized <- call(
          fixture.rpc,
          uninitialized,
          AgentsInvoke.method,
          AgentsInvoke.Params(assistant, input, human)
        )
        viewer <- fixture.rpc.openConnection
        info <- initialize(fixture.rpc, viewer, None)
        forbidden <- call(fixture.rpc, viewer, AgentsInvoke.method, AgentsInvoke.Params(assistant, input, human))
        spoof = Message(input.data.copy(agentId = assistant))
        spoofed <- invoke(fixture, spoof)
        _ <- fixture.rpc.disconnect(uninitialized) *> fixture.rpc.disconnect(viewer)
        count <- calls.get
        stored <- fixture.live.listMessages
      } yield {
        assertEquals(decode[JsonRpc.Failure](unauthorized).error.code, ErrorCode.Unauthorized)
        assertEquals(decode[JsonRpc.Failure](forbidden).error.code, ErrorCode.Forbidden)
        assertEquals(decode[JsonRpc.Failure](spoofed).error.code, ErrorCode.Forbidden)
        assert(!decode[JsonRpc.Success[Initialize.Result]](info).result.methods.contains(AgentsInvoke.method))
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

  test("agent loading logs the configuration reason before returning a sanitized RPC error") {
    val logs = new java.util.concurrent.CopyOnWriteArrayList[String]()
    val capture = ZLogger.default.map(line => { logs.add(line); () })
    val invalid = config.copy(agents =
      Map(
        assistant -> definition.copy(
          config = Json.Obj("systemPrompt" -> Json.Str("PRIVATE_PROMPT"))
        )
      )
    )
    run(ZIO.scoped {
      for {
        fixture <- make(_ => ZIO.dieMessage("Invalid settings must not invoke the provider"), configuration = invalid)
        _ <- (Runtime.removeDefaultLoggers >>> Runtime.addLogger(capture)).build
        _ <- ZIO.succeed(assert(logs.isEmpty))
        reply <- invoke(fixture)
        stored <- fixture.live.listMessages
      } yield {
        assertEquals(
          decode[JsonRpc.Failure](reply).error,
          JsonRpc.Error(ErrorCode.Rejected, "Invalid author or assistant configuration")
        )
        assertEquals(logs.size(), 1)
        val line = logs.get(0)
        assert(line.contains("level=ERROR"))
        assert(line.contains("Unable to load agent 'assistant'"))
        assert(line.contains("Agent config.model is required when the provider has no default model"))
        assert(!line.contains("PRIVATE_PROMPT"))
        assert(!reply.contains("config.model"))
        assertEquals(stored, Chunk.empty)
      }
    })
  }

  test("independent invocations advertise configured capabilities but cannot start a tool loop") {
    run(ZIO.scoped {
      for {
        requests <- Ref.make(Vector.empty[String])
        executions <- Ref.make(0)
        tool = new AgentFunction[Any] {
          val definition = FunctionToolDefinition(FunctionKey.unsafe("echo"), "Echo")
          def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
            executions.update(_ + 1).as(ExternalFunctionResult("tool output"))
        }
        enabled = config.copy(agents =
          Map(
            assistant -> definition.copy(capabilities =
              AgentCapabilities(functions = Some(Set(FunctionKey.unsafe("echo"))))
            )
          )
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
          tools = Chunk(tool),
          toolDefinitions = ApplicationTools.ToolDefinitions :+ fmgp.yallmo.tool
            .ToolDefinition(fmgp.yallmo.tool.ToolKey.unsafe("echo"), Set(tool.definition))
        )
        reply <- invoke(fixture)
        sent <- requests.get
        count <- executions.get
        stored <- fixture.live.listMessages
      } yield {
        assertEquals(decode[JsonRpc.Failure](reply).error.code, ErrorCode.Rejected)
        assert(decode[JsonRpc.Failure](reply).error.message.contains("turn.submit"))
        assertEquals(count, 0)
        assertEquals(sent.size, 1)
        assertEquals(ujson.read(sent.head)("tools")(0)("function")("name").str, "echo")
        assertEquals(stored, Chunk(input))
      }
    })
  }

  test("direct scalamode calls preserve correlation, persist ToolOutput, and never invoke the provider") {
    run(ZIO.scoped {
      for {
        providers <- Ref.make(0)
        executions <- Ref.make(Chunk.empty[ExternalFunctionCall])
        tool = new AgentFunction[Any] {
          val definition = fmgp.yallmo.tool.scalamode.ScalaTool.definition
          def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
            executions
              .update(_ :+ call)
              .as(ExternalFunctionResult.ScalaMode(ScalaModeResult.Executed("42\n")))
        }
        allowed = config.copy(agents =
          Map(
            assistant -> definition.copy(capabilities =
              AgentCapabilities(functions = Some(Set(ScalamodeToolDefinition.Execute)))
            )
          )
        )
        fixture <- make(_ => providers.update(_ + 1).as(answer), configuration = allowed, tools = Chunk(tool))
        function = FunctionCall(
          FunctionCallId.unsafe("browser-call"),
          ScalamodeToolDefinition.Execute,
          """{"code":"40 + 2"}"""
        )
        request = Message(input.data.copy(payload = Message.ToolCall(function)))
        reply <- invoke(fixture, request)
        response = decode[JsonRpc.Success[Message]](reply).result
        invalid = function.copy(id = FunctionCallId.unsafe("bad-arguments"), argumentsJson = """{"code":42}""")
        badReply <- invoke(fixture, Message(input.data.copy(payload = Message.ToolCall(invalid))))
        unavailable = function.copy(id = FunctionCallId.unsafe("denied"), name = BashToolDefinition.Execute)
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
            ScalaModeOutput(function.id, ScalaModeResult.Executed("42\n"))
          )
        )
        assertEquals(calls, Chunk(ExternalFunctionCall(function.name, function.argumentsJson, Some(function.id))))
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

  for (toolDefinition, arguments) <- List(
      fmgp.yallmo.tool.scalamode.ScalaTool.definition -> """{"code":"42"}""",
      fmgp.yallmo.tool.sdlc.SdlcTools.SetDefinition -> """{"tasksJson":[]}""",
      fmgp.yallmo.tool.sdlc.SdlcTools.ReadTaskDefinition -> """{"taskIds":[]}""",
      fmgp.yallmo.tool.sdlc.SdlcTools.SetIntentsDefinition -> """{"intentsJson":[]}""",
      fmgp.yallmo.tool.sdlc.SdlcTools.ReadIntentDefinition -> """{"intentIds":[]}""",
      fmgp.yallmo.tool.sdlc.SdlcTools.ListDefinition -> "{}"
    )
  do
    test(s"direct ${toolDefinition.name} retains configured denial and approval gates without inference") {
      run(ZIO.scoped {
        for {
          executions <- Ref.make(0)
          approvals <- Ref.make(0)
          tool = new AgentFunction[Any] {
            val definition = toolDefinition
            def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
              executions.update(_ + 1).as(ExternalFunctionResult("must not run"))
          }
          guarded = FunctionApproval.guard(
            tool,
            new FunctionApproval[Any] {
              def approve(definition: FunctionToolDefinition, call: ExternalFunctionCall): IO[YallmoError, Unit] =
                approvals.update(_ + 1) *> ZIO.fail(YallmoError.InvalidConfig("Approval denied"))
            }
          )
          allowed = config.copy(agents =
            Map(
              assistant -> definition.copy(capabilities = AgentCapabilities(functions = Some(Set(toolDefinition.name))))
            )
          )
          denied <- make(_ => ZIO.dieMessage("No provider inference"), tools = Chunk(guarded))
          permitted <- make(
            _ => ZIO.dieMessage("No provider inference"),
            configuration = allowed,
            tools = Chunk(guarded)
          )
          request = Message(
            input.data.copy(payload =
              Message.ToolCall(FunctionCall(FunctionCallId.unsafe("gate"), toolDefinition.name, arguments))
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
            Message.ToolOutput(
              FunctionGenericOutput(FunctionCallId.unsafe("forwarded"), FunctionKey.unsafe("other"), "data")
            )
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
        port <- fmgp.yallmo.server.TestWebSession.start(fixture.backend)
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
        cookie <- TestWebSession.cookie(port, "human")
        _ <- socket
          .connect(
            s"ws://127.0.0.1:$port/rpc",
            Headers(Header.Custom("Cookie", cookie), Header.Custom("Origin", s"http://127.0.0.1:$port"))
          )
          .provideSomeEnvironment[Scope](_.add(environment.get[Client]))
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
          Initialize.Params("test", Some(UserId(AgentId.value(human).stripPrefix("user-")))).toJsonAST.toOption.get
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
        _ <- send("invoke", AgentsInvoke.method, AgentsInvoke.Params(assistant, audio, human).toJsonAST.toOption.get)
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

  private def scalaRequest(code: String = "42", arguments: Option[String] = None): Message =
    Message(
      input.data.copy(payload =
        Message.ToolCall(
          FunctionCall(
            FunctionCallId.unsafe("manual-scala"),
            FunctionKey.scalamode,
            arguments.getOrElse(ujson.write(ujson.Obj("code" -> code)))
          )
        )
      )
    )

  private def pure(fixture: Fixture, message: Message): UIO[String] =
    call(
      fixture.rpc,
      fixture.connection,
      AgentsInvoke.method,
      AgentsInvoke.Params(assistant, message, human, Some(ScalamodeExecution.JsNode))
    )

  private def scalaConfiguration(serviceBound: Boolean, permitted: Boolean = true): Config = {
    val enabled =
      definition.copy(capabilities = AgentCapabilities(functions = Some(if permitted then Set(FunctionKey.scalamode)
      else Set.empty)))
    if !serviceBound then config.copy(agents = Map(assistant -> enabled))
    else {
      val encoded = java.util.Base64.getUrlEncoder
        .withoutPadding()
        .encodeToString(
          """{"https://api.openai.com/auth":{"chatgpt_account_id":"fake"}}""".getBytes(
            java.nio.charset.StandardCharsets.UTF_8
          )
        )
      config.copy(
        agents = Map(assistant -> enabled.copy(agentType = "ChatGPTAgent")),
        providers = Map(
          providerId -> ProviderConfig.ChatGPT(
            Some(OAuthCredentialsConfig(s"header.$encoded.signature", "fake-refresh", Long.MaxValue))
          )
        )
      )
    }
  }

  test("manual selector rejects inapplicable and unconfigured requests before persistence, without inference") {
    run(ZIO.scoped(for {
      fixture <- make(_ => ZIO.dieMessage("No inference"))
      invalid <- ZIO.foreach(
        List(
          input,
          Message(
            input.data.copy(payload =
              Message.ToolCall(FunctionCall(FunctionCallId.unsafe("other"), FunctionKey.unsafe("other"), "{}"))
            )
          ),
          Message(input.data.copy(payload = Message.Assistant("answer")))
        )
      ) { message =>
        ZIO.foreach(ScalamodeExecution.values.toList)(mode =>
          call(
            fixture.rpc,
            fixture.connection,
            AgentsInvoke.method,
            AgentsInvoke.Params(assistant, message, human, Some(mode))
          )
        )
      }
      spoofed <- pure(fixture, Message(scalaRequest().data.copy(agentId = AgentId("spoofed"))))
      foreignActor <- call(
        fixture.rpc,
        fixture.connection,
        AgentsInvoke.method,
        AgentsInvoke.Params(assistant, scalaRequest(), AgentId("foreign"), Some(ScalamodeExecution.JsNode))
      )
      unconfigured <- pure(fixture, scalaRequest())
      capability <- call(fixture.rpc, fixture.connection, ScalamodeCapabilities.method, Empty())
      stored <- fixture.live.listMessages
      viewer <- fixture.rpc.openConnection
      _ <- initialize(fixture.rpc, viewer, None)
      forbidden <- call(
        fixture.rpc,
        viewer,
        AgentsInvoke.method,
        AgentsInvoke.Params(assistant, scalaRequest(), human, Some(ScalamodeExecution.JsNode))
      )
      _ <- fixture.rpc.disconnect(viewer)
    } yield {
      assert(invalid.flatten.forall(_.contains("explicit Scalamode ToolCall")))
      assertEquals(decode[JsonRpc.Failure](spoofed).error.code, ErrorCode.Forbidden)
      assertEquals(decode[JsonRpc.Failure](foreignActor).error.code, ErrorCode.Forbidden)
      assert(unconfigured.contains("not configured"))
      assert(!decode[JsonRpc.Success[ScalamodeCapabilities.Result]](capability).result.jsNodeAvailable)
      assertEquals(decode[JsonRpc.Failure](forbidden).error.code, ErrorCode.Forbidden)
      assertEquals(stored, Chunk.empty)
    }))
  }

  for serviceBound <- List(false, true) do
    test(
      s"manual pure preserves selected-agent approval/schema/denial and JVM binding (default services=$serviceBound)"
    ) {
      run(ZIO.scoped(for {
        approvals <- Ref.make(0)
        pureCalls <- Ref.make(Vector.empty[String])
        jvmCalls <- Ref.make(0)
        deny <- Ref.make(false)
        jvm: fmgp.yallmo.tool.scalamode.ScalaCodeExecutor = new fmgp.yallmo.tool.scalamode.ScalaCodeExecutor
          with fmgp.yallmo.tool.scalamode.ScalaCodeExecutorServices {
          def isAvailable = ZIO.succeed(true)
          def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) =
            jvmCalls.update(_ + 1).as(ExternalFunctionResult.ScalaMode(ScalaModeResult.Executed("jvm")))
          override def executeWithServices[R <: fmgp.yallmo.tool.scalamode.ScalamodeService](
              code: String,
              service: R,
              timeout: Duration,
              maxOutputBytes: Int
          ) =
            jvmCalls.update(_ + 1).as(ExternalFunctionResult.ScalaMode(ScalaModeResult.Executed("jvm-services")))
        }
        node: fmgp.yallmo.tool.scalamode.ScalaCodeExecutor = new fmgp.yallmo.tool.scalamode.ScalaCodeExecutor
          with fmgp.yallmo.tool.scalamode.ScalaCodeExecutorServices {
          def isAvailable = ZIO.succeed(true)
          def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) =
            pureCalls.update(_ :+ code).as(ExternalFunctionResult.ScalaMode(ScalaModeResult.Executed("42")))
          override def executeWithServices[R <: fmgp.yallmo.tool.scalamode.ScalamodeService](
              code: String,
              service: R,
              timeout: Duration,
              maxOutputBytes: Int
          ) = ZIO.dieMessage("No services in pure mode")
        }
        manual <- ManualJsScalamode.checked(node)
        builtin = AgentFunction.provideEnvironment(new fmgp.yallmo.tool.scalamode.ScalaTool(), ZEnvironment(jvm))
        guarded = FunctionApproval.guard(
          builtin,
          new FunctionApproval[Any] {
            def approve(definition: FunctionToolDefinition, call: ExternalFunctionCall) =
              approvals.update(_ + 1) *> deny.get
                .flatMap(value => ZIO.fail(YallmoError.InvalidConfig("Approval denied")).when(value))
                .unit
          }
        )
        fixture <- make(
          _ => ZIO.dieMessage("No inference"),
          configuration = scalaConfiguration(serviceBound),
          tools = Chunk(guarded),
          manualJs = manual,
          scalaExecutor = jvm
        )
        malformed <- pure(fixture, scalaRequest(arguments = Some("{}")))
        before <- approvals.get
        _ <- deny.set(true)
        rejected <- pure(fixture, scalaRequest())
        _ <- deny.set(false)
        request = scalaRequest()
        successful <- pure(fixture, request)
        normal <- invoke(fixture, scalaRequest("43"))
        binding <- fixture.runtime.resolve(assistant)
        denied <- make(
          _ => ZIO.dieMessage("No inference"),
          configuration = scalaConfiguration(serviceBound, false),
          tools = Chunk(guarded),
          manualJs = manual,
          scalaExecutor = jvm
        )
        notPermitted <- pure(denied, scalaRequest())
        count <- pureCalls.get
        normalCount <- jvmCalls.get
        gates <- approvals.get
        stored <- fixture.live.listMessages
        sessions <- fixture.live.listSessions
      } yield {
        assert(malformed.contains("InvalidToolArguments"))
        assertEquals(before, 0)
        assert(rejected.contains("Approval denied"))
        val response = decode[JsonRpc.Success[Message]](successful).result
        assertEquals(response.parentId, Some(request.id))
        assertEquals(response.agentId, assistant)
        assertEquals(
          response.payload.asInstanceOf[MessagePayload.ToolOutput].output.callId,
          FunctionCallId.unsafe("manual-scala")
        )
        assertEquals(count, Vector("42"))
        assertEquals(normalCount, 1)
        assertEquals(gates, 3)
        assert(normal.contains(if serviceBound then "jvm-services" else "jvm"))
        assert(notPermitted.contains("ToolUnavailableToAgent"))
        assertEquals(binding.functions.head.definition.scalamode.get.services.nonEmpty, serviceBound)
        assert(stored.contains(request) && stored.contains(response))
        assert(!request.toJson.contains("js-node"))
        assertEquals(sessions, Chunk.empty)
      }))
    }

  test("custom default-looking closures, opaque wrappers and custom services are never substituted for pure mode") {
    run(ZIO.scoped(for {
      calls <- Ref.make(0)
      executor = new fmgp.yallmo.tool.scalamode.ScalaCodeExecutor {
        def isAvailable = ZIO.succeed(true)
        def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) =
          ZIO.dieMessage("No pure execution")
      }
      manual <- ManualJsScalamode.checked(executor)
      custom = new AgentFunction[Any] {
        val definition = fmgp.yallmo.tool.scalamode.ScalaTool.definition
        def execute(call: ExternalFunctionCall) = calls.update(_ + 1).as(ExternalFunctionResult("custom JVM"))
      }
      _ <- ZIO.foreachDiscard(
        List(
          custom,
          new AgentFunction[Any] {
            val definition = custom.definition
            def execute(call: ExternalFunctionCall) = custom.execute(call)
          },
          AgentFunction.provideEnvironment(
            fmgp.yallmo.tool.scalamode.ScalaTool.withServices(fmgp.yallmo.tool.scalamode.HelloWorldService),
            ZEnvironment(executor)
          )
        )
      ) { function =>
        for {
          fixture <- make(
            _ => ZIO.dieMessage("No inference"),
            configuration = scalaConfiguration(true),
            tools = Chunk(function),
            manualJs = manual,
            scalaExecutor = executor
          )
          result <- pure(fixture, scalaRequest())
        } yield assert(result.contains("does not support JS / Node playground"))
      }
      fixture <- make(
        _ => ZIO.dieMessage("No inference"),
        configuration = scalaConfiguration(true),
        tools = Chunk(custom)
      )
      jvm <- invoke(fixture, scalaRequest())
      count <- calls.get
    } yield {
      assert(jvm.contains("custom JVM"))
      assertEquals(count, 1)
    }))
  }

  test("manual pure work is request-local, survives disconnect and is finalized by application shutdown") {
    run(ZIO.scoped(for {
      started <- Promise.make[Nothing, Unit]
      release <- Promise.make[Nothing, Unit]
      stopped <- Promise.make[Nothing, Unit]
      node = new fmgp.yallmo.tool.scalamode.ScalaCodeExecutor {
        def isAvailable = ZIO.succeed(true)
        def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) =
          (started.succeed(()) *> release.await.as(ExternalFunctionResult.ScalaMode(ScalaModeResult.Executed(code))))
            .ensuring(stopped.succeed(()))
      }
      jvm: fmgp.yallmo.tool.scalamode.ScalaCodeExecutor = new fmgp.yallmo.tool.scalamode.ScalaCodeExecutor
        with fmgp.yallmo.tool.scalamode.ScalaCodeExecutorServices {
        def isAvailable = ZIO.succeed(true)
        def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) =
          ZIO.succeed(ExternalFunctionResult.ScalaMode(ScalaModeResult.Executed("jvm-" + code)))
        override def executeWithServices[R <: fmgp.yallmo.tool.scalamode.ScalamodeService](
            code: String,
            service: R,
            timeout: Duration,
            maxOutputBytes: Int
        ) =
          execute(code, timeout, maxOutputBytes, Seq.empty)
      }
      manual <- ManualJsScalamode.checked(node)
      builtin = AgentFunction.provideEnvironment(new fmgp.yallmo.tool.scalamode.ScalaTool(), ZEnvironment(jvm))
      fixture <- make(
        _ => ZIO.dieMessage("No inference"),
        configuration = scalaConfiguration(true),
        tools = Chunk(builtin),
        manualJs = manual,
        scalaExecutor = jvm
      )
      generation <- fixture.runtime.resolve(assistant)
      pending <- pure(fixture, scalaRequest()).fork
      _ <- started.await
      concurrent <- invoke(fixture, scalaRequest("43"))
      _ = assert(concurrent.contains("jvm-43"))
      _ <- pending.interrupt
      _ <- fixture.rpc.disconnect(fixture.connection)
      _ <- release.succeed(())
      _ <- stopped.await
      saved <- fixture.live.listMessages.repeatUntil(_.exists(_.parentId.contains(scalaRequest().id)))
      after <- fixture.runtime.resolve(assistant)
      _ = assert(generation eq after)
      _ = assertEquals(saved.size, 4)
      shutdown <- Scope.make
      entered <- Promise.make[Nothing, Unit]
      finalized <- Promise.make[Nothing, Unit]
      blocking = new fmgp.yallmo.tool.scalamode.ScalaCodeExecutor {
        def isAvailable = ZIO.succeed(true)
        def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) =
          (entered.succeed(()) *> ZIO.never).ensuring(finalized.succeed(()))
      }
      blockedManual <- ManualJsScalamode.checked(blocking)
      blocked <- make(
        _ => ZIO.dieMessage("No inference"),
        configuration = scalaConfiguration(false),
        tools = Chunk(builtin),
        manualJs = blockedManual
      ).provideEnvironment(ZEnvironment(shutdown))
      waiter <- pure(blocked, scalaRequest()).fork
      _ <- entered.await
      _ <- shutdown.close(Exit.unit)
      _ <- finalized.await
      outcome <- waiter.join
    } yield {
      assertEquals(decode[JsonRpc.Failure](outcome).error, JsonRpc.Error.InternalError)
    }))
  }

  test("runtime failure is sanitized and never silently falls back to JVM") {
    run(ZIO.scoped(for {
      node = new fmgp.yallmo.tool.scalamode.ScalaCodeExecutor {
        def isAvailable = ZIO.succeed(true)
        def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) =
          ZIO.fail(new IllegalStateException("/private/artifacts credentials"))
      }
      manual <- ManualJsScalamode.checked(node)
      builtin = AgentFunction.provideEnvironment(new fmgp.yallmo.tool.scalamode.ScalaTool(), ZEnvironment(node))
      fixture <- make(
        _ => ZIO.dieMessage("No inference"),
        configuration = scalaConfiguration(false),
        tools = Chunk(builtin),
        manualJs = manual
      )
      result <- pure(fixture, scalaRequest())
    } yield {
      assert(result.contains("no JVM fallback"))
      assert(!result.contains("/private"))
      assert(!result.contains("credentials"))
    }))
  }

  test("provisioned live manual JVM and JS / Node execute without agent services") {
    assume(
      sys.env.contains("YALLMO_TEST_JS_ROOT"),
      "Set YALLMO_TEST_JS_ROOT to reviewed staged artifacts for live integration"
    )
    run(ZIO.scoped(for {
      manual <- ManualJsScalamode.provision(Path.of(sys.env("YALLMO_TEST_JS_ROOT")))
      _ = assert(manual.capabilities.jsNodeAvailable, manual.capabilities.reason)
      jvm = fmgp.yallmo.tool.scalamode.ScalaCodeExecutorJVM.make()
      builtin = AgentFunction.provideEnvironment(new fmgp.yallmo.tool.scalamode.ScalaTool(), ZEnvironment(jvm))
      fixture <- make(
        _ => ZIO.dieMessage("No inference"),
        configuration = scalaConfiguration(true),
        tools = Chunk(builtin),
        manualJs = manual,
        scalaExecutor = jvm
      )
      normal <- invoke(fixture, scalaRequest("40 + 2"))
      js <- pure(fixture, scalaRequest("ZIO.succeed(40 + 2)"))
      noHello <- pure(fixture, scalaRequest("hello.helloWorld"))
      noFs <- pure(fixture, scalaRequest("fs.list(\".\")"))
    } yield {
      for response <- List(normal, js) do {
        val output = decode[JsonRpc.Success[Message]](response).result.payload
          .asInstanceOf[MessagePayload.ToolOutput]
          .output
          .asInstanceOf[ScalaModeOutput]
        assertEquals(output.exitCode, 0)
        assert(output.output.contains("42"))
        assert(output.result.isInstanceOf[ScalaModeResult.Executed])
      }
      for response <- List(noHello, noFs) do {
        val output = decode[JsonRpc.Success[Message]](response).result.payload
          .asInstanceOf[MessagePayload.ToolOutput]
          .output
          .asInstanceOf[ScalaModeOutput]
        assert(output.result.isInstanceOf[ScalaModeResult.CompilationError])
      }
    }))
  }

  private final case class Fixture(
      runtime: ServerApplication,
      backend: ServerExecutionService,
      rpc: OrchestratorRpc,
      connection: OrchestratorRpc.Connection
  ) {
    def live: OrchestratorLive[Any] = backend.application.master
  }

  private def make(
      respond: Request => Task[Response],
      root: Option[Path] = None,
      configuration: Config = config,
      tools: Chunk[AgentFunction[Any]] = Chunk.empty,
      manualJs: ManualJsScalamode = ManualJsScalamode.disabled,
      scalaExecutor: fmgp.yallmo.tool.scalamode.ScalaCodeExecutor =
        fmgp.yallmo.tool.scalamode.ScalaCodeExecutorJVM.make(),
      toolDefinitions: Chunk[fmgp.yallmo.tool.ToolDefinition] = ApplicationTools.ToolDefinitions
  ): ZIO[Scope, YallmoError, Fixture] =
    for {
      runtime <- ServerApplication
        .make(
          configuration,
          Chunk.empty,
          root,
          tools,
          scalaCodeExecutor = scalaExecutor,
          manualJs = manualJs,
          toolDefinitions = toolDefinitions
        )
        .provide(ZLayer.succeed(fakeClient(respond)))
      scope <- ZIO.service[Scope]
      inFlight <- Ref.make(Set.empty[SessionId])
      continuations <- Ref.make(Map.empty[SessionId, (AgentId, Set[fmgp.yallmo.model.llm.ProviderContinuation])])
      captures <- Ref.make(VectorMap.empty[SessionId, Path])
      catalog <- ProviderCatalog.make(Path.of("unused-provider-config.json"))
      providers <- ProviderAdministration.make(
        catalog,
        fmgp.yallmo.provider.openai.OpenAiCodexAuth.oauthFlow(fakeClient(respond))
      )
      backend = new ServerExecutionService(
        runtime,
        Path.of("."),
        providers,
        scope,
        inFlight,
        continuations,
        captures
      )
      rpc = new OrchestratorRpc(backend)
      connection <- rpc.openConnection
      _ <- initialize(rpc, connection, Some(human))
      _ <- ZIO.addFinalizer(rpc.disconnect(connection))
    } yield Fixture(runtime, backend, rpc, connection)

  private def invoke(fixture: Fixture, message: Message = input, recipient: AgentId = assistant): UIO[String] =
    call(fixture.rpc, fixture.connection, AgentsInvoke.method, AgentsInvoke.Params(recipient, message, human))

  private def initialize(
      rpc: OrchestratorRpc,
      connection: OrchestratorRpc.Connection,
      user: Option[AgentId]
  ): UIO[String] =
    call(
      rpc,
      connection,
      Initialize.method,
      Initialize.Params("test", user.map(id => UserId(AgentId.value(id).stripPrefix("user-"))))
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
  private def seed(
      backend: ServerExecutionService,
      author: AgentId,
      name: Option[SessionId]
  ): IO[YallmoError, SessionSnapshot] =
    for {
      _ <- backend.application.registerUser(author)
      session <- backend.application.master
        .createSession(name.getOrElse(SessionId.unsafe(s"main-${java.util.UUID.randomUUID()}")), Chunk(author))
    } yield SessionSnapshot.from(session)

}
