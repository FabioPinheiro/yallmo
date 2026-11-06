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
  private val author = AgentId("user-human")
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

  test("viewer subscriptions multiplex pending and canonical updates with replies; legacy clients receive none") {
    run(ZIO.scoped {
      for {
        master <- live
        _ <- master.createSession(sessionId, Chunk(author))
        port <- new OrchestratorWSServer(master).start(0)
        first <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc")
        second <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc")
        legacy <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc")
        baseline <- first.subscribeEvents("first")
        other <- second.subscribeEvents("second")
        saved <- master.append(sessionId, author, Chunk(MessagePayload.User("pushed")))
        reads <- first.listSessions.zipPar(second.listAgentIds).zipPar(legacy.listMessages)
        events <- first.nextEventUpdate.zip(first.nextEventUpdate)
        others <- second.nextEventUpdate.zip(second.nextEventUpdate)
        waiting <- first.nextEventUpdate.either.fork
        _ <- first.unsubscribeEvents(baseline.subscriptionId)
        stopped <- waiting.join
        replacement <- first.subscribeEvents("replacement")
        _ <- first.unsubscribeEvents(baseline.subscriptionId) // Stale unsubscribe cannot close the replacement.
        _ <- master.append(sessionId, author, Chunk(MessagePayload.User("still pushed")))
        next <- first.nextEventUpdate.zip(first.nextEventUpdate)
        _ <- first.unsubscribeEvents(replacement.subscriptionId)
        _ <- second.unsubscribeEvents(other.subscriptionId)
        _ <- master.append(sessionId, author, Chunk(MessagePayload.User("unsubscribed")))
        inventory <- first.listMessages
      } yield {
        assertEquals(stopped, Left(OrchestratorClient.Error.Disconnected))
        assertEquals(baseline.messages, Chunk.empty)
        assertEquals(baseline.sessions.head.head, None)
        assertEquals(events._1.subscriptionId, baseline.subscriptionId)
        assertEquals(others._1.subscriptionId, other.subscriptionId)
        assertEquals(events._1.change, others._1.change)
        assertEquals(events._2.sequence, events._1.sequence + 1)
        assert(events._1.change.isInstanceOf[EventChange.Pending])
        val confirmed = events._2.change.asInstanceOf[EventChange.Confirmed]
        assertEquals(confirmed.messages.map(_.id), saved.messages.map(_.id))
        assertEquals(confirmed.sessions.head.head, saved.messages.lastOption.map(_.id))
        assert(confirmed.sessions.head.revision > baseline.sessions.head.revision)
        assertEquals(reads._3.map(_.content), Chunk("pushed"))
        assertEquals(next._1.subscriptionId, replacement.subscriptionId)
        assertEquals(inventory.map(_.content), Chunk("pushed", "still pushed", "unsubscribed"))
      }
    })
  }

  test("JVM graph demultiplexing accepts notification before subscribe response and interleaved request IDs") {
    run(ZIO.scoped {
      for {
        port <- peer { (channel, request) =>
          if request.method == EventsSubscribe.method then
            for {
              _ <- channel.send(
                ChannelEvent.Read(
                  WebSocketFrame.Text(
                    JsonRpc
                      .Notification(
                        EventsUpdate.method,
                        EventsUpdate.Params("sub", "stream", "early", 1L, EventChange.Pending("op", Chunk.empty))
                      )
                      .toJson
                  )
                )
              )
              _ <- reply(
                channel,
                request.id,
                EventsSubscribe
                  .Result("sub", "stream", "early", 0L, Chunk.empty, Chunk.empty, Chunk.empty)
                  .toJsonAST
                  .fold(fail(_), identity)
              )
            } yield ()
          else reply(channel, request.id, Json.Arr())
        }
        client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc")
        baseline <- client.subscribeEvents("early")
        listed <- client.listSessions
        early <- client.nextEventUpdate
      } yield {
        assertEquals(baseline.startSequence, 0L)
        assertEquals(early.sequence, 1L)
        assertEquals(early.nonce, "early")
        assertEquals(listed, Chunk.empty)
      }
    })
  }

  for fault <- List("gap", "stream", "subscription") do
    test(s"JVM event feed rejects wrong $fault without weakening response validation") {
      run(ZIO.scoped {
        for {
          port <- peer { (channel, request) =>
            for {
              _ <- reply(
                channel,
                request.id,
                EventsSubscribe
                  .Result("sub", "stream", "checked", 0L, Chunk.empty, Chunk.empty, Chunk.empty)
                  .toJsonAST
                  .fold(fail(_), identity)
              )
              _ <- channel.send(
                ChannelEvent.Read(
                  WebSocketFrame.Text(
                    JsonRpc
                      .Notification(
                        EventsUpdate.method,
                        EventsUpdate.Params(
                          if fault == "subscription" then "wrong" else "sub",
                          if fault == "stream" then "wrong" else "stream",
                          "checked",
                          if fault == "gap" then 2L else 1L,
                          EventChange.Uncertain("op")
                        )
                      )
                      .toJson
                  )
                )
              )
            } yield ()
          }
          client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc")
          _ <- client.subscribeEvents("checked")
          result <- client.nextEventUpdate.either
        } yield assertEquals(result, Left(OrchestratorClient.Error.InvalidResponse))
      })
    }

  test("JVM bounds notification-before-response buffering and fails explicitly on overflow") {
    run(ZIO.scoped {
      for {
        port <- peer { (channel, _) =>
          ZIO.foreachDiscard(1 to 129) { sequence =>
            channel.send(
              ChannelEvent.Read(
                WebSocketFrame.Text(
                  JsonRpc
                    .Notification(
                      EventsUpdate.method,
                      EventsUpdate.Params(
                        "sub",
                        "stream",
                        "overflow",
                        sequence.toLong,
                        EventChange.Pending(s"op-$sequence", Chunk.empty)
                      )
                    )
                    .toJson
                )
              )
            )
          }
        }
        client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc")
        result <- client.subscribeEvents("overflow").either
      } yield assertEquals(result, Left(OrchestratorClient.Error.InvalidResponse))
    })
  }

  for invalid <- List(
      """{"jsonrpc":"2.0","method":"unknown.event","params":{}}""",
      """{"jsonrpc":"2.0","method":"events.update","id":"bad","params":{}}""",
      """{"jsonrpc":"2.0","method":"events.update","result":[],"params":{}}""",
      """{"jsonrpc":"2.0","method":"events.update","params":{},"params":{}}""",
      """{"jsonrpc":"2.0","method":"events.update","params":{"subscriptionId":"s","streamId":"s","nonce":"n","sequence":1,"sequence":2,"change":{"kind":"uncertain","operationId":"o"}}}""",
      JsonRpc
        .Notification(
          EventsUpdate.method,
          EventsUpdate.Params("unsolicited", "stream", "nonce", 1L, EventChange.Uncertain("op"))
        )
        .toJson
    )
  do
    test(s"JVM rejects malformed or unsolicited event notification: ${invalid.take(90)}") {
      run(ZIO.scoped {
        for {
          port <- peer { (channel, _) => channel.send(ChannelEvent.Read(WebSocketFrame.Text(invalid))) }
          client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc")
          failure <- client.listSessions.either
        } yield assertEquals(failure, Left(OrchestratorClient.Error.InvalidResponse))
      })
    }

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
        // Stay below the connection's nonwaiting worker bound; overload behavior is tested separately.
        concurrent <- ZIO.foreachPar(1 to 20)(_ => client.loadSession(sessionId)).withParallelism(4)
      } yield {
        assertEquals(info.identity.role, Role.Viewer)
        assertEquals(info.notifications, List(EventsUpdate.method))
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

  test("human viewer presence is connection-owned, deduplicated and removed only after the final socket closes") {
    val removed = new LinkedBlockingQueue[String]()
    val capture = ZLogger.default.map { line =>
      if line.contains("Viewer identity removed") then removed.offer(line)
      ()
    }
    def awaitRemoval: Task[String] = ZIO.attemptBlocking {
      Option(removed.poll(3, TimeUnit.SECONDS)).getOrElse(throw new AssertionError("No identity removal"))
    }
    run(ZIO.scoped {
      for {
        _ <- OrchestratorServerMain.configureLogging(LogLevel.Info, capture).build
        master <- live
        port <- new OrchestratorWSServer(master).start(0)
        endpoint = s"ws://127.0.0.1:$port/rpc"
        browser <- raw(port)
        _ <- initialize(browser)
        observer <- OrchestratorWSClient.connect(endpoint)
        initial <- observer.listAgentIds
        alice = AgentId("user-alice")
        bob = AgentId("user-bob")
        pair <- ZIO.foreachPar(1 to 2)(_ =>
          OrchestratorWSClient.connect(endpoint, userId = Some(UserId(AgentId.value(alice).stripPrefix("user-"))))
        )
        other <- OrchestratorWSClient.connect(
          endpoint,
          userId = Some(UserId(AgentId.value(bob).stripPrefix("user-"))),
          clientName = "connected-repl"
        )
        bound <- other.initialize("different-client-metadata")
        all <- observer.listAgentIds
        _ <- pair.head.close
        _ <- awaitRemoval
        retained <- observer.listAgentIds
        _ <- pair.last.close
        _ <- awaitRemoval
        isolated <- observer.listAgentIds
        _ <- other.close
        _ <- awaitRemoval
        finalIds <- observer.listAgentIds
        registered <- master.listAgentIds
        _ <- browser.send(JsonRpc.Request(Json.Str("browser"), AgentsList.method, Empty()).toJson)
        browserIds <- browser.read
      } yield {
        assertEquals(initial, Chunk(author))
        assertEquals(
          bound.identity,
          Identity(
            UserId(AgentId.value(bob).stripPrefix("user-")),
            Role.Viewer,
            Chunk(bob, UserId.websiteAgent(UserId(AgentId.value(bob).stripPrefix("user-"))))
          )
        )
        assertEquals(all.toSet, Set(alice, bob, author, AgentId("web-user-alice"), AgentId("web-user-bob")))
        assertEquals(retained, all)
        assertEquals(isolated.toSet, Set(bob, author, AgentId("web-user-bob")))
        assertEquals(finalIds, Chunk(author))
        assertEquals(registered, Chunk(author))
        assertEquals(decode[JsonRpc.Success[Chunk[AgentId]]](browserIds).result, Chunk(author))
      }
    })
  }

  test("aborted and rejected-frame sockets remove presence and identity logs escape terminal controls") {
    val identities = new LinkedBlockingQueue[String]()
    val capture = ZLogger.default.map { line =>
      if line.contains("Viewer identity") then identities.offer(line)
      ()
    }
    def identityLog: Task[String] = ZIO.attemptBlocking {
      Option(identities.poll(3, TimeUnit.SECONDS)).getOrElse(throw new AssertionError("Missing identity lifecycle log"))
    }
    run(ZIO.scoped {
      for {
        _ <- OrchestratorServerMain.configureLogging(LogLevel.Info, capture).build
        master <- live
        port <- new OrchestratorWSServer(master).start(0)
        observer <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc")
        _ <- ZIO.foreachDiscard(List(false, true)) { rejectFrame =>
          for {
            socket <- raw(port)
            label = AgentId("evil\n\u001b[31m\u009b2J")
            _ <- socket.send(
              JsonRpc
                .Request(
                  Json.Str("init"),
                  Initialize.method,
                  Initialize.Params("raw", Some(label).map(id => UserId(AgentId.value(id).stripPrefix("user-"))))
                )
                .toJson
            )
            _ <- socket.read
            registration <- identityLog
            present <- observer.listAgentIds
            _ <- if rejectFrame then socket.sendBinary else socket.abort
            removal <- identityLog
            absent <- observer.listAgentIds
          } yield {
            assert(present.contains(AgentId("user-" + AgentId.value(label))))
            assertEquals(absent, Chunk(author))
            assert(registration.contains("registered"))
            assert(removal.contains("removed"))
            List(registration, removal).foreach { line =>
              assert(!line.contains('\u001b'))
              assert(!line.contains('\u009b'))
              assert(!line.contains("evil\n"))
              assert(line.contains("\\u001b"))
              assert(line.contains("\\u009b"))
            }
          }
        }
      } yield ()
    })
  }

  test("raw sockets retain their initialized human or anonymous identity on every reinitialize") {
    run(ZIO.scoped {
      for {
        master <- live
        port <- new OrchestratorWSServer(master).start(0)
        human <- raw(port)
        alice = AgentId("user-alice")
        replies <- ZIO.foreach(List(Some(alice), Some(alice), Some(AgentId("user-bob")), None)) { id =>
          human.send(
            JsonRpc
              .Request(
                Json.Str("init"),
                Initialize.method,
                Initialize.Params("raw", id.map(id => UserId(AgentId.value(id).stripPrefix("user-"))))
              )
              .toJson
          ) *> human.read
        }
        browser <- raw(port)
        _ <- initialize(browser)
        _ <- initialize(browser)
        _ <- browser.send(
          JsonRpc
            .Request(
              Json.Str("add"),
              Initialize.method,
              Initialize.Params("raw", Some(alice).map(id => UserId(AgentId.value(id).stripPrefix("user-"))))
            )
            .toJson
        )
        added <- browser.read
        _ <- browser.send(JsonRpc.Request(Json.Str("agents"), AgentsList.method, Empty()).toJson)
        ids <- browser.read
      } yield {
        assertEquals(
          decode[JsonRpc.Success[Initialize.Result]](replies.head).result.identity,
          Identity(
            UserId(AgentId.value(alice).stripPrefix("user-")),
            Role.Viewer,
            Chunk(alice, UserId.websiteAgent(UserId(AgentId.value(alice).stripPrefix("user-"))))
          )
        )
        assertEquals(replies(1), replies.head)
        assertEquals(
          replies.drop(2).map(decode[JsonRpc.Failure](_).error.code),
          List(ErrorCode.Conflict, ErrorCode.Conflict)
        )
        assertEquals(decode[JsonRpc.Failure](added).error.code, ErrorCode.Conflict)
        assertEquals(
          decode[JsonRpc.Success[Chunk[AgentId]]](ids).result.toSet,
          Set(alice, author, AgentId("web-user-alice"))
        )
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
        _ <- socket.send(
          JsonRpc
            .Request(
              Json.Str("collision"),
              Initialize.method,
              Initialize.Params("test", Some(author).map(id => UserId(AgentId.value(id).stripPrefix("user-"))))
            )
            .toJson
        )
        collision <- socket.read
        _ <- socket.send("""{"jsonrpc":"2.0","id":"after-failure","method":"agents.list","params":{}}""")
        unauthorized <- socket.read
        observer <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc")
        unpolluted <- observer.listAgentIds
        _ <- initialize(socket)
        _ <- socket.send("""{"jsonrpc":"2.0","id":"write","method":"sessions.create","params":{}}""")
        write <- socket.read
        other <- raw(port)
        _ <- other.send("""{"jsonrpc":"2.0","id":"other","method":"sessions.list","params":{}}""")
        isolated <- other.read
      } yield {
        assertEquals(decode[JsonRpc.Failure](before).error.code, ErrorCode.Unauthorized)
        assertEquals(decode[JsonRpc.Failure](collision).error.code, ErrorCode.Conflict)
        assertEquals(decode[JsonRpc.Failure](unauthorized).error.code, ErrorCode.Unauthorized)
        assertEquals(unpolluted, Chunk(author))
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

  test("fragmented text is assembled and arbitrary browser origins are accepted") {
    run(ZIO.scoped {
      for {
        master <- live
        port <- new OrchestratorWSServer(master).start(0)
        socket <- raw(port)
        _ <- socket.send(
          """{"jsonrpc":"2.0","id":"utf8","method":"initialize","params":{"clientName":"""",
          false
        )
        _ <- socket.send("日本🙂\"}}", true)
        reply <- socket.read
        browser <- raw(port, Some("https://browser.internal:9443"))
        _ <- initialize(browser)
      } yield {
        assertEquals(decode[JsonRpc.Success[Initialize.Result]](reply).id, Json.Str("utf8"))
      }
    })
  }

  test("client sends human label and custom name in the first initialize and retains it on repeated initialize") {
    run(ZIO.scoped {
      for {
        seen <- Ref.make(Vector.empty[Initialize.Params])
        alice = AgentId("user-alice")
        port <- peer(
          (_, _) => ZIO.unit,
          initializeWith = Some { (channel, request) =>
            val params = request.params.as[Initialize.Params].fold(fail(_), identity)
            seen.update(_ :+ params) *> reply(
              channel,
              request.id,
              Initialize
                .Result(
                  "peer",
                  Identity(
                    params.userId.getOrElse(UserId("local-viewer")),
                    Role.Viewer,
                    params.userId.fold(Chunk.empty[AgentId])(id => Chunk(UserId.chatAgent(id), UserId.websiteAgent(id)))
                  ),
                  Nil,
                  Nil,
                  MaxBlobChunkBytes
                )
                .toJsonAST
                .fold(fail(_), identity)
            )
          }
        )
        client <- OrchestratorWSClient.connect(
          s"ws://127.0.0.1:$port/rpc",
          userId = Some(UserId(AgentId.value(alice).stripPrefix("user-"))),
          clientName = "repl"
        )
        repeated <- client.initialize("renamed")
        requests <- seen.get
      } yield {
        assertEquals(
          requests,
          Vector(
            Initialize.Params("repl", Some(alice).map(id => UserId(AgentId.value(id).stripPrefix("user-")))),
            Initialize.Params("renamed", Some(alice).map(id => UserId(AgentId.value(id).stripPrefix("user-"))))
          )
        )
        assertEquals(
          repeated.identity,
          Identity(
            UserId(AgentId.value(alice).stripPrefix("user-")),
            Role.Viewer,
            Chunk(alice, UserId.websiteAgent(UserId(AgentId.value(alice).stripPrefix("user-"))))
          )
        )
      }
    })
  }

  test("failed initialization closes the client scope for ignored labels, wrong roles and remote rejection") {
    run(ZIO.scoped {
      ZIO.foreachDiscard(List("ignored", "operator", "rejected")) { mode =>
        for {
          closed <- Promise.make[Nothing, Unit]
          port <- peer(
            (_, _) => ZIO.unit,
            initializeWith = Some { (channel, request) =>
              if mode == "rejected" then
                channel.send(
                  ChannelEvent.Read(
                    WebSocketFrame.Text(
                      JsonRpc
                        .Failure(
                          request.id,
                          JsonRpc.Error(ErrorCode.Forbidden, "Initialization rejected")
                        )
                        .toJson
                    )
                  )
                )
              else
                reply(
                  channel,
                  request.id,
                  Initialize
                    .Result(
                      "old-peer",
                      Identity(
                        UserId(
                          AgentId
                            .value(if mode == "ignored" then author else AgentId("user-alice"))
                            .stripPrefix("user-")
                        ),
                        if mode == "operator" then Role.Operator else Role.Viewer,
                        Chunk(
                          if mode == "ignored" then author else AgentId("user-alice"),
                          UserId.websiteAgent(
                            UserId(
                              AgentId
                                .value(if mode == "ignored" then author else AgentId("user-alice"))
                                .stripPrefix("user-")
                            )
                          )
                        )
                      ),
                      Nil,
                      Nil,
                      MaxBlobChunkBytes
                    )
                    .toJsonAST
                    .fold(fail(_), identity)
                )
            },
            onClose = closed.succeed(()).unit
          )
          result <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc", userId = Some(UserId("alice"))).either
          _ <- closed.await.timeoutFail(new AssertionError("Failed initialize leaked its socket"))(3.seconds)
        } yield {
          if mode == "rejected" then
            assertEquals(
              result,
              Left(
                OrchestratorClient.Error.Remote(
                  JsonRpc.Error(ErrorCode.Forbidden, "Initialization rejected")
                )
              )
            )
          else assertEquals(result, Left(OrchestratorClient.Error.InvalidResponse))
        }
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

  test("interrupting a local wait sends no cancellation or replay and ignores its late reply") {
    run(ZIO.scoped {
      for {
        abandoned <- Promise.make[Nothing, Json]
        methods <- Ref.make(Vector.empty[String])
        port <- peer { (channel, request) =>
          methods.update(_ :+ request.method) *>
            (if request.method == SessionsList.method then abandoned.succeed(request.id).unit
             else
               abandoned.await.flatMap(id => reply(channel, id, Json.Arr())) *>
                 reply(channel, request.id, Json.Arr(Json.Str("still-connected"))))
        }
        client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc")
        wait <- client.listSessions.fork
        _ <- abandoned.await
        _ <- wait.interrupt
        agents <- client.listAgentIds
        seen <- methods.get
      } yield {
        assertEquals(agents, Chunk(AgentId("still-connected")))
        assertEquals(seen, Vector(SessionsList.method, AgentsList.method))
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

  test("client assembles fragmented replies including a Unicode code point split across wire frames") {
    run(ZIO.scoped {
      for {
        port <- peer { (channel, request) =>
          val output = JsonRpc.Success(request.id, Chunk(AgentId("日本🙂"))).toJson
          val start = output.indexOf('日')
          val bytes = Chunk.fromArray(output.substring(start).getBytes(java.nio.charset.StandardCharsets.UTF_8))
          channel.send(ChannelEvent.Read(WebSocketFrame.Text(output.substring(0, start), false))) *>
            channel.send(ChannelEvent.Read(WebSocketFrame.Continuation(bytes.take(2), false))) *>
            channel.send(ChannelEvent.Read(WebSocketFrame.Continuation(bytes.drop(2), true)))
        }
        client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc")
        agents <- client.listAgentIds
      } yield assertEquals(agents, Chunk(AgentId("日本🙂")))
    })
  }

  test("client rejects binary and oversized assembled replies and cleans up pending requests") {
    run(ZIO.scoped {
      ZIO.foreachDiscard(List(false, true)) { oversized =>
        for {
          port <- peer { (channel, request) =>
            if !oversized then channel.send(ChannelEvent.Read(WebSocketFrame.Binary(Chunk[Byte](1))))
            else {
              val output = JsonRpc.Success(request.id, Chunk("x" * RpcWire.MaxMessageBytes)).toJson
              val middle = output.length / 2
              channel.send(ChannelEvent.Read(WebSocketFrame.Text(output.take(middle), false))) *>
                channel.send(
                  ChannelEvent.Read(
                    WebSocketFrame.Continuation(
                      Chunk.fromArray(output.drop(middle).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                      true
                    )
                  )
                )
            }
          }
          client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc")
          pending <- client.listAgentIds.either
          after <- client.listAgentIds.either
        } yield {
          assertEquals(pending, Left(OrchestratorClient.Error.InvalidResponse))
          assertEquals(after, Left(OrchestratorClient.Error.Disconnected))
        }
      }
    })
  }

  private def peer(
      respond: (WebSocketChannel, JsonRpc.Request[Json.Obj]) => Task[Unit],
      initializeWith: Option[(WebSocketChannel, JsonRpc.Request[Json.Obj]) => Task[Unit]] = None,
      onClose: UIO[Unit] = ZIO.unit
  ): ZIO[Scope, Throwable, Int] = {
    val socket: WebSocketApp[Any] = Handler.webSocket { channel =>
      channel
        .receiveAll {
          case ChannelEvent.Read(WebSocketFrame.Text(text)) =>
            val request = decode[JsonRpc.Request[Json.Obj]](text)
            if request.method == Initialize.method then
              initializeWith.fold(
                channel.send(
                  ChannelEvent.Read(
                    WebSocketFrame.Text(
                      JsonRpc
                        .Success(
                          request.id,
                          Initialize.Result(
                            "peer",
                            Identity(
                              UserId(AgentId.value(author).stripPrefix("user-")),
                              Role.Viewer,
                              Chunk(author, UserId.websiteAgent(UserId(AgentId.value(author).stripPrefix("user-"))))
                            ),
                            List(SessionsList.method, AgentsList.method),
                            Nil,
                            MaxBlobChunkBytes
                          )
                        )
                        .toJson
                    )
                  )
                )
              )(handler => handler(channel, request))
            else respond(channel, request)
          case ChannelEvent.Read(_: WebSocketFrame.Close) => channel.shutdown
          case _                                          => ZIO.unit
        }
        .ensuring(channel.shutdown *> onClose)
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
    def abort: UIO[Unit] = ZIO.succeed(socket.abort())
    def sendBinary: Task[Unit] =
      ZIO
        .attemptBlocking(socket.sendBinary(java.nio.ByteBuffer.wrap(Array[Byte](1)), true).get(3, TimeUnit.SECONDS))
        .unit
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
      JsonRpc.Request(Json.Str("init"), Initialize.method, Initialize.Params("test")).toJson
    ) *> socket.read.unit

  private def decode[A: JsonDecoder](value: String): A = value.fromJson[A].fold(fail(_), identity)

  private def run[A](effect: ZIO[Any, Any, A]): A =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe
        .run(effect.timeoutFail(new AssertionError("WebSocket test timed out"))(15.seconds))
        .getOrThrowFiberFailure()
    }
}
