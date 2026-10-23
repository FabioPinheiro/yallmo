package fmgp.yallmo.server

import fmgp.yallmo.model.Agent
import fmgp.yallmo.orchestration.{OrchestratorLive, RuntimeInstances}
import java.net.{InetSocketAddress, Socket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import munit.FunSuite
import zio.*

final class OrchestratorWebHostSuite extends FunSuite {
  private val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build()
  override def afterAll(): Unit = http.close()

  private def start: ZIO[Scope, Throwable, Int] =
    for {
      registry <- RuntimeInstances.fromAgents(Chunk.empty[Agent[Any]])
      master <- OrchestratorLive.inMemory(registry)
      port <- new OrchestratorWSServer(master).start(0)
    } yield port

  test("the WebSocket host serves packaged SPA assets and security headers") {
    run(ZIO.scoped {
      for {
        port <- start
        page <- get(port, "/")
        script <- get(port, "/assets/test.js")
      } yield {
        assertEquals(page.statusCode(), 200)
        assert(page.body().contains("app"))
        assertEquals(page.headers().firstValue("Content-Type").orElse(""), "text/html; charset=utf-8")
        assertEquals(script.statusCode(), 200)
        assertEquals(script.headers().firstValue("Content-Type").orElse(""), "text/javascript; charset=utf-8")
        assertEquals(page.headers().firstValue("Cache-Control").orElse(""), "no-store")
        assertEquals(page.headers().firstValue("X-Content-Type-Options").orElse(""), "nosniff")
        assertEquals(page.headers().firstValue("X-Frame-Options").orElse(""), "DENY")
        assert(page.headers().firstValue("Content-Security-Policy").orElse("").contains(s"ws://127.0.0.1:$port"))
        assert(page.headers().firstValue("Access-Control-Allow-Origin").isEmpty)
      }
    })
  }

  test("REST endpoints are gone; unknown paths/resources never fall back to SPA HTML") {
    run(ZIO.scoped {
      for {
        port <- start
        responses <- ZIO.foreach(
          List(
            "/api/messages",
            "/api/sessions",
            "/api/agents",
            "/api/blobs",
            "/api/blobs/id",
            "/api/sessions/main",
            "/assets/missing.js",
            "/assets/../index.html",
            "/fmgp/yallmo/server/OrchestratorWSServer.class"
          )
        )(path => get(port, path))
      } yield responses.foreach { response =>
        assertEquals(response.statusCode(), 404)
        assert(!response.body().contains("<html"))
      }
    })
  }

  test("host/origin validation protects static assets as well as WebSocket upgrades") {
    run(ZIO.scoped {
      for {
        port <- start
        foreign <- get(port, "/", List("Origin" -> "https://evil.invalid"))
        nullOrigin <- get(port, "/assets/test.js", List("Origin" -> "null"))
        vite <- get(port, "/assets/test.js", List("Origin" -> "http://127.0.0.1:5173"))
        wrongHost <- ZIO.attemptBlocking {
          val socket = new Socket("127.0.0.1", port)
          try {
            socket.setSoTimeout(3000)
            socket.getOutputStream.write(
              "GET / HTTP/1.1\r\nHost: evil.invalid:8080\r\nConnection: close\r\n\r\n".getBytes(UTF_8)
            )
            new String(socket.getInputStream.readAllBytes(), UTF_8)
          } finally socket.close()
        }
      } yield {
        assertEquals(foreign.statusCode(), 403)
        assertEquals(nullOrigin.statusCode(), 403)
        assertEquals(vite.statusCode(), 200)
        assert(wrongHost.startsWith("HTTP/1.1 403"))
      }
    })
  }

  test("HTTP writes are rejected and the listener is scoped") {
    run {
      for {
        scope <- Scope.make
        port <- start.provideEnvironment(ZEnvironment(scope))
        response <- ZIO.attemptBlocking(
          http.send(
            HttpRequest
              .newBuilder(URI.create(s"http://127.0.0.1:$port/rpc"))
              .POST(HttpRequest.BodyPublishers.noBody())
              .build(),
            HttpResponse.BodyHandlers.ofString()
          )
        )
        _ <- scope.close(Exit.unit)
        refused <- ZIO.attemptBlocking {
          val socket = new Socket()
          try socket.connect(new InetSocketAddress("127.0.0.1", port), 1000)
          finally socket.close()
        }.either
      } yield {
        assertEquals(response.statusCode(), 405)
        assertEquals(response.headers().firstValue("Allow").orElse(""), "GET")
        assert(refused.isLeft)
      }
    }
  }

  test("the new standalone entry point retains validated storage/port options") {
    assertEquals(
      OrchestratorServerMain.parse(List("--storage", "sessions")),
      Right(OrchestratorServerMain.Options(Path.of("sessions")))
    )
    assertEquals(
      OrchestratorServerMain.parse(List("--storage", "sessions", "--port", "8081")),
      Right(OrchestratorServerMain.Options(Path.of("sessions"), 8081))
    )
    List("0", "65536", "not-a-number").foreach { port =>
      assert(OrchestratorServerMain.parse(List("--storage", "sessions", "--port", port)).isLeft)
    }
    assert(OrchestratorServerMain.parse(Nil).isLeft)
  }

  private def get(port: Int, path: String, headers: List[(String, String)] = Nil): Task[HttpResponse[String]] =
    ZIO.attemptBlocking {
      val request = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
      headers.foreach { case (name, value) => request.header(name, value) }
      http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

  private def run[A](effect: ZIO[Any, Any, A]): A =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe
        .run(effect.timeoutFail(new AssertionError("Web host test timed out"))(10.seconds))
        .getOrThrowFiberFailure()
    }
}
