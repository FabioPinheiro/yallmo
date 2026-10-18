package fmgp.yallmo.repl.browser

import munit.FunSuite
import zio.*
import zio.http.Server

import java.io.{ByteArrayInputStream, IOException}
import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8

final class BrowserAudioBridgeSuite extends FunSuite {
  // Tests use isolated ephemeral ports; the REPL defaults to 5180.
  private def makeBridge: ZIO[Scope, BrowserAudioError, BrowserAudioBridge] =
    BrowserAudioBridge.start(zio.http.Server.Config.default.binding("127.0.0.1", 0))

  import BrowserAudioTestSupport.*

  private val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build()

  test("default browser audio binding is all interfaces on port 5180") {
    assertEquals(BrowserAudioBridge.defaultConfig.address.getHostString, "0.0.0.0")
    assertEquals(BrowserAudioBridge.defaultConfig.address.getPort, 5180)
  }

  test("configured port is used in the listener and setup URL; invalid ports fail locally") {
    run(ZIO.scoped {
      for {
        port <- ZIO.acquireReleaseWith(ZIO.attempt(new ServerSocket(0)))(socket => ZIO.succeed(socket.close()))(
          socket => ZIO.succeed(socket.getLocalPort)
        )
        bridge <- BrowserAudioBridge.make(port)
        page <- request(bridge, "/", authenticated = false)
        invalid <- ZIO.foreach(List(-1, 0, 65536))(port => BrowserAudioBridge.make(port).either)
      } yield {
        assertEquals(URI.create(base(bridge)).getPort, port)
        assertEquals(page.statusCode(), 200)
        assert(invalid.forall(result => code(result) == "startup"))
      }
    })
  }

  test("Docker wildcard listener keeps the loopback setup URL and Host/Origin security boundary") {
    run(ZIO.scoped {
      for {
        bridge <- BrowserAudioBridge.start(BrowserAudioBridge.defaultConfig.binding("0.0.0.0", 0))
        page <- request(bridge, "/", authenticated = false)
        connected <- request(bridge, "/api/connect", Some(connect), headers = List("Origin" -> base(bridge)))
        foreign <- request(bridge, "/api/poll", headers = List("Origin" -> "http://foreign.invalid:5180"))
        host <- raw(bridge, "GET", "/", "attacker.invalid", "", Array.emptyByteArray)
      } yield {
        assertEquals(URI.create(base(bridge)).getHost, "127.0.0.1")
        assertEquals(page.statusCode(), 200)
        assertEquals(connected.statusCode(), 200)
        assertEquals(foreign.statusCode(), 403)
        assert(host.contains("403"), host)
        assert(connected.headers().firstValue("Access-Control-Allow-Origin").isEmpty)
      }
    })
  }

  test("real HTTP enforces token, Host, Origin, single client, no CORS and bounded JSON") {
    run(ZIO.scoped {
      for {
        bridge <- makeBridge
        missing <- request(bridge, "/api/connect", Some(connect), authenticated = false)
        badToken <- request(
          bridge,
          "/api/connect",
          Some(connect),
          headers = List("Authorization" -> "Bearer wrong"),
          authenticated = false
        )
        foreign <- request(bridge, "/api/connect", Some(connect), headers = List("Origin" -> "https://foreign.invalid"))
        nullOrigin <- request(bridge, "/api/connect", Some(connect), headers = List("Origin" -> "null"))
        host <- raw(bridge, "GET", "/", "attacker.invalid", "", Array.emptyByteArray)
        connected <- request(bridge, "/api/connect", Some(connect), headers = List("Origin" -> base(bridge)))
        idle <- request(bridge, "/api/poll")
        conflict <- request(bridge, "/api/connect", Some(s"""{"clientId":"$OtherClient"}"""))
        wrong <- request(bridge, "/api/poll", clientId = OtherClient)
        jsonCap <- request(bridge, "/api/event", Some("x" * 4097))
        malformed <- request(bridge, "/api/event", Some("not json"))
        asset <- request(bridge, "/not-found.js", authenticated = false)
      } yield {
        assertEquals(missing.statusCode(), 401)
        assertEquals(missing.body(), """{"error":"unauthorized"}""")
        assertEquals(idle.body(), """{"heartbeatMs":1000}""")
        assertEquals(badToken.statusCode(), 401)
        assertEquals(foreign.statusCode(), 403)
        assertEquals(nullOrigin.statusCode(), 403)
        assert(host.contains("403"), host)
        assertEquals(connected.statusCode(), 200)
        assert(connected.body().contains("\"maxUploadBytes\":1048576"))
        assertEquals(conflict.statusCode(), 409)
        assertEquals(wrong.statusCode(), 403)
        assertEquals(jsonCap.statusCode(), 413)
        assertEquals(malformed.statusCode(), 400)
        assertEquals(asset.statusCode(), 404)
        assert(connected.headers().firstValue("Access-Control-Allow-Origin").isEmpty)
        assertEquals(connected.headers().firstValue("Cache-Control").orElse(""), "no-store")
        assert(!connected.body().contains(token(bridge)))
      }
    })
  }

