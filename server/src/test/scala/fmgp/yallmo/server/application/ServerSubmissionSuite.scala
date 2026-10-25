package fmgp.yallmo.server.application

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.orchestration.config.{AgentCapabilities, AgentConfig, Config, ProviderConfig, ProviderId}
import fmgp.yallmo.server.*
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import fmgp.yallmo.tool.*
import java.nio.file.{Files, Path}
import java.util.Comparator
import munit.FunSuite
import zio.{Config as _, *}
import zio.http.{Version as HttpVersion, *}
import zio.json.*
import zio.json.ast.Json

final class ServerSubmissionSuite extends FunSuite {
  private val human = AgentId("local-human")
  private val first = AgentId("first")
  private val second = AgentId("second")
  private val provider = ProviderId.unsafe("fake")
  private val definition = AgentConfig(
    "OAIAgent",
    Seq(provider),
    Json.Obj("model" -> Json.Str("test")),
    capabilities = AgentCapabilities(tools = Some(Set.empty))
  )
  private val config = Config(
    agents = Map(first -> definition, second -> definition),
    providers = Map(provider -> ProviderConfig.OAI("http://fake.invalid/v1"))
  )
  private def answer: Response =
    Response.text("""{"choices":[{"message":{"role":"assistant","content":"answer"},"finish_reason":"stop"}]}""")

  test("OrchestratorRpc and OrchestratorWSServer reject a configured backend owned by another master") {
    run(ZIO.scoped {
      for {
        calls <- Ref.make(0)
        appA <- application(_ => calls.update(_ + 1).as(answer))
        appB <- application(_ => calls.update(_ + 1).as(answer))
        _ <- ZIO.attempt {
          assertNotEquals(appA.configured.get.workspace, appB.configured.get.workspace)
          val hostError = intercept[IllegalArgumentException] {
            new OrchestratorWSServer(appA.master, appB.configured)
          }
          val rpcError = intercept[IllegalArgumentException] {
            new OrchestratorRpc(appA.master, appB.configured)
          }
          assertEquals(hostError.getMessage, "requirement failed: Configured backend must use the supplied master")
          assertEquals(rpcError.getMessage, hostError.getMessage)
        }
        _ <- ZIO.attempt(new OrchestratorWSServer(appA.master, appA.configured))
        _ <- ZIO.attempt(new OrchestratorWSServer(appA.master, None))
        viewer = new OrchestratorRpc(appA.master, None)
        connection <- viewer.openConnection(true)
        initialized <- viewer.handle(
          JsonRpc
            .Request(
              Json.Str("init"),
              Initialize.method,
              Initialize.Params(fmgp.yallmo.server.protocol.YallmoProtocol.Version, "test", Some(human))
            )
            .toJson,
          connection
        )
        rejected <- viewer.handle(
          JsonRpc.Request(Json.Str("create"), SessionsCreate.method, SessionsCreate.Params()).toJson,
          connection
        )
        _ <- viewer.disconnect(connection)
        sessions <- ZIO.foreach(List(appA, appB))(_.master.listSessions)
        agents <- ZIO.foreach(List(appA, appB))(_.master.listAgentIds)
        count <- calls.get
      } yield {
        val info = decode[JsonRpc.Success[Initialize.Result]](initialized.get).result
        assertEquals(info.identity, Identity(human, Role.Viewer))
        assertEquals(info.methods.size, 7)
        assertEquals(decode[JsonRpc.Failure](rejected.get).error, JsonRpc.Error.MethodNotFound)
        assert(sessions.forall(_.isEmpty))
        assert(agents.forall(_.toSet == Set(first, second)))
        assertEquals(count, 0)
      }
    })
  }

