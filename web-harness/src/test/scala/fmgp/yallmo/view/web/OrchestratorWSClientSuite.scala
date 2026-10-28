package fmgp.yallmo.view.web

import fmgp.yallmo.model.{AgentId, BlobId, Message, MessageData}
import java.time.Instant
import fmgp.yallmo.orchestration.{AgentTask, SessionId}
import zio.Chunk
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import munit.FunSuite
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.timers.setTimeout
import zio.json.*
import zio.json.ast.Json

final class OrchestratorWSClientSuite extends FunSuite {
  import OrchestratorWSClientSuite.*
  private val blob = BlobId.from("00000000-0000-0000-0000-000000000001").fold(fail(_), identity)

  test("mount initialization gates queries and repeated connect reuses one socket") {
    val h = new Harness()
    val sessions = h.client.sessions()
    assertEquals(h.sockets.size, 0)
    for {
      _ <- h.initialize()
      request <- h.awaitRequest(SessionsList.method)
      _ = h.reply(request, Json.Arr())
      result <- sessions
      _ <- h.client.connect()
    } yield {
      assertEquals(result, Nil)
      assertEquals(h.sockets.size, 1)
      assertEquals(h.sent.head.method, Initialize.method)
      h.client.close()
    }
  }

  test("connection attempts have a deadline even before the socket opens") {
    val h = new Harness(timeout = 30)
    h.client.connect().failed.map { error =>
      assertEquals(error.getMessage, "Connection timed out")
      assert(h.sockets.last.closed)
      assertEquals(h.sent.size, 0)
      h.client.close()
    }
  }

  test("concurrent replies correlate by ID even when arriving in reverse order") {
    val h = new Harness()
    for {
      _ <- h.initialize()
      sessions = h.client.sessions()
      agents = h.client.agents()
      sessionRequest <- h.awaitRequest(SessionsList.method)
      agentRequest <- h.awaitRequest(AgentsList.method)
      _ = h.reply(agentRequest, Json.Arr(Json.Str("agent1")))
      _ = h.reply(sessionRequest, Json.Arr())
      result <- sessions.zip(agents)
    } yield {
      assertEquals(result, (Nil, List("agent1")))
      h.client.close()
    }
  }

  test("close fails pending requests; explicit reconnect does not replay old queries") {
    val h = new Harness()
    for {
      _ <- h.initialize()
      old = h.client.sessions()
      oldRequest <- h.awaitRequest(SessionsList.method)
      oldSocket = h.sockets.head
      _ = h.client.close()
      error <- old.failed
      _ <- h.initialize()
      _ = oldSocket.events.message(JsonRpc.Success(oldRequest.id, Json.Arr()).toJson)
      agents = h.client.agents()
      request <- h.awaitRequest(AgentsList.method)
      _ = h.reply(request, Json.Arr())
      result <- agents
    } yield {
      assert(error.getMessage.contains("closed"))
      assertEquals(h.sent.count(_.method == SessionsList.method), 1)
      assertEquals(h.sockets.size, 2)
      assertEquals(result, Nil)
      h.client.close()
    }
  }

  test("timeouts discard pending entries and late replies do not interfere with later queries") {
    val h = new Harness(timeout = 30)
    for {
      _ <- h.initialize()
      timed = h.client.sessions()
      request <- h.awaitRequest(SessionsList.method)
      error <- timed.failed
      _ = h.reply(request, Json.Arr())
      agents = h.client.agents()
      next <- h.awaitRequest(AgentsList.method)
      _ = h.reply(next, Json.Arr(Json.Str("alive")))
      result <- agents
    } yield {
      assertEquals(error.getMessage, "Request timed out")
      assertEquals(result, List("alive"))
      h.client.close()
    }
  }

  test("remote errors become display errors; malformed envelopes close and fail pending queries") {
    val h = new Harness()
    for {
      _ <- h.initialize()
      rejected = h.client.sessions()
      request <- h.awaitRequest(SessionsList.method)
      _ = h.sockets.last.events.message(JsonRpc.Failure(request.id, JsonRpc.Error(-32004, "Not found")).toJson)
      remote <- rejected.failed
      pending = h.client.agents()
      _ <- h.awaitRequest(AgentsList.method)
      _ = h.sockets.last.events.message("{} {}")
      malformed <- pending.failed
    } yield {
      assertEquals(remote.getMessage, "Not found")
      assert(malformed.getMessage.nonEmpty)
      assert(h.sockets.last.closed)
      h.client.close()
    }
  }

