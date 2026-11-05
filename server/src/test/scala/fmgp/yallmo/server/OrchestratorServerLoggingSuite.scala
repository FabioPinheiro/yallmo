package fmgp.yallmo.server

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import fmgp.yallmo.orchestration.{OrchestratorLive, RuntimeInstances, SessionId}
import java.nio.file.{Files, Path}
import java.util.Comparator
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import zio.json.*
import zio.json.ast.Json
import java.util.concurrent.{CopyOnWriteArrayList, CountDownLatch, TimeUnit}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import zio.*

final class OrchestratorServerLoggingSuite extends FunSuite {
  import OrchestratorServerMain.{Options, parse}

  test("startup defaults to INFO and accepts reordered/equal-syntax options") {
    assertEquals(parse(List("--storage", "sessions")), Right(Options(Path.of("sessions"))))
    assertEquals(
      parse(List("--log-level=DeBuG", "--port", "8081", "--storage=sessions")),
      Right(Options(Path.of("sessions"), 8081, LogLevel.Debug))
    )
    assertEquals(
      parse(List("--log-level", "trace", "--storage", "sessions")),
      Right(Options(Path.of("sessions"), 8080, LogLevel.Trace))
    )
  }

  test("all supported levels parse in either syntax, case-insensitively") {
    val levels = List(
      "trace" -> LogLevel.Trace,
      "debug" -> LogLevel.Debug,
      "info" -> LogLevel.Info,
      "warning" -> LogLevel.Warning,
      "error" -> LogLevel.Error,
      "fatal" -> LogLevel.Fatal,
      "off" -> LogLevel.None
    )
    levels.foreach { case (name, level) =>
      assertEquals(parse(List("--storage", "sessions", s"--log-level=$name")).map(_.logLevel), Right(level))
      assertEquals(
        parse(List("--log-level", name.toUpperCase(java.util.Locale.ROOT), "--storage", "sessions")).map(_.logLevel),
        Right(level)
      )
    }
  }

  test("invalid, duplicate, missing and unknown options are rejected") {
    List(
      List("--storage", "sessions", "--log-level", "nope"),
      List("--storage", "sessions", "--log-level"),
      List("--storage", "sessions", "--log-level="),
      List("--storage", "sessions", "--log-level=info", "--log-level=trace"),
      List("--storage", "sessions", "--port=0"),
      List("--storage", "sessions", "--port=65536"),
      List("--storage", "sessions", "--port=private-value"),
      List("--storage", "sessions", "--port"),
      List("--storage", "sessions", "--unknown=ignored"),
      List("--storage", "sessions", "--storage=other"),
      List("--storage="),
      List("--log-level=debug"),
      Nil
    ).foreach(args => assert(parse(args).isLeft, args.toString))
  }

  test("minimum levels filter real severities rather than relabeling statements") {
    val messages = new CopyOnWriteArrayList[String]()
    val capture = ZLogger.default.map(line => { messages.add(line); () })
    run(ZIO.scoped {
      OrchestratorServerMain.configureLogging(LogLevel.Warning, capture).build *>
        (ZIO.logTrace("trace marker") *> ZIO.logDebug("debug marker") *> ZIO.logInfo("info marker") *>
          ZIO.logWarning("warning marker") *> ZIO.logError("error marker") *> ZIO.logFatal("fatal marker"))
    })
    val lines = messages.asScala.toVector
    assertEquals(lines.size, 3)
    assert(lines.exists(line => line.contains("level=WARN") && line.contains("warning marker")))
    assert(lines.exists(line => line.contains("level=ERROR") && line.contains("error marker")))
    assert(lines.exists(line => line.contains("level=FATAL") && line.contains("fatal marker")))
    run(ZIO.scoped {
      OrchestratorServerMain.configureLogging(LogLevel.None, capture).build *>
        (ZIO.logFatal("suppressed fatal") *> LogLevel.None(ZIO.log("suppressed OFF-severity statement")))
    })
    assertEquals(messages.size(), 3)
  }

