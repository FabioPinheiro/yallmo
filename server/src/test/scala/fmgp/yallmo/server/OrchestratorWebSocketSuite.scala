package fmgp.yallmo.server

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse, ProviderContinuation, ProviderId}
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import java.net.URI
import java.net.http.{HttpClient as JHttpClient, WebSocket as JWebSocket}
import java.util.concurrent.{CompletableFuture, CompletionStage, LinkedBlockingQueue, TimeUnit}
import munit.FunSuite
import zio.*
import zio.http.{
  ChannelEvent,
  Handler,
  Method,
  Request,
  Routes,
  Server,
  WebSocketApp,
  WebSocketChannel,
  WebSocketFrame,
  handler
}
import zio.json.*
import zio.json.ast.Json

final class OrchestratorWebSocketSuite extends FunSuite {
  private val author = AgentId("human")
  private val sessionId = SessionId.unsafe("session-one")
  private val agent = new Agent[Any] {
    val id: AgentId = author
    def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
      ZIO.dieMessage("Read-only server invoked an agent")
  }

  private def live: IO[YallmoError, OrchestratorLive[Any]] =
    for {
      registry <- RuntimeInstances.fromAgents(Chunk(agent))
      master <- OrchestratorLive.inMemory(registry)
    } yield master

  test("WS client reads the supplied live master, including appends after connection") {
    run(ZIO.scoped {
      for {
        master <- live
        _ <- master.createSession(sessionId, Chunk(author))
        port <- new OrchestratorWSServer(master).start(0)
        client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc")
        info <- client.initialize()
        sessions <- client.listSessions
        before <- client.loadSession(sessionId)
        _ <- master.append(sessionId, author, Chunk(MessagePayload.User("Hello")))
        after <- client.loadSession(sessionId)
        messages <- client.listMessages
        agents <- client.listAgentIds
        concurrent <- ZIO.foreachPar(1 to 20)(_ => client.loadSession(sessionId))
      } yield {
        assertEquals(info.identity.role, Role.Viewer)
        assertEquals(info.notifications, Nil)
        assert(!info.methods.contains(TurnSubmit.method))
        assertEquals(sessions.map(_.id), Chunk(sessionId))
        assertEquals(before.messages, Chunk.empty)
        assertEquals(after.messages.map(_.content), Chunk("Hello"))
        assertEquals(messages, after.messages)
        assertEquals(agents, Chunk(author))
        assertEquals(concurrent.map(_.messages.size).toSet, Set(1))
      }
    })
  }

  test("WS results exclude private provider continuations and return typed remote errors") {
    run(ZIO.scoped {
      for {
        master <- live
        _ <- master.createSession(sessionId, Chunk(author))
        _ <- master.append(
          sessionId,
          author,
          Chunk(
            MessagePayload.Reasoning(
              Some("Public summary"),
              ProviderContinuation(ProviderId.unsafe("provider"), Some("private-item"), "private-ciphertext")
            )
          )
        )
        port <- new OrchestratorWSServer(master).start(0)
        client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc")
        messages <- client.listMessages
        missing <- client.loadSession(SessionId.unsafe("missing")).either
      } yield {
        assertEquals(messages.head.content, "Public summary")
        assert(!messages.toJson.contains("private-item"))
        assert(!messages.toJson.contains("private-ciphertext"))
        assertEquals(missing, Left(OrchestratorClient.Error.Remote(JsonRpc.Error(ErrorCode.NotFound, "Not found"))))
      }
    })
  }

  test("blob chunks, offsets and limits use the existing master without a blob REST endpoint") {
    run(ZIO.scoped {
      for {
        master <- live
        _ <- master.createSession(sessionId, Chunk(author))
        blob = BlobId.fresh()
        _ <- master.appendWithBlobBytes(
          sessionId,
          author,
          MessagePayload.AudioAttachment(blob, AudioFormat.Wav),
          Chunk[Byte](0, 1, -1)
        )
        port <- new OrchestratorWSServer(master).start(0)
        client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc")
        listed <- client.listBlobIds
        first <- client.readBlob(blob, 0, 2)
        last <- client.readBlob(blob, 2, 2)
        eof <- client.readBlob(blob, 3, 2)
        invalid <- ZIO.foreach(List((-1L, 2), (4L, 2), (0L, 0), (0L, MaxBlobChunkBytes + 1))) { case (offset, max) =>
          client.readBlob(blob, offset, max).either
        }
      } yield {
        assertEquals(listed, Chunk(blob))
        assertEquals(first.dataBase64, "AAE=")
        assert(!first.eof)
        assertEquals(last.dataBase64, "/w==")
        assert(last.eof)
        assertEquals(eof.dataBase64, "")
        assert(eof.eof)
        assert(invalid.forall(_ == Left(OrchestratorClient.Error.Remote(JsonRpc.Error.InvalidParams))))
      }
    })
  }