  test("blob downloads reconstruct bytes from bounded chunks using consistent offsets") {
    val h = new Harness(chunkSize = 2)
    for {
      _ <- h.initialize()
      _ = h.onRequest = request => {
        val params = decode[BlobsRead.Params](request.params.toJson)
        h.reply(
          request,
          if params.offset == 0 then BlobsRead.Result(blob, 0, "AAE=", 3, false)
          else BlobsRead.Result(blob, 2, "/w==", 3, true)
        )
      }
      bytes <- h.client.readDownload(blob)
    } yield {
      assertEquals(bytes.toList, List[Byte](0, 1, -1))
      val params =
        h.sent.filter(_.method == BlobsRead.method).map(request => decode[BlobsRead.Params](request.params.toJson))
      assertEquals(params.map(_.offset).toList, List(0L, 2L))
      assert(params.forall(_.maxBytes == 2))
      h.client.close()
    }
  }

  test("invalid/oversized partial transfers fail instead of looping or collecting unbounded bytes") {
    val invalid = List(
      BlobsRead.Result(blob, 1, "AAE=", 3, false),
      BlobsRead.Result(blob, 0, "", 3, false),
      BlobsRead.Result(blob, 0, "AAE=", 3, true),
      BlobsRead.Result(blob, 0, "invalid-base64!", 3, true),
      BlobsRead.Result(blob, 0, "AAE=", 1025, false)
    )
    invalid.foldLeft(Future.successful(())) { (previous, chunk) =>
      previous.flatMap { _ =>
        val h = new Harness(maxBytes = 1024)
        for {
          _ <- h.initialize()
          _ = h.onRequest = request => h.reply(request, chunk)
          error <- h.client.readDownload(blob).failed
        } yield {
          assert(error.getMessage.nonEmpty)
          assertEquals(h.sent.count(_.method == BlobsRead.method), 1)
          h.client.close()
        }
      }
    }
  }

  test("closing during a partial transfer prevents subsequent chunk requests") {
    val h = new Harness(chunkSize = 2)
    for {
      _ <- h.initialize()
      _ = h.onRequest = request => {
        h.reply(request, BlobsRead.Result(blob, 0, "AAE=", 3, false))
        h.client.close()
      }
      error <- h.client.readDownload(blob).failed
    } yield {
      assert(error.getMessage.contains("interrupted"))
      assertEquals(h.sent.count(_.method == BlobsRead.method), 1)
    }
  }

  test(
    "claimed identity, advertised capabilities, create and diagnostic share protocol DTOs; turn has no read deadline"
  ) {
    val h = new Harness(timeout = 40)
    val user = OrchestratorWSClient.claimedUser(" Alice ")
    val sessionId = SessionId.unsafe("chat")
    val snapshot = SessionSnapshot(sessionId, Chunk.empty, Chunk.empty)
    for {
      info <- h.initialize(user, List(SessionsCreate.method, TurnSubmit.method), Some(Chunk(AgentId("assistant"))))
      _ = assertEquals(decode[Initialize.Params](h.sent.head.params.toJson).userId, user)
      _ = assert(OrchestratorWSClient.canChat(info))
      _ <- h.client.connect(OrchestratorWSClient.claimedUser("Ignored until reconnect"))
      create = h.client.createSession(Some(sessionId))
      createRequest <- h.awaitRequest(SessionsCreate.method)
      _ = assertEquals(decode[SessionsCreate.Params](createRequest.params.toJson).name, Some(sessionId))
      _ = h.reply(createRequest, snapshot)
      _ <- create
      diagnostic = h.client.diagnostic(sessionId)
      diagnosticRequest <- h.awaitRequest(DiagnosticsRequest.method)
      _ = h.reply(diagnosticRequest, DiagnosticsRequest.Result(None))
      _ <- diagnostic
      turn = h.client.submitTurn(TurnSubmit.Params(sessionId, AgentId("assistant"), AgentTask("hello")))
      turnRequest <- h.awaitRequest(TurnSubmit.method)
      read = h.client.agents()
      _ <- h.awaitRequest(AgentsList.method)
      _ <- read.failed
      _ = assert(!turn.isCompleted, "turn must remain pending beyond a read timeout")
      _ = h.reply(
        turnRequest,
        TurnOutcome.Failed(PromptAcceptance.NotAccepted, Some(snapshot), ApplicationError("test", "test")): TurnOutcome
      )
      _ <- turn
    } yield {
      assertEquals(h.sent.count(_.method == Initialize.method), 1)
      assertEquals(info.configuredAgentIds, Some(Chunk(AgentId("assistant"))))
      assertEquals(OrchestratorWSClient.claimedUser("  "), None)
      h.client.close()
    }
  }