  test("configured server executes once with durable claimed author, prompt and real message delta IDs") {
    run(ZIO.scoped {
      for {
        calls <- Ref.make(0)
        app <- application(_ => calls.update(_ + 1).as(answer))
        port <- new OrchestratorWSServer(app.master, app.configured).start(0)
        client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc", humanId = Some(human))
        info <- client.capabilities
        created <- client.createSession(Some(SessionId.unsafe("created")))
        outcome <- client.submitTurn(created.id, first, AgentTask("prompt"))
        stored <- app.master.loadSession(created.id)
        count <- calls.get
        _ <- client.close
        again <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc", humanId = Some(human))
        reloaded <- again.loadSession(created.id)
      } yield {
        assertEquals(info.get.identity, Identity(human, Role.Operator))
        assertEquals(info.get.methods.size, 9)
        assertEquals(info.get.configuredAgentIds, Some(Chunk(first, second)))
        assertEquals(count, 1)
        assertEquals(stored.messages.map(_.agentId), Chunk(human, first))
        assertEquals(
          stored.messages.map(_.payload),
          Chunk(MessagePayload.User("prompt"), MessagePayload.Assistant("answer"))
        )
        val success = outcome.asInstanceOf[TurnOutcome.Succeeded]
        assertEquals(success.session, SessionSnapshot.from(stored))
        assertEquals(success.newMessages.map(_.id), stored.messages.map(_.id))
        assertEquals(reloaded, success.session)
      }
    })
  }

  test("accepted typed provider failure is sanitized and leaves prompt once; later submission succeeds") {
    run(ZIO.scoped {
      for {
        calls <- Ref.make(0)
        app <- application(_ =>
          calls.updateAndGet(_ + 1).map {
            case 1 => Response.text("PRIVATE /secret credential").status(Status.InternalServerError)
            case _ => answer
          }
        )
        backend = app.configured.get
        session <- backend.create(human, Some(SessionId.unsafe("failed")))
        failed <- backend.submit(human, session.id, first, "keep")
        recovered <- backend.submit(human, session.id, first, "continue")
        loaded <- app.master.loadSession(session.id)
      } yield {
        val failure = failed.asInstanceOf[TurnOutcome.Failed]
        assertEquals(failure.acceptance, PromptAcceptance.Accepted)
        assertEquals(failure.session.get.messages.map(_.content), Chunk("keep"))
        assert(!(failure: TurnOutcome).toJson.contains("PRIVATE"))
        assert(!(failure: TurnOutcome).toJson.contains("credential"))
        assert(recovered.isInstanceOf[TurnOutcome.Succeeded])
        assertEquals(
          loaded.messages.map(_.payload),
          Chunk(MessagePayload.User("keep"), MessagePayload.User("continue"), MessagePayload.Assistant("answer"))
        )
      }
    })
  }

  test("provider defect returns terminal Unknown with safe snapshot and releases admission") {
    run(ZIO.scoped {
      for {
        calls <- Ref.make(0)
        app <- application(_ =>
          calls.updateAndGet(_ + 1).flatMap {
            case 1 => ZIO.dieMessage("PRIVATE stack /secret credential")
            case _ => ZIO.succeed(answer)
          }
        )
        backend = app.configured.get
        session <- backend.create(human, Some(SessionId.unsafe("defect")))
        failed <- backend.submit(human, session.id, first, "keep")
        success <- backend.submit(human, session.id, first, "continue")
      } yield {
        val failure = failed.asInstanceOf[TurnOutcome.Failed]
        assertEquals(failure.acceptance, PromptAcceptance.Unknown)
        assertEquals(failure.session.get.messages.map(_.content), Chunk("keep"))
        assert(!(failure: TurnOutcome).toJson.contains("PRIVATE"))
        assert(success.isInstanceOf[TurnOutcome.Succeeded])
      }
    })
  }

  test("independent sessions overlap providers; same-session Busy rejects without a second prompt") {
    run(ZIO.scoped {
      for {
        entered <- Promise.make[Nothing, Unit]
        calls <- Ref.make(0)
        release <- Promise.make[Nothing, Unit]
        app <- application(_ =>
          calls.updateAndGet(_ + 1).flatMap(n => ZIO.when(n == 2)(entered.succeed(()))) *> release.await.as(answer)
        )
        backend = app.configured.get
        one <- backend.create(human, Some(SessionId.unsafe("one")))
        two <- backend.create(human, Some(SessionId.unsafe("two")))
        a <- backend.submit(human, one.id, first, "one").fork
        b <- backend.submit(human, two.id, second, "two").fork
        _ <- entered.await
        busy <- backend.submit(human, one.id, second, "must not append").either
        during <- app.master.loadSession(one.id)
        _ <- release.succeed(())
        _ <- a.join.zipPar(b.join)
      } yield {
        assertEquals(busy.left.toOption.map(_.code), Some(ErrorCode.Busy))
        assertEquals(during.messages.map(_.payload), Chunk(MessagePayload.User("one")))
      }
    })
  }

