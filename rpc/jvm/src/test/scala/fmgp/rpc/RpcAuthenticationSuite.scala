package fmgp.rpc

import java.net.Socket
import java.nio.charset.StandardCharsets
import munit.FunSuite
import zio.*
import zio.http.{Handler, Method, Routes, Server}
import zio.json.ast.Json

class RpcAuthenticationSuite extends FunSuite {
  import WebSocketFixtures.*
  override val munitTimeout = scala.concurrent.duration.Duration(40, "seconds")
  private def run[A](effect: ZIO[Scope, Throwable, A]): A = Unsafe.unsafe { _ ?=>
    Runtime.default.unsafe
      .run(ZIO.scoped(effect).timeoutFail(new Exception("test deadline"))(25.seconds))
      .getOrThrowFiberFailure()
  }
  private def host(prefix: String): Calls = new Calls {
    def waitFor(id: Int): Task[Int] = ZIO.succeed(id)
    def fail(): Task[Unit] = ZIO.unit
    def defect(): Task[Unit] = ZIO.unit
    def echo(value: String): Task[String] = ZIO.succeed(prefix + value)
  }
  private def error[A](result: Either[Throwable, A]): Option[RpcError.Code] =
    result.left.toOption.collect { case value: RpcError => value.code }

  // Raw HTTP proves status is returned before upgrade. Never include request/credential in a failure.
  private def request(
      port: Int,
      headers: List[(String, String)],
      upgrade: Boolean = true,
      httpVersion: String = "HTTP/1.1",
      path: String = "/rpc"
  ): Task[Int] =
    ZIO
      .scoped {
        ZIO
          .acquireRelease(ZIO.attemptBlocking(new Socket("127.0.0.1", port)))(socket => ZIO.succeed(socket.close()))
          .flatMap { socket =>
            ZIO.attemptBlocking {
              socket.setSoTimeout(3000)
              val base = if upgrade then
                List(
                  "Host" -> "127.0.0.1",
                  "Upgrade" -> "websocket",
                  "Connection" -> "Upgrade",
                  "Sec-WebSocket-Version" -> "13",
                  "Sec-WebSocket-Key" -> java.util.Base64.getEncoder.encodeToString(new Array[Byte](16))
                )
              else List("Host" -> "127.0.0.1")
              val wire = s"GET $path $httpVersion\r\n" + (base ++ headers).map { case (key, value) =>
                s"$key: $value\r\n"
              }.mkString + "\r\n"
              socket.getOutputStream.write(wire.getBytes(StandardCharsets.US_ASCII))
              socket.getOutputStream.flush()
              val reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(socket.getInputStream, StandardCharsets.US_ASCII)
              )
              reader.readLine().split(" ")(1).toInt
            }
          }
      }
      .mapError(_ => new Exception("HTTP probe failed"))

  test("RpcToken parses canonical secrets, exports explicitly and redacts default rendering and parse errors") {
    val token = run(RpcToken.generate)
    assertEquals(token.toString, "RpcToken(<redacted>)")
    assertEquals(token.exportSecret.length, 43)
    assert(RpcToken.parse(token.exportSecret).exists(_.exportSecret == token.exportSecret))
    assertEquals(java.util.Base64.getUrlDecoder.decode(token.exportSecret).length, 32)
    val invalid = List(null, "", "short", token.exportSecret + "=", "!" * 43, "_" * 43)
    invalid.foreach { value =>
      assertEquals(RpcToken.parse(value).left.map(_.getMessage), Left("Unauthorized"))
    }
    assert(run(RpcToken.generate).exportSecret != token.exportSecret)
  }

  test(
    "empty listener rejects missing, wrong and malformed credentials before any dispatch; later registration works"
  ) {
    val result = run {
      for {
        server <- RpcTestServer.start()
        unknown <- RpcToken.generate
        missing <- request(server.port, Nil)
        wrong <- request(server.port, List("Authorization" -> s"Bearer ${unknown.exportSecret}"))
        malformed <- request(server.port, List("Authorization" -> "Bearer malformed"))
        wrongScheme <- request(server.port, List("Authorization" -> "Basic malformed"))
        invoked <- Ref.make(0)
        dispatcher = calls.bind(new Calls {
          def waitFor(id: Int): Task[Int] = invoked.update(_ + 1).as(id)
          def fail(): Task[Unit] = invoked.update(_ + 1)
          def defect(): Task[Unit] = invoked.update(_ + 1)
          def echo(value: String): Task[String] = invoked.update(_ + 1).as(value)
        })
        token <- server.rpc.register(dispatcher)
        before <- invoked.get
        connection <- RpcWebSocket.connect(server.endpoint, token)
        reply <- calls.client(connection).echo("registered later")
        after <- invoked.get
      } yield (List(missing, wrong, malformed, wrongScheme), before, reply, after)
    }
    assertEquals(result, (List.fill(4)(401), 0, "registered later", 1))
  }

  test("caller-owned server composes authenticated RPC and unrelated routes, with late registration") {
    val result = run {
      for {
        rpc <- RpcWebSocket.route()
        health = Routes(Method.GET / "health" -> Handler.text("ok"))
        http <- Server.defaultWith(_.binding("127.0.0.1", 0).gracefulShutdownTimeout(1.second)).build
        port <- Server.install(rpc.routes ++ health).provideEnvironment(http)
        healthy <- request(port, Nil, upgrade = false, path = "/health")
        denied <- request(port, Nil)
        ticket <- rpc.register(calls.bind(host("custom:")))
        connection <- RpcWebSocket.connect(port, ticket)
        reply <- calls.client(connection).echo("server")
      } yield (healthy, denied, reply)
    }
    assertEquals(result, (200, 401, "custom:server"))
  }

  test("route instances own independent ticket registries even for identical service IDs") {
    val result = run {
      for {
        a <- RpcWebSocket.route()
        b <- RpcWebSocket.route()
        httpA <- Server.defaultWith(_.binding("127.0.0.1", 0).gracefulShutdownTimeout(1.second)).build
        httpB <- Server.defaultWith(_.binding("127.0.0.1", 0).gracefulShutdownTimeout(1.second)).build
        portA <- Server.install(a.routes).provideEnvironment(httpA)
        portB <- Server.install(b.routes).provideEnvironment(httpB)
        ticketA <- a.register(calls.bind(host("A:")))
        ticketB <- b.register(calls.bind(host("B:")))
        wrongRoute <- request(portB, List("Authorization" -> s"Bearer ${ticketA.exportSecret}"))
        wrongRevoke <- b.revoke(ticketA)
        connectionA <- RpcWebSocket.connect(portA, ticketA)
        connectionB <- RpcWebSocket.connect(portB, ticketB)
        replyA <- calls.client(connectionA).echo("own registry")
        replyB <- calls.client(connectionB).echo("own registry")
      } yield (wrongRoute, wrongRevoke, replyA, replyB)
    }
    assertEquals(result, (401, false, "A:own registry", "B:own registry"))
  }

  test("route scope shutdown invalidates pending tickets without stopping the caller-owned server or active sockets") {
    val result = run {
      for {
        routeScope <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        rpc <- RpcWebSocket.route().provideEnvironment(ZEnvironment(routeScope))
        health = Routes(Method.GET / "health" -> Handler.text("ok"))
        http <- Server.defaultWith(_.binding("127.0.0.1", 0).gracefulShutdownTimeout(1.second)).build
        port <- Server.install(rpc.routes ++ health).provideEnvironment(http)
        activeTicket <- rpc.register(calls.bind(host("active:")))
        connection <- RpcWebSocket.connect(port, activeTicket)
        pending <- rpc.register(calls.bind(host("pending:")))
        _ <- routeScope.close(Exit.unit)
        registered <- rpc.register(calls.bind(host("closed:"))).either
        revoked <- rpc.revoke(pending)
        denied <- request(port, List("Authorization" -> s"Bearer ${pending.exportSecret}"))
        healthy <- request(port, Nil, upgrade = false, path = "/health")
        reply <- calls.client(connection).echo("still open")
      } yield (error(registered), revoked, denied, healthy, reply)
    }
    assertEquals(result, (Some(RpcError.Code.Disconnected), false, 401, 200, "active:still open"))
  }

  test("one ticket claims one connection, replay fails while original remains usable for multiple calls") {
    val result = run {
      for {
        server <- RpcTestServer.start()
        token <- server.rpc.register(calls.bind(host("original:")))
        connection <- RpcWebSocket.connect(server.port, token)
        first <- calls.client(connection).echo("one")
        replay <- request(server.port, List("Authorization" -> s"Bearer ${token.exportSecret}"))
        clientReplay <- RpcWebSocket.connect(server.endpoint, token).either
        revoked <- server.rpc.revoke(token)
        second <- calls.client(connection).echo("two")
      } yield (first, replay, error(clientReplay), revoked, second)
    }
    // zio-http 3.11.6 discards the HTTP status in an untyped handshake exception.
    assertEquals(result, ("original:one", 401, Some(RpcError.Code.Disconnected), false, "original:two"))
  }

  test("concurrent connection attempts atomically claim exactly one ticket") {
    val result = run {
      for {
        server <- RpcTestServer.start()
        token <- server.rpc.register(calls.bind(host("winner:")))
        start <- Promise.make[Nothing, Unit]
        first <- (start.await *> RpcWebSocket.connect(server.endpoint, token).either).fork
        second <- (start.await *> RpcWebSocket.connect(server.endpoint, token).either).fork
        _ <- start.succeed(())
        a <- first.join
        b <- second.join
        winners = List(a, b).flatMap(_.toOption)
        replies <- ZIO.foreach(winners)(connection => calls.client(connection).echo("ok"))
      } yield (winners.size, List(a, b).count(_.isLeft), replies)
    }
    assertEquals(result, (1, 1, List("winner:ok")))
  }

  test("separate tokens isolate identical service IDs and immutable merged service sets") {
    val result = run {
      for {
        server <- RpcTestServer.start()
        stateA <- Ref.make(0)
        stateB <- Ref.make(100)
        controlsA = new Controls { def release(id: Int): Task[Unit] = stateA.update(_ + id) }
        controlsB = new Controls { def release(id: Int): Task[Unit] = stateB.update(_ + id) }
        mergedA <- ZIO.fromEither(calls.bind(host("A:")).merge(controls.bind(controlsA)))
        mergedB <- ZIO.fromEither(calls.bind(host("B:")).merge(controls.bind(controlsB)))
        tokenA <- server.rpc.register(mergedA)
        a <- RpcWebSocket.connect(server.endpoint, tokenA)
        tokenB <- server.rpc.register(mergedB)
        b <- RpcWebSocket.connect(server.endpoint, tokenB)
        tokenC <- server.rpc.register(calls.bind(host("C:")))
        c <- RpcWebSocket.connect(server.endpoint, tokenC)
        _ <- controls.client(a).release(2)
        _ <- controls.client(b).release(7)
        answerA <- calls.client(a).echo("ok")
        answerB <- calls.client(b).echo("ok")
        answerC <- calls.client(c).echo("ok")
        absent <- c.call("test.controls", 1, "release", Chunk(Json.Num(1))).either
        valueA <- stateA.get
        valueB <- stateB.get
      } yield (answerA, answerB, answerC, valueA, valueB, error(absent))
    }
    assertEquals(result, ("A:ok", "B:ok", "C:ok", 2, 107, Some(RpcError.Code.UnknownService)))
  }

  test("non-upgrade, disallowed or duplicate Origin and duplicate credentials do not burn tickets") {
    val result = run {
      for {
        server <- RpcTestServer.start()
        token <- server.rpc.register(calls.bind(host("ok:")))
        auth = "Authorization" -> s"Bearer ${token.exportSecret}"
        ordinary <- request(server.port, List(auth), upgrade = false)
        browser <- request(server.port, List(auth, "Origin" -> "https://untrusted.example"))
        duplicateOrigin <- request(server.port, List(auth, "Origin" -> "null", "Origin" -> "null"))
        duplicateAuth <- request(server.port, List(auth, auth))
        connection <- RpcWebSocket.connect(server.endpoint, token)
        answer <- calls.client(connection).echo("still available")
        absentOriginToken <- server.rpc.register(calls.bind(host("unused")))
        absentOrigin <- request(server.port, List("Authorization" -> s"Bearer ${absentOriginToken.exportSecret}"))
      } yield (List(ordinary, browser, duplicateOrigin, duplicateAuth), answer, absentOrigin)
    }
    assertEquals(result, (List.fill(4)(401), "ok:still available", 101))
  }

  test("HTTP/1.0 upgrade is rejected without consuming the ticket") {
    val result = run {
      for {
        server <- RpcTestServer.start()
        token <- server.rpc.register(calls.bind(host("ok:")))
        status <- request(
          server.port,
          List("Authorization" -> s"Bearer ${token.exportSecret}"),
          httpVersion = "HTTP/1.0"
        )
        _ <- ZIO.succeed(assertEquals(status, 401))
        connection <- RpcWebSocket.connect(server.endpoint, token)
        answer <- calls.client(connection).echo("same ticket")
      } yield answer
    }
    assertEquals(result, "ok:same ticket")
  }

  test("deterministic TTL, capacity pruning, revoke and shutdown invalidate only pending tickets") {
    val result = run {
      for {
        time <- Ref.make(0L)
        tickets <- RpcTickets.make(RpcWebSocket.AuthConfig(maxPendingTickets = 1, maxTtl = 1.minute), time.get)
        dispatcher = calls.bind(host("ok:"))
        invalid <- ZIO.foreach(List(Duration.Zero, (-1).seconds, Duration.Infinity, 61.seconds))(ttl =>
          tickets.register(dispatcher, ttl).either.map(error)
        )
        expired <- tickets.register(dispatcher, 1.second)
        full <- tickets.register(dispatcher, 1.second).either
        _ <- time.set(1.second.toNanos)
        fresh <- tickets.register(dispatcher, 2.seconds)
        expiredClaim <- tickets.redeem(expired).either
        revoked <- tickets.revoke(fresh)
        revokedAgain <- tickets.revoke(fresh)
        revokedClaim <- tickets.redeem(fresh).either
        pending <- tickets.register(dispatcher, 2.seconds)
        _ <- tickets.close
        shutdownClaim <- tickets.redeem(pending).either
        shutdownRegister <- tickets.register(dispatcher, 1.second).either
        shutdownRevoke <- tickets.revoke(pending)
      } yield (
        invalid.toList,
        error(full),
        error(expiredClaim),
        revoked,
        revokedAgain,
        error(revokedClaim),
        error(shutdownClaim),
        error(shutdownRegister),
        shutdownRevoke
      )
    }
    assertEquals(
      result,
      (
        List.fill(4)(Some(RpcError.Code.InvalidArguments)),
        Some(RpcError.Code.LimitExceeded),
        Some(RpcError.Code.Unauthorized),
        true,
        false,
        Some(RpcError.Code.Unauthorized),
        Some(RpcError.Code.Unauthorized),
        Some(RpcError.Code.Disconnected),
        false
      )
    )
  }

  test("revoked and expired tickets return generic HTTP401 and capacity recovers") {
    val result = run {
      for {
        server <- RpcTestServer.start(auth = RpcWebSocket.AuthConfig(maxPendingTickets = 1))
        token <- server.rpc.register(calls.bind(host("revoked")))
        full <- server.rpc.register(calls.bind(host("full"))).either
        revoked <- server.rpc.revoke(token)
        revokedStatus <- request(server.port, List("Authorization" -> s"Bearer ${token.exportSecret}"))
        expired <- server.rpc.register(calls.bind(host("expired")), ttl = 1.nanosecond)
        afterRegistration <- Clock.nanoTime
        _ <- Clock.nanoTime.repeatUntil(time => time - afterRegistration > 1L).unit
        expiredStatus <- request(server.port, List("Authorization" -> s"Bearer ${expired.exportSecret}"))
        fresh <- server.rpc.register(calls.bind(host("fresh:")))
        connection <- RpcWebSocket.connect(server.endpoint, fresh)
        answer <- calls.client(connection).echo("ok")
      } yield (error(full), revoked, revokedStatus, expiredStatus, answer)
    }
    assertEquals(result, (Some(RpcError.Code.LimitExceeded), true, 401, 401, "fresh:ok"))
  }

  test("authentication configuration enforces finite bounded capacity and TTL") {
    intercept[IllegalArgumentException](RpcWebSocket.AuthConfig(maxPendingTickets = 0))
    intercept[IllegalArgumentException](RpcWebSocket.AuthConfig(maxPendingTickets = 65537))
    intercept[IllegalArgumentException](RpcWebSocket.AuthConfig(maxTtl = Duration.Zero))
    intercept[IllegalArgumentException](RpcWebSocket.AuthConfig(maxTtl = Duration.Infinity))
    intercept[IllegalArgumentException](RpcWebSocket.AuthConfig(maxTtl = 2.days))
  }

  test("lifecycle and RPC TRACE logging never include connection tickets or authorization headers") {
    val messages = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val safe = run {
      for {
        _ <- (Runtime.removeDefaultLoggers >>> Runtime.addLogger(ZLogger.default.map { line =>
          messages.add(line)
          ()
        })).build
        server <- RpcTestServer.start()
        token <- server.rpc.register(calls.bind(host("safe:")))
        connection <- RpcWebSocket.connect(server.endpoint, token)
        _ <- calls.client(connection).echo("payload")
        _ <- connection.close
        logs = messages.toArray.mkString("\n")
      } yield logs.contains("RPC request sent") && logs.contains("payload") &&
        logs.contains("client connected") && !logs.contains(token.exportSecret) &&
        !logs.contains("Authorization") && !logs.contains("Bearer")
    }
    assert(safe)
  }

  test("caller-owned server scope shutdown clears pending tickets and rejects later registrations") {
    val result = run {
      for {
        scope <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        server <- RpcTestServer.start().provideEnvironment(ZEnvironment(scope))
        token <- server.rpc.register(calls.bind(host("never")))
        _ <- scope.close(Exit.unit)
        revoked <- server.rpc.revoke(token)
        registered <- server.rpc.register(calls.bind(host("never"))).either
      } yield (revoked, error(registered))
    }
    assertEquals(result, (false, Some(RpcError.Code.Disconnected)))
  }
}