  test("agents.invoke uses Message codecs without a read deadline, while generic upload commands remain bounded") {
    val h = new Harness(timeout = 30)
    val input = Message(MessageData(AgentId("human"), None, Instant.EPOCH, Message.User("hello")))
    val output =
      Message(MessageData(AgentId("Transcriber"), Some(input.id), Instant.EPOCH, Message.Assistant("transcript")))
    val blobId = BlobId.fresh()
    for {
      _ <- h.initialize()
      begun = h.client.uploadBlob(BlobsUpload.Params.Begin(blobId, 46))
      uploadRequest <- h.awaitRequest(BlobsUpload.method)
      _ = h.reply(uploadRequest, BlobsUpload.Result.Begun(blobId): BlobsUpload.Result)
      _ <- begun
      invocation = h.client.invokeAgent(AgentsInvoke.Params(AgentId("Transcriber"), input))
      request <- h.awaitRequest(AgentsInvoke.method)
      _ = assertEquals(decode[AgentsInvoke.Params](request.params.toJson).input, input)
      read = h.client.agents()
      _ <- h.awaitRequest(AgentsList.method)
      _ <- read.failed
      _ = assert(!invocation.isCompleted)
      _ = h.reply(request, output)
      result <- invocation
    } yield { assertEquals(result, output); h.client.close() }
  }

  test("a request queued before disconnect is not sent over a replacement socket") {
    val h = new Harness()
    for {
      _ <- h.initialize()
      old = h.client.agents()
      _ = h.client.close()
      _ <- h.initialize()
      _ <- old.failed
    } yield {
      assertEquals(h.sent.count(_.method == AgentsList.method), 0)
      h.client.close()
    }
  }

  private def decode[A: JsonDecoder](text: String): A = text.fromJson[A].fold(fail(_), identity)
}

private[web] object OrchestratorWSClientSuite {
  final class FakeSocket(val events: OrchestratorWSClient.Events, sent: String => Unit)
      extends OrchestratorWSClient.Socket {
    var closed = false
    def send(text: String): Unit = sent(text)
    def close(): Unit = { closed = true; events.closed() }
  }

  final class Harness(timeout: Int = 1000, maxBytes: Long = 1024, chunkSize: Int = MaxBlobChunkBytes) {
    val sockets = mutable.ArrayBuffer.empty[FakeSocket]
    val sent = mutable.ArrayBuffer.empty[JsonRpc.Request[Json.Obj]]
    var onRequest: JsonRpc.Request[Json.Obj] => Unit = _ => ()
    val client = new OrchestratorWSClient(
      "ws://test/rpc",
      (_, events) => {
        val socket = new FakeSocket(
          events,
          text => {
            val request = decode[JsonRpc.Request[Json.Obj]](text)
            sent += request
            onRequest(request)
          }
        )
        sockets += socket
        socket
      },
      timeout,
      maxBytes
    )

    def initialize(
        userId: Option[AgentId] = None,
        methods: List[String] = Nil,
        configured: Option[Chunk[AgentId]] = None
    ): Future[Initialize.Result] = {
      val connected = client.connect(userId)
      sockets.last.events.opened()
      reply(
        sent.last,
        Initialize.Result(
          Version,
          "test",
          Identity(AgentId("viewer"), Role.Viewer),
          methods,
          Nil,
          chunkSize,
          configured
        )
      )
      connected
    }

    def reply[A: JsonCodec](request: JsonRpc.Request[Json.Obj], result: A): Unit =
      sockets.last.events.message(JsonRpc.Success(request.id, result).toJson)

    def awaitRequest(method: String, skip: Int = 0): Future[JsonRpc.Request[Json.Obj]] = {
      val result = Promise[JsonRpc.Request[Json.Obj]]()
      def poll(remaining: Int): Unit = sent.filter(_.method == method).drop(skip).headOption match {
        case Some(request)         => result.trySuccess(request); ()
        case None if remaining > 0 => setTimeout(2)(poll(remaining - 1)); ()
        case _                     => result.tryFailure(new AssertionError(s"No request for $method")); ()
      }
      poll(250)
      result.future
    }
  }

  private def decode[A: JsonDecoder](text: String): A =
    text.fromJson[A].fold(error => throw new AssertionError(error), identity)
}