  test("INFO records each established connection and disconnect with the same connection ID, without payloads") {
    val lines = exchange(LogLevel.Info)
    val connected = lines.filter(_.contains("WebSocket connected"))
    val disconnected = lines.filter(_.contains("WebSocket disconnected"))
    assertEquals(connected.size, 1)
    assertEquals(disconnected.size, 1)
    val id = "connectionId=([0-9a-f-]{36})".r
      .findFirstMatchIn(connected.head)
      .map(_.group(1))
      .getOrElse(fail("Missing connection ID"))
    assert(disconnected.head.contains(s"connectionId=$id"))
    assert(connected.head.contains("peer="))
    assert(!lines.exists(_.contains("RPC incoming")))
    assert(!lines.exists(_.contains("PRIVATE_TRANSCRIPT")))
  }

  test("DEBUG records message direction/byte counts but not full RPC data") {
    val lines = exchange(LogLevel.Debug)
    assert(lines.exists(_.contains("RPC incoming bytes=")))
    assert(lines.exists(_.contains("RPC outgoing bytes=")))
    assert(!lines.exists(_.contains("payload=")))
    assert(!lines.exists(_.contains("PRIVATE_TRANSCRIPT")))
    assert(!lines.exists(_.contains("AAH/")))
  }

  test("TRACE emits RPC directions without binary content, while OFF suppresses application logs") {
    val lines = exchange(LogLevel.Trace)
    assert(lines.exists(line => line.contains("RPC incoming payload=") && line.contains("sessions.get")))
    assert(lines.exists(line => line.contains("RPC outgoing payload=") && line.contains("PRIVATE_TRANSCRIPT")))
    assert(!lines.exists(_.contains("AAH/")))
    assert(lines.exists(_.contains("<redacted>")))
    assert(lines.filter(_.contains("payload=")).forall(_.contains("connectionId=")))
    assertEquals(exchange(LogLevel.None), Vector.empty)
  }

  test("TRACE redaction covers batch fields, escaped field names, responses and malformed JSON") {
    List(
      """[{"params":{"dataBase64":"PRIVATE_BINARY"}},{"result":{"dataBase64":"PRIVATE_BINARY"}}]""",
      """{"params":{"data\u0042ase64":"PRIVATE_BINARY"}}""",
      """{"params":{"dataBase64":"PRIVATE_BINARY""""
    ).foreach { payload => assert(!RpcWire.tracePayload(payload).contains("PRIVATE_BINARY")) }
  }

  test("TRACE redacts OAuth URLs, attempt IDs and tokens in batches and escaped keys") {
    List(
      """[{"params":{"redirectUrl":"PRIVATE_OAUTH"}},{"result":{"authorizationUrl":"PRIVATE_OAUTH","attemptId":"PRIVATE_OAUTH"}}]""",
      """{"params":{"redirect\u0055rl":"PRIVATE_OAUTH"}}""",
      """{"accessToken":"PRIVATE_OAUTH","refreshToken":"PRIVATE_OAUTH","codeVerifier":"PRIVATE_OAUTH"}""",
      """{"method":"providers.login.complete","params":{"wrongField":"PRIVATE_OAUTH"}}""",
      """{"params":{"redirectUrl":"PRIVATE_OAUTH""""
    ).foreach(payload => assert(!RpcWire.tracePayload(payload).contains("PRIVATE_OAUTH")))
  }