  test("expectedHead is checked under admission before membership/prompt writes; None skips") {
    run(ZIO.scoped {
      for {
        calls <- Ref.make(0)
        app <- application(_ => calls.update(_ + 1).as(answer))
        backend = app.configured.get
        session <- backend.create(human, Some(SessionId.unsafe("head")))
        stale <- backend
          .submit(AgentId("other-human"), session.id, first, "no", Some(MessageId.unsafe("sha256:" + "a" * 64)))
          .either
        after <- app.master.loadSession(session.id)
        before <- calls.get
        success <- backend.submit(human, session.id, first, "yes")
        current = success.asInstanceOf[TurnOutcome.Succeeded].session
        matched <- backend.submit(human, session.id, first, "matched", current.messages.lastOption.map(_.id))
      } yield {
        assertEquals(stale.left.toOption.map(_.code), Some(ErrorCode.Conflict))
        assertEquals(SessionSnapshot.from(after), session)
        assertEquals(before, 0)
        assert(matched.isInstanceOf[TurnOutcome.Succeeded])
      }
    })
  }

  test("selected assistants must be configured, never registered humans or arbitrary presence IDs") {
    run(ZIO.scoped {
      for {
        app <- application(_ => ZIO.dieMessage("must not execute"))
        backend = app.configured.get
        session <- backend.create(human, Some(SessionId.unsafe("selection")))
        rejected <- ZIO.foreach(List(human, AgentId("viewer")))(id =>
          backend.submit(human, session.id, id, "no").either
        )
        after <- app.master.loadSession(session.id)
      } yield {
        assert(rejected.forall(_.left.toOption.exists(_.code == ErrorCode.Rejected)))
        assertEquals(after.messages, Chunk.empty)
      }
    })
  }

  test("disconnect interrupts only wait; same socket reads remain responsive while provider is blocked") {
    run(ZIO.scoped {
      for {
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        finished <- Promise.make[Nothing, Unit]
        app <- application(_ => (entered.succeed(()) *> release.await.as(answer)).ensuring(finished.succeed(()).unit))
        port <- new OrchestratorWSServer(app.master, app.configured).start(0)
        endpoint = s"ws://127.0.0.1:$port/rpc"
        client <- OrchestratorWSClient.connect(endpoint, humanId = Some(human))
        session <- client.createSession(Some(SessionId.unsafe("disconnect")))
        wait <- client.submitTurn(session.id, first, AgentTask("keep")).fork
        _ <- entered.await
        during <- client.loadSession(session.id)
        _ <- client.close
        lost <- wait.await
        observer <- OrchestratorWSClient.connect(endpoint, humanId = Some(human))
        busy <- observer.submitTurn(session.id, first, AgentTask("not retried")).either
        _ <- release.succeed(())
        _ <- finished.await
        after <- (ZIO.yieldNow *> observer.loadSession(session.id)).repeatUntil(_.messages.size == 2)
      } yield {
        assertEquals(during.messages.map(_.content), Chunk("keep"))
        assertEquals(lost.causeOption.flatMap(_.failureOption), Some(OrchestratorClient.Error.Disconnected))
        assertEquals(
          busy.left.toOption.collect { case OrchestratorClient.Error.Remote(error) => error.code },
          Some(ErrorCode.Busy)
        )
        assertEquals(after.messages.map(_.content), Chunk("keep", "answer"))
      }
    })
  }

  test("shutdown interrupts app work, runs provider finalizers before root lease and services release") {
    run(ZIO.scoped {
      for {
        entered <- Promise.make[Nothing, Unit]
        finalized <- Ref.make(false)
        ownerScope <- Scope.make
        app <- application(_ => (entered.succeed(()) *> ZIO.never).ensuring(finalized.set(true)))
          .provideEnvironment(ZEnvironment(ownerScope))
        backend = app.configured.get
        session <- backend.create(human, Some(SessionId.unsafe("shutdown")))
        wait <- backend.submit(human, session.id, first, "keep").fork
        _ <- entered.await
        _ <- ownerScope.close(Exit.unit)
        outcome <- wait.join
        done <- finalized.get
      } yield {
        assert(done)
        assertEquals(outcome.asInstanceOf[TurnOutcome.Failed].acceptance, PromptAcceptance.Unknown)
      }
    })
  }

