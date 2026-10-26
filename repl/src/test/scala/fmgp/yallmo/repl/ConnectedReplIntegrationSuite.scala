package fmgp.yallmo.repl

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.server.{OrchestratorWSClient, OrchestratorWSServer}
import fmgp.yallmo.repl.terminal.TerminalRepl
import fmgp.yallmo.orchestration.config.{AgentCapabilities, AgentConfig, Config, ProviderConfig, ProviderId}
import fmgp.yallmo.server.application.ServerApplication
import fmgp.yallmo.tool.*
import java.nio.file.{Files, Path}
import java.util.Comparator
import zio.http.{Version as HttpVersion, *}
import zio.json.*
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import org.jline.terminal.impl.DumbTerminal
import munit.FunSuite
import zio.{Config as _, *}

final class ConnectedReplIntegrationSuite extends FunSuite {
  private val agentId = AgentId("server-agent")
  private val human = AgentId("user-claimed-human")
  private val id = SessionId.unsafe("server-session")
  private val agent = new Agent[Any] {
    val id = agentId
    def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
      ZIO.dieMessage("read-only REPL executed an agent")
  }
  private def master = RuntimeInstances.fromAgents(Chunk(agent)).flatMap(OrchestratorLive.inMemory(_))

  test("actual launch reads a separate WS master freshly, publishes human metadata and cleans presence on scope exit") {
    run(ZIO.scoped {
      for {
        service <- master
        _ <- service.createSession(id, Chunk(agentId))
        _ <- service.append(id, agentId, Chunk(MessagePayload.Assistant("before")))
        removed <- Promise.make[Nothing, Unit]
        runtime <- ZIO.runtime[Any]
        _ <- Runtime
          .addLogger(ZLogger.default.map { line =>
            if line.contains("Viewer identity removed") then
              Unsafe.unsafe { u ?=>
                runtime.unsafe.run(removed.succeed(())).getOrThrowFiberFailure()
              }
            ()
          })
          .build
        port <- new OrchestratorWSServer(service).start(0)
        options <- ZIO
          .fromEither(
            ReplLaunchOptions.parse(
              Chunk(
                "--user-name",
                "claimed-human",
                "--server",
                s"ws://127.0.0.1:$port/rpc",
                "--log-level",
                "debug"
              )
            )
          )
          .mapError(new IllegalArgumentException(_))
        _ <- ZIO.scoped {
          ReplMain.launch(
            options,
            app =>
              for {
                _ <- ZIO.succeed {
                  assert(!app.canSubmit)
                  assert(app.agentChoices.exists(_.id == human))
                  assertEquals(app.userId, human)
                  assertEquals(app.initialState.logLevel, LogLevel.Debug)
                }
                selected <- app.selectAgent(agentId)
                resumed <- selected.submit(selected.initialState, "/session resume server-session")
                _ <- service.append(id, agentId, Chunk(MessagePayload.Assistant("after")))
                refreshed <- selected.submit(resumed.state, "/history")
                _ <- ZIO.succeed {
                  val snapshot = refreshed.events.head.asInstanceOf[ReplEvent.SnapshotTranscript]
                  assertEquals(snapshot.messages.map(_.content), Chunk("before", "after"))
                  assertEquals(selected.initialState.logLevel, LogLevel.Debug)
                  assertEquals(refreshed.state.logLevel, LogLevel.Debug)
                }
              } yield ()
          )
        }
        _ <- removed.await
        observer <- OrchestratorWSClient
          .connect(s"ws://127.0.0.1:$port/rpc")
          .mapError(error => new Exception(error.toString))
        ids <- observer.listAgentIds.mapError(error => new Exception(error.toString))
      } yield assertEquals(ids, Chunk(agentId))
    })
  }

