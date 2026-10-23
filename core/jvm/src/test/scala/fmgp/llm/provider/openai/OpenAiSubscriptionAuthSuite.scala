package fmgp.yallmo.provider.openai

import fmgp.yallmo.orchestration.config.OAuthCredentialsConfig
import fmgp.yallmo.model.{AgentId, FunctionCallOutput, MessagePayload}
import fmgp.yallmo.model.llm.{AgentTurnRequest, LlmStopReason, ProviderGenerateOptions}
import munit.FunSuite
import zio.*
import zio.http.*

final class OpenAiSubscriptionAuthSuite extends FunSuite {
  private def run[A](effect: Task[A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

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

  test("OpenAiCodexAuth storage-free browser completion validates callbacks and returns credentials without saving") {
    run {
      for {
        calls <- Ref.make(0)
        client = fakeClient(request =>
          request.body.asString.flatMap { body =>
            ZIO.attempt {
              assertEquals(request.url.toString, OpenAiCodexAuth.TokenUrl)
              assertEquals(request.method, Method.POST)
              assert(body.contains("grant_type=authorization_code"))
              assert(body.contains("code=oauth-code"))
              assert(body.contains("code_verifier=secret-verifier"))
              assert(body.contains("redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback"))
            } *> calls.update(_ + 1).as(Response.text(tokenResponse))
          }
        )
        flow = OpenAiCodexAuth.oauthFlow(client)
        invalid <- ZIO.foreach(
          List(
            s"$callback?code=secret&state=wrong",
            s"$callback?state=expected-state&error=secret&error_description=secret",
            s"https://untrusted.example/auth/callback?code=secret&state=expected-state"
          )
        )(redirect => flow.completeBrowserLogin(login, redirect).either)
        before <- calls.get
        credentials <- flow.completeBrowserLogin(login, s"$callback?code=oauth-code&state=expected-state")
        after <- calls.get
      } yield {
        assert(invalid.forall(_.isLeft))
        assert(invalid.forall(_.swap.toOption.exists(error => !error.getMessage.contains("secret"))))
        assertEquals(before, 0)
        assertEquals(after, 1)
        assertEquals(credentials.accessToken, "new-access")
        assertEquals(credentials.refreshToken, "new-refresh")
        assert(credentials.expiresAtMillis > java.lang.System.currentTimeMillis())
      }
    }
  }

  test("OpenAiCodexAuth storage-free completion sanitizes provider and transport failures") {
    List(
      fakeClient(_ => ZIO.succeed(Response.text("secret-token-and-code").status(Status.Unauthorized))),
      fakeClient(_ => ZIO.fail(new Exception("secret-token-and-code")))
    ).foreach { client =>
      val flow = OpenAiCodexAuth.oauthFlow(client)
      List(
        flow.completeBrowserLogin(login, s"$callback?code=oauth-code&state=expected-state")
      ).foreach { completion =>
        val result = run(completion.either)
        assert(result.isLeft)
        assert(!result.swap.toOption.get.getMessage.contains("secret"))
        assertEquals(result.swap.toOption.get.getCause, null)
      }
    }
  }

  test("OpenAiCodexAuth browser login creates distinct state and S256 PKCE without a network request") {
    val auth = OpenAiCodexAuth.oauthFlow(fakeClient(_ => ZIO.dieMessage("Unexpected network request")))
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

  test("OpenAiCodexAuth validates finite positive expiry") {
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
  }

  test("OpenAiCodexAuth sensitive objects redact their rendering") {
    List(
      OAuthCredentialsConfig("secret-access", "secret-refresh", 1234).toString,
      login.toString,
      OpenAiCodexAuthorization("secret-code", "secret-verifier").toString
    ).foreach { rendered =>
      assert(rendered.contains("<redacted>"))
      assert(!rendered.contains("secret"))
    }
  }

  test("OpenAiCodexAuth in-memory access returns valid credentials without HTTP requests") {
    val credentials = OAuthCredentialsConfig("access", "refresh", Long.MaxValue)
    val client = fakeClient(_ => ZIO.dieMessage("Unexpected network request"))
    run {
      for {
        auth <- OpenAiCodexAuth.inMemory(Some(credentials)).provideEnvironment(ZEnvironment(client))
        first <- auth.validCredentials
        second <- auth.validCredentials
      } yield {
        assertEquals(first, credentials)
        assertEquals(second, credentials)
      }
    }
  }

  test("OpenAiCodexAuth in-memory logged-out access fails only when credentials are requested") {
    val client = fakeClient(_ => ZIO.dieMessage("Unexpected network request"))
    run {
      for {
        auth <- OpenAiCodexAuth.inMemory(None).provideEnvironment(ZEnvironment(client))
        result <- auth.validCredentials.either
      } yield assertEquals(result.swap.toOption.map(_.getMessage), Some("ChatGPT profile is logged out"))
    }
  }

  test("OpenAiCodexAuth concurrent in-memory refresh uses a resource-safe per-instance lock") {
    run {
      for {
        calls <- Ref.make(0)
        client = fakeClient(request =>
          request.body.asString.flatMap { body =>
            ZIO.attempt(assert(body.contains("refresh_token=old-refresh"))) *>
              calls.update(_ + 1) *> ZIO.sleep(30.millis).as(Response.text(tokenResponse))
          }
        )
        auth <- OpenAiCodexAuth
          .inMemory(Some(OAuthCredentialsConfig("old-access", "old-refresh", 1)))
          .provideEnvironment(ZEnvironment(client))
        results <- ZIO.foreachPar(1 to 20)(_ => auth.validCredentials)
        count <- calls.get
        cached <- auth.validCredentials
      } yield {
        assertEquals(count, 1)
        assert(results.forall(_.refreshToken == "new-refresh"))
        assertEquals(cached.refreshToken, "new-refresh")
      }
    }
  }

  test("OpenAiCodexAuth releases refresh lock after interruption") {
    run {
      for {
        entered <- Promise.make[Nothing, Unit]
        calls <- Ref.make(0)
        client = fakeClient(_ =>
          calls.getAndUpdate(_ + 1).flatMap {
            case 0 => entered.succeed(()) *> ZIO.never
            case _ => ZIO.succeed(Response.text(tokenResponse))
          }
        )
        auth <- OpenAiCodexAuth
          .inMemory(Some(OAuthCredentialsConfig("old", "old", 1)))
          .provideEnvironment(ZEnvironment(client))
        fiber <- auth.validCredentials.fork
        _ <- entered.await
        _ <- fiber.interrupt
        result <- auth.validCredentials.timeoutFail(new Exception("lock not released"))(2.seconds)
      } yield assertEquals(result.accessToken, "new-access")
    }
  }

  test("OpenAiCodexAuth bounds the entire HTTP operation including response body consumption") {
    val clock = new LoginClock(duration =>
      Clock.ClockLive.sleep(if duration == OpenAiCodexAuth.AuthHttpTimeout then 20.millis else 5.seconds)
    )
    val client = fakeClient(_ => ZIO.succeed(Response(body = Body.fromStreamChunked(zio.stream.ZStream.never))))
    val result = run(
      OpenAiCodexAuth
        .oauthFlow(client)
        .completeBrowserLogin(login, s"$callback?code=oauth-code&state=expected-state")
        .withClock(clock)
        .either
    )
    assertEquals(result.swap.toOption.map(_.getMessage), Some("OpenAI Codex authentication request timed out"))
  }

  private val inferenceCredentials = {
    val payload = java.util.Base64.getUrlEncoder
      .withoutPadding()
      .encodeToString(
        """{"https://api.openai.com/auth":{"chatgpt_account_id":"test-account"}}"""
          .getBytes(java.nio.charset.StandardCharsets.UTF_8)
      )
    OAuthCredentialsConfig(s"header.$payload.signature", "test-refresh", Long.MaxValue)
  }

  test("provider text inference retains OAuth headers and native HTTP/SSE completion without an agent") {
    val client = fakeClient(incoming =>
      incoming.body.asString.flatMap(body =>
        ZIO.attempt {
          assertEquals(incoming.url.toString, OpenAiCodexClient.CodexResponsesUrl)
          assertEquals(incoming.headers.get("Authorization"), Some(s"Bearer ${inferenceCredentials.accessToken}"))
          assertEquals(incoming.headers.get("chatgpt-account-id"), Some("test-account"))
          val wire = ujson.read(body)
          assertEquals(wire("model").str, "legacy-model")
          assertEquals(wire("instructions").str, "Provider instructions")
          assertEquals(wire("input")(0)("content")(0)("text").str, "Hello")
          Response.text(
            """data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Provider reply"}]}]}}""" + "\n\n"
          )
        }
      )
    )
    val result = run {
      for {
        provider <- OpenAiCodexClient.make(Some(inferenceCredentials)).provideEnvironment(ZEnvironment(client))
        text <- provider.inferenceWithText(
          "Hello",
          ProviderGenerateOptions(systemInstruction = Some("Provider instructions")),
          Some("legacy-model")
        )
      } yield text
    }
    assertEquals(result, "Provider reply")
  }

  test("agent and provider inference retain the HTTP response-body timeout") {
    List(true, false).foreach { useAgent =>
      run {
        for {
          started <- Promise.make[Nothing, Unit]
          clock = new LoginClock(duration =>
            ZIO.succeed(assertEquals(duration, OpenAiCodexClient.RequestTimeout)) *> started.await
          )
          client = fakeClient(_ =>
            ZIO.succeed(
              Response(body =
                Body.fromStreamChunked(
                  zio.stream.ZStream.fromZIO(started.succeed(()).unit *> ZIO.never.as(0.toByte))
                )
              )
            )
          )
          provider <- OpenAiCodexClient.make(Some(inferenceCredentials)).provideEnvironment(ZEnvironment(client))
          agent = new ChatGPTAgent(AgentId("subscription-agent"), provider)
          invocation =
            if useAgent then
              agent
                .turn(
                  AgentTurnRequest(
                    "model",
                    Chunk(MessagePayload.User("Hello"), MessagePayload.Instructions("Be helpful"))
                  )
                )
                .unit
            else provider.inferenceWithText("Hello", ProviderGenerateOptions(), Some("model")).unit
          result <- invocation.withClock(clock).either
        } yield assert(result.swap.toOption.exists(_.getMessage == "OpenAI Codex request timed out"))
      }
    }
  }

  test("subscription native HTTP turns use OAuth headers and replay tool/continuation items without executing tools") {
    val payload = java.util.Base64.getUrlEncoder
      .withoutPadding()
      .encodeToString(
        """{"https://api.openai.com/auth":{"chatgpt_account_id":"test-account"}}"""
          .getBytes(java.nio.charset.StandardCharsets.UTF_8)
      )
    val token = s"header.$payload.signature"
    run {
      for {
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
        provider <- OpenAiCodexClient
          .make(Some(OAuthCredentialsConfig(token, "test-refresh", Long.MaxValue)))
          .provideEnvironment(ZEnvironment(client))
        agent = new ChatGPTAgent(AgentId("subscription-agent"), provider)
        first <- agent.turn(
          AgentTurnRequest(
            "account-model",
            Chunk(MessagePayload.User("Read README"), MessagePayload.Instructions("Stay concise"))
          )
        )
        call <- ZIO
          .fromOption(first.output.collectFirst { case MessagePayload.FunctionCallRequest(value) => value })
          .orElseFail(new IllegalStateException("Missing tool proposal"))
        second <- agent.turn(
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
