package fmgp.yallmo.view.web

import fmgp.yallmo.model.{AgentId, BlobId}
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

  private final class FakeSocket(val events: OrchestratorWSClient.Events, sent: String => Unit)
      extends OrchestratorWSClient.Socket {
    var closed = false
    def send(text: String): Unit = sent(text)
    def close(): Unit = { closed = true; events.closed() }
  }

  private final class Harness(timeout: Int = 1000, maxBytes: Long = 1024, chunkSize: Int = MaxBlobChunkBytes) {
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

    def initialize(): Future[Unit] = {
      val connected = client.connect()
      sockets.last.events.opened()
      reply(
        sent.last,
        Initialize.Result(Version, "test", Identity(AgentId("viewer"), Role.Viewer), Nil, Nil, chunkSize)
      )
      connected.map(_ => ())
    }

    def reply[A: JsonCodec](request: JsonRpc.Request[Json.Obj], result: A): Unit =
      sockets.last.events.message(JsonRpc.Success(request.id, result).toJson)

    def awaitRequest(method: String): Future[JsonRpc.Request[Json.Obj]] = {
      val result = Promise[JsonRpc.Request[Json.Obj]]()
      def poll(remaining: Int): Unit = sent.find(_.method == method) match {
        case Some(request)         => result.trySuccess(request); ()
        case None if remaining > 0 => setTimeout(2)(poll(remaining - 1)); ()
        case _                     => result.tryFailure(new AssertionError(s"No request for $method")); ()
      }
      poll(250)
      result.future
    }
  }

  private def decode[A: JsonDecoder](text: String): A = text.fromJson[A].fold(fail(_), identity)
}
