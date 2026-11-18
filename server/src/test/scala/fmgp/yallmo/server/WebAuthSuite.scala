package fmgp.yallmo.server

import fmgp.yallmo.model.{User, UserId}
import fmgp.yallmo.orchestration.config.{Config as AppConfig, ConfigFile}
import fmgp.yallmo.server.application.TestServerApplication
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, WebSocket}
import java.util.concurrent.{CompletableFuture, CompletionStage, TimeUnit}
import munit.FunSuite
import zio.*
import zio.http.{Body, Method, Request, Status, URL}
import zio.json.*
import zio.json.ast.Json

final class WebAuthSuite extends FunSuite {
  // Independently generated with Python hashlib.pbkdf2_hmac('sha256', password.encode(), b'yallmo', 600000, 32).
  private val secretHash =
    "pbkdf2-sha256$600000$yallmo$a55328c40930bb6b784148df64e01cbcfdf4ed8c893457a61b574322eb854b97"
  // Python secrets.token_bytes(16) salt, hashlib.pbkdf2_hmac('sha256', b'test-secret', salt, 600000, 32).
  private val randomSecretHash =
    "pbkdf2-sha256$600000$5071be5d35e6a8a3274c320fd9630622$c9a2eec774d1b4f2a71879335dc71e7ebf655d762385791a69b407f34a4e92e1"
  private val users = Map(UserId("Alice") -> User(UserId("Alice"), randomSecretHash))
  private val origin = "https://example.test"
  private def request(path: String, method: Method = Method.GET, cookie: Option[String] = None): Request = {
    val request = Request(method = method, url = URL.decode(s"$origin$path").toOption.get)
      .addHeader("Host", "example.test")
      .addHeader("Origin", origin)
      .addHeader("X-Yallmo-CSRF", "1")
    cookie.fold(request)(request.addHeader("Cookie", _))
  }
  private def login(
      auth: WebAuth,
      password: String = "test-secret",
      username: String = "Alice"
  ): UIO[zio.http.Response] = auth.endpoint(
    request("/auth/login", Method.POST)
      .copy(body = Body.fromString(Map("username" -> username, "password" -> password).toJson))
  )
  private def cookie(response: zio.http.Response): String =
    response.headers.iterator.find(_.headerName.equalsIgnoreCase("Set-Cookie")).get.renderedValue

  test("login creates opaque HttpOnly SameSite Secure cookie; session restores only username; logout revokes") {
    run(for {
      auth <- ZIO.succeed(new WebAuth(users))
      response <- login(auth)
      value = cookie(response)
      restored <- auth.endpoint(request("/auth/session", cookie = Some(value.takeWhile(_ != ';'))))
      body <- restored.body.asString
      session <- auth
        .session(request("/rpc", cookie = Some(value.takeWhile(_ != ';'))))
        .someOrFail(new AssertionError("Missing session"))
      revoked <- auth.untilRevoked(session).fork
      logout <- auth.endpoint(request("/auth/logout", Method.POST, Some(value.takeWhile(_ != ';'))))
      _ <- revoked.join.timeoutFail(new AssertionError("Revocation did not wake socket"))(1.second)
      missing <- auth.endpoint(request("/auth/session", cookie = Some(value.takeWhile(_ != ';'))))
    } yield {
      assertEquals(response.status, Status.Ok)
      assert(value.matches("yallmo-session=[A-Za-z0-9_-]{43};.*"))
      List("HttpOnly", "SameSite=Strict", "Secure", "Path=/").foreach(flag => assert(value.contains(flag)))
      assert(!value.contains("Alice")); assert(!value.contains("test-secret"))
      assertEquals(body.fromJson[Map[String, String]], Right(Map("username" -> "Alice")))
      assertEquals(logout.status, Status.NoContent)
      assert(cookie(logout).contains("Max-Age=0"))
      assertEquals(missing.status, Status.Unauthorized)
    })
  }