  test("raw JSON-RPC requires per-connection initialization and rejects write methods") {
    run(ZIO.scoped {
      for {
        master <- live
        port <- new OrchestratorWSServer(master).start(0)
        socket <- raw(port)
        _ <- socket.send("""{"jsonrpc":"2.0","id":"before","method":"sessions.list","params":{}}""")
        before <- socket.read
        _ <- socket.send(JsonRpc.Request(Json.Str("version"), Initialize.method, Initialize.Params(99, "test")).toJson)
        version <- socket.read
        _ <- initialize(socket)
        _ <- socket.send("""{"jsonrpc":"2.0","id":"write","method":"sessions.create","params":{}}""")
        write <- socket.read
        other <- raw(port)
        _ <- other.send("""{"jsonrpc":"2.0","id":"other","method":"sessions.list","params":{}}""")
        isolated <- other.read
      } yield {
        assertEquals(decode[JsonRpc.Failure](before).error.code, ErrorCode.Unauthorized)
        assertEquals(decode[JsonRpc.Failure](version).error.code, ErrorCode.UnsupportedVersion)
        assertEquals(decode[JsonRpc.Failure](write).error, JsonRpc.Error.MethodNotFound)
        assertEquals(decode[JsonRpc.Failure](isolated).error.code, ErrorCode.Unauthorized)
      }
    })
  }

  test("raw protocol handles parse/validation errors, batches, null/numeric IDs and notifications") {
    run(ZIO.scoped {
      for {
        master <- live
        port <- new OrchestratorWSServer(master).start(0)
        socket <- raw(port)
        _ <- initialize(socket)
        _ <- socket.send("{} {}")
        parse <- socket.read
        _ <- socket.send("""{"jsonrpc":"1.0","id":true,"method":"sessions.list"}""")
        invalid <- socket.read
        _ <- socket.send(
          """[{"jsonrpc":"2.0","id":null,"method":"sessions.list"},1,{"jsonrpc":"2.0","method":"agents.list"},{"jsonrpc":"2.0","id":7,"method":"missing"}]"""
        )
        batch <- socket.read
        _ <- socket.send("""[{"jsonrpc":"2.0","method":"sessions.list"}]""")
        noReply <- socket.poll
        _ <- socket.send("[]")
        empty <- socket.read
      } yield {
        assertEquals(decode[JsonRpc.Failure](parse).error, JsonRpc.Error.ParseError)
        assertEquals(decode[JsonRpc.Failure](invalid).error, JsonRpc.Error.InvalidRequest)
        val responses = decode[List[Json]](batch)
        assertEquals(responses.size, 3)
        assertEquals(responses.head.as[JsonRpc.Success[Json]].toOption.map(_.id), Some(Json.Null))
        assertEquals(responses.last.as[JsonRpc.Failure].toOption.map(_.id), Some(Json.Num(7)))
        assertEquals(noReply, None)
        assertEquals(decode[JsonRpc.Failure](empty).error, JsonRpc.Error.InvalidRequest)
      }
    })
  }

  test("fragmented text is assembled and foreign browser origins are rejected") {
    run(ZIO.scoped {
      for {
        master <- live
        port <- new OrchestratorWSServer(master).start(0)
        socket <- raw(port)
        _ <- socket.send(
          """{"jsonrpc":"2.0","id":"utf8","method":"initialize","params":{"protocolVersion":1,"clientName":"""",
          false
        )
        _ <- socket.send("日本🙂\"}}", true)
        reply <- socket.read
        rejected <- raw(port, Some("https://evil.invalid")).either
      } yield {
        assertEquals(decode[JsonRpc.Success[Initialize.Result]](reply).id, Json.Str("utf8"))
        assert(rejected.isLeft)
      }
    })
  }

