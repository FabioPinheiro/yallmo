package fmgp.yallmo.server.application

import fmgp.yallmo.orchestration.config.{Config, OAuthCredentialsConfig, ProviderConfig, ProviderId}
import fmgp.yallmo.provider.openai.{OpenAiCodexAuth, OpenAiCodexBrowserLogin}
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.time.{Instant, ZoneOffset}
import java.util.{Comparator, UUID}
import munit.FunSuite
import zio.{Config as _, *}
import zio.json.*

final class ProviderAdministrationSuite extends FunSuite {
  private val owner = UUID.randomUUID()
  private val other = UUID.randomUUID()
  private val credentials =
    OAuthCredentialsConfig("synthetic-access-secret", "synthetic-refresh-secret", 9999999999999L)
  private val fixture =
    """{"agents":{},"providers":{"account":{"providerType":"ChatGPT"},"local":{"providerType":"OAI","endpoint":"http://user:secret@localhost/v1?key=secret"}},"preserve":{"value":true}}"""
  private val baseline = Instant.parse("2026-01-01T00:00:00Z")
  private def clock(at: Instant): Clock = Clock.ClockJava(java.time.Clock.fixed(at, ZoneOffset.UTC))

  private final case class Fixture(path: Path, service: ProviderAdministration, exchanges: Ref[Int])
  private def fixtureScope: ZIO[Scope, Throwable, Fixture] =
    fixtureScopeWithActivation((_, _, _) => ZIO.succeed(false))