  test("missing users, bad password, unknown user and malformed login fail without credentials in response") {
    run(
      ZIO.foreach(
        List(
          """{"username":"Alice","password":"wrong-secret"}""",
          """{"username":"unknown","password":"test-secret"}""",
          "not-json"
        )
      ) { body =>
        for {
          auth <- ZIO.succeed(new WebAuth(users))
          response <- auth.endpoint(request("/auth/login", Method.POST).copy(body = Body.fromString(body)))
          text <- response.body.asString
        } yield { assertEquals(response.status, Status.Unauthorized); assertEquals(text, "") }
      } *> login(new WebAuth(Map.empty)).map(response => assertEquals(response.status, Status.Unauthorized))
    )
  }

  test("legacy fixed-salt PBKDF2 Python vectors still authenticate and reject wrong passwords and hash-as-password") {
    val hashedUsers = Map(UserId("Alice") -> User(UserId("Alice"), secretHash))
    val unicodeHash =
      "pbkdf2-sha256$600000$yallmo$32cbb2c3e74626e89c1d381040cfe054fd969a9488490cc180124bb24a451dc6"
    run(for {
      auth <- ZIO.succeed(new WebAuth(hashedUsers))
      correct <- login(auth)
      restored <- auth.endpoint(request("/auth/session", cookie = Some(cookie(correct).takeWhile(_ != ';'))))
      wrong <- login(new WebAuth(hashedUsers), "wrong-secret")
      hashAsPassword <- login(new WebAuth(hashedUsers), secretHash)
      unknown <- login(new WebAuth(hashedUsers), username = "unknown")
      oversized <- login(new WebAuth(hashedUsers), "a" * 1025)
      unicode <- login(new WebAuth(Map(UserId("Alice") -> User(UserId("Alice"), unicodeHash))), "pässw🔒rd")
    } yield {
      assertEquals(correct.status, Status.Ok)
      assert(cookie(correct).contains("HttpOnly"))
      assertEquals(restored.status, Status.Ok)
      List(wrong, hashAsPassword, unknown, oversized).foreach { response =>
        assertEquals(response.status, Status.Unauthorized)
        assert(!response.headers.iterator.exists(_.headerName.equalsIgnoreCase("Set-Cookie")))
      }
      assertEquals(unicode.status, Status.Ok)
    })
  }

  test("random-salt Python vectors including Unicode authenticate; distinct salts separate the same password") {
    // Independently generated using secrets.token_bytes(16), then hashlib.pbkdf2_hmac with UTF-8 passwords.
    val secondHash =
      "pbkdf2-sha256$600000$eb768063fdb740db6fa82449e7e330fa$71f1489be666f6bb92fe822cefaa465934c9eeaf1f8757314ee024e3614e1c2b"
    val unicodeHash =
      "pbkdf2-sha256$600000$29122c2ddd4bfa074b965fe9a7b718e1$a48c275ae078c302d95a982ac55cff3abbf8943075d721e7812044c18f5571c0"
    assertNotEquals(randomSecretHash.split("\\$")(2), secondHash.split("\\$")(2))
    assertNotEquals(randomSecretHash.split("\\$")(3), secondHash.split("\\$")(3))
    run(ZIO.foreach(List(randomSecretHash -> "test-secret", secondHash -> "test-secret", unicodeHash -> "pässw🔒rd")) {
      case (hash, password) =>
        val hashedUsers = Map(UserId("Alice") -> User(UserId("Alice"), hash))
        assertEquals(ConfigFile.validate(AppConfig(users = hashedUsers)), Right(()))
        for {
          auth <- ZIO.succeed(new WebAuth(hashedUsers))
          correct <- login(auth, password)
          restored <- auth.endpoint(request("/auth/session", cookie = Some(cookie(correct).takeWhile(_ != ';'))))
          wrong <- login(new WebAuth(hashedUsers), "wrong-secret")
          hashAsPassword <- login(new WebAuth(hashedUsers), hash)
          unknown <- login(new WebAuth(hashedUsers), password, username = "unknown")
          oversized <- login(new WebAuth(hashedUsers), "a" * 1025)
        } yield {
          assertEquals(correct.status, Status.Ok)
          assertEquals(restored.status, Status.Ok)
          List(wrong, hashAsPassword, unknown, oversized).foreach { response =>
            assertEquals(response.status, Status.Unauthorized)
            assert(!response.headers.iterator.exists(_.headerName.equalsIgnoreCase("Set-Cookie")))
          }
        }
    })
  }