  test("anonymous, browser-origin and nonloopback policies cannot gain authority from clientName") {
    run(ZIO.scoped {
      for {
        app <- application(_ => ZIO.dieMessage("no execution"))
        rpc = new OrchestratorRpc(app.master, app.configured)
        results <- ZIO.foreach(List((true, None), (false, Some(human)))) { case (trusted, author) =>
          for {
            connection <- rpc.openConnection(trusted)
            response <- rpc.handle(
              JsonRpc
                .Request(
                  Json.Str("init"),
                  Initialize.method,
                  Initialize
                    .Params(fmgp.yallmo.server.protocol.YallmoProtocol.Version, "connected-repl-operator", author)
                )
                .toJson,
              connection
            )
            info = decode[JsonRpc.Success[Initialize.Result]](response.get).result
            rejected <- rpc.handle(
              JsonRpc.Request(Json.Str("create"), SessionsCreate.method, SessionsCreate.Params()).toJson,
              connection
            )
            _ <- rpc.disconnect(connection)
          } yield (info, decode[JsonRpc.Failure](rejected.get).error.code)
        }
      } yield results.foreach { case (info, code) =>
        assertEquals(info.identity.role, Role.Viewer)
        assertEquals(info.methods.size, 7)
        assertEquals(code, ErrorCode.Forbidden)
      }
    })
  }

  test("operator capability is per connection and first initialized identity cannot change") {
    run(ZIO.scoped {
      for {
        app <- application(_ => ZIO.dieMessage("no execution"))
        rpc = new OrchestratorRpc(app.master, app.configured)
        connection <- rpc.openConnection(true)
        response <- rpc.handle(
          JsonRpc
            .Request(
              Json.Str("init"),
              Initialize.method,
              Initialize.Params(fmgp.yallmo.server.protocol.YallmoProtocol.Version, "any-name", Some(human))
            )
            .toJson,
          connection
        )
        changed <- rpc.handle(
          JsonRpc
            .Request(
              Json.Str("change"),
              Initialize.method,
              Initialize
                .Params(fmgp.yallmo.server.protocol.YallmoProtocol.Version, "any-name", Some(AgentId("changed")))
            )
            .toJson,
          connection
        )
        forbidden <- rpc.handle(
          JsonRpc.Request(Json.Str("reserved"), ToolsList.method, ToolsList.Params(first)).toJson,
          connection
        )
        _ <- rpc.disconnect(connection)
      } yield {
        val info = decode[JsonRpc.Success[Initialize.Result]](response.get).result
        assertEquals(info.methods.takeRight(2), List(SessionsCreate.method, TurnSubmit.method))
        assertEquals(info.notifications, Nil)
        assertEquals(decode[JsonRpc.Failure](changed.get).error.code, ErrorCode.Conflict)
        assertEquals(decode[JsonRpc.Failure](forbidden.get).error, JsonRpc.Error.MethodNotFound)
      }
    })
  }

  test("turn waits schedule no read deadline even when the caller clock would expire every sleep immediately") {
    val noDeadline = new Clock {
      export Clock.ClockLive.{currentTime, currentDateTime, instant, javaClock, localDateTime, nanoTime, scheduler}
      def sleep(duration: => Duration)(using Trace): UIO[Unit] =
        ZIO.dieMessage(s"Unexpected client read deadline $duration")
    }
    run(ZIO.scoped {
      for {
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        app <- application(_ => entered.succeed(()) *> release.await.as(answer))
        port <- new OrchestratorWSServer(app.master, app.configured).start(0)
        client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc", humanId = Some(human))
        session <- client.createSession(Some(SessionId.unsafe("deadline")))
        wait <- client.submitTurn(session.id, first, AgentTask("long")).withClock(noDeadline).fork
        _ <- entered.await
        _ <- release.succeed(())
        outcome <- wait.join
      } yield assert(outcome.isInstanceOf[TurnOutcome.Succeeded])
    })
  }