  private def fixtureScopeWithActivation(
      activate: (Path, ProviderId, OAuthCredentialsConfig) => UIO[Boolean]
  ): ZIO[Scope, Throwable, Fixture] =
    for {
      root <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("provider-admin-")))(root =>
        ZIO.attemptBlocking {
          val paths = Files.walk(root)
          try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
          finally paths.close()
        }.orDie
      )
      path <- ZIO.attemptBlocking(Files.writeString(root.resolve("private.json"), fixture))
      catalog <- ProviderCatalog.make(path)
      exchanges <- Ref.make(0)
      auth = new OpenAiCodexAuth.OAuthFlow {
        def startBrowserLogin: Task[OpenAiCodexBrowserLogin] = ZIO.succeed(
          OpenAiCodexBrowserLogin(
            "https://auth.openai.com/oauth/authorize?state=synthetic-state",
            "synthetic-state",
            "secret-verifier"
          )
        )
        def completeBrowserLogin(login: OpenAiCodexBrowserLogin, redirectUrl: String): Task[OAuthCredentialsConfig] =
          exchanges.update(_ + 1) *> ZIO
            .fail(new Exception("secret-code-and-token"))
            .unless(
              redirectUrl == "valid-callback" && login.codeVerifier == "secret-verifier"
            )
            .as(credentials)
      }
      service <- ProviderAdministration.make(catalog, auth, (id, value) => activate(path, id, value))
    } yield Fixture(path, service, exchanges)

  test("listing is a safe presence projection and login target validation never exchanges tokens") {
    run(ZIO.scoped {
      for {
        f <- fixtureScope
        entries <- f.service.list
        unsupported <- f.service.start(owner, "local").either
        missing <- f.service.start(owner, "missing").either
        blank <- f.service.start(owner, " ").either
        count <- f.exchanges.get
      } yield {
        assertEquals(entries.map(_.id), Chunk("account", "local"))
        assertEquals(entries.head.credentials, ProviderCredentialStatus.LoggedOut)
        assert(!entries.toJson.contains("secret"))
        assert(!entries.toJson.contains("endpoint"))
        assertEquals(unsupported.swap.toOption.get.code, ErrorCode.Rejected)
        assertEquals(missing.swap.toOption.get.code, ErrorCode.NotFound)
        assert(blank.isLeft)
        assertEquals(count, 0)
        assertEquals(Files.readString(f.path), fixture)
      }
    })
  }

  test("attempts are exact-connection owned and single-use even with concurrent completion") {
    run(ZIO.scoped {
      for {
        f <- fixtureScope
        started <- f.service.start(owner, "account")
        _ <- f.service.cancel(other, started.attemptId)
        foreign <- f.service.complete(other, ProvidersLoginComplete.Params(started.attemptId, "valid-callback")).either
        outcomes <- ZIO.foreachPar(List(1, 2))(_ =>
          f.service.complete(owner, ProvidersLoginComplete.Params(started.attemptId, "valid-callback")).either
        )
        count <- f.exchanges.get
        after <- f.service.list
      } yield {
        assertEquals(foreign.swap.toOption.get.code, ErrorCode.NotFound)
        assertEquals(outcomes.count(_.isRight), 1)
        assertEquals(outcomes.count(_.swap.toOption.exists(_.code == ErrorCode.NotFound)), 1)
        assertEquals(count, 1)
        assertEquals(after.head.credentials, ProviderCredentialStatus.Configured)
        val saved = Files.readString(f.path).fromJson[Config].toOption.get
        assertEquals(saved.providers(ProviderId.unsafe("account")), ProviderConfig.ChatGPT(Some(credentials)))
        assert(Files.readString(f.path).contains("preserve"))
        assertEquals(Files.getPosixFilePermissions(f.path), PosixFilePermissions.fromString("rw-------"))
        assert(!outcomes.toString.contains("synthetic-access-secret"))
      }
    })
  }

  test("cancel, replacement, disconnect and expiry invalidate attempts without changing configuration") {
    run(ZIO.scoped {
      for {
        f <- fixtureScope
        first <- f.service.start(owner, "account").withClock(clock(baseline))
        second <- f.service.start(owner, "account").withClock(clock(baseline))
        replaced <- f.service.complete(owner, ProvidersLoginComplete.Params(first.attemptId, "valid-callback")).either
        _ <- f.service.cancel(owner, second.attemptId)
        cancelled <- f.service.complete(owner, ProvidersLoginComplete.Params(second.attemptId, "valid-callback")).either
        third <- f.service.start(owner, "account").withClock(clock(baseline))
        expired <- f.service
          .complete(owner, ProvidersLoginComplete.Params(third.attemptId, "valid-callback"))
          .withClock(clock(baseline.plusSeconds(900)))
          .either
        fourth <- f.service.start(owner, "account")
        _ <- f.service.disconnect(owner)
        disconnected <- f.service
          .complete(owner, ProvidersLoginComplete.Params(fourth.attemptId, "valid-callback"))
          .either
        count <- f.exchanges.get
      } yield {
        assert(
          List(replaced, cancelled, expired, disconnected).forall(_.swap.toOption.exists(_.code == ErrorCode.NotFound))
        )
        assertEquals(count, 0)
        assertEquals(Files.readString(f.path), fixture)
      }
    })
  }

  test("failed authorization is sanitized and consumed; changed/deleted targets are not recreated") {
    run(ZIO.scoped {
      for {
        f <- fixtureScope
        first <- f.service.start(owner, "account")
        denied <- f.service.complete(owner, ProvidersLoginComplete.Params(first.attemptId, "bad-secret-code")).either
        replay <- f.service.complete(owner, ProvidersLoginComplete.Params(first.attemptId, "valid-callback")).either
        second <- f.service.start(owner, "account")
        _ <- ZIO.attemptBlocking(Files.writeString(f.path, "{\"agents\":{},\"providers\":{}}"))
        removed <- f.service.complete(owner, ProvidersLoginComplete.Params(second.attemptId, "valid-callback")).either
        count <- f.exchanges.get
      } yield {
        assertEquals(denied.swap.toOption.get.code, ErrorCode.Rejected)
        assert(!denied.toString.contains("secret-code"))
        assertEquals(replay.swap.toOption.get.code, ErrorCode.NotFound)
        assertEquals(removed.swap.toOption.get.code, ErrorCode.NotFound)
        assertEquals(count, 1)
        assert(!Files.readString(f.path).contains("account"))
      }
    })
  }

  test("a profile removed during token exchange is not recreated at commit") {
    run(ZIO.scoped {
      for {
        f <- fixtureScope
        catalog <- ProviderCatalog.make(f.path)
        flow = new OpenAiCodexAuth.OAuthFlow {
          def startBrowserLogin: Task[OpenAiCodexBrowserLogin] = ZIO.succeed(
            OpenAiCodexBrowserLogin("https://auth.openai.com/oauth/authorize?state=synthetic", "synthetic", "private")
          )
          def completeBrowserLogin(
              login: OpenAiCodexBrowserLogin,
              redirectUrl: String
          ): Task[OAuthCredentialsConfig] = {
            val _ = (login, redirectUrl)
            ZIO
              .attemptBlocking(Files.writeString(f.path, "{\"agents\":{},\"providers\":{}}"))
              .as(credentials)
          }
        }
        service <- ProviderAdministration.make(catalog, flow)
        login <- service.start(owner, "account")
        result <- service.complete(owner, ProvidersLoginComplete.Params(login.attemptId, "valid-callback")).either
      } yield {
        assertEquals(result.swap.toOption.get.code, ErrorCode.NotFound)
        assert(!Files.readString(f.path).contains("account"))
        assert(!Files.readString(f.path).contains("synthetic-access-secret"))
      }
    })
  }

  test("activation follows a successful private save and reports whether restart is still required") {
    run(ZIO.scoped {
      for {
        activated <- Ref.make(Vector.empty[OAuthCredentialsConfig])
        f <- fixtureScopeWithActivation((path, id, value) =>
          ZIO.succeed {
            val stored = Files.readString(path).fromJson[Config].toOption.get.providers(id)
            assertEquals(stored, ProviderConfig.ChatGPT(Some(value)))
          } *> activated.update(_ :+ value).as(true)
        )
        login <- f.service.start(owner, "account")
        result <- f.service.complete(owner, ProvidersLoginComplete.Params(login.attemptId, "valid-callback"))
        next <- f.service.start(owner, "account")
        _ <- ZIO.attemptBlocking(Files.writeString(f.path, "{\"agents\":{},\"providers\":{}}"))
        failedSave <- f.service.complete(owner, ProvidersLoginComplete.Params(next.attemptId, "valid-callback")).either
        seen <- activated.get
        fallback <- fixtureScope
        pending <- fallback.service.start(owner, "account")
        savedOnly <- fallback.service
          .complete(owner, ProvidersLoginComplete.Params(pending.attemptId, "valid-callback"))
      } yield {
        assert(!result.restartRequired)
        assertEquals(seen, Vector(credentials))
        assert(failedSave.isLeft)
        assert(savedOnly.restartRequired)
      }
    })
  }

  test("concurrent login commits cannot reorder persisted and active credentials") {
    run(ZIO.scoped {
      for {
        f <- fixtureScope
        catalog <- ProviderCatalog.make(f.path)
        entering <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        exchangedSecond <- Promise.make[Nothing, Unit]
        active <- Ref.make(Option.empty[OAuthCredentialsConfig])
        flow = new OpenAiCodexAuth.OAuthFlow {
          def startBrowserLogin: Task[OpenAiCodexBrowserLogin] =
            ZIO.succeed(
              OpenAiCodexBrowserLogin("https://auth.openai.com/oauth/authorize?state=test", "test", "private")
            )
          def completeBrowserLogin(
              login: OpenAiCodexBrowserLogin,
              redirectUrl: String
          ): Task[OAuthCredentialsConfig] = {
            val _ = login
            (if redirectUrl == "second" then exchangedSecond.succeed(()) else ZIO.unit)
              .as(credentials.copy(accessToken = redirectUrl))
          }
        }
        service <- ProviderAdministration.make(
          catalog,
          flow,
          (_, value) =>
            (if value.accessToken == "first" then entering.succeed(()) *> release.await else ZIO.unit) *>
              active.set(Some(value)).as(true)
        )
        first <- service.start(owner, "account")
        second <- service.start(other, "account")
        a <- service.complete(owner, ProvidersLoginComplete.Params(first.attemptId, "first")).fork
        _ <- entering.await
        b <- service.complete(other, ProvidersLoginComplete.Params(second.attemptId, "second")).fork
        _ <- exchangedSecond.await
        _ <- ZIO.yieldNow
        during <- ZIO.attemptBlocking(Files.readString(f.path).fromJson[Config].toOption.get)
        _ <- release.succeed(())
        outcomes <- a.join.zip(b.join)
        current <- active.get
        saved <- ZIO.attemptBlocking(Files.readString(f.path).fromJson[Config].toOption.get)
      } yield {
        assertEquals(
          during.providers(ProviderId.unsafe("account")),
          ProviderConfig.ChatGPT(Some(credentials.copy(accessToken = "first")))
        )
        assertEquals(current, Some(credentials.copy(accessToken = "second")))
        assertEquals(saved.providers(ProviderId.unsafe("account")), ProviderConfig.ChatGPT(current))
        assert(!outcomes._1.restartRequired && !outcomes._2.restartRequired)
      }
    })
  }

  test("capacity is bounded, replacement does not grow it, and expired attempts are reclaimed") {
    run(ZIO.scoped {
      for {
        f <- fixtureScope
        owners = List.fill(64)(UUID.randomUUID())
        _ <- ZIO.foreachDiscard(owners)(id => f.service.start(id, "account").withClock(clock(baseline)))
        busy <- f.service.start(other, "account").withClock(clock(baseline)).either
        replacement <- f.service.start(owners.head, "account").withClock(clock(baseline))
        _ <- f.service.expire.withClock(clock(baseline.plusSeconds(901)))
        available <- f.service.start(other, "account").withClock(clock(baseline.plusSeconds(901))).either
      } yield {
        assertEquals(busy.swap.toOption.get.code, ErrorCode.Busy)
        assert(replacement.attemptId.nonEmpty)
        assert(available.isRight)
      }
    })
  }

  private def run[A](effect: ZIO[Any, Any, A]): A = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(effect.timeoutFail(new AssertionError("Provider test timed out"))(10.seconds))
      .getOrThrowFiberFailure()
  }
}