  test("missing session messages log the original storage cause while RPC replies remain sanitized") {
    val logs = new CopyOnWriteArrayList[String]()
    val capture = ZLogger.default.map(line => { logs.add(line); () })
    val missing = MessageId.sha256("missing-message")
    run(ZIO.scoped {
      for {
        root <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("yallmo-rpc-logging-"))) { root =>
          ZIO.attemptBlocking {
            val paths = Files.walk(root)
            try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
            finally paths.close()
          }.orDie
        }
        author = AgentId("author")
        registry <- RuntimeInstances.fromAgents(Chunk(new Agent[Any] {
          val id = author
          def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
            ZIO.dieMessage("Reading a broken session must not execute an agent")
        }))
        master <- OrchestratorLive.filesystem(root, registry)
        session = SessionId.unsafe("broken")
        _ <- master.createSession(session, Chunk(author))
        _ <- master.append(session, author, Chunk(MessagePayload.User("PRIVATE_TRANSCRIPT")))
        _ <- ZIO.attemptBlocking(
          Files.writeString(
            root.resolve("refs/sessions/broken"),
            s"""{"agentIds":["author"],"head":"${MessageId.value(missing)}"}"""
          )
        )
        rpc = new OrchestratorRpc(master)
        connection <- rpc.openConnection
        _ <- rpc.handle(
          JsonRpc
            .Request(
              Json.Str("init"),
              Initialize.method,
              Initialize.Params("logging-test", None)
            )
            .toJson,
          connection
        )
        _ <- (Runtime.removeDefaultLoggers >>> Runtime.addLogger(capture)).build
        listed <- rpc.handle(JsonRpc.Request(Json.Str("list"), SessionsList.method, Empty()).toJson, connection)
        loaded <- rpc.handle(
          JsonRpc
            .Request(
              Json.Str("get"),
              SessionsGet.method,
              SessionsGet.Params(session)
            )
            .toJson,
          connection
        )
        notFound <- rpc.handle(
          JsonRpc
            .Request(
              Json.Str("absent"),
              SessionsGet.method,
              SessionsGet.Params(SessionId.unsafe("absent"))
            )
            .toJson,
          connection
        )
      } yield {
        List(listed.get, loaded.get).foreach { reply =>
          assertEquals(reply.fromJson[JsonRpc.Failure].toOption.get.error, JsonRpc.Error.InternalError)
          assert(!reply.contains(MessageId.value(missing)))
          assert(!reply.contains("PRIVATE_TRANSCRIPT"))
        }
        assertEquals(notFound.get.fromJson[JsonRpc.Failure].toOption.get.error.code, ErrorCode.NotFound)
        val errors = logs.asScala.filter(_.contains("level=ERROR")).toVector
        assertEquals(errors.size, 2)
        assert(errors.forall(_.contains("session refers to missing message")))
        assert(errors.forall(_.contains(MessageId.value(missing))))
        assert(errors.exists(_.contains("rpcMethod=sessions.list")))
        assert(errors.exists(line => line.contains("rpcMethod=sessions.get") && line.contains("sessionId=broken")))
        assert(!logs.asScala.exists(_.contains("PRIVATE_TRANSCRIPT")))
      }
    })
  }

  private def exchange(level: LogLevel): Vector[String] = {
    val messages = new CopyOnWriteArrayList[String]()
    val disconnected = new CountDownLatch(1)
    val capture = ZLogger.default.map { line =>
      messages.add(line)
      if line.contains("WebSocket disconnected") then disconnected.countDown()
      ()
    }
    run(ZIO.scoped {
      for {
        _ <- OrchestratorServerMain.configureLogging(level, capture).build
        registry <- RuntimeInstances.fromAgents(Chunk(new Agent[Any] {
          val id = AgentId("human")
          def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] = ZIO.dieMessage("No agent execution")
        }))
        master <- OrchestratorLive.inMemory(registry)
        session = SessionId.unsafe("logging-test")
        _ <- master.createSession(session, Chunk(AgentId("human")))
        _ <- master.append(session, AgentId("human"), Chunk(MessagePayload.User("PRIVATE_TRANSCRIPT")))
        blob = BlobId.fresh()
        _ <- master.appendWithBlobBytes(
          session,
          AgentId("human"),
          MessagePayload.AudioAttachment(blob, AudioFormat.Wav),
          Chunk[Byte](0, 1, -1)
        )
        port <- new OrchestratorWSServer(master).start(0)
        client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc")
        _ <- client.loadSession(session)
        _ <- client.readBlob(blob)
        _ <- client.close
        _ <- ZIO.when(level != LogLevel.None) {
          ZIO
            .attemptBlocking(disconnected.await(3, TimeUnit.SECONDS))
            .flatMap(done => ZIO.fail(new AssertionError("No disconnection log")).unless(done))
        }
      } yield ()
    })
    messages.asScala.toVector
  }

  private def run[A](effect: ZIO[Any, Any, A]): A =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe
        .run(effect.timeoutFail(new AssertionError("Logging test timed out"))(10.seconds))
        .getOrThrowFiberFailure()
    }
}