  test("closed application scope cannot hang create/submit or start provider work") {
    run(ZIO.scoped {
      for {
        calls <- Ref.make(0)
        owner <- Scope.make
        app <- application(_ => calls.update(_ + 1).as(answer)).provideEnvironment(ZEnvironment(owner))
        backend = app.configured.get
        session <- backend.create(human, Some(SessionId.unsafe("closed")))
        _ <- owner.close(Exit.unit)
        created <- backend.create(human, Some(SessionId.unsafe("too-late"))).either
        outcomes <- ZIO.foreach(1 to 20)(_ => backend.submit(human, session.id, first, "too late").either)
        count <- calls.get
      } yield {
        assert(created.isLeft)
        assert(outcomes.forall {
          case Left(error)                       => error.code != ErrorCode.Busy
          case Right(failed: TurnOutcome.Failed) => failed.acceptance == PromptAcceptance.Unknown
          case _                                 => false
        })
        assertEquals(count, 0)
      }
    })
  }

  test("application lease remains held until blocked execution finalizers complete") {
    run(ZIO.scoped {
      for {
        entered <- Promise.make[Nothing, Unit]
        finalizing <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        owner <- Scope.make
        app <- application(_ =>
          (entered.succeed(()) *> ZIO.never)
            .ensuring(finalizing.succeed(()) *> release.await)
        )
          .provideEnvironment(ZEnvironment(owner))
        backend = app.configured.get
        session <- backend.create(human, Some(SessionId.unsafe("lease")))
        wait <- backend.submit(human, session.id, first, "keep").fork
        _ <- entered.await
        closing <- owner.close(Exit.unit).fork
        _ <- finalizing.await
        competing <- ZIO
          .scoped(
            ServerApplication
              .open(backend.workspace)
              .provideSomeLayer[Scope](ZLayer.succeed(fakeClient(_ => ZIO.succeed(answer))) ++ tools)
          )
          .either
        _ <- release.succeed(())
        _ <- closing.join
        outcome <- wait.join
      } yield {
        assert(competing.isLeft)
        assertEquals(outcome.asInstanceOf[TurnOutcome.Failed].acceptance, PromptAcceptance.Unknown)
      }
    })
  }

  test("actual Origin-bearing configured socket is a viewer regardless of claimed human and clientName") {
    run(ZIO.scoped {
      for {
        app <- application(_ => ZIO.dieMessage("Browser must not execute"))
        port <- new OrchestratorWSServer(app.master, app.configured).start(0)
        environment <- (ZLayer.succeed(Client.Config.default.webSocketConfig(RpcWire.webSocketConfig)) ++
          ZLayer.succeed(
            zio.http.netty.NettyConfig.defaultWithFastShutdown
          ) ++ DnsResolver.default >>> Client.live).build
        replies <- zio.Queue.bounded[String](4)
        socket = Handler
          .webSocket { channel =>
            channel.receiveAll {
              case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
                channel.send(
                  ChannelEvent.Read(
                    WebSocketFrame.Text(
                      JsonRpc
                        .Request(
                          Json.Str("init"),
                          Initialize.method,
                          Initialize.Params(
                            fmgp.yallmo.server.protocol.YallmoProtocol.Version,
                            "connected-repl-operator",
                            Some(human)
                          )
                        )
                        .toJson
                    )
                  )
                )
              case ChannelEvent.Read(WebSocketFrame.Text(text)) =>
                replies.offer(
                  text
                ) *> (if text.contains("\"id\":\"init\"") then
                        channel.send(
                          ChannelEvent.Read(
                            WebSocketFrame.Text(
                              JsonRpc.Request(Json.Str("write"), SessionsCreate.method, SessionsCreate.Params()).toJson
                            )
                          )
                        )
                      else channel.shutdown)
              case _ => ZIO.unit
            }
          }
          .withConfig(RpcWire.webSocketConfig)
        _ <- socket
          .connect(s"ws://127.0.0.1:$port/rpc", Headers(Header.Custom("Origin", s"http://127.0.0.1:$port")))
          .provideSomeEnvironment[Scope](_.add(environment.get[Client]))
        initialized <- replies.take
        rejected <- replies.take
      } yield {
        val info = decode[JsonRpc.Success[Initialize.Result]](initialized).result
        assertEquals(info.identity, Identity(human, Role.Viewer))
        assertEquals(info.methods.size, 7)
        assertEquals(decode[JsonRpc.Failure](rejected).error.code, ErrorCode.Forbidden)
      }
    })
  }

