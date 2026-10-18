package fmgp.yallmo.repl

import fmgp.yallmo.orchestration.config.{Config, OAuthCredentialsConfig, ProviderConfig, ProviderId}
import fmgp.yallmo.provider.openai.*
import munit.FunSuite
import zio.*
import zio.json.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{FileSystems, Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*

final class ProviderLoginServiceSuite extends FunSuite {
  import ProviderLoginService.*

  private val fixture = """{
    "userName":"synthetic-user","futureRoot":{"keep":true},
    "providers":{
      "first":{"providerType":"ChatGPT","futureProfile":[1,2]},
      "second":{"providerType":"ChatGPT","credentials":{"accessToken":"other-access","refreshToken":"other-refresh","expiresAtMillis":1}},
      "local":{"providerType":"OAI","endpoint":"http://localhost:1234/v1"}
    }
  }"""
  private val selected = ProviderId.unsafe("first")
  private val credentials = OAuthCredentialsConfig("synthetic-access-secret", "synthetic-refresh-secret", 1234)
  private val browser =
    OpenAiCodexBrowserLogin("https://auth.example/authorize?state=synthetic", "synthetic", "verifier-secret")
  private val redirect = "http://localhost:1455/auth/callback?state=synthetic&code=callback-secret"

  private class FakeAuth extends OpenAiCodexAuth.OAuthFlow {
    var starts = 0
    var completions = 0
    def startBrowserLogin = ZIO.succeed { starts += 1; browser }
    def completeBrowserLogin(login: OpenAiCodexBrowserLogin, value: String): Task[OAuthCredentialsConfig] =
      ZIO.succeed {
        assertEquals(login, browser)
        assertEquals(value, redirect)
        completions += 1
        credentials
      }
  }

  private class Interaction(input: Option[String] = Some(redirect)) extends LoginInteraction {
    val output = new CopyOnWriteArrayList[String]()
    def show(message: String): Task[Unit] = ZIO.succeed { output.add(message); () }
    def readRedirect: Task[Option[String]] = ZIO.succeed(input)
    def awaitCancellation: Task[Unit] = ZIO.never
    def rendered: String = output.asScala.mkString("\n")
  }

  private def run[A](effect: ZIO[Any, Any, A]): A = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
  }

  private def withFile(body: (Path, ReplProviderCatalog) => Unit): Unit = {
    val directory = Files.createTempDirectory("provider-login-suite-")
    val path = directory.resolve("synthetic.json")
    try {
      Files.writeString(path, fixture, UTF_8)
      body(path, run(ReplProviderCatalog.make(path)))
    } finally {
      val paths = Files.walk(directory)
      try paths.iterator.asScala.toVector.reverse.foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }

  private def assertPrivate(value: String): Unit =
    List(
      "synthetic-access-secret",
      "synthetic-refresh-secret",
      "callback-secret",
      "verifier-secret",
      redirect
    )
      .foreach(secret => assert(!value.contains(secret), s"sensitive value rendered"))

  test("browser login persists only the selected profile") {
    withFile { (path, catalog) =>
      val io = new Interaction
      val result = run(new ProviderLoginService(catalog, new FakeAuth).run(selected, LoginMethod.Browser, io))
      assertEquals(result, Result.Saved)
      val config = Files.readString(path, UTF_8).fromJson[Config].toOption.get
      assertEquals(
        config.providers(selected).asInstanceOf[ProviderConfig.ChatGPT].credentials.map(_.accessToken),
        Some(credentials.accessToken)
      )
      assertEquals(
        config
          .providers(ProviderId.unsafe("second"))
          .asInstanceOf[ProviderConfig.ChatGPT]
          .credentials
          .map(_.accessToken),
        Some("other-access")
      )
      assert(Files.readString(path, UTF_8).contains("futureProfile"))
      assert(Files.readString(path, UTF_8).contains("futureRoot"))
      assertEquals(PosixFilePermissions.toString(Files.getPosixFilePermissions(path)), "rw-------")
      assert(io.rendered.contains("require restart"))
      assertPrivate(io.rendered + result.toString)
    }
  }

  test("OAI, missing and untrusted targets are rejected before authentication or interaction") {
    withFile { (path, catalog) =>
      for (
        (id, error) <- List(
          "local" -> ReplProviderCatalog.CredentialError.NotChatGPT,
          "missing" -> ReplProviderCatalog.CredentialError.MissingProvider
        )
      ) {
        val before = Files.readString(path, UTF_8)
        val auth = new FakeAuth
        val io = new Interaction
        assertEquals(
          run(new ProviderLoginService(catalog, auth).run(ProviderId.unsafe(id), LoginMethod.Browser, io)),
          Result.Failed(Error.Catalog(error))
        )
        assertEquals(auth.starts, 0)
        assertEquals(io.rendered, "")
        assertEquals(Files.readString(path, UTF_8), before)
      }
      val changed =
        fixture.replace("\"futureProfile\":", "\"endpoint\":\"https://untrusted.example\",\"futureProfile\":")
      Files.writeString(path, changed, UTF_8)
      val auth = new FakeAuth
      assertEquals(
        run(new ProviderLoginService(catalog, auth).run(selected, LoginMethod.Browser, new Interaction)),
        Result.Failed(
          Error.Catalog(ReplProviderCatalog.CredentialError.Catalog(ReplProviderCatalog.Error.InvalidEndpoint))
        )
      )
      assertEquals(auth.starts, 0)
      assertEquals(Files.readString(path, UTF_8), changed)
    }
  }

  test("empty input and explicit cancellation never exchange or write credentials") {
    List(None, Some(""), Some("  "), Some("cancel")).foreach { input =>
      withFile { (path, catalog) =>
        val auth = new FakeAuth
        assertEquals(
          run(new ProviderLoginService(catalog, auth).run(selected, LoginMethod.Browser, new Interaction(input))),
          Result.Cancelled
        )
        assertEquals(auth.completions, 0)
        assertEquals(Files.readString(path, UTF_8), fixture)
      }
    }
  }

  test("cancellation during browser startup interrupts auth before any write") {
    withFile { (path, catalog) =>
      val finalized = new java.util.concurrent.atomic.AtomicBoolean(false)
      val result = run(for {
        started <- Promise.make[Nothing, Unit]
        pending = (started.succeed(()) *> ZIO.never).ensuring(ZIO.succeed(finalized.set(true)))
        auth = new FakeAuth {
          override def startBrowserLogin = pending
        }
        io = new Interaction { override def awaitCancellation = started.await }
        result <- new ProviderLoginService(catalog, auth).run(selected, LoginMethod.Browser, io)
      } yield result)
      assertEquals(result, Result.Cancelled)
      assert(finalized.get())
      assertEquals(Files.readString(path, UTF_8), fixture)
    }
  }

  test("timeouts bound pending redirect input without writes") {
    withFile { (path, catalog) =>
      val pendingAuth = new FakeAuth
      val pendingInput = new Interaction { override def readRedirect = ZIO.never }
      assertEquals(
        run(new ProviderLoginService(catalog, pendingAuth, 40.millis).run(selected, LoginMethod.Browser, pendingInput)),
        Result.TimedOut
      )
      assertEquals(Files.readString(path, UTF_8), fixture)
    }
  }

  test("initial interaction is bounded and terminal failures never write") {
    withFile { (path, catalog) =>
      val auth = new FakeAuth
      val pending = new Interaction { override def show(message: String): Task[Unit] = ZIO.never }
      assertEquals(
        run(new ProviderLoginService(catalog, auth, 40.millis).run(selected, LoginMethod.Browser, pending)),
        Result.TimedOut
      )
      assertEquals(auth.starts, 0)
      val broken = new Interaction { override def readRedirect = ZIO.die(new RuntimeException(redirect)) }
      val result = run(new ProviderLoginService(catalog, auth).run(selected, LoginMethod.Browser, broken))
      assertEquals(result, Result.Failed(Error.AuthenticationFailed))
      assertPrivate(result.toString + broken.rendered)
      assertEquals(Files.readString(path, UTF_8), fixture)
    }
  }

  test("save uses latest unrelated edits made during OAuth and leaves absent legacy storage absent") {
    withFile { (path, catalog) =>
      val changed = fixture.replace("synthetic-user", "updated-user").replace("other-access", "updated-other-access")
      val auth = new FakeAuth {
        override def completeBrowserLogin(login: OpenAiCodexBrowserLogin, value: String) = ZIO.attempt {
          Files.writeString(path, changed, UTF_8)
          credentials
        }
      }
      assertEquals(
        run(new ProviderLoginService(catalog, auth).run(selected, LoginMethod.Browser, new Interaction)),
        Result.Saved
      )
      val saved = Files.readString(path, UTF_8).fromJson[Config].toOption.get
      assertEquals(saved.userName, "updated-user")
      assertEquals(
        saved
          .providers(ProviderId.unsafe("second"))
          .asInstanceOf[ProviderConfig.ChatGPT]
          .credentials
          .map(_.accessToken),
        Some("updated-other-access")
      )
    }
  }

  test("auth failures and defects are fixed sanitized errors and never write") {
    withFile { (path, catalog) =>
      List(false, true).foreach { defect =>
        val auth = new FakeAuth {
          override def completeBrowserLogin(login: OpenAiCodexBrowserLogin, value: String) =
            if defect then ZIO.die(new RuntimeException(redirect + credentials.accessToken))
            else ZIO.fail(new RuntimeException(redirect + credentials.refreshToken))
        }
        val io = new Interaction
        val result = run(new ProviderLoginService(catalog, auth).run(selected, LoginMethod.Browser, io))
        assertEquals(result, Result.Failed(Error.AuthenticationFailed))
        assertPrivate(result.toString + io.rendered)
        assertEquals(Files.readString(path, UTF_8), fixture)
      }
    }
  }

  test("deleted, converted and untrusted targets changed during OAuth never receive credentials") {
    withFile { (path, catalog) =>
      val scenarios = List(
        """{"userName":"changed","providers":{}}""" -> ReplProviderCatalog.CredentialError.MissingProvider,
        """{"userName":"changed","providers":{"first":{"providerType":"OAI","endpoint":"http://localhost:1234/v1"}}}""" -> ReplProviderCatalog.CredentialError.NotChatGPT,
        """{"userName":"changed","providers":{"first":{"providerType":"ChatGPT","endpoint":"https://untrusted.example"}}}""" -> ReplProviderCatalog.CredentialError
          .Catalog(ReplProviderCatalog.Error.InvalidEndpoint)
      )
      scenarios.foreach { (changed, error) =>
        Files.writeString(path, fixture, UTF_8)
        val auth = new FakeAuth {
          override def completeBrowserLogin(login: OpenAiCodexBrowserLogin, value: String) = ZIO.attempt {
            Files.writeString(path, changed, UTF_8)
            credentials
          }
        }
        assertEquals(
          run(new ProviderLoginService(catalog, auth).run(selected, LoginMethod.Browser, new Interaction)),
          Result.Failed(Error.Catalog(error))
        )
        assertEquals(Files.readString(path, UTF_8), changed)
      }
    }
  }

  test("invalid returned credentials fail without reporting saved or modifying configuration") {
    withFile { (path, catalog) =>
      val auth = new FakeAuth {
        override def completeBrowserLogin(login: OpenAiCodexBrowserLogin, value: String) =
          ZIO.succeed(credentials.copy(accessToken = ""))
      }
      assertEquals(
        run(new ProviderLoginService(catalog, auth).run(selected, LoginMethod.Browser, new Interaction)),
        Result.Failed(Error.Catalog(ReplProviderCatalog.CredentialError.InvalidCredentials))
      )
      assertEquals(Files.readString(path, UTF_8), fixture)
    }
  }

  test("atomic save failure is not reported saved and preserves bytes on unsupported filesystems") {
    withFile { (path, _) =>
      val archive = path.resolveSibling("synthetic.zip")
      val fileSystem = FileSystems.newFileSystem(URI.create(s"jar:${archive.toUri}"), Map("create" -> "true").asJava)
      try {
        val target = fileSystem.getPath("/synthetic.json")
        Files.writeString(target, fixture, UTF_8)
        val catalog = run(ReplProviderCatalog.make(target))
        assertEquals(
          run(new ProviderLoginService(catalog, new FakeAuth).run(selected, LoginMethod.Browser, new Interaction)),
          Result.Failed(
            Error.Catalog(ReplProviderCatalog.CredentialError.Catalog(ReplProviderCatalog.Error.SaveFailed))
          )
        )
        assertEquals(Files.readString(target, UTF_8), fixture)
      } finally fileSystem.close()
    }
  }

  test("profile display sanitizes terminal controls and does not claim runtime activation") {
    withFile { (path, catalog) =>
      val unsafeId = "unsafe\u001b\n\u202e"
      Files.writeString(
        path,
        Config("test", providers = Map(ProviderId.unsafe(unsafeId) -> ProviderConfig.ChatGPT())).toJson,
        UTF_8
      )
      val io = new Interaction(None)
      assertEquals(
        run(new ProviderLoginService(catalog, new FakeAuth).run(ProviderId.unsafe(unsafeId), LoginMethod.Browser, io)),
        Result.Cancelled
      )
      assert(io.rendered.contains("unsafe???"))
      assert(!io.rendered.contains("\u001b"))
      assert(!io.rendered.contains("\u202e"))
      assert(io.rendered.contains("does not switch the active agent"))
    }
  }
}