  test("programmatic users with a mismatched mapping key and identity cannot authenticate") {
    val mismatched = Map(UserId("Alice") -> User(UserId("Bob"), randomSecretHash))
    run(login(new WebAuth(mismatched)).map(response => assertEquals(response.status, Status.Unauthorized)))
  }

  test("oversized passwords are rejected before credential work") {
    run(login(new WebAuth(users), "a" * 1025).map(response => assertEquals(response.status, Status.Unauthorized)))
  }

  test(
    "Origin and CSRF enforcement rejects cross-origin, null, missing and duplicate Origins; local proxy preserves Host"
  ) {
    val base = request("/auth/login", Method.POST)
    val foreign = base
      .copy(headers = zio.http.Headers.fromIterable(base.headers.filter(!_.headerName.equalsIgnoreCase("Origin"))))
      .addHeader("Origin", "https://evil.test")
    val absent =
      base.copy(headers = zio.http.Headers.fromIterable(base.headers.filter(!_.headerName.equalsIgnoreCase("Origin"))))
    val noCsrf = base.copy(headers =
      zio.http.Headers.fromIterable(base.headers.filter(!_.headerName.equalsIgnoreCase("X-Yallmo-CSRF")))
    )
    val duplicate = base.addHeader("Origin", origin)
    val nullOrigin = absent.addHeader("Origin", "null")
    val vite = Request
      .get(URL.decode("http://127.0.0.1:8080/rpc").toOption.get)
      .addHeader("Host", "localhost:5173")
      .addHeader("Origin", "http://localhost:5173")
    assert(WebAuth.sameOrigin(vite, required = true))
    assert(!WebAuth.sameOrigin(vite.addHeader("Sec-Fetch-Site", "cross-site"), required = true))
    run(
      ZIO
        .foreach(List(foreign, absent, noCsrf, duplicate, nullOrigin))(new WebAuth(users).endpoint(_))
        .map(_.foreach(response => assertEquals(response.status, Status.Forbidden)))
    )
  }

  test("expired and duplicate cookies are rejected server-side; login admission is bounded") {
    run(for {
      auth <- ZIO.succeed(new WebAuth(users, 200.millis))
      response <- login(auth)
      value = cookie(response).takeWhile(_ != ';')
      duplicate <- auth.session(request("/rpc", cookie = Some(s"$value; $value")))
      admissionAuth <- ZIO.succeed(new WebAuth(users))
      concurrent <- ZIO.foreachPar(List(1, 2))(_ => login(admissionAuth))
      _ <- ZIO.sleep(250.millis)
      expired <- auth.session(request("/rpc", cookie = Some(value)))
    } yield {
      assertEquals(duplicate, None)
      assertEquals(concurrent.map(_.status).toSet, Set[Status](Status.Ok, Status.TooManyRequests))
      assertEquals(expired, None)
    })
  }

  test("network boundary rejects unauthenticated HTTP and WS before upgrade; JVM client logs in against secretHash") {
    run(ZIO.scoped(for {
      backend <- TestServerApplication.make(config =
        AppConfig(users = Map(UserId("Alice") -> User(UserId("Alice"), randomSecretHash)))
      )
      port <- new OrchestratorWSServer(backend).start(0)
      http <- ZIO.acquireRelease(ZIO.attempt(HttpClient.newHttpClient()))(client =>
        ZIO.attemptBlocking(client.close()).ignore
      )
      root = s"http://127.0.0.1:$port"
      denied <- ZIO.attemptBlocking(
        http.send(
          HttpRequest.newBuilder(URI.create(s"$root/api/sdlc/tasks")).build(),
          HttpResponse.BodyHandlers.ofString()
        )
      )
      upgrade <- ZIO
        .attemptBlocking(
          http
            .newWebSocketBuilder()
            .header("Origin", root)
            .buildAsync(URI.create(s"ws://127.0.0.1:$port/rpc"), new WebSocket.Listener {})
            .join()
        )
        .either
      client <- OrchestratorWSClient.connect(
        s"ws://127.0.0.1:$port/rpc",
        userId = Some(UserId("Alice")),
        password = Some("test-secret")
      )
      initialized <- client.capabilities
      _ <- client.listSessions
    } yield {
      assertEquals(denied.statusCode(), 401)
      assert(upgrade.isLeft)
      assertEquals(initialized.get.identity.id, UserId("Alice"))
      assertEquals(initialized.get.identity.role, Role.Operator)
    }))
  }