  test("real HTTP accepts maximum WAV over the old 100KiB cap; wrong, stale and duplicate uploads fail") {
    run(ZIO.scoped {
      for {
        bridge <- makeBridge
        _ <- request(bridge, "/api/connect", Some(connect))
        recording <- bridge.recorder.record(30).fork
        poll <- until(request(bridge, "/api/poll"))(_.body().contains("\"kind\":\"record\""))
        id = operationId(poll.body())
        before <- bridge.status
        premature <- binary(bridge, id, wav(32).toArray)
        wrong <- binary(bridge, id, wav(32).toArray, OtherClient)
        started <- request(bridge, "/api/event", Some(s"""{"operationId":"$id","event":"started"}"""))
        ready <- bridge.status
        uploaded <- binary(bridge, id, wav(960000).toArray)
        audio <- recording.join
        duplicate <- binary(bridge, id, wav(32).toArray)
        stale <- request(bridge, "/api/event", Some(s"""{"operationId":"$id","event":"started"}"""))
      } yield {
        assert(!before.recordingStarted)
        assertEquals(premature.statusCode(), 409)
        assertEquals(wrong.statusCode(), 403)
        assertEquals(started.statusCode(), 200)
        assert(ready.recordingStarted)
        assertEquals(uploaded.statusCode(), 200)
        assertEquals(audio.bytes.size, 960044)
        assertEquals(duplicate.statusCode(), 409)
        assertEquals(stale.statusCode(), 409)
      }
    })
  }

  test("real transport rejects oversized content-length and chunked bodies before route allocation") {
    run(ZIO.scoped {
      for {
        bridge <- makeBridge
        length <- raw(
          bridge,
          "POST",
          "/api/connect",
          authority(bridge),
          s"Content-Length: ${BrowserProtocol.MaxUploadBytes + 1}\r\n",
          Array.emptyByteArray
        )
        chunk = Array.fill[Byte](BrowserProtocol.MaxUploadBytes + 1)(0)
        chunked <- requestChunked(bridge, chunk)
        healthy <- request(bridge, "/api/connect", Some(connect))
      } yield {
        assert(length.contains("413"), length)
        assertEquals(chunked.statusCode(), 413)
        assertEquals(healthy.statusCode(), 200)
      }
    })
  }

