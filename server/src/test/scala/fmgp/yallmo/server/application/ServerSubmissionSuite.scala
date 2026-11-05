package fmgp.yallmo.server.application

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.orchestration.config.{
  AgentCapabilities,
  AgentConfig,
  Config,
  FileSystemConfig,
  ProviderConfig,
  ProviderId
}
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
        connection <- viewer.openConnection
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
        diagnostic <- viewer.handle(
          JsonRpc
            .Request(
              Json.Str("diagnostic"),
              DiagnosticsRequest.method,
              DiagnosticsRequest.Params(SessionId.unsafe("missing"))
            )
            .toJson,
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
        assertEquals(decode[JsonRpc.Failure](diagnostic.get).error, JsonRpc.Error.MethodNotFound)
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
        client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc", userId = Some(human))
        info <- client.capabilities
        created <- client.createSession(Some(SessionId.unsafe("created")))
        outcome <- client.submitTurn(created.id, first, AgentTask("prompt"))
        stored <- app.master.loadSession(created.id)
        count <- calls.get
        _ <- client.close
        again <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc", userId = Some(human))
        reloaded <- again.loadSession(created.id)
      } yield {
        assertEquals(info.get.identity, Identity(human, Role.Operator))
        assertEquals(info.get.methods.size, 16)
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

  test("pre-submission storage failures log their cause before becoming an Internal error") {
    val logs = new java.util.concurrent.CopyOnWriteArrayList[String]()
    val capture = ZLogger.default.map(line => { logs.add(line); () })
    val missing = MessageId.sha256("missing-submission-message")
    run(ZIO.scoped {
      for {
        calls <- Ref.make(0)
        app <- application(_ => calls.update(_ + 1).as(answer))
        backend = app.configured.get
        session <- backend.create(human, Some(SessionId.unsafe("broken")))
        _ <- ZIO.attemptBlocking(
          Files.writeString(
            backend.workspace.resolve("refs/sessions/broken"),
            s"""{"agentIds":["${AgentId.value(human)}"],"head":"${MessageId.value(missing)}"}"""
          )
        )
        rpc = new OrchestratorRpc(app.master, app.configured)
        connection <- rpc.openConnection
        _ <- initializeUpload(rpc, connection, Some(human))
        _ <- (Runtime.removeDefaultLoggers >>> Runtime.addLogger(capture)).build
        reply <- rpc
          .handle(
            JsonRpc
              .Request(
                Json.Str("submit"),
                TurnSubmit.method,
                TurnSubmit.Params(session.id, first, AgentTask("PRIVATE_PROMPT"))
              )
              .toJson,
            connection
          )
          .map(_.get)
        count <- calls.get
      } yield {
        assertEquals(reply.fromJson[JsonRpc.Failure].toOption.get.error, JsonRpc.Error.InternalError)
        assert(!reply.contains(MessageId.value(missing)))
        assert(!reply.contains("PRIVATE_PROMPT"))
        assertEquals(count, 0)
        assertEquals(logs.size(), 1)
        val line = logs.get(0)
        assert(line.contains("level=ERROR"))
        assert(line.contains("Configured domain operation failed"))
        assert(line.contains("session refers to missing message"))
        assert(line.contains(MessageId.value(missing)))
        assert(line.contains("rpcMethod=turn.submit"))
        assert(line.contains("sessionId=broken"))
        assert(line.contains(s"agentId=${AgentId.value(first)}"))
        assert(!line.contains("PRIVATE_PROMPT"))
      }
    })
  }

  test("accepted typed provider failure is sanitized and leaves prompt once; later submission succeeds") {
    val logs = new java.util.concurrent.CopyOnWriteArrayList[String]()
    val capture = ZLogger.default.map(line => { logs.add(line); () })
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
        _ <- (Runtime.removeDefaultLoggers >>> Runtime.addLogger(capture)).build
        failed <- backend.submit(human, session.id, first, "keep")
        recovered <- backend.submit(human, session.id, first, "continue")
        loaded <- app.master.loadSession(session.id)
      } yield {
        val failure = failed.asInstanceOf[TurnOutcome.Failed]
        assertEquals(failure.acceptance, PromptAcceptance.Accepted)
        assertEquals(failure.session.get.messages.map(_.content), Chunk("keep"))
        assert(!(failure: TurnOutcome).toJson.contains("PRIVATE"))
        assert(!(failure: TurnOutcome).toJson.contains("credential"))
        assert(logs.toString.contains("Session execution failed"))
        assert(logs.toString.contains("PRIVATE /secret credential"))
        assert(recovered.isInstanceOf[TurnOutcome.Succeeded])
        assertEquals(
          loaded.messages.map(_.payload),
          Chunk(MessagePayload.User("keep"), MessagePayload.User("continue"), MessagePayload.Assistant("answer"))
        )
      }
    })
  }

  test("provider defect returns terminal Unknown with safe snapshot and releases admission") {
    val logs = new java.util.concurrent.CopyOnWriteArrayList[String]()
    val capture = ZLogger.default.map(line => { logs.add(line); () })
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
        _ <- (Runtime.removeDefaultLoggers >>> Runtime.addLogger(capture)).build
        failed <- backend.submit(human, session.id, first, "keep")
        success <- backend.submit(human, session.id, first, "continue")
      } yield {
        val failure = failed.asInstanceOf[TurnOutcome.Failed]
        assertEquals(failure.acceptance, PromptAcceptance.Unknown)
        assertEquals(failure.session.get.messages.map(_.content), Chunk("keep"))
        assert(!(failure: TurnOutcome).toJson.contains("PRIVATE"))
        assert(logs.toString.contains("Configured operation failed unexpectedly"))
        assert(logs.toString.contains("PRIVATE stack /secret credential"))
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
        client <- OrchestratorWSClient.connect(endpoint, userId = Some(human))
        session <- client.createSession(Some(SessionId.unsafe("disconnect")))
        wait <- client.submitTurn(session.id, first, AgentTask("keep")).fork
        _ <- entered.await
        during <- client.loadSession(session.id)
        pendingCapture <- client.providerRequest(session.id)
        _ <- client.close
        lost <- wait.await
        observer <- OrchestratorWSClient.connect(endpoint, userId = Some(human))
        busy <- observer.submitTurn(session.id, first, AgentTask("not retried")).either
        _ <- release.succeed(())
        _ <- finished.await
        after <- (ZIO.yieldNow *> observer.loadSession(session.id)).repeatUntil(_.messages.size == 2)
        diagnostic <- (ZIO.yieldNow *> observer.providerRequest(session.id)).repeatUntil(_.providerRequest.nonEmpty)
      } yield {
        assertEquals(during.messages.map(_.content), Chunk("keep"))
        assertEquals(pendingCapture.providerRequest, None)
        assert(diagnostic.toJson.contains("keep"))
        assertEquals(lost.causeOption.flatMap(_.failureOption), Some(OrchestratorClient.Error.Disconnected))
        assertEquals(
          busy.left.toOption.collect { case OrchestratorClient.Error.Remote(error) => error.code },
          Some(ErrorCode.Busy)
        )
        assertEquals(after.messages.map(_.content), Chunk("keep", "answer"))
      }
    })
  }

  test("interrupting a local turn wait leaves server execution running once and the same socket usable") {
    run(ZIO.scoped {
      for {
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        calls <- Ref.make(0)
        app <- application(_ => calls.update(_ + 1) *> entered.succeed(()) *> release.await.as(answer))
        port <- new OrchestratorWSServer(app.master, app.configured).start(0)
        client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc", userId = Some(human))
        session <- client.createSession(Some(SessionId.unsafe("abandoned")))
        wait <- client.submitTurn(session.id, first, AgentTask("keep running")).fork
        _ <- entered.await
        interrupted <- wait.interrupt
        during <- client.loadSession(session.id)
        busy <- client.submitTurn(session.id, first, AgentTask("not retried")).either
        _ <- release.succeed(())
        after <- (ZIO.yieldNow *> client.loadSession(session.id)).repeatUntil(_.messages.size == 2)
        executions <- calls.get
      } yield {
        assert(interrupted.isInterrupted)
        assertEquals(during.messages.map(_.content), Chunk("keep running"))
        assertEquals(
          busy.left.toOption.collect { case OrchestratorClient.Error.Remote(error) => error.code },
          Some(ErrorCode.Busy)
        )
        assertEquals(after.messages.map(_.content), Chunk("keep running", "answer"))
        assertEquals(executions, 1)
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

  test("anonymous clients cannot gain authority from clientName") {
    run(ZIO.scoped {
      for {
        app <- application(_ => ZIO.dieMessage("no execution"))
        rpc = new OrchestratorRpc(app.master, app.configured)
        results <- ZIO.foreach(List("connected-repl-operator", "browser")) { clientName =>
          for {
            connection <- rpc.openConnection
            response <- rpc.handle(
              JsonRpc
                .Request(
                  Json.Str("init"),
                  Initialize.method,
                  Initialize
                    .Params(fmgp.yallmo.server.protocol.YallmoProtocol.Version, clientName, None)
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
        connection <- rpc.openConnection
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
        assertEquals(
          info.methods.takeRight(9),
          List(
            SessionsCreate.method,
            TurnSubmit.method,
            DiagnosticsRequest.method,
            BlobsUpload.method,
            AgentsInvoke.method,
            ProvidersList.method,
            ProvidersLoginStart.method,
            ProvidersLoginComplete.method,
            ProvidersLoginCancel.method
          )
        )
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
        client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc", userId = Some(human))
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

  test("Origin-bearing browser can create, submit and read diagnostics with its claimed author") {
    run(ZIO.scoped {
      for {
        calls <- Ref.make(0)
        app <- application(_ => calls.update(_ + 1).as(answer))
        port <- new OrchestratorWSServer(app.master, app.configured).start(0)
        environment <- (ZLayer.succeed(Client.Config.default.webSocketConfig(RpcWire.webSocketConfig)) ++
          ZLayer.succeed(
            zio.http.netty.NettyConfig.defaultWithFastShutdown
          ) ++ DnsResolver.default >>> Client.live).build
        replies <- zio.Queue.bounded[String](4)
        sessionId = SessionId.unsafe("browser-created")
        requests = List(
          JsonRpc
            .Request(
              Json.Str("init"),
              Initialize.method,
              Initialize.Params(fmgp.yallmo.server.protocol.YallmoProtocol.Version, "browser", Some(human))
            )
            .toJson,
          JsonRpc.Request(Json.Str("create"), SessionsCreate.method, SessionsCreate.Params(Some(sessionId))).toJson,
          JsonRpc
            .Request(
              Json.Str("submit"),
              TurnSubmit.method,
              TurnSubmit.Params(sessionId, first, AgentTask("browser prompt"))
            )
            .toJson,
          JsonRpc
            .Request(Json.Str("diagnostic"), DiagnosticsRequest.method, DiagnosticsRequest.Params(sessionId))
            .toJson
        )
        socket = Handler
          .webSocket { channel =>
            for {
              remaining <- Ref.make(requests)
              sendNext = remaining
                .modify {
                  case head :: tail => (Some(head), tail)
                  case Nil          => (None, Nil)
                }
                .flatMap {
                  case Some(text) => channel.send(ChannelEvent.Read(WebSocketFrame.Text(text)))
                  case None       => channel.shutdown
                }
              _ <- channel.receiveAll {
                case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) => sendNext
                case ChannelEvent.Read(WebSocketFrame.Text(text)) => replies.offer(text) *> sendNext
                case _                                            => ZIO.unit
              }
            } yield ()
          }
          .withConfig(RpcWire.webSocketConfig)
        _ <- socket
          .connect(s"ws://127.0.0.1:$port/rpc", Headers(Header.Custom("Origin", "https://browser.internal:9443")))
          .provideSomeEnvironment[Scope](_.add(environment.get[Client]))
        initialized <- replies.take
        created <- replies.take
        submitted <- replies.take
        diagnostic <- replies.take
        stored <- app.master.loadSession(sessionId)
        count <- calls.get
      } yield {
        val info = decode[JsonRpc.Success[Initialize.Result]](initialized).result
        assertEquals(info.identity, Identity(human, Role.Operator))
        assertEquals(
          info.methods.takeRight(9),
          List(
            SessionsCreate.method,
            TurnSubmit.method,
            DiagnosticsRequest.method,
            BlobsUpload.method,
            AgentsInvoke.method,
            ProvidersList.method,
            ProvidersLoginStart.method,
            ProvidersLoginComplete.method,
            ProvidersLoginCancel.method
          )
        )
        assertEquals(decode[JsonRpc.Success[SessionSnapshot]](created).result.id, sessionId)
        assert(decode[JsonRpc.Success[TurnOutcome]](submitted).result.isInstanceOf[TurnOutcome.Succeeded])
        assert(decode[JsonRpc.Success[DiagnosticsRequest.Result]](diagnostic).result.providerRequest.nonEmpty)
        assertEquals(stored.messages.map(_.agentId), Chunk(human, first))
        assertEquals(
          stored.messages.map(_.payload),
          Chunk(MessagePayload.User("browser prompt"), MessagePayload.Assistant("answer"))
        )
        assertEquals(count, 1)
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
        client <- OrchestratorWSClient.connect(endpoint, userId = Some(human))
        sessions <- ZIO.foreach(1 to OrchestratorWSServer.MaxConnectionWorkers)(n =>
          client.createSession(Some(SessionId.unsafe(s"overload-$n")))
        )
        waits <- ZIO.foreach(sessions)(session => client.submitTurn(session.id, first, AgentTask("keep")).fork)
        _ <- allEntered.await
        overflow <- client.listSessions.either
        stopped <- ZIO.foreach(waits)(_.await)
        _ <- release.succeed(())
        observer <- OrchestratorWSClient.connect(endpoint, userId = Some(human))
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
        diagnostic <- backend.providerRequest(session.id)
        _ <- backend.submit(human, session.id, first, "reset", resetContinuation = true)
        _ <- backend.submit(human, session.id, first, "after reset")
        beforeRewrite <- app.master.loadSession(session.id)
        continuationProvider = fmgp.yallmo.model.llm.ProviderId.unsafe("openai-codex-responses")
        stale = MessagePayload.Reasoning(
          Some("different summary"),
          fmgp.yallmo.model.llm.ProviderContinuation(continuationProvider, None, "private-1")
        )
        fresh = MessagePayload.Reasoning(
          Some("fresh"),
          fmgp.yallmo.model.llm.ProviderContinuation(continuationProvider, None, "private-after-rebase")
        )
        program = new AgentInstance[Any] {
          val id = first
          def next(task: AgentTask, context: StaticContext[Any], current: RunContext) =
            ZIO.dieMessage("Managed entry point required")
          override def nextManaged(
              task: AgentTask,
              context: StaticContext[Any],
              current: RunContext,
              history: ManagedHistory
          ) =
            for {
              initial <- history.initialMessages
              _ <- history.rebase(initial(initial.size - 2).id, initial.last.id, None)
              _ <- history.messages
            } yield AgentTurn(
              Some(MessagePayload.Assistant("compacted")),
              AgentDecision.FinalAnswer("compacted"),
              Chunk(stale, fresh)
            )
        }
        rewritten <- app.master.submit(
          session.id,
          human,
          AgentTask("shorten"),
          new AgentRunner[Any](Map.empty, Map.empty).execution(program, StaticContext[Any]("Synthetic"))
        )
        _ <- backend.submit(human, session.id, first, "after shortening")
        _ <- backend.submit(human, session.id, second, "switch")
        _ <- backend.submit(human, session.id, first, "switch back")
        _ <- owner.close(Exit.unit)
        restarted <- ServerApplication
          .open(root, Some(path), Some(root))
          .provideSomeLayer[Scope](ZLayer.succeed(client) ++ tools)
        result <- restarted.configured.get.submit(human, session.id, first, "unknown after restart")
        requests <- seen.get
        stored <- restarted.master.loadSession(session.id)
        inventory <- restarted.master.listMessages
      } yield {
        assertEquals(
          rewritten.session.messages.map(_.payload),
          Chunk(MessagePayload.User("shorten"), MessagePayload.Assistant("compacted"), stale, fresh)
        )
        assert(beforeRewrite.messages.forall(inventory.contains))
        assert(rewritten.session.messages.forall(inventory.contains))
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
            Vector("private-after-rebase"),
            Vector.empty,
            Vector.empty,
            Vector.empty
          )
        )
        assert(requests.forall(body => ujson.read(body).obj.get("tools").forall(_.arr.isEmpty)))
        assert(diagnostic.toJson.contains("<redacted>"))
        assert(!diagnostic.toJson.contains("private-1"))
        assert(!diagnostic.toJson.contains("fake-refresh"))
        assert(!diagnostic.toJson.contains("header."))
        assertEquals(stored.messages.count(_.payload.isInstanceOf[MessagePayload.Reasoning]), 6)
        assertEquals(stored.messages.count(_.payload.isInstanceOf[MessagePayload.User]), 5)
        assert(!SessionSnapshot.from(stored).toJson.contains("private-"))
      }
    })
  }

  test("request diagnostics return actual provider bodies, isolate concurrent sessions and survive reconnect") {
    run(ZIO.scoped {
      for {
        seen <- Ref.make(Set.empty[Json.Obj])
        app <- application(request =>
          request.body.asString.flatMap(body =>
            ZIO
              .fromEither(body.fromJson[Json.Obj])
              .mapError(new Exception(_))
              .flatMap(value => seen.update(_ + value))
              .as(answer)
          )
        )
        port <- new OrchestratorWSServer(app.master, app.configured).start(0)
        endpoint = s"ws://127.0.0.1:$port/rpc"
        client <- OrchestratorWSClient.connect(endpoint, userId = Some(human))
        one <- client.createSession(Some(SessionId.unsafe("debug-one")))
        two <- client.createSession(Some(SessionId.unsafe("debug-two")))
        empty <- client.providerRequest(one.id)
        _ <- client
          .submitTurn(one.id, first, AgentTask("only-one"))
          .zipPar(client.submitTurn(two.id, second, AgentTask("only-two")))
        firstCapture <- client.providerRequest(one.id)
        secondCapture <- client.providerRequest(two.id)
        bodies <- seen.get
        _ <- client.close
        observer <- OrchestratorWSClient.connect(endpoint, userId = Some(human))
        retained <- observer.providerRequest(one.id)
        sessions <- observer.listSessions
      } yield {
        assertEquals(empty.providerRequest, None)
        val documents = List(firstCapture.providerRequest.get, secondCapture.providerRequest.get)
        assertEquals(
          documents.map(document => document.fields.toMap.apply("payload").asInstanceOf[Json.Obj]).toSet,
          bodies
        )
        assert(firstCapture.toJson.contains("only-one")); assert(!firstCapture.toJson.contains("only-two"))
        assert(secondCapture.toJson.contains("only-two")); assert(!secondCapture.toJson.contains("only-one"))
        assertEquals(retained, firstCapture)
        assertEquals(sessions.size, 2)
        assert(!retained.toJson.contains("-request.json"))
        assert(!retained.toJson.contains(app.configured.get.workspace.toString))
      }
    })
  }

  test("typed failures and defects retain the latest attempted request instead of the earlier successful capture") {
    run(ZIO.scoped {
      for {
        calls <- Ref.make(0)
        app <- application(_ =>
          calls.updateAndGet(_ + 1).flatMap {
            case 1 => ZIO.succeed(answer)
            case 2 => ZIO.succeed(Response.text("PRIVATE provider error").status(Status.InternalServerError))
            case _ => ZIO.dieMessage("PRIVATE provider defect")
          }
        )
        backend = app.configured.get
        session <- backend.create(human, Some(SessionId.unsafe("diagnostic-failures")))
        _ <- backend.submit(human, session.id, first, "success")
        before <- backend.providerRequest(session.id)
        failed <- backend.submit(human, session.id, first, "typed-failure")
        typed <- backend.providerRequest(session.id)
        defect <- backend.submit(human, session.id, first, "defect-failure")
        after <- backend.providerRequest(session.id)
      } yield {
        assert(failed.isInstanceOf[TurnOutcome.Failed]); assert(defect.isInstanceOf[TurnOutcome.Failed])
        assert(typed.toJson.contains("typed-failure")); assert(after.toJson.contains("defect-failure"))
        assertNotEquals(before, typed); assertNotEquals(typed, after)
        assert(!typed.toJson.contains("PRIVATE")); assert(!after.toJson.contains("PRIVATE"))
      }
    })
  }

  test("a turn without provider work clears the earlier capture and new server instances have no capture index") {
    run(ZIO.scoped {
      for {
        root <- temporaryRoot
        configuration = config.copy(agents =
          config.agents
            .updated(second, AgentConfig("FolderStructureAgent", filesystem = Some(FileSystemConfig(root.toString))))
        )
        path <- ZIO.attemptBlocking(Files.writeString(root.resolve("config.json"), configuration.toJson))
        owner <- Scope.make
        app <- ServerApplication
          .open(root, Some(path), Some(root))
          .provideSomeLayer[Scope](ZLayer.succeed(fakeClient(_ => ZIO.succeed(answer))) ++ tools)
          .provideEnvironment(ZEnvironment(owner))
        session <- app.configured.get.create(human, Some(SessionId.unsafe("no-stale-capture")))
        _ <- app.configured.get.submit(human, session.id, first, "captured")
        before <- app.configured.get.providerRequest(session.id)
        _ <- app.configured.get.submit(human, session.id, second, "no-provider")
        cleared <- app.configured.get.providerRequest(session.id)
        _ <- app.configured.get.submit(human, session.id, first, "captured-again")
        _ <- owner.close(Exit.unit)
        restarted <- ServerApplication
          .open(root, Some(path), Some(root))
          .provideSomeLayer[Scope](ZLayer.succeed(fakeClient(_ => ZIO.succeed(answer))) ++ tools)
        absent <- restarted.configured.get.providerRequest(session.id)
      } yield {
        assert(before.providerRequest.nonEmpty)
        assertEquals(cleared.providerRequest, None)
        assertEquals(absent.providerRequest, None)
      }
    })
  }

  test("diagnostic reads bound files, reject corrupt data and symlinks, and tolerate a deleted capture") {
    run(ZIO.scoped {
      for {
        app <- application(_ => ZIO.succeed(answer))
        backend = app.configured.get
        session <- backend.create(human, Some(SessionId.unsafe("capture-limits")))
        _ <- backend.submit(human, session.id, first, "small")
        valid <- backend.providerRequest(session.id)
        turnId = ujson.read(valid.toJson)("providerRequest")("turnId").str
        path = Path.of("debug", s"$turnId-request.json")
        _ <- ZIO.attemptBlocking(Files.writeString(path, "x" * (DiagnosticsRequest.MaxCaptureBytes + 1)))
        large <- backend.providerRequest(session.id).either
        port <- new OrchestratorWSServer(app.master, app.configured).start(0)
        client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc", userId = Some(human))
        oversizedReply <- client.providerRequest(session.id).either
        stillConnected <- client.listSessions
        _ <- ZIO.attemptBlocking(Files.writeString(path, "{broken json"))
        corrupt <- backend.providerRequest(session.id).either
        _ <- ZIO.attemptBlocking(Files.delete(path))
        missing <- backend.providerRequest(session.id)
        secret <- ZIO.attemptBlocking(
          Files.writeString(backend.workspace.resolve("private.json"), "{\"secret\":\"PRIVATE\"}")
        )
        _ <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.createSymbolicLink(path, secret)))(_ =>
          ZIO.attemptBlocking(Files.deleteIfExists(path)).orDie
        )
        symlink <- backend.providerRequest(session.id).either
        unknown <- backend.providerRequest(SessionId.unsafe("unknown")).either
      } yield {
        assertEquals(large.left.toOption.map(_.code), Some(ErrorCode.Rejected))
        assertEquals(
          oversizedReply.left.toOption.collect { case OrchestratorClient.Error.Remote(error) =>
            error.code
          },
          Some(ErrorCode.Rejected)
        )
        assertEquals(stillConnected.map(_.id), Chunk(session.id))
        assertEquals(corrupt.left.toOption.map(_.code), Some(ErrorCode.Rejected))
        assertEquals(missing.providerRequest, None)
        assertEquals(symlink.left.toOption.map(_.code), Some(ErrorCode.Rejected))
        assertEquals(unknown.left.toOption.map(_.code), Some(ErrorCode.NotFound))
        assert(!symlink.toString.contains("PRIVATE")); assert(!symlink.toString.contains(secret.toString))
      }
    })
  }

  test("diagnostics require initialization and operator permission, not clientName or a submitted file path") {
    run(ZIO.scoped {
      for {
        app <- application(_ => ZIO.dieMessage("diagnostics must not execute"))
        rpc = new OrchestratorRpc(app.master, app.configured)
        connection <- rpc.openConnection
        uninitialized <- rpc.handle(
          JsonRpc
            .Request(
              Json.Str("read"),
              DiagnosticsRequest.method,
              DiagnosticsRequest.Params(SessionId.unsafe("missing"))
            )
            .toJson,
          connection
        )
        replies <- ZIO.foreach(List("operator-debug", "browser")) { clientName =>
          for {
            viewer <- rpc.openConnection
            initialized <- rpc.handle(
              JsonRpc
                .Request(
                  Json.Str("init"),
                  Initialize.method,
                  Initialize.Params(fmgp.yallmo.server.protocol.YallmoProtocol.Version, clientName, None)
                )
                .toJson,
              viewer
            )
            result <- rpc.handle(
              JsonRpc
                .Request(
                  Json.Str("read"),
                  DiagnosticsRequest.method,
                  DiagnosticsRequest.Params(SessionId.unsafe("missing"))
                )
                .toJson,
              viewer
            )
            _ <- rpc.disconnect(viewer)
          } yield (
            decode[JsonRpc.Success[Initialize.Result]](initialized.get).result,
            decode[JsonRpc.Failure](result.get).error.code
          )
        }
        _ <- rpc.handle(
          JsonRpc
            .Request(
              Json.Str("init"),
              Initialize.method,
              Initialize.Params(fmgp.yallmo.server.protocol.YallmoProtocol.Version, "operator", Some(human))
            )
            .toJson,
          connection
        )
        invalid <- rpc.handle(
          JsonRpc
            .Request(Json.Str("bad"), DiagnosticsRequest.method, Json.Obj("sessionId" -> Json.Str("../private")))
            .toJson,
          connection
        )
        _ <- rpc.disconnect(connection)
      } yield {
        assertEquals(decode[JsonRpc.Failure](uninitialized.get).error.code, ErrorCode.Unauthorized)
        replies.foreach { case (info, code) =>
          assert(!info.methods.contains(DiagnosticsRequest.method)); assertEquals(code, ErrorCode.Forbidden)
        }
        assertEquals(decode[JsonRpc.Failure](invalid.get).error, JsonRpc.Error.InvalidParams)
      }
    })
  }

  test("blobs.upload authorization and discovery require initialized configured operators") {
    run(ZIO.scoped {
      for {
        app <- application(_ => ZIO.dieMessage("Upload must never invoke a provider"))
        rpc = new OrchestratorRpc(app.master, app.configured)
        uninitialized <- rpc.openConnection
        command = BlobsUpload.Params.Begin(BlobId.fresh(), 0)
        before <- uploadCall(rpc, uninitialized, command)
        replies <- ZIO.foreach(List(None, Some(AgentId("")))) { claimed =>
          for {
            connection <- rpc.openConnection
            initialized <- initializeUpload(rpc, connection, claimed)
            rejected <- uploadCall(rpc, connection, command)
            _ <- rpc.disconnect(connection)
          } yield (initialized, rejected)
        }
        viewerRpc = new OrchestratorRpc(app.master)
        viewer <- viewerRpc.openConnection
        info <- initializeUpload(viewerRpc, viewer, Some(human))
        forbidden <- uploadCall(viewerRpc, viewer, command)
        _ <- viewerRpc.disconnect(viewer)
        _ <- rpc.disconnect(uninitialized)
      } yield {
        assertEquals(decode[JsonRpc.Failure](before).error.code, ErrorCode.Unauthorized)
        // Blank identities are rejected by the existing AgentId codec and remain uninitialized.
        replies.foreach { case (initialized, rejected) =>
          initialized
            .fromJson[JsonRpc.Success[Initialize.Result]]
            .foreach(value => assert(!value.result.methods.contains(BlobsUpload.method)))
          assert(
            Set(ErrorCode.Forbidden, ErrorCode.Unauthorized).contains(decode[JsonRpc.Failure](rejected).error.code)
          )
        }
        assert(!decode[JsonRpc.Success[Initialize.Result]](info).result.methods.contains(BlobsUpload.method))
        assertEquals(decode[JsonRpc.Failure](forbidden).error.code, ErrorCode.Forbidden)
      }
    })
  }

  test(
    "blobs.upload validates canonical bounded base64 and offsets, isolates same-user connections, and wakes awaiters"
  ) {
    run(ZIO.scoped {
      for {
        app <- application(_ => ZIO.dieMessage("Upload must never invoke a provider"))
        rpc = new OrchestratorRpc(app.master, app.configured)
        owner <- rpc.openConnection
        alien <- rpc.openConnection
        info <- initializeUpload(rpc, owner, Some(human))
        _ <- initializeUpload(rpc, alien, Some(human))
        id = BlobId.fresh()
        begun <- uploadCall(rpc, owner, BlobsUpload.Params.Begin(id, 3))
        _ = assertEquals(decode[JsonRpc.Success[BlobsUpload.Result]](begun).result, BlobsUpload.Result.Begun(id))
        waiter <- app.master.readBlob(id, BlobUploadLimits.AwaitTimeout).either.fork
        _ <- (ZIO.yieldNow *> waiter.status).repeatUntil(_.isInstanceOf[Fiber.Status.Suspended])
        invalid <- ZIO.foreach(
          List("!", "AQ", "AR==", "AQ==\n", "A" * (BlobsUpload.MaxEncodedChunkChars + 1), "AAAA" * 21846)
        ) { encoded =>
          uploadCall(rpc, owner, BlobsUpload.Params.Append(id, 0, encoded))
        }
        unauthorized <- ZIO.foreach(
          List[BlobsUpload.Params](
            BlobsUpload.Params.Append(id, 0, "AQID"),
            BlobsUpload.Params.Complete(id),
            BlobsUpload.Params.Abort(id)
          )
        )(uploadCall(rpc, alien, _))
        foreignDuplicate <- uploadCall(rpc, alien, BlobsUpload.Params.Begin(id, 0))
        badOffset <- uploadCall(rpc, owner, BlobsUpload.Params.Append(id, 1, "AQID"))
        appended <- uploadCall(rpc, owner, BlobsUpload.Params.Append(id, 0, "AQID"))
        pending <- waiter.poll
        duplicate <- uploadCall(rpc, owner, BlobsUpload.Params.Begin(id, 0))
        completed <- uploadCall(rpc, owner, BlobsUpload.Params.Complete(id))
        result <- waiter.join
        _ <- rpc.disconnect(owner)
        _ <- rpc.disconnect(alien)
        sessions <- app.master.listSessions
        messages <- app.master.listMessages
      } yield {
        assert(decode[JsonRpc.Success[Initialize.Result]](info).result.methods.contains(BlobsUpload.method))
        invalid.foreach(value => assertEquals(decode[JsonRpc.Failure](value).error, JsonRpc.Error.InvalidParams))
        unauthorized.foreach(value => assertEquals(decode[JsonRpc.Failure](value).error.code, ErrorCode.NotFound))
        assertEquals(decode[JsonRpc.Failure](foreignDuplicate).error.code, ErrorCode.Conflict)
        assertEquals(decode[JsonRpc.Failure](badOffset).error.code, ErrorCode.Rejected)
        assertEquals(decode[JsonRpc.Failure](duplicate).error.code, ErrorCode.Conflict)
        assertEquals(
          decode[JsonRpc.Success[BlobsUpload.Result]](appended).result,
          BlobsUpload.Result.Appended(id, 3)
        )
        assertEquals(pending, None)
        assertEquals(decode[JsonRpc.Success[BlobsUpload.Result]](completed).result, BlobsUpload.Result.Completed(id))
        assertEquals(result, Right(Blob(id, Chunk[Byte](1, 2, 3))))
        assertEquals(sessions, Chunk.empty)
        assertEquals(messages, Chunk.empty)
      }
    })
  }

  test("blobs.upload disconnect races cannot leak newly admitted uploads; Abort is cleanup only") {
    run(ZIO.scoped {
      for {
        app <- application(_ => ZIO.dieMessage("Upload must never invoke a provider"))
        rpc = new OrchestratorRpc(app.master, app.configured)
        owner <- rpc.openConnection
        _ <- initializeUpload(rpc, owner, Some(human))
        id = BlobId.fresh()
        begun <- uploadCall(rpc, owner, BlobsUpload.Params.Begin(id, 3))
        _ = assertEquals(decode[JsonRpc.Success[BlobsUpload.Result]](begun).result, BlobsUpload.Result.Begun(id))
        waiter <- app.master.readBlob(id, BlobUploadLimits.AwaitTimeout).either.fork
        _ <- (ZIO.yieldNow *> waiter.status).repeatUntil(_.isInstanceOf[Fiber.Status.Suspended])
        _ <- uploadCall(rpc, owner, BlobsUpload.Params.Append(id, 0, "AQ=="))
        _ <- uploadCall(rpc, owner, BlobsUpload.Params.Abort(id))
        aborted <- waiter.join
        racedId = BlobId.fresh()
        _ <- uploadCall(rpc, owner, BlobsUpload.Params.Begin(racedId, 3)).zipPar(rpc.disconnect(owner))
        absent <- app.master.readBlob(racedId, BlobUploadLimits.AwaitTimeout).either
        remaining <- app.master.listBlobIds
      } yield {
        // Suspension alone does not prove the bounded wait has captured its dependency yet.
        assert(
          aborted == Left(YallmoError.BlobUploadFailed(BlobUploadFailure.Aborted)) ||
            aborted == Left(YallmoError.BlobMissing(id))
        )
        assertEquals(absent, Left(YallmoError.BlobMissing(racedId)))
        assertEquals(remaining, Chunk.empty)
      }
    })
  }

  test("connection maps drop terminal uploaders automatically without new traffic") {
    run(ZIO.scoped {
      for {
        app <- application(_ => ZIO.dieMessage("Upload must never invoke a provider"))
        rpc = new OrchestratorRpc(app.master, app.configured)
        owner <- rpc.openConnection
        _ <- initializeUpload(rpc, owner, Some(human))
        time <- Ref.make(0L)
        sleeping <- Promise.make[Nothing, Unit]
        wake <- Promise.make[Nothing, Unit]
        clock = new Clock {
          export Clock.ClockLive.{currentTime, currentDateTime, javaClock, localDateTime, scheduler, instant}
          def nanoTime(using Trace): UIO[Long] = time.get
          def sleep(duration: => Duration)(using Trace): UIO[Unit] =
            if duration == BlobUploadLimits.IdleTimeout then sleeping.succeed(()) *> wake.await
            else ZIO.never
        }
        id = BlobId.fresh()
        _ <- uploadCall(rpc, owner, BlobsUpload.Params.Begin(id, 1)).withClock(clock)
        _ <- sleeping.await
        before <- owner.uploads.get
        _ <- time.set(10.seconds.toNanos)
        _ <- wake.succeed(())
        after <- (ZIO.yieldNow *> owner.uploads.get).repeatUntil(_.isEmpty)
        replacement <- uploadCall(rpc, owner, BlobsUpload.Params.Begin(id, 0))
        _ <- uploadCall(rpc, owner, BlobsUpload.Params.Complete(id))
        completed <- owner.uploads.get
        _ <- rpc.disconnect(owner)
      } yield {
        assertEquals(before.size, 1)
        assertEquals(after.size, 0)
        assertEquals(decode[JsonRpc.Success[BlobsUpload.Result]](replacement).result, BlobsUpload.Result.Begun(id))
        assertEquals(completed.size, 0)
      }
    })
  }

  test("WebSocket upload control remains responsive while an early orchestrator await is pending") {
    val logs = new java.util.concurrent.CopyOnWriteArrayList[String]()
    val capture = ZLogger.default.map(line => { logs.add(line); () })
    run(ZIO.scoped {
      for {
        _ <- OrchestratorServerMain.configureLogging(LogLevel.Trace, capture).build
        app <- application(_ => ZIO.dieMessage("Upload must never invoke a provider"))
        port <- new OrchestratorWSServer(app.master, app.configured).start(0)
        channelReady <- Promise.make[Nothing, WebSocketChannel]
        replies <- Queue.unbounded[String]
        socket = Handler.webSocket { channel =>
          channel.receiveAll {
            case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
              channelReady.succeed(channel).unit
            case ChannelEvent.Read(WebSocketFrame.Text(value)) => replies.offer(value).unit
            case _                                             => ZIO.unit
          }
        }
        environment <- Client.default.build
        _ <- socket.connect(s"ws://127.0.0.1:$port/rpc").provideSomeEnvironment[Scope](_.add(environment.get[Client]))
        channel <- channelReady.await
        send = (method: String, params: Json) =>
          channel.send(
            ChannelEvent.Read(WebSocketFrame.Text(JsonRpc.Request(Json.Str("request"), method, params).toJson))
          ) *> replies.take
        _ <- send(
          Initialize.method,
          Initialize
            .Params(fmgp.yallmo.server.protocol.YallmoProtocol.Version, "test", Some(human))
            .toJsonAST
            .toOption
            .get
        )
        id = BlobId.fresh()
        begun <- send(BlobsUpload.method, (BlobsUpload.Params.Begin(id, 3): BlobsUpload.Params).toJsonAST.toOption.get)
        _ = assertEquals(decode[JsonRpc.Success[BlobsUpload.Result]](begun).result, BlobsUpload.Result.Begun(id))
        waiter <- app.master.readBlob(id, BlobUploadLimits.AwaitTimeout).fork
        _ <- (ZIO.yieldNow *> waiter.status).repeatUntil(_.isInstanceOf[Fiber.Status.Suspended])
        _ <- send(
          BlobsUpload.method,
          (BlobsUpload.Params.Append(id, 0, "AQID"): BlobsUpload.Params).toJsonAST.toOption.get
        )
        pending <- waiter.poll
        _ <- send(BlobsUpload.method, (BlobsUpload.Params.Complete(id): BlobsUpload.Params).toJsonAST.toOption.get)
        blob <- waiter.join
        // Disconnect cleanup also reaches awaiters on real sockets, without another upload-control RPC.
        next = BlobId.fresh()
        _ <- send(BlobsUpload.method, (BlobsUpload.Params.Begin(next, 1): BlobsUpload.Params).toJsonAST.toOption.get)
        disconnected <- app.master.readBlob(next, BlobUploadLimits.AwaitTimeout).either.fork
        _ <- (ZIO.yieldNow *> disconnected.status).repeatUntil(_.isInstanceOf[Fiber.Status.Suspended])
        _ <- channel.shutdown
        failure <- disconnected.join
      } yield {
        assertEquals(pending, None)
        assertEquals(blob.bytes, Chunk[Byte](1, 2, 3))
        assert(!logs.toString.contains("AQID"))
        assert(logs.toString.contains("<redacted>"))
        assert(
          failure == Left(YallmoError.BlobUploadFailed(BlobUploadFailure.Aborted)) ||
            failure == Left(YallmoError.BlobMissing(next))
        )
      }
    })
  }

  test("provider login over WebSocket validates state, saves privately and never traces OAuth material") {
    val logs = new java.util.concurrent.CopyOnWriteArrayList[String]()
    val capture = ZLogger.default.map(line => { logs.add(line); () })
    run(ZIO.scoped {
      for {
        _ <- OrchestratorServerMain.configureLogging(LogLevel.Trace, capture).build
        exchanges <- Ref.make(0)
        app <- application(
          request =>
            request.body.asString.flatMap(body =>
              ZIO.attempt {
                assertEquals(request.url.toString, "https://auth.openai.com/oauth/token")
                assert(body.contains("code=PRIVATE_AUTH_CODE"))
                assert(body.contains("code_verifier="))
              } *> exchanges
                .update(_ + 1)
                .as(
                  Response.text(
                    """{"access_token":"PRIVATE_ACCESS_TOKEN","refresh_token":"PRIVATE_REFRESH_TOKEN","expires_in":3600}"""
                  )
                )
            ),
          config.copy(providers = config.providers.updated(ProviderId.unsafe("account"), ProviderConfig.ChatGPT()))
        )
        port <- new OrchestratorWSServer(app.master, app.configured).start(0)
        ready <- Promise.make[Nothing, WebSocketChannel]
        replies <- Queue.unbounded[String]
        socket = Handler.webSocket { channel =>
          channel.receiveAll {
            case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
              ready.succeed(channel).unit
            case ChannelEvent.Read(WebSocketFrame.Text(value)) => replies.offer(value).unit
            case _                                             => ZIO.unit
          }
        }
        http <- Client.default.build
        _ <- socket.connect(s"ws://127.0.0.1:$port/rpc").provideSomeEnvironment[Scope](_.add(http.get[Client]))
        channel <- ready.await
        send = (method: String, params: Json) =>
          channel.send(
            ChannelEvent.Read(WebSocketFrame.Text(JsonRpc.Request(Json.Str("provider"), method, params).toJson))
          ) *> replies.take
        initialized <- send(
          Initialize.method,
          Initialize
            .Params(fmgp.yallmo.server.protocol.YallmoProtocol.Version, "web-test", Some(human))
            .toJsonAST
            .toOption
            .get
        )
        before <- send(ProvidersList.method, Json.Obj())
        started <- send(ProvidersLoginStart.method, ProvidersLoginStart.Params("account").toJsonAST.toOption.get)
        login = decode[JsonRpc.Success[ProvidersLoginStart.Result]](started).result
        rejected <- send(
          ProvidersLoginComplete.method,
          ProvidersLoginComplete
            .Params(login.attemptId, "http://localhost:1455/auth/callback?code=PRIVATE_AUTH_CODE&state=wrong")
            .toJsonAST
            .toOption
            .get
        )
        startedAgain <- send(ProvidersLoginStart.method, ProvidersLoginStart.Params("account").toJsonAST.toOption.get)
        next = decode[JsonRpc.Success[ProvidersLoginStart.Result]](startedAgain).result
        state = java.net.URI
          .create(next.authorizationUrl)
          .getRawQuery
          .split("&")
          .find(_.startsWith("state="))
          .get
          .stripPrefix("state=")
        params = ProvidersLoginComplete.Params(
          next.attemptId,
          s"http://localhost:1455/auth/callback?code=PRIVATE_AUTH_CODE&state=$state"
        )
        completed <- send(ProvidersLoginComplete.method, params.toJsonAST.toOption.get)
        replay <- send(ProvidersLoginComplete.method, params.toJsonAST.toOption.get)
        after <- send(ProvidersList.method, Json.Obj())
        count <- exchanges.get
        sessions <- app.master.listSessions
        messages <- app.master.listMessages
        saved <- ZIO.attemptBlocking(Files.readString(app.configured.get.workspace.resolve("config.json")))
        _ <- channel.shutdown
      } yield {
        assert(
          decode[JsonRpc.Success[Initialize.Result]](initialized).result.methods.contains(ProvidersLoginStart.method)
        )
        assertEquals(
          decode[JsonRpc.Success[ProvidersList.Result]](before).result.find(_.id == "account").get.credentials,
          ProviderCredentialStatus.LoggedOut
        )
        assertEquals(decode[JsonRpc.Failure](rejected).error.code, ErrorCode.Rejected)
        assertEquals(decode[JsonRpc.Success[ProvidersLoginComplete.Result]](completed).result.restartRequired, false)
        assertEquals(decode[JsonRpc.Failure](replay).error.code, ErrorCode.NotFound)
        assertEquals(
          decode[JsonRpc.Success[ProvidersList.Result]](after).result.find(_.id == "account").get.credentials,
          ProviderCredentialStatus.Configured
        )
        assertEquals(count, 1)
        assertEquals(sessions, Chunk.empty)
        assertEquals(messages, Chunk.empty)
        assert(saved.contains("PRIVATE_ACCESS_TOKEN"))
        List("PRIVATE_AUTH_CODE", "PRIVATE_ACCESS_TOKEN", "PRIVATE_REFRESH_TOKEN", state, next.attemptId).foreach(
          secret => assert(!logs.toString.contains(secret), "OAuth material leaked to logs")
        )
        assert(!List(before, after, completed, rejected, replay).mkString.contains("PRIVATE_"))
      }
    })
  }

  test("successful provider login activates fresh tokens for the next server turn without rebuilding the application") {
    def token(account: String): String = {
      val payload = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(
        s"""{"https://api.openai.com/auth":{"chatgpt_account_id":"$account"}}""".getBytes(
          java.nio.charset.StandardCharsets.UTF_8
        )
      )
      s"header.$payload.synthetic-signature"
    }
    val account = ProviderId.unsafe("account")
    val native = AgentConfig(
      "ChatGPTAgent",
      Seq(account),
      Json.Obj("model" -> Json.Str("test")),
      capabilities = AgentCapabilities(tools = Some(Set.empty))
    )
    val definition = Config(
      agents = Map(first -> native, second -> native),
      providers = Map(
        account -> ProviderConfig.ChatGPT(
          Some(
            fmgp.yallmo.orchestration.config.OAuthCredentialsConfig(token("before"), "before-refresh", Long.MaxValue)
          )
        )
      )
    )
    run(ZIO.scoped {
      for {
        headers <- Ref.make(Vector.empty[String])
        app <- application(
          request =>
            if request.url.toString == "https://auth.openai.com/oauth/token" then
              ZIO.succeed(
                Response.text(
                  ujson.write(
                    ujson.Obj(
                      "access_token" -> token("after"),
                      "refresh_token" -> "after-refresh",
                      "expires_in" -> 3600
                    )
                  )
                )
              )
            else
              headers
                .update(_ :+ request.headers.get("Authorization").getOrElse(""))
                .as(
                  Response.text(
                    "data: " + ujson.write(
                      ujson.Obj(
                        "type" -> "response.done",
                        "response" -> ujson.Obj(
                          "status" -> "completed",
                          "output" -> ujson.Arr(
                            ujson.Obj(
                              "type" -> "message",
                              "content" -> ujson.Arr(ujson.Obj("type" -> "output_text", "text" -> "answer"))
                            )
                          )
                        )
                      )
                    ) + "\n\n"
                  )
                )
          ,
          definition
        )
        backend = app.configured.get
        warmed <- backend.runtime.resolve(second)
        session <- backend.create(human, Some(SessionId.unsafe("live-login")))
        before <- backend.submit(human, session.id, first, "before login")
        owner = java.util.UUID.randomUUID()
        login <- backend.providers.start(owner, "account")
        state = java.net.URI
          .create(login.authorizationUrl)
          .getRawQuery
          .split("&")
          .find(_.startsWith("state="))
          .get
          .stripPrefix("state=")
        completed <- backend.providers.complete(
          owner,
          ProvidersLoginComplete.Params(
            login.attemptId,
            s"http://localhost:1455/auth/callback?code=synthetic-code&state=$state"
          )
        )
        after <- backend.submit(human, session.id, second, "after login")
        rebound <- backend.runtime.resolve(second)
        sent <- headers.get
        stored <- app.master.loadSession(session.id)
        saved <- ZIO.attemptBlocking(
          Files.readString(backend.workspace.resolve("config.json")).fromJson[Config].toOption.get
        )
      } yield {
        assert(!completed.restartRequired)
        assert(before.isInstanceOf[TurnOutcome.Succeeded])
        assert(after.isInstanceOf[TurnOutcome.Succeeded])
        assert(!(warmed.adapter eq rebound.adapter))
        assertEquals(sent, Vector(s"Bearer ${token("before")}", s"Bearer ${token("after")}"))
        assertEquals(
          stored.messages.map(_.payload),
          Chunk(
            MessagePayload.User("before login"),
            MessagePayload.Assistant("answer"),
            MessagePayload.User("after login"),
            MessagePayload.Assistant("answer")
          )
        )
        assertEquals(
          saved.providers(account).asInstanceOf[ProviderConfig.ChatGPT].credentials.map(_.accessToken),
          Some(token("after"))
        )
        assert(app.master eq backend.assembly.orchestrator)
      }
    })
  }

  test("provider RPCs require a configured operator and bind attempts to the exact connection") {
    run(ZIO.scoped {
      for {
        app <- application(
          _ => ZIO.dieMessage("Invalid login must not exchange tokens"),
          config.copy(providers = config.providers.updated(ProviderId.unsafe("account"), ProviderConfig.ChatGPT()))
        )
        rpc = new OrchestratorRpc(app.master, app.configured)
        owner <- rpc.openConnection
        other <- rpc.openConnection
        viewer <- rpc.openConnection
        _ <- initializeUpload(rpc, owner, Some(human))
        _ <- initializeUpload(rpc, other, Some(human))
        info <- initializeUpload(rpc, viewer, None)
        call = (connection: OrchestratorRpc.Connection, method: String, params: Json) =>
          rpc.handle(JsonRpc.Request(Json.Str("provider"), method, params).toJson, connection).map(_.get)
        forbidden <- ZIO.foreach(
          List(
            ProvidersList.method,
            ProvidersLoginStart.method,
            ProvidersLoginComplete.method,
            ProvidersLoginCancel.method
          )
        )(method => call(viewer, method, Json.Obj()))
        started <- call(owner, ProvidersLoginStart.method, ProvidersLoginStart.Params("account").toJsonAST.toOption.get)
        login = decode[JsonRpc.Success[ProvidersLoginStart.Result]](started).result
        _ <- call(
          other,
          ProvidersLoginCancel.method,
          ProvidersLoginCancel.Params(login.attemptId).toJsonAST.toOption.get
        )
        foreign <- call(
          other,
          ProvidersLoginComplete.method,
          ProvidersLoginComplete.Params(login.attemptId, "secret-code").toJsonAST.toOption.get
        )
        oversized <- call(
          owner,
          ProvidersLoginComplete.method,
          ProvidersLoginComplete
            .Params(login.attemptId, "x" * (ProvidersLoginComplete.MaxRedirectChars + 1))
            .toJsonAST
            .toOption
            .get
        )
        _ <- rpc.disconnect(owner)
        absent <- app.configured.get.providers
          .complete(owner.id, ProvidersLoginComplete.Params(login.attemptId, "secret-code"))
          .either
        racing <- rpc.openConnection
        _ <- initializeUpload(rpc, racing, Some(human))
        raced <- call(racing, ProvidersLoginStart.method, ProvidersLoginStart.Params("account").toJsonAST.toOption.get)
          .zipPar(rpc.disconnect(racing))
        _ <- ZIO.foreachDiscard(raced.fromJson[JsonRpc.Success[ProvidersLoginStart.Result]].toOption) { value =>
          app.configured.get.providers
            .complete(racing.id, ProvidersLoginComplete.Params(value.result.attemptId, "secret-code"))
            .either
            .map { result =>
              assertEquals(result.swap.toOption.get.code, ErrorCode.NotFound)
            }
        }
        _ <- rpc.disconnect(other)
        _ <- rpc.disconnect(viewer)
      } yield {
        assert(!decode[JsonRpc.Success[Initialize.Result]](info).result.methods.contains(ProvidersList.method))
        forbidden.foreach(reply => assertEquals(decode[JsonRpc.Failure](reply).error.code, ErrorCode.Forbidden))
        assertEquals(decode[JsonRpc.Failure](foreign).error.code, ErrorCode.NotFound)
        assertEquals(decode[JsonRpc.Failure](oversized).error, JsonRpc.Error.InvalidParams)
        assertEquals(absent.swap.toOption.get.code, ErrorCode.NotFound)
      }
    })
  }

  private def initializeUpload(
      rpc: OrchestratorRpc,
      connection: OrchestratorRpc.Connection,
      claimed: Option[AgentId]
  ): UIO[String] =
    rpc
      .handle(
        JsonRpc
          .Request(
            Json.Str("init"),
            Initialize.method,
            Initialize.Params(fmgp.yallmo.server.protocol.YallmoProtocol.Version, "test", claimed)
          )
          .toJson,
        connection
      )
      .map(_.get)

  private def uploadCall(
      rpc: OrchestratorRpc,
      connection: OrchestratorRpc.Connection,
      command: BlobsUpload.Params
  ): UIO[String] =
    rpc.handle(JsonRpc.Request(Json.Str("upload"), BlobsUpload.method, command).toJson, connection).map(_.get)

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
      def execute(
          code: String,
          timeout: Duration,
          maxOutputBytes: Int,
          dependencies: Seq[String]
      ): Task[ExternalToolResult.ScalaMode] = unexpected
    }
    ZLayer.succeed[BashExecutor](bash) ++
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