  test("HTTP logout revokes session restoration and closes an already upgraded socket") {
    run(ZIO.scoped(for {
      backend <- TestServerApplication.make(config = AppConfig(users = users))
      port <- new OrchestratorWSServer(backend).start(0)
      http <- ZIO.acquireRelease(ZIO.attempt(HttpClient.newHttpClient()))(client =>
        ZIO.attemptBlocking(client.close()).ignore
      )
      root = s"http://127.0.0.1:$port"
      loggedIn <- ZIO.attemptBlocking(
        http.send(
          HttpRequest
            .newBuilder(URI.create(s"$root/auth/login"))
            .header("Origin", root)
            .header("X-Yallmo-CSRF", "1")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"username":"Alice","password":"test-secret"}"""))
            .build(),
          HttpResponse.BodyHandlers.ofString()
        )
      )
      value = loggedIn.headers().firstValue("Set-Cookie").orElseThrow().takeWhile(_ != ';')
      closed = new CompletableFuture[Int]()
      _ <- ZIO.acquireRelease(
        ZIO.attemptBlocking(
          http
            .newWebSocketBuilder()
            .header("Origin", root)
            .header("Cookie", value)
            .buildAsync(
              URI.create(s"ws://127.0.0.1:$port/rpc"),
              new WebSocket.Listener {
                override def onOpen(socket: WebSocket): Unit = { socket.request(1); () }
                override def onClose(socket: WebSocket, code: Int, reason: String): CompletionStage[?] = {
                  closed.complete(code); CompletableFuture.completedFuture(null)
                }
              }
            )
            .join()
        )
      )(socket => ZIO.succeed(socket.abort()))
      logout <- ZIO.attemptBlocking(
        http.send(
          HttpRequest
            .newBuilder(URI.create(s"$root/auth/logout"))
            .header("Origin", root)
            .header("X-Yallmo-CSRF", "1")
            .header("Cookie", value)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
          HttpResponse.BodyHandlers.ofString()
        )
      )
      code <- ZIO.attemptBlocking(closed.get(2, TimeUnit.SECONDS))
      restored <- ZIO.attemptBlocking(
        http.send(
          HttpRequest
            .newBuilder(URI.create(s"$root/auth/session"))
            .header("Cookie", value)
            .build(),
          HttpResponse.BodyHandlers.ofString()
        )
      )
    } yield {
      assertEquals(logout.statusCode(), 204)
      assertEquals(code, 1008)
      assertEquals(restored.statusCode(), 401)
    }))
  }

  test("authenticated principal cannot be changed, even on the first initialize; omitted claimed ID uses principal") {
    run(ZIO.scoped(for {
      backend <- TestServerApplication.make()
      rpc = new OrchestratorRpc(backend)
      connection <- rpc.openAuthenticatedConnection(UserId("Alice"))
      spoofed <- rpc.handle(
        JsonRpc.Request(Json.Str("spoof"), Initialize.method, Initialize.Params("browser", Some(UserId("Bob")))).toJson,
        connection
      )
      initialized <- rpc.handle(
        JsonRpc.Request(Json.Str("init"), Initialize.method, Initialize.Params("browser")).toJson,
        connection
      )
      _ <- rpc.disconnect(connection)
    } yield {
      assertEquals(spoofed.get.fromJson[JsonRpc.Failure].toOption.get.error.code, ErrorCode.Forbidden)
      assertEquals(
        initialized.get.fromJson[JsonRpc.Success[Initialize.Result]].toOption.get.result.identity.id,
        UserId("Alice")
      )
    }))
  }

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