  test("cancellation during an in-flight upload discards bytes and rejects the late request") {
    run(ZIO.scoped {
      for {
        bridge <- makeBridge
        _ <- request(bridge, "/api/connect", Some(connect))
        recording <- bridge.recorder.record(30).fork
        poll <- until(request(bridge, "/api/poll"))(_.body().contains("\"kind\":\"record\""))
        id = operationId(poll.body())
        _ <- request(bridge, "/api/event", Some(s"""{"operationId":"$id","event":"started"}"""))
        response <- ZIO.acquireReleaseWith(ZIO.attempt(new Socket("127.0.0.1", URI.create(base(bridge)).getPort)))(
          socket => ZIO.succeed(socket.close())
        ) { socket =>
          val bytes = wav(960000).toArray
          for {
            _ <- ZIO.attemptBlocking {
              socket.setSoTimeout(5000)
              val headers = s"POST /api/audio/$id HTTP/1.1\r\nHost: ${authority(bridge)}\r\n" +
                s"Authorization: Bearer ${token(bridge)}\r\nX-Browser-Client: $Client\r\n" +
                s"Content-Length: ${bytes.length}\r\nConnection: close\r\n\r\n"
              socket.getOutputStream.write(headers.getBytes(UTF_8))
              socket.getOutputStream.write(bytes, 0, 44)
              socket.getOutputStream.flush()
            }
            _ <- recording.interrupt
            response <- ZIO.attemptBlocking {
              socket.getOutputStream.write(bytes, 44, bytes.length - 44)
              socket.getOutputStream.flush()
              new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream, UTF_8)).readLine()
            }
          } yield response
        }
        idle <- request(bridge, "/api/poll")
      } yield {
        assert(response.contains("409"), response)
        assert(!idle.body().contains("\"kind\""))
      }
    })
  }

  test("WAV content is validated regardless of MIME; malformed upload fails recorder promptly") {
    run(ZIO.scoped {
      for {
        bridge <- makeBridge
        _ <- request(bridge, "/api/connect", Some(connect))
        recording <- bridge.recorder.record(1).either.fork
        poll <- until(request(bridge, "/api/poll"))(_.body().contains("\"kind\":\"record\""))
        id = operationId(poll.body())
        _ <- request(bridge, "/api/event", Some(s"""{"operationId":"$id","event":"started"}"""))
        response <- binary(bridge, id, "webm pretending to be WAV".getBytes(UTF_8))
        result <- recording.join
        idle <- request(bridge, "/api/poll")
      } yield {
        assertEquals(response.statusCode(), 400)
        assertEquals(code(result), "invalid_wav")
        assert(!idle.body().contains("\"kind\""))
      }
    })
  }

  test("server is scoped and startup bind defects become typed local failures") {
    run {
      for {
        scope <- Scope.make
        bridge <- makeBridge.provideEnvironment(ZEnvironment[Scope](scope))
        _ <- scope.close(Exit.unit)
        closed <- ZIO.attemptBlocking {
          val socket = new Socket()
          try {
            socket.connect(new InetSocketAddress("127.0.0.1", URI.create(base(bridge)).getPort), 1000)
            false
          } catch { case _: IOException => true }
          finally socket.close()
        }
        failure <- ZIO.acquireReleaseWith(ZIO.attempt(new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))))(
          socket => ZIO.succeed(socket.close())
        )(socket =>
          ZIO.scoped(BrowserAudioBridge.start(Server.Config.default.binding("127.0.0.1", socket.getLocalPort))).either
        )
      } yield {
        assert(closed)
        assertEquals(code(failure), "startup")
      }
    }
  }

  private val connect = s"""{"clientId":"$Client"}"""
  private def base(bridge: BrowserAudioBridge): String = bridge.setupUrl.takeWhile(_ != '#').stripSuffix("/")
  private def authority(bridge: BrowserAudioBridge): String = URI.create(base(bridge)).getAuthority
  private def token(bridge: BrowserAudioBridge): String = URI.create(bridge.setupUrl).getFragment.stripPrefix("token=")
  private def operationId(body: String): String = "\"id\":\"([^\"]+)\"".r.findFirstMatchIn(body).get.group(1)

  private def request(
      bridge: BrowserAudioBridge,
      path: String,
      body: Option[String] = None,
      clientId: String = Client,
      headers: List[(String, String)] = Nil,
      authenticated: Boolean = true
  ): Task[HttpResponse[String]] = ZIO.attemptBlocking {
    val builder = HttpRequest
      .newBuilder(URI.create(base(bridge) + path))
      .timeout(java.time.Duration.ofSeconds(5))
      .header("X-Browser-Client", clientId)
    if authenticated then builder.header("Authorization", s"Bearer ${token(bridge)}")
    headers.foreach { case (key, value) => builder.header(key, value) }
    body match {
      case Some(text) =>
        builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(text))
      case None => builder.GET()
    }
    http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
  }

  private def binary(bridge: BrowserAudioBridge, id: String, bytes: Array[Byte], clientId: String = Client) =
    ZIO.attemptBlocking {
      val request = HttpRequest
        .newBuilder(URI.create(base(bridge) + s"/api/audio/$id"))
        .timeout(java.time.Duration.ofSeconds(5))
        .header("Authorization", s"Bearer ${token(bridge)}")
        .header("X-Browser-Client", clientId)
        .header("Content-Type", "audio/wav")
        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
        .build()
      http.send(request, HttpResponse.BodyHandlers.ofString())
    }

  private def requestChunked(bridge: BrowserAudioBridge, bytes: Array[Byte]) = ZIO.attemptBlocking {
    val request = HttpRequest
      .newBuilder(URI.create(base(bridge) + "/api/connect"))
      .timeout(java.time.Duration.ofSeconds(5))
      .header("Authorization", s"Bearer ${token(bridge)}")
      .POST(HttpRequest.BodyPublishers.ofInputStream(() => new ByteArrayInputStream(bytes)))
      .build()
    http.send(request, HttpResponse.BodyHandlers.ofString())
  }

  private def raw(
      bridge: BrowserAudioBridge,
      method: String,
      path: String,
      host: String,
      headers: String,
      bytes: Array[Byte]
  ) =
    ZIO.attemptBlocking {
      val socket = new Socket("127.0.0.1", URI.create(base(bridge)).getPort)
      socket.setSoTimeout(5000)
      try {
        val out = socket.getOutputStream
        out.write(s"$method $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n$headers\r\n".getBytes(UTF_8))
        out.write(bytes)
        out.flush()
        val reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream, UTF_8))
        reader.readLine()
      } finally socket.close()
    }
}
