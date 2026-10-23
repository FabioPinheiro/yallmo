package fmgp.yallmo.view

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.view.ViewProtocol.*
import java.net.{InetSocketAddress, Socket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import zio.*
import zio.json.*

final class OrchestratorViewServerSuite extends FunSuite {
  private val author = AgentId("human")
  private val sessionId = SessionId.unsafe("session-one")
  private val agent = new Agent[Any] {
    val id: AgentId = author
    def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
      ZIO.die(new AssertionError("The viewer must never invoke agents"))
  }
  private val client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build()

  private def live: IO[YallmoError, Orchestrator[Any]] =
    for {
      registry <- RuntimeInstances.fromAgents(Chunk(agent))
      orchestrator <- OrchestratorLive.inMemory(registry)
      _ <- orchestrator.createSession(sessionId, Chunk(author))
    } yield orchestrator

  test("reads the supplied live instance and sees subsequent appends without invoking agents") {
    run(ZIO.scoped {
      for {
        orchestrator <- live
        _ <- orchestrator.append(sessionId, author, Chunk(MessagePayload.User("<script>alert('text only')</script>")))
        port <- new OrchestratorViewServer(orchestrator).start(0)
        listing <- get(port, "/api/sessions")
        first <- get(port, "/api/sessions/session-one")
        _ <- orchestrator.append(sessionId, author, Chunk(MessagePayload.Assistant("new response")))
        latest <- get(port, "/api/sessions/session-one")
      } yield {
        assertEquals(listing.statusCode(), 200)
        assertEquals(decode[List[SessionSummaryView]](listing).map(_.messageCount), List(1L))
        val before = decode[SessionView](first)
        val after = decode[SessionView](latest)
        assertEquals(before.agentIds, List("human"))
        assertEquals(before.messages.head.role, "user")
        assertEquals(before.messages.head.content, "<script>alert('text only')</script>")
        assertEquals(after.messages.map(_.content), List(before.messages.head.content, "new response"))
        assertEquals(after.messages.last.parentId, Some(before.messages.head.id))
        assertEquals(latest.headers().firstValue("Cache-Control").orElse(""), "no-store")
        assertEquals(latest.headers().firstValue("X-Content-Type-Options").orElse(""), "nosniff")
        assert(latest.headers().firstValue("Access-Control-Allow-Origin").isEmpty)
      }
    })
  }

  test("global messages deduplicate shared history, preserve tool names and list only actual stored blobs") {
    run(ZIO.scoped {
      for {
        orchestrator <- live
        _ <- orchestrator.append(sessionId, author, Chunk(MessagePayload.User("shared")))
        _ <- orchestrator.fork(sessionId, SessionId.unsafe("fork"))
        callId = FunctionCallId.unsafe("call-one")
        _ <- orchestrator.append(
          sessionId,
          author,
          Chunk(
            MessagePayload.FunctionCallRequest(FunctionCall(callId, "inspect", """{"path":"."}""")),
            MessagePayload.FunctionCallOutput(FunctionCallOutput(callId, "inspect", """{"ok":true}"""))
          )
        )
        blob = BlobId.fresh()
        missing = BlobId.fresh()
        _ <- orchestrator.appendWithBlobBytes(
          sessionId,
          author,
          MessagePayload.AudioAttachment(blob, AudioFormat.Wav),
          Chunk.empty
        )
        _ <- orchestrator.append(sessionId, author, Chunk(MessagePayload.AudioAttachment(missing, AudioFormat.Wav)))
        port <- new OrchestratorViewServer(orchestrator).start(0)
        messages <- get(port, "/api/messages")
        agents <- get(port, "/api/agents")
        blobs <- get(port, "/api/blobs")
      } yield {
        val all = decode[List[MessageView]](messages)
        assertEquals(all.size, 5)
        assertEquals(all.count(_.content == "shared"), 1)
        assertEquals(all.filter(_.role.startsWith("tool_")).map(_.toolName), List(Some("inspect"), Some("inspect")))
        assertEquals(all.find(_.role == "tool_call").map(_.content), Some("""{"path":"."}"""))
        assertEquals(decode[List[String]](agents), List("human"))
        assertEquals(decode[List[String]](blobs), List(blob.value))
      }
    })
  }

  test("attachment references and binary downloads preserve bytes without interpreting content") {
    run(ZIO.scoped {
      for {
        orchestrator <- live
        blobId = BlobId.fresh()
        bytes = Chunk[Byte](0, 1, 2, 127, -1)
        _ <- orchestrator.appendWithBlobBytes(
          sessionId,
          author,
          MessagePayload.AudioAttachment(blobId, AudioFormat.Wav),
          bytes
        )
        port <- new OrchestratorViewServer(orchestrator).start(0)
        session <- get(port, "/api/sessions/session-one")
        blob <- request(port, s"/api/blobs/${blobId.value}")
        missing <- get(port, s"/api/blobs/${BlobId.fresh().value}")
        invalid <- get(port, "/api/blobs/not-a-uuid")
      } yield {
        assertEquals(decode[SessionView](session).messages.head.blobIds, List(blobId.value))
        assertEquals(blob.statusCode(), 200)
        assertEquals(blob.body().toList, bytes.toList)
        assertEquals(blob.headers().firstValue("Content-Type").orElse(""), "application/octet-stream")
        assertEquals(
          blob.headers().firstValue("Content-Disposition").orElse(""),
          s"""attachment; filename="${blobId.value}""""
        )
        assertEquals(missing.statusCode(), 404)
        assertEquals(decode[ApiError](missing).code, "blob_not_found")
        assertEquals(invalid.statusCode(), 400)
      }
    })
  }

  test("read-only boundary, validated IDs and unknown routes return API errors, never SPA HTML") {
    run(ZIO.scoped {
      for {
        orchestrator <- live
        port <- new OrchestratorViewServer(orchestrator).start(0)
        missing <- get(port, "/api/sessions/missing")
        invalid <- get(port, "/api/sessions/invalid!")
        unknown <- get(port, "/api/unknown")
        nested <- get(port, "/api/sessions/missing/extra")
        writes <- ZIO.foreach(List("POST", "PUT", "PATCH", "DELETE"))(method =>
          request(port, "/api/sessions", method = method)
        )
        listing <- get(port, "/api/sessions")
      } yield {
        assertEquals(missing.statusCode(), 404)
        assertEquals(decode[ApiError](missing).code, "session_not_found")
        assertEquals(invalid.statusCode(), 400)
        assertEquals(unknown.statusCode(), 404)
        assertEquals(nested.statusCode(), 404)
        assert(writes.forall(_.statusCode() == 405))
        assert(writes.forall(_.headers().firstValue("Allow").orElse("") == "GET"))
        assertEquals(decode[List[SessionSummaryView]](listing).map(_.messageCount), List(0L))
      }
    })
  }

  test("storage failures do not reveal internal paths or exception messages") {
    run(ZIO.scoped {
      for {
        delegate <- live
        broken = new Orchestrator[Any] {
          export delegate.{getAgent, submit, createSession, loadSession, append, fork}
          def listSessions: IO[YallmoError, Chunk[SessionSummary]] =
            ZIO.fail(YallmoError.StorageFailure("/private/secret/path", new Exception("secret-token")))
          @deprecated("Test-only implementation", "2.0.0")
          def turn(id: AgentId, request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
            ZIO.dieMessage("The viewer must not execute agents")
        }
        port <- new OrchestratorViewServer(broken).start(0)
        response <- get(port, "/api/sessions")
      } yield {
        assertEquals(response.statusCode(), 500)
        assertEquals(decode[ApiError](response).code, "storage_error")
        assert(!response.body().contains("private"))
        assert(!response.body().contains("secret"))
      }
    })
  }

  test("serves only packaged SPA assets with correct MIME types, not arbitrary resources or fallbacks") {
    run(ZIO.scoped {
      for {
        orchestrator <- live
        port <- new OrchestratorViewServer(orchestrator).start(0)
        page <- get(port, "/")
        script <- get(port, "/assets/test.js")
        unknown <- get(port, "/assets/missing.js")
        resources <- get(port, "/fmgp/yallmo/view/ViewProtocol.class")
        traversal <- get(port, "/assets/../index.html")
      } yield {
        assertEquals(page.statusCode(), 200)
        assert(page.body().contains("<div id=\"app\">"))
        assertEquals(page.headers().firstValue("Content-Type").orElse(""), "text/html; charset=utf-8")
        assertEquals(script.statusCode(), 200)
        assertEquals(script.headers().firstValue("Content-Type").orElse(""), "text/javascript; charset=utf-8")
        assertEquals(unknown.statusCode(), 404)
        assertEquals(resources.statusCode(), 404)
        assertEquals(traversal.statusCode(), 404)
      }
    })
  }

  test("rejects foreign Origin and Host while permitting the loopback Vite proxy without CORS") {
    run(ZIO.scoped {
      for {
        orchestrator <- live
        port <- new OrchestratorViewServer(orchestrator).start(0)
        foreign <- get(port, "/api/sessions", List("Origin" -> "https://attacker.invalid"))
        nullOrigin <- get(port, "/api/sessions", List("Origin" -> "null"))
        dev <- get(port, "/api/sessions", List("Origin" -> "http://127.0.0.1:5173"))
        host <- ZIO.attemptBlocking {
          val socket = new Socket("127.0.0.1", port)
          try {
            socket.setSoTimeout(3000)
            socket.getOutputStream.write(
              "GET /api/sessions HTTP/1.1\r\nHost: attacker.invalid\r\nConnection: close\r\n\r\n".getBytes(UTF_8)
            )
            new String(socket.getInputStream.readAllBytes(), UTF_8)
          } finally socket.close()
        }
      } yield {
        assertEquals(foreign.statusCode(), 403)
        assertEquals(nullOrigin.statusCode(), 403)
        assertEquals(dev.statusCode(), 200)
        assert(dev.headers().firstValue("Access-Control-Allow-Origin").isEmpty)
        assert(host.startsWith("HTTP/1.1 403"), host.take(100))
      }
    })
  }

  test("listener lifetime belongs to the caller's scope") {
    run {
      for {
        scope <- Scope.make
        orchestrator <- live
        port <- new OrchestratorViewServer(orchestrator).start(0).provideEnvironment(ZEnvironment[Scope](scope))
        _ <- scope.close(Exit.unit)
        stopped <- ZIO.attemptBlocking {
          val socket = new Socket()
          try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000)
            false
          } catch { case _: java.io.IOException => true }
          finally socket.close()
        }
      } yield assert(stopped)
    }
  }

  test("standalone CLI requires a storage path and a valid port") {
    assert(ViewServerMain.parse(List("--storage", "./zzz")).isRight)
    assertEquals(ViewServerMain.parse(List("--storage", "./zzz", "--port", "8081")).map(_._2), Right(8081))
    List(
      Nil,
      List("--storage"),
      List("--storage", "./zzz", "--port", "0"),
      List("--storage", "./zzz", "--port", "65536"),
      List("--config", "secrets.json")
    )
      .foreach(args => assert(ViewServerMain.parse(args).isLeft))
  }

  private def get(port: Int, path: String, headers: List[(String, String)] = Nil): Task[HttpResponse[String]] =
    ZIO.attemptBlocking {
      val builder = HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
        .timeout(java.time.Duration.ofSeconds(5))
        .GET()
      headers.foreach { case (name, value) => builder.header(name, value) }
      client.send(builder.build(), HttpResponse.BodyHandlers.ofString(UTF_8))
    }

  private def request(
      port: Int,
      path: String,
      method: String = "GET",
      headers: List[(String, String)] = Nil
  ): Task[HttpResponse[Array[Byte]]] = ZIO.attemptBlocking {
    val builder = HttpRequest
      .newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
      .timeout(java.time.Duration.ofSeconds(5))
      .method(method, HttpRequest.BodyPublishers.noBody())
    headers.foreach { case (name, value) => builder.header(name, value) }
    client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
  }

  private def decode[A: JsonDecoder](response: HttpResponse[String]): A =
    response.body().fromJson[A].fold(message => fail(message), identity)

  private def run[A](effect: Task[A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