  test("client correlates out-of-order replies, not arrival order") {
    run(ZIO.scoped {
      for {
        first <- Ref.make(Option.empty[Json])
        port <- peer { (channel, request) =>
          if request.method == SessionsList.method then first.set(Some(request.id))
          else
            for {
              id <- first.get.someOrFail(new IllegalStateException("Missing first request"))
              _ <- reply(channel, request.id, Json.Arr(Json.Str("remote-agent")))
              _ <- reply(channel, id, Json.Arr())
            } yield ()
        }
        client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc")
        sessions <- client.listSessions.fork
        _ <- first.get.repeatUntil(_.isDefined)
        agents <- client.listAgentIds
        loaded <- sessions.join
      } yield {
        assertEquals(loaded, Chunk.empty)
        assertEquals(agents, Chunk(AgentId("remote-agent")))
      }
    })
  }

  test("request timeout removes its pending entry and a late reply does not break the next request") {
    run(ZIO.scoped {
      for {
        port <- peer { (channel, request) =>
          if request.method == SessionsList.method then ZIO.sleep(750.millis) *> reply(channel, request.id, Json.Arr())
          else reply(channel, request.id, Json.Arr(Json.Str("still-connected")))
        }
        client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc", 500.millis)
        timedOut <- client.listSessions.either
        agents <- client.listAgentIds
      } yield {
        assertEquals(timedOut, Left(OrchestratorClient.Error.RequestTimedOut))
        assertEquals(agents, Chunk(AgentId("still-connected")))
      }
    })
  }

  test("disconnect fails pending requests and explicit close makes subsequent calls fail promptly") {
    run(ZIO.scoped {
      for {
        port <- peer { (channel, _) => channel.shutdown }
        client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc")
        pending <- client.listSessions.either
        _ <- client.close
        after <- client.listSessions.either
      } yield {
        assertEquals(pending, Left(OrchestratorClient.Error.Disconnected))
        assertEquals(after, Left(OrchestratorClient.Error.Disconnected))
      }
    })
  }

  test("pending requests are bounded and interruption frees a slot") {
    run(ZIO.scoped {
      for {
        seen <- Ref.make(0)
        port <- peer { (channel, request) =>
          if request.method == SessionsList.method then seen.update(_ + 1)
          else reply(channel, request.id, Json.Arr(Json.Str("available")))
        }
        client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc")
        pending <- ZIO.foreach(1 to 128)(_ => client.listSessions.fork)
        _ <- seen.get.repeatUntil(_ == 128)
        overflow <- client.listAgentIds.either
        _ <- pending.head.interrupt
        agents <- client.listAgentIds
        _ <- client.close
        stopped <- ZIO.foreach(pending.tail)(_.await)
      } yield {
        assertEquals(overflow, Left(OrchestratorClient.Error.TooManyPendingRequests))
        assertEquals(agents, Chunk(AgentId("available")))
        assert(stopped.forall(_.isFailure))
      }
    })
  }

  test("malformed server replies fail pending calls as protocol errors") {
    run(ZIO.scoped {
      for {
        port <- peer { (channel, _) => channel.send(ChannelEvent.Read(WebSocketFrame.Text("{bad"))) }
        client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc")
        result <- client.listSessions.either
      } yield assertEquals(result, Left(OrchestratorClient.Error.InvalidResponse))
    })
  }

  test("scope exit closes clients and rejects invalid endpoints without opening connections") {
    run(ZIO.scoped {
      for {
        master <- live
        port <- new OrchestratorWSServer(master).start(0)
        scope <- Scope.make
        client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc").provideEnvironment(ZEnvironment(scope))
        _ <- scope.close(Exit.unit)
        closed <- client.listSessions.either
        invalid <- ZIO.foreach(List("http://localhost/rpc", "ws://user:secret@localhost/rpc", "not a URL")) { url =>
          OrchestratorWSClient.connect(url).either
        }
      } yield {
        assertEquals(closed, Left(OrchestratorClient.Error.Disconnected))
        assert(invalid.forall(_ == Left(OrchestratorClient.Error.InvalidEndpoint)))
      }
    })
  }