  test("unavailable server exits with a fixed error without starting the terminal") {
    val socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress)
    val port = socket.getLocalPort
    socket.close()
    run(ZIO.scoped {
      ReplMain
        .launch(
          ReplLaunchOptions("human", server = s"ws://127.0.0.1:$port/rpc"),
          _ => ZIO.dieMessage("terminal must not start")
        )
        .either
        .map { result =>
          assertEquals(
            result.left.toOption.map(_.getMessage),
            Some("Connected REPL connection failed; no local fallback.")
          )
        }
    })
  }

  test("unavailable default server fails without retry or terminal startup") {
    run(ZIO.scoped {
      for {
        calls <- Ref.make(0)
        result <- ReplMain
          .launch(
            ReplLaunchOptions("human"),
            _ => ZIO.dieMessage("terminal must not start"),
            connect = (endpoint, user) => {
              assertEquals(endpoint, "ws://127.0.0.1:8080/rpc")
              assertEquals(user, AgentId("user-human"))
              calls.update(_ + 1) *> ZIO.fail(fmgp.yallmo.server.OrchestratorClient.Error.Disconnected)
            }
          )
          .either
        attempts <- calls.get
      } yield {
        assertEquals(attempts, 1)
        assertEquals(
          result.left.toOption.map(_.getMessage),
          Some("Connected REPL connection failed; no local fallback.")
        )
      }
    })
  }

  test("invalid direct username is rejected before connecting") {
    for name <- List("", " ", "\t\n") do
      run(ZIO.scoped {
        ReplMain
          .launch(
            ReplLaunchOptions(name, server = "not-an-endpoint"),
            _ => ZIO.dieMessage("terminal must not start")
          )
          .either
          .map(result => assertEquals(result.left.toOption.map(_.getMessage), Some("Invalid --user-name.")))
      })
  }

  test("absent --server connects to the default server rather than bootstrapping locally") {
    run(ZIO.scoped {
      for {
        service <- master
        port <- new OrchestratorWSServer(service).start(0)
        options <- ZIO
          .fromEither(ReplLaunchOptions.parse(Chunk("--user-name=claimed-human")))
          .mapError(new IllegalArgumentException(_))
        entered <- Ref.make(false)
        _ <- ReplMain.launch(
          options,
          app =>
            ZIO.succeed {
              assert(!app.canSubmit)
              assertEquals(app.userId, human)
            } *> entered.set(true),
          connect = (endpoint, user) => {
            assertEquals(endpoint, "ws://127.0.0.1:8080/rpc")
            OrchestratorWSClient.connect(
              s"ws://127.0.0.1:$port/rpc",
              userId = Some(user),
              clientName = "yallmo-repl"
            )
          }
        )
        connected <- entered.get
      } yield assert(connected)
    })
  }

  test("connected launch drives the existing terminal byte seam with no recorder, browser or provider login") {
    run(ZIO.scoped {
      for {
        service <- master
        port <- new OrchestratorWSServer(service).start(0)
        output = new ByteArrayOutputStream
        terminal <- ZIO.acquireRelease(ZIO.attempt {
          val terminal = new DumbTerminal(
            "connected-test",
            "xterm",
            new ByteArrayInputStream("i/help\n/session list\nhello\n/exit\n".getBytes(UTF_8)),
            output,
            UTF_8
          )
          terminal.setSize(new org.jline.terminal.Size(120, 35))
          terminal
        })(terminal => ZIO.attempt(terminal.close()).ignore)
        _ <- ReplMain.launch(
          ReplLaunchOptions("human", server = s"ws://127.0.0.1:$port/rpc"),
          app => TerminalRepl.runConnectedWithTerminal(app, terminal)
        )
      } yield {
        assert(!output.toString(UTF_8).contains("Browser audio setup"))
        assert(output.toString(UTF_8).contains("/session list"))
        assert(output.toString(UTF_8).contains("/exit"))
      }
    })
  }

  test("configured launch submits terminal prompt to separate server storage without local bootstrap or voice") {
    run(ZIO.scoped {
      for {
        calls <- Ref.make(0)
        app <- configured(_ => calls.update(_ + 1).as(providerAnswer))
        port <- new OrchestratorWSServer(app.master, app.configured).start(0)
        output = new ByteArrayOutputStream
        terminal <- ZIO.acquireRelease(ZIO.attempt {
          val terminal = new DumbTerminal(
            "operator-test",
            "xterm",
            new ByteArrayInputStream("ihello\n/debug request\n/history\n/exit\n".getBytes(UTF_8)),
            output,
            UTF_8
          )
          terminal.setSize(new org.jline.terminal.Size(120, 35))
          terminal
        })(terminal => ZIO.attempt(terminal.close()).ignore)
        _ <- ReplMain.launch(
          ReplLaunchOptions(
            "claimed-human",
            agentId = Some(AgentId("bob")),
            server = s"ws://127.0.0.1:$port/rpc",
            logLevel = LogLevel.Debug
          ),
          connected => {
            assert(connected.canSubmit)
            assert(connected.canReadRequests)
            assertEquals(connected.agentChoices.map(_.id), Vector(AgentId("bob")));
            assertEquals(connected.initialState.logLevel, LogLevel.Debug)
            TerminalRepl.runConnectedWithTerminal(connected, terminal)
          }
        )
        sessions <- app.master.listSessions
        stored <- app.master.loadSession(sessions.head.id)
        count <- calls.get
      } yield {
        assertEquals(count, 1); assertEquals(sessions.size, 1)
        assertEquals(stored.messages.map(_.agentId), Chunk(human, AgentId("bob")))
        assertEquals(
          stored.messages.map(_.payload),
          Chunk(MessagePayload.User("hello"), MessagePayload.Assistant("server answer"))
        )
        assert(!output.toString(UTF_8).contains("Browser audio setup"))
      }
    })
  }

  test("application wait loss retains session; same socket reads work; reconnect history sees detached completion") {
    run(ZIO.scoped {
      for {
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        calls <- Ref.make(0)
        app <- configured(_ => calls.update(_ + 1) *> entered.succeed(()) *> release.await.as(providerAnswer))
        port <- new OrchestratorWSServer(app.master, app.configured).start(0)
        client <- OrchestratorWSClient
          .connect(s"ws://127.0.0.1:$port/rpc", userId = Some(human))
          .mapError(error => new Exception(error.toString))
        connected <- ConnectedReplApplication.make(client, human, Some(AgentId("bob")))
        created <- connected.submit(connected.initialState, "/new detached")
        wait <- connected.submit(created.state, "keep running").fork
        _ <- entered.await
        during <- connected.submit(created.state, "/history")
        list <- connected.submit(created.state, "/session list")
        busy <- connected.submit(during.state, "must not append")
        _ <- client.close
        lost <- wait.join
        _ <- release.succeed(())
        observer <- OrchestratorWSClient
          .connect(s"ws://127.0.0.1:$port/rpc", userId = Some(human))
          .mapError(error => new Exception(error.toString))
        reconnected <- ConnectedReplApplication.make(observer, human, Some(AgentId("bob")))
        resumed <- reconnected.submit(reconnected.initialState, "/session resume detached")
        complete <- (ZIO.yieldNow *> reconnected.submit(resumed.state, "/history"))
          .repeatUntil(_.events.head.asInstanceOf[ReplEvent.SnapshotTranscript].messages.size == 2)
        forbidden <- ZIO.foreach(List("/tools", "/debug print", "/login", "/voice", "/tts"))(
          reconnected.submit(complete.state, _)
        )
        count <- calls.get
      } yield {
        assertEquals(lost.state.sessionId, Some("detached")); assert(lost.events.toString.contains("unknown"))
        assertEquals(
          during.events.head.asInstanceOf[ReplEvent.SnapshotTranscript].messages.map(_.content),
          Chunk("keep running")
        )
        assert(list.events.head.isInstanceOf[ReplEvent.SessionList])
        assertEquals(busy.state, during.state); assert(busy.events.toString.contains("busy"))
        assertEquals(count, 1)
        assertEquals(
          complete.events.head.asInstanceOf[ReplEvent.SnapshotTranscript].messages.map(_.content),
          Chunk("keep running", "server answer")
        );
        assert(complete.state.remoteResetContinuation)
        assert(complete.state.remoteHeadMessageId.nonEmpty)
        assert(forbidden.forall(_.state == complete.state))
        assert(
          forbidden.forall(
            _.events.forall(event =>
              event.isInstanceOf[ReplEvent.Unavailable] || event.isInstanceOf[ReplEvent.InputRejected]
            )
          )
        )
      }
    })
  }

  private def providerAnswer =
    Response.text("""{"choices":[{"message":{"role":"assistant","content":"server answer"},"finish_reason":"stop"}]}""")

  private def configured(respond: Request => Task[Response]): ZIO[Scope, Throwable, ServerApplication] =
    for {
      root <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("yallmo-repl-server-"))) { root =>
        ZIO.attemptBlocking {
          val paths = Files.walk(root)
          try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
          finally paths.close()
        }.orDie
      }
      provider = ProviderId.unsafe("fake")
      config = Config(
        agents = Map(
          AgentId("bob") -> AgentConfig(
            "OAIAgent",
            Seq(provider),
            zio.json.ast.Json.Obj("model" -> zio.json.ast.Json.Str("fake-model")),
            capabilities = AgentCapabilities(tools = Some(Set.empty))
          )
        ),
        providers = Map(provider -> ProviderConfig.OAI("http://fake.invalid/v1"))
      )
      path <- ZIO.attemptBlocking(Files.writeString(root.resolve("private-config.json"), config.toJson))
      service <- ServerApplication
        .open(root, Some(path), Some(root))
        .provideSomeLayer[Scope](ZLayer.succeed(fakeClient(respond)) ++ tools)
    } yield service

  private val tools: ULayer[ServerApplication.ToolEnvironment] = {
    def unexpected[A]: UIO[A] = ZIO.dieMessage("connected test executed a local tool")
    val fs = new WorkspaceFileSystem {
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
    ZLayer.succeed[WorkspaceFileSystem](fs) ++ ZLayer.succeed[BashExecutor](bash) ++
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
        ZIO.dieMessage("unexpected provider socket")
      }
    })

  private def run[A](effect: Task[A]): A = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe.run(effect.timeoutFail(new Exception("test timeout"))(15.seconds)).getOrThrowFiberFailure()
  }
}