  test("connection overload closes bounded waits without cancelling application tasks or leaking presence") {
    run(ZIO.scoped {
      for {
        calls <- Ref.make(0)
        allEntered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        app <- application(_ =>
          calls
            .updateAndGet(_ + 1)
            .flatMap(n =>
              ZIO.when(n == OrchestratorWSServer.MaxConnectionWorkers)(allEntered.succeed(()))
            ) *> release.await.as(answer)
        )
        port <- new OrchestratorWSServer(app.master, app.configured).start(0)
        endpoint = s"ws://127.0.0.1:$port/rpc"
        client <- OrchestratorWSClient.connect(endpoint, humanId = Some(human))
        sessions <- ZIO.foreach(1 to OrchestratorWSServer.MaxConnectionWorkers)(n =>
          client.createSession(Some(SessionId.unsafe(s"overload-$n")))
        )
        waits <- ZIO.foreach(sessions)(session => client.submitTurn(session.id, first, AgentTask("keep")).fork)
        _ <- allEntered.await
        overflow <- client.listSessions.either
        stopped <- ZIO.foreach(waits)(_.await)
        _ <- release.succeed(())
        observer <- OrchestratorWSClient.connect(endpoint, humanId = Some(human))
        stored <- ZIO.foreach(sessions)(session =>
          (ZIO.yieldNow *> observer.loadSession(session.id))
            .repeatUntil(_.messages.size == 2)
        )
        ids <- app.master.listAgentIds
        count <- calls.get
      } yield {
        assertEquals(overflow, Left(OrchestratorClient.Error.Disconnected))
        assert(stopped.forall(_.isFailure))
        assertEquals(count, OrchestratorWSServer.MaxConnectionWorkers)
        assert(stored.forall(_.messages.map(_.content) == Chunk("keep", "answer")))
        assert(ids.contains(human)) // Submitted humans intentionally survive connection cleanup.
      }
    })
  }

  test("server continuation policy survives same-binding turns but resets on explicit reset, switch and restart") {
    run(ZIO.scoped {
      for {
        seen <- Ref.make(Vector.empty[String])
        root <- temporaryRoot
        payload = java.util.Base64.getUrlEncoder
          .withoutPadding()
          .encodeToString(
            """{"https://api.openai.com/auth":{"chatgpt_account_id":"fake"}}""".getBytes(
              java.nio.charset.StandardCharsets.UTF_8
            )
          )
        credentials = fmgp.yallmo.orchestration.config
          .OAuthCredentialsConfig(s"header.$payload.signature", "fake-refresh", Long.MaxValue)
        configuration = Config(
          agents = Map(
            first -> definition.copy(agentType = "ChatGPTAgent"),
            second -> definition.copy(agentType = "ChatGPTAgent")
          ),
          providers = Map(provider -> ProviderConfig.ChatGPT(Some(credentials)))
        )
        path <- ZIO.attemptBlocking(Files.writeString(root.resolve("config.json"), configuration.toJson))
        client = fakeClient(request =>
          request.body.asString.flatMap(body => seen.updateAndGet(_ :+ body)).map { requests =>
            Response.text(
              s"""data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"reasoning","encrypted_content":"private-${requests.size}","summary":[{"type":"summary_text","text":"summary"}]},{"type":"message","content":[{"type":"output_text","text":"answer"}]}]}}

"""
            )
          }
        )
        owner <- Scope.make
        app <- ServerApplication
          .open(root, Some(path), Some(root))
          .provideSomeLayer[Scope](ZLayer.succeed(client) ++ tools)
          .provideEnvironment(ZEnvironment(owner))
        backend = app.configured.get
        session <- backend.create(human, Some(SessionId.unsafe("continuation")))
        _ <- backend.submit(human, session.id, first, "first")
        _ <- backend.submit(human, session.id, first, "same")
        _ <- backend.submit(human, session.id, first, "reset", resetContinuation = true)
        _ <- backend.submit(human, session.id, first, "after reset")
        _ <- backend.submit(human, session.id, second, "switch")
        _ <- backend.submit(human, session.id, first, "switch back")
        _ <- owner.close(Exit.unit)
        restarted <- ServerApplication
          .open(root, Some(path), Some(root))
          .provideSomeLayer[Scope](ZLayer.succeed(client) ++ tools)
        result <- restarted.configured.get.submit(human, session.id, first, "unknown after restart")
        requests <- seen.get
        stored <- restarted.master.loadSession(session.id)
      } yield {
        def reasoning(body: String) =
          ujson.read(body)("input").arr.toVector.flatMap(item => item.obj.get("encrypted_content").map(_.str))
        assert(result.isInstanceOf[TurnOutcome.Succeeded])
        assertEquals(
          requests.map(reasoning),
          Vector(
            Vector.empty,
            Vector("private-1"),
            Vector.empty,
            Vector("private-3"),
            Vector.empty,
            Vector.empty,
            Vector.empty
          )
        )
        assert(requests.forall(body => ujson.read(body).obj.get("tools").forall(_.arr.isEmpty)))
        assertEquals(stored.messages.count(_.payload.isInstanceOf[MessagePayload.Reasoning]), 7)
        assertEquals(stored.messages.count(_.payload.isInstanceOf[MessagePayload.User]), 7)
        assert(!SessionSnapshot.from(stored).toJson.contains("private-"))
      }
    })
  }