  private def peer(
      respond: (WebSocketChannel, JsonRpc.Request[Json.Obj]) => Task[Unit]
  ): ZIO[Scope, Throwable, Int] = {
    val socket: WebSocketApp[Any] = Handler.webSocket { channel =>
      channel
        .receiveAll {
          case ChannelEvent.Read(WebSocketFrame.Text(text)) =>
            val request = decode[JsonRpc.Request[Json.Obj]](text)
            if request.method == Initialize.method then
              channel.send(
                ChannelEvent.Read(
                  WebSocketFrame.Text(
                    JsonRpc
                      .Success(
                        request.id,
                        Initialize.Result(
                          Version,
                          "peer",
                          Identity(author, Role.Viewer),
                          List(SessionsList.method, AgentsList.method),
                          Nil,
                          MaxBlobChunkBytes
                        )
                      )
                      .toJson
                  )
                )
              )
            else respond(channel, request)
          case _ => ZIO.unit
        }
        .ensuring(channel.shutdown)
    }
    for {
      env <- Server.defaultWith(_.binding("127.0.0.1", 0).gracefulShutdownTimeout(100.millis)).build
      port <- Server
        .install(Routes(Method.GET / "rpc" -> handler { (_: Request) => socket.toResponse }))
        .provideEnvironment(env)
    } yield port
  }

  private def reply(channel: WebSocketChannel, id: Json, result: Json): Task[Unit] =
    channel.send(ChannelEvent.Read(WebSocketFrame.Text(JsonRpc.Success(id, result).toJson)))

  private final class Raw(socket: JWebSocket, messages: LinkedBlockingQueue[Either[Throwable, String]]) {
    def send(text: String, last: Boolean = true): Task[Unit] =
      ZIO.attemptBlocking(socket.sendText(text, last).get(3, TimeUnit.SECONDS)).unit
    def read: Task[String] = ZIO.attemptBlocking {
      Option(messages.poll(3, TimeUnit.SECONDS))
        .getOrElse(throw new AssertionError("No WebSocket reply"))
        .fold(throw _, identity)
    }
    def poll: Task[Option[Either[Throwable, String]]] =
      ZIO.attemptBlocking(Option(messages.poll(100, TimeUnit.MILLISECONDS)))
  }

  private def raw(port: Int, origin: Option[String] = None): ZIO[Scope, Throwable, Raw] =
    for {
      http <- ZIO.acquireRelease(ZIO.attempt(JHttpClient.newHttpClient()))(client =>
        ZIO.attemptBlocking(client.close()).ignore
      )
      messages = new LinkedBlockingQueue[Either[Throwable, String]]()
      listener = new JWebSocket.Listener {
        private val text = new StringBuilder
        override def onOpen(socket: JWebSocket): Unit = { socket.request(1); () }
        override def onText(socket: JWebSocket, data: CharSequence, last: Boolean): CompletionStage[?] = {
          text.append(data)
          if last then { messages.offer(Right(text.toString)); text.clear() }
          socket.request(1)
          CompletableFuture.completedFuture(null)
        }
        override def onError(socket: JWebSocket, error: Throwable): Unit = { messages.offer(Left(error)); () }
      }
      socket <- ZIO.acquireRelease(ZIO.attemptBlocking {
        val builder = http.newWebSocketBuilder().connectTimeout(java.time.Duration.ofSeconds(3))
        origin.foreach(value => builder.header("Origin", value))
        builder.buildAsync(URI.create(s"ws://127.0.0.1:$port/rpc"), listener).get(3, TimeUnit.SECONDS)
      })(socket => ZIO.succeed(socket.abort()))
    } yield new Raw(socket, messages)

  private def initialize(socket: Raw): Task[Unit] =
    socket.send(
      JsonRpc.Request(Json.Str("init"), Initialize.method, Initialize.Params(Version, "test")).toJson
    ) *> socket.read.unit

  private def decode[A: JsonDecoder](value: String): A = value.fromJson[A].fold(fail(_), identity)

  private def run[A](effect: ZIO[Any, Any, A]): A =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe
        .run(effect.timeoutFail(new AssertionError("WebSocket test timed out"))(15.seconds))
        .getOrThrowFiberFailure()
    }
}
