package fmgp.yallmo.provider.openai

import fmgp.yallmo.model.{FunctionCallOutput, MessagePayload}
import fmgp.yallmo.model.llm.{AgentTurnRequest, LlmStopReason}
import munit.FunSuite
import zio.*
import zio.http.*

import java.nio.file.{Files, Path as FilePath}
import java.nio.file.attribute.PosixFilePermissions
import scala.jdk.CollectionConverters.*

final class OpenAiSubscriptionAuthSuite extends FunSuite {
  private def run[A](effect: Task[A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def withSecrets[A](test: (OpenAiCodexSecrets, FilePath) => A): A = {
    val directory = Files.createTempDirectory("yallmo-subscription-auth-")
    try test(OpenAiCodexSecrets(directory.resolve(".env")), directory)
    finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }

  private def fakeClient(respond: Request => Task[Response]): Client =
    Client.fromDriver(new ZClient.Driver[Any, Scope, Throwable] {
      override def request(
          version: Version,
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

      override def socket[Env1 <: Any](
          version: Version,
          url: URL,
          headers: Headers,
          app: WebSocketApp[Env1]
      )(using Trace, Scope =:= Scope): ZIO[Env1, Throwable, Response] = {
        val _ = (version, url, headers, app)
        ZIO.fail(new IllegalStateException("Unexpected WebSocket call"))
      }
    })

  private final class LoginClock(sleepEffect: Duration => UIO[Unit]) extends Clock {
    export Clock.ClockLive.{currentTime, currentDateTime, instant, javaClock, localDateTime, nanoTime, scheduler}
    override def sleep(duration: => Duration)(using Trace): UIO[Unit] = sleepEffect(duration)
  }

  private val login = OpenAiCodexBrowserLogin("https://auth.openai.com/secret", "expected-state", "secret-verifier")
  private val callback = "http://localhost:1455/auth/callback"
  private val tokenResponse = """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}"""

  test("OpenAiCodexAuth browser login creates distinct state and S256 PKCE without a network request") {
    withSecrets { (secrets, _) =>
      val auth = new OpenAiCodexAuth(fakeClient(_ => ZIO.dieMessage("Unexpected network request")), secrets)
      val first = run(auth.startBrowserLogin)
      val second = run(auth.startBrowserLogin)
      val query = java.net.URI
        .create(first.url)
        .getRawQuery
        .split("&")
        .map { pair =>
          val parts = pair.split("=", 2)
          java.net.URLDecoder.decode(parts(0), java.nio.charset.StandardCharsets.UTF_8) ->
            java.net.URLDecoder.decode(parts(1), java.nio.charset.StandardCharsets.UTF_8)
        }
        .toMap
      val challenge = java.util.Base64.getUrlEncoder
        .withoutPadding()
        .encodeToString(
          java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(first.codeVerifier.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        )
      assertEquals(query("code_challenge"), challenge)
      assertEquals(query("code_challenge_method"), "S256")
      assertEquals(query("state"), first.state)
      assertEquals(query("redirect_uri"), OpenAiCodexAuth.BrowserRedirectUri)
      assert(first.state.nonEmpty)
      assertNotEquals(first.state, second.state)
      assertNotEquals(first.codeVerifier, second.codeVerifier)
    }
  }

  test("OpenAiCodexAuth browser completion checks state before exchanging and persists valid credentials") {
    withSecrets { (secrets, _) =>
      run {
        for {
          calls <- Ref.make(0)
          client = fakeClient(request =>
            request.body.asString.flatMap { body =>
              ZIO.attempt {
                assert(body.contains("code=oauth-code"))
                assert(body.contains("code_verifier=secret-verifier"))
                assert(body.contains("grant_type=authorization_code"))
              } *> calls.update(_ + 1).as(Response.text(tokenResponse))
            }
          )
          auth = new OpenAiCodexAuth(client, secrets)
          invalid <- auth.completeBrowserLogin(login, s"$callback?code=oauth-code&state=wrong").either
          before <- calls.get
          valid <- auth.completeBrowserLogin(login, s"$callback?code=oauth-code&state=expected-state")
          after <- calls.get
          saved <- secrets.load
        } yield {
          assert(invalid.isLeft)
          assertEquals(before, 0)
          assertEquals(after, 1)
          assertEquals(saved, valid)
        }
      }
    }
  }

  test("OpenAiCodexAuth browser redirect validates the exact callback, state, duplicates and OAuth errors") {
    assertEquals(
      OpenAiCodexAuth.parseBrowserRedirect(login, s"$callback?code=code%2B123&state=expected-state"),
      Right(OpenAiCodexAuthorization("code+123", "secret-verifier"))
    )
    val invalid = List(
      "code-only",
      s"https://localhost:1455/auth/callback?code=secret&state=expected-state",
      s"http://example.com:1455/auth/callback?code=secret&state=expected-state",
      s"http://user@localhost:1455/auth/callback?code=secret&state=expected-state",
      s"http://localhost:1456/auth/callback?code=secret&state=expected-state",
      s"http://localhost:1455/other?code=secret&state=expected-state",
      s"$callback?code=secret&state=expected-state#secret",
      s"$callback?code=secret",
      s"$callback?code=secret&state=wrong",
      s"$callback?code=&state=expected-state",
      s"$callback?code=secret&state=expected-state&state=expected-state",
      s"$callback?code=secret&code=other&state=expected-state",
      s"$callback?code=secret&state=expected-state&error=secret&error_description=secret",
      s"$callback?code=%secret&state=expected-state"
    )
    invalid.foreach { input =>
      val parsed = OpenAiCodexAuth.parseBrowserRedirect(login, input)
      assert(parsed.isLeft)
      assert(!parsed.swap.toOption.get.contains("secret"))
    }
  }

  test("OpenAiCodexAuth validates finite positive expiry and bounded device intervals") {
    List("0", "-1", "1e100", "1e999", "0.0001", "\"3600\"", "null").foreach { expiry =>
      assert(
        OpenAiCodexAuth
          .parseCredentials(
            s"""{"access_token":"secret","refresh_token":"secret","expires_in":$expiry}"""
          )
          .isLeft
      )
    }
    assert(OpenAiCodexAuth.parseCredentials(tokenResponse).isRight)
    assert(OpenAiCodexAuth.parseCredentials("""{"access_token":" ","refresh_token":"r","expires_in":3600}""").isLeft)
    List("-1", "61", "1e999", "\"NaN\"", "\"Infinity\"", "null").foreach { interval =>
      assert(
        OpenAiCodexAuth
          .parseDeviceCode(
            s"""{"device_auth_id":"secret","user_code":"secret","interval":$interval}"""
          )
          .isLeft
      )
    }
    assertEquals(
      OpenAiCodexAuth.parseDeviceCode("""{"device_auth_id":"id","user_code":"code","interval":0}""").map(_.interval),
      Right(1.second)
    )
    assertEquals(
      OpenAiCodexAuth
        .parseDeviceCode("""{"device_auth_id":"id","user_code":"code","interval":"1.25"}""")
        .map(_.interval),
      Right(1250.millis)
    )
  }

  test("OpenAiCodexAuth classifies Pi pending, slow_down and fatal device results without body leakage") {
    import OpenAiCodexAuth.DevicePollResult.*
    assertEquals(OpenAiCodexAuth.classifyDevicePoll(403, "secret"), Right(Pending))
    assertEquals(OpenAiCodexAuth.classifyDevicePoll(404, "secret"), Right(Pending))
    assertEquals(
      OpenAiCodexAuth.classifyDevicePoll(400, """{"error":{"code":"deviceauth_authorization_pending"}}"""),
      Right(Pending)
    )
    assertEquals(OpenAiCodexAuth.classifyDevicePoll(429, """{"error":"slow_down"}"""), Right(SlowDown))
    assertEquals(OpenAiCodexAuth.classifyDevicePoll(403, """{"error":{"code":"slow_down"}}"""), Right(SlowDown))
    List(400, 401, 429, 500).foreach { status =>
      val result = OpenAiCodexAuth.classifyDevicePoll(status, """{"error":"secret-token-and-code"}""")
      assert(result.isLeft)
      assert(!result.swap.toOption.get.contains("secret"))
    }
    assert(OpenAiCodexAuth.classifyDevicePoll(200, "secret").isLeft)
  }

  test("OpenAiCodexAuth and Secrets sensitive objects redact their rendering") {
    List(
      OpenAiCodexCredentials("secret-access", "secret-refresh", 1234).toString,
      login.toString,
      OpenAiCodexDeviceCode("secret-id", "secret-code", 5.seconds).toString,
      OpenAiCodexAuthorization("secret-code", "secret-verifier").toString
    ).foreach { rendered =>
      assert(rendered.contains("<redacted>"))
      assert(!rendered.contains("secret"))
    }
  }

  test(
    "OpenAiCodexSecrets preserves unrelated entries, replaces old entries, and writes private files without temp leftovers"
  ) {
    withSecrets { (secrets, directory) =>
      Files.writeString(secrets.path, "OTHER=keep\n# comment\n OPENAI_CODEX_ACCESS_TOKEN = old\n")
      val credentials = OpenAiCodexCredentials("access", "refresh", 1234)
      run(secrets.save(credentials))
      assertEquals(run(secrets.load), credentials)
      val content = Files.readString(secrets.path)
      assert(content.startsWith("OTHER=keep\n# comment\n"))
      assert(!content.contains("old"))
      assertEquals(Files.getPosixFilePermissions(secrets.path), PosixFilePermissions.fromString("rw-------"))
      val files = Files.list(directory)
      try assertEquals(files.iterator().asScala.toList, List(secrets.path))
      finally files.close()
    }
  }

  test("OpenAiCodexSecrets cleans temporary files after a failed atomic replacement") {
    withSecrets { (_, directory) =>
      // The temporary file is valid, but the destination exceeds the filesystem's filename limit.
      val secrets = OpenAiCodexSecrets(directory.resolve("x" * 256))
      val result = run(secrets.save(OpenAiCodexCredentials("secret", "secret", 1234)).either)
      assert(result.isLeft)
      assert(!result.swap.toOption.get.getMessage.contains("secret"))
      val files = Files.list(directory)
      try assertEquals(files.iterator().asScala.toList, List.empty[FilePath])
      finally files.close()
    }
  }

  test("OpenAiCodexSecrets rejects dotenv injection and redacts invalid expiry errors") {
    withSecrets { (secrets, _) =>
      assert(run(secrets.save(OpenAiCodexCredentials("secret\nOTHER=injected", "refresh", 1234)).either).isLeft)
      Files.writeString(
        secrets.path,
        "OPENAI_CODEX_ACCESS_TOKEN=access\nOPENAI_CODEX_REFRESH_TOKEN=refresh\nOPENAI_CODEX_TOKEN_EXPIRES_AT=secret\n"
      )
      val result = run(secrets.load.either)
      assert(result.isLeft)
      assert(!result.swap.toOption.get.getMessage.contains("secret"))
    }
  }

  test("OpenAiCodexAuth concurrent refresh reloads inside a resource-safe per-instance lock") {
    withSecrets { (secrets, _) =>
      run {
        for {
          _ <- secrets.save(OpenAiCodexCredentials("old-access", "old-refresh", 1))
          calls <- Ref.make(0)
          client = fakeClient(request =>
            request.body.asString.flatMap { body =>
              ZIO.attempt(assert(body.contains("refresh_token=old-refresh"))) *>
                calls.update(_ + 1) *> ZIO.sleep(30.millis).as(Response.text(tokenResponse))
            }
          )
          auth = new OpenAiCodexAuth(client, secrets)
          results <- ZIO.foreachPar(1 to 20)(_ => auth.validCredentials)
          count <- calls.get
          saved <- secrets.load
        } yield {
          assertEquals(count, 1)
          assert(results.forall(_.refreshToken == "new-refresh"))
          assertEquals(saved.refreshToken, "new-refresh")
        }
      }
    }
  }

  test("OpenAiCodexAuth releases refresh lock after interruption") {
    withSecrets { (secrets, _) =>
      run {
        for {
          _ <- secrets.save(OpenAiCodexCredentials("old", "old", 1))
          entered <- Promise.make[Nothing, Unit]
          calls <- Ref.make(0)
          client = fakeClient(_ =>
            calls.getAndUpdate(_ + 1).flatMap {
              case 0 => entered.succeed(()) *> ZIO.never
              case _ => ZIO.succeed(Response.text(tokenResponse))
            }
          )
          auth = new OpenAiCodexAuth(client, secrets)
          fiber <- auth.validCredentials.fork
          _ <- entered.await
          _ <- fiber.interrupt
          result <- auth.validCredentials.timeoutFail(new Exception("lock not released"))(2.seconds)
        } yield assertEquals(result.accessToken, "new-access")
      }
    }
  }

  test("OpenAiCodexAuth never exposes provider bodies or transport errors") {
    withSecrets { (secrets, _) =>
      List(
        fakeClient(_ => ZIO.succeed(Response.text("secret-token-and-callback").status(Status.Unauthorized))),
        fakeClient(_ => ZIO.fail(new Exception("secret-token-and-callback")))
      ).foreach { client =>
        val auth = new OpenAiCodexAuth(client, secrets)
        val result = run(auth.startDeviceLogin.either)
        assert(result.isLeft)
        assert(!result.swap.toOption.get.getMessage.contains("secret"))
        assertEquals(result.swap.toOption.get.getCause, null)
      }
    }
  }

  test("OpenAiCodexAuth bounds the entire HTTP operation including response body consumption") {
    withSecrets { (secrets, _) =>
      val clock = new LoginClock(_ => Clock.ClockLive.sleep(20.millis))
      val client = fakeClient(_ => ZIO.succeed(Response(body = Body.fromStreamChunked(zio.stream.ZStream.never))))
      val result = run(new OpenAiCodexAuth(client, secrets).startDeviceLogin.withClock(clock).either)
      assertEquals(result.swap.toOption.map(_.getMessage), Some("OpenAI Codex authentication request timed out"))
    }
  }

  test("OpenAiCodexAuth device pending retries and slow_down increases subsequent delay") {
    withSecrets { (secrets, _) =>
      run {
        for {
          sleeps <- Ref.make(Vector.empty[Duration])
          calls <- Ref.make(0)
          clock = new LoginClock(duration =>
            if duration == OpenAiCodexAuth.AuthHttpTimeout || duration == OpenAiCodexAuth.DeviceLoginTimeout then
              Clock.ClockLive.sleep(5.seconds)
            else sleeps.update(_ :+ duration) *> ZIO.yieldNow
          )
          client = fakeClient(_ =>
            calls.getAndUpdate(_ + 1).map {
              case 0 => Response.status(Status.Forbidden)
              case 1 => Response.text("""{"error":"slow_down"}""").status(Status.TooManyRequests)
              case 2 => Response.text("""{"authorization_code":"oauth-code","code_verifier":"verifier"}""")
              case _ => Response.text(tokenResponse)
            }
          )
          auth = new OpenAiCodexAuth(client, secrets)
          result <- auth.completeDeviceLogin(OpenAiCodexDeviceCode("id", "code", 1.second)).withClock(clock)
          delays <- sleeps.get
          count <- calls.get
        } yield {
          assertEquals(delays, Vector(1.second, 6.seconds))
          assertEquals(count, 4)
          assertEquals(result.refreshToken, "new-refresh")
        }
      }
    }
  }

  test("OpenAiCodexAuth whole device flow deadline includes token exchange and interrupts it") {
    withSecrets { (secrets, _) =>
      run {
        for {
          interrupted <- Ref.make(false)
          calls <- Ref.make(0)
          clock = new LoginClock(duration =>
            Clock.ClockLive.sleep(if duration == OpenAiCodexAuth.DeviceLoginTimeout then 40.millis else 5.seconds)
          )
          client = fakeClient(_ =>
            calls.getAndUpdate(_ + 1).flatMap {
              case 0 => ZIO.succeed(Response.text("""{"authorization_code":"code","code_verifier":"verifier"}"""))
              case _ => ZIO.never.onInterrupt(interrupted.set(true))
            }
          )
          auth = new OpenAiCodexAuth(client, secrets)
          result <- auth.completeDeviceLogin(OpenAiCodexDeviceCode("id", "code", 1.second)).withClock(clock).either
          wasInterrupted <- interrupted.get
        } yield {
          assertEquals(result.swap.toOption.map(_.getMessage), Some("OpenAI subscription login timed out"))
          assert(wasInterrupted)
          assert(!Files.exists(secrets.path))
        }
      }
    }
  }

  test("subscription native HTTP turns use OAuth headers and replay tool/continuation items without executing tools") {
    withSecrets { (secrets, _) =>
      val payload = java.util.Base64.getUrlEncoder
        .withoutPadding()
        .encodeToString(
          """{"https://api.openai.com/auth":{"chatgpt_account_id":"test-account"}}"""
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
      val token = s"header.$payload.signature"
      run {
        for {
          _ <- secrets.save(
            OpenAiCodexCredentials(token, "test-refresh", java.lang.System.currentTimeMillis() + 3600000)
          )
          bodies <- Ref.make(Vector.empty[ujson.Value])
          client = fakeClient(request =>
            for {
              body <- request.body.asString.map(ujson.read(_))
              _ <- ZIO.attempt {
                assertEquals(request.url.toString, OpenAiCodexClient.CodexResponsesUrl)
                assertEquals(request.headers.get("Authorization"), Some(s"Bearer $token"))
                assertEquals(request.headers.get("chatgpt-account-id"), Some("test-account"))
                assertEquals(request.headers.get("OpenAI-Beta"), Some("responses=experimental"))
                assertEquals(request.headers.get("Accept"), Some("text/event-stream"))
                assertEquals(body("model").str, "account-model")
                assertEquals(body("instructions").str, "Stay concise")
                assertEquals(body("store").bool, false)
                assertEquals(body("stream").bool, true)
              }
              count <- bodies.modify(values => (values.size, values :+ body))
              output =
                if count == 0 then
                  ujson.Arr(
                    ujson.Obj("type" -> "reasoning", "id" -> "rs-1", "encrypted_content" -> "opaque"),
                    ujson.Obj(
                      "type" -> "function_call",
                      "id" -> "fc-1",
                      "call_id" -> "call-1",
                      "name" -> "read",
                      "arguments" -> "{}"
                    )
                  )
                else
                  ujson.Arr(
                    ujson.Obj(
                      "type" -> "message",
                      "content" -> ujson.Arr(ujson.Obj("type" -> "output_text", "text" -> "Done"))
                    )
                  )
            } yield Response.text(
              "data: " + ujson.write(
                ujson.Obj(
                  "type" -> "response.done",
                  "response" -> ujson.Obj("status" -> "completed", "output" -> output)
                )
              ) + "\n\n"
            )
          )
          provider = new OpenAiCodexClient(client, new OpenAiCodexAuth(client, secrets))
          first <- provider.turn(
            AgentTurnRequest(
              "account-model",
              Chunk(MessagePayload.User("Read README"), MessagePayload.Instructions("Stay concise"))
            )
          )
          call <- ZIO
            .fromOption(first.output.collectFirst { case MessagePayload.FunctionCallRequest(value) => value })
            .orElseFail(new IllegalStateException("Missing tool proposal"))
          second <- provider.turn(
            AgentTurnRequest(
              "account-model",
              Chunk(MessagePayload.User("Read README")) ++ first.output ++ Chunk(
                MessagePayload.FunctionCallOutput(FunctionCallOutput(call.id, call.name, "README contents")),
                MessagePayload.Instructions("Stay concise")
              )
            )
          )
          sent <- bodies.get
        } yield {
          assertEquals(first.stopReason, LlmStopReason.ToolUse)
          assertEquals(second.output, Chunk(MessagePayload.Assistant("Done")))
          assertEquals(sent.size, 2)
          val replay = sent(1)("input").arr
          assert(replay.exists(item => item.obj.get("encrypted_content").contains(ujson.Str("opaque"))))
          assert(
            replay.exists(item =>
              item.obj.get("type").contains(ujson.Str("function_call_output")) && item("call_id").str == "call-1"
            )
          )
        }
      }
    }
  }

  test("OpenAiCodexAuth device fatal errors do not poll again or save credentials") {
    withSecrets { (secrets, _) =>
      run {
        for {
          calls <- Ref.make(0)
          client = fakeClient(_ => calls.update(_ + 1).as(Response.text("secret").status(Status.Unauthorized)))
          auth = new OpenAiCodexAuth(client, secrets)
          result <- auth.completeDeviceLogin(OpenAiCodexDeviceCode("id", "code", 1.second)).either
          count <- calls.get
        } yield {
          assert(result.isLeft)
          assertEquals(count, 1)
          assert(!Files.exists(secrets.path))
        }
      }
    }
  }
}