  private def application(
      respond: Request => Task[Response],
      configuration: Config = config
  ): ZIO[Scope, Throwable, ServerApplication] =
    for {
      root <- temporaryRoot
      path <- ZIO.attemptBlocking(Files.writeString(root.resolve("config.json"), configuration.toJson))
      app <- ServerApplication
        .open(root, Some(path), Some(root))
        .provideSomeLayer[Scope](ZLayer.succeed(fakeClient(respond)) ++ tools)
    } yield app

  private def temporaryRoot: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("yallmo-submission-"))) { root =>
      ZIO.attemptBlocking {
        val paths = Files.walk(root)
        try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
        finally paths.close()
      }.orDie
    }

  private val tools: ULayer[ServerApplication.ToolEnvironment] = {
    def unexpected[A]: UIO[A] = ZIO.dieMessage("Unexpected tool execution")
    val filesystem = new WorkspaceFileSystem {
      def isDirectory(path: Path): UIO[Boolean] = ZIO.succeed(false)
      def readUtf8(path: Path): Task[String] = unexpected
      def list(path: Path): Task[Chunk[String]] = unexpected
    }
    val bash = new BashExecutor {
      def isAvailable: UIO[Boolean] = ZIO.succeed(false)
      def execute(command: String, workspace: Path, timeout: Duration, maxOutputBytes: Int): Task[CommandOutput] =
        unexpected
    }
    val git = new GitClient {
      def isAvailable: UIO[Boolean] = ZIO.succeed(false)
      def isWorkTree(workspace: Path): UIO[Boolean] = unexpected
      def status(workspace: Path): IO[GitError, CommandOutput] = unexpected
      def unstagedDiff(workspace: Path): IO[GitError, CommandOutput] = unexpected
      def stagedDiff(workspace: Path): IO[GitError, CommandOutput] = unexpected
    }
    val scala = new ScalaCodeExecutor {
      def isAvailable: UIO[Boolean] = ZIO.succeed(false)
      def execute(code: String, timeout: Duration, maxOutputBytes: Int): Task[CommandOutput] = unexpected
    }
    ZLayer.succeed[WorkspaceFileSystem](filesystem) ++ ZLayer.succeed[BashExecutor](bash) ++
      ZLayer.succeed[GitClient](git) ++ ZLayer.succeed[ScalaCodeExecutor](scala)
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

  private def decode[A: JsonDecoder](input: String): A = input.fromJson[A].fold(fail(_), identity)
  private def run[A](effect: ZIO[Any, Any, A]): A = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(effect.timeoutFail(new AssertionError("Submission test timed out"))(25.seconds))
      .getOrThrowFiberFailure()
  }
}
