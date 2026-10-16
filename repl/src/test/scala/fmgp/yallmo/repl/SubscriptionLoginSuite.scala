package fmgp.yallmo.repl

import fmgp.yallmo.provider.openai.*
import fmgp.yallmo.repl.grammar.LoginMethod
import munit.FunSuite
import zio.*
import java.util.concurrent.CopyOnWriteArrayList

final class SubscriptionLoginSuite extends FunSuite {
  private val browser =
    OpenAiCodexBrowserLogin("https://auth.example/authorize?state=synthetic", "synthetic", "verifier")
  private val device = OpenAiCodexDeviceCode("private-device-id", "PAIR-CODE", 1.second, "https://auth.example/device")
  private val redirect = "http://localhost:1455/auth/callback?state=synthetic&code=secret-code"

  private class FakeAuth extends SubscriptionLoginAuth {
    var completedRedirect: Option[String] = None
    var deviceCompletions = 0
    def startBrowser = ZIO.succeed(browser)
    def completeBrowser(login: OpenAiCodexBrowserLogin, value: String): Task[Unit] = ZIO.succeed {
      assertEquals(login, browser)
      completedRedirect = Some(value)
    }
    def startDevice = ZIO.succeed(device)
    def completeDevice(value: OpenAiCodexDeviceCode): Task[Unit] = ZIO.succeed {
      assertEquals(value, device)
      deviceCompletions += 1
    }
  }

  private class FakeInteraction(input: Option[String] = Some(redirect)) extends LoginInteraction {
    val output = new CopyOnWriteArrayList[String]()
    def show(message: String): Task[Unit] = ZIO.succeed { output.add(message); () }
    def readRedirect = ZIO.succeed(input)
    def awaitCancellation: Task[Unit] = ZIO.never
    def rendered: String = output.toArray.mkString("\n")
  }

  test("browser login displays real auth URL before collecting a full redirect through private IO") {
    val auth = new FakeAuth
    val io = new FakeInteraction {
      override def readRedirect = ZIO.succeed {
        assert(rendered.contains(browser.url))
        assert(rendered.contains("including state"))
        Some(redirect)
      }
    }
    assertEquals(runZIO(new SubscriptionLogin(auth).run(LoginMethod.Browser, io)), LoginResult.Saved)
    assertEquals(auth.completedRedirect, Some(redirect))
    assert(!io.rendered.contains("secret-code"))
    assert(io.rendered.contains("does not switch"))
  }

  test("device instructions are visible before polling; device auth ID stays private") {
    val io = new FakeInteraction
    val auth = new FakeAuth {
      override def completeDevice(value: OpenAiCodexDeviceCode) = ZIO.succeed {
        assertEquals(value, device)
        assert(io.rendered.contains(device.verificationUri))
        assert(io.rendered.contains(device.userCode))
        assert(io.rendered.contains("Enter, Ctrl+C or EOF cancels"))
      }
    }
    assertEquals(runZIO(new SubscriptionLogin(auth).run(LoginMethod.Device, io)), LoginResult.Saved)
    assert(!io.rendered.contains(device.deviceAuthId))
  }

  test("empty input, explicit cancel and EOF cancel without exchanging or saving credentials") {
    for (value <- List(None, Some(""), Some("  "), Some("cancel"))) {
      val auth = new FakeAuth
      assertEquals(
        runZIO(new SubscriptionLogin(auth).run(LoginMethod.Browser, new FakeInteraction(value))),
        LoginResult.Cancelled
      )
      assertEquals(auth.completedRedirect, None)
    }
  }

  test("auth and terminal failures including defects are sanitized") {
    val io = new FakeInteraction
    val auth = new FakeAuth {
      override def completeBrowser(login: OpenAiCodexBrowserLogin, value: String) =
        ZIO.fail(new RuntimeException(value + " token=private"))
    }
    assertEquals(runZIO(new SubscriptionLogin(auth).run(LoginMethod.Browser, io)), LoginResult.Failed)
    assert(!io.rendered.contains("secret-code"))
    val brokenIo = new FakeInteraction {
      override def readRedirect = ZIO.die(new RuntimeException(redirect))
    }
    assertEquals(runZIO(new SubscriptionLogin(new FakeAuth).run(LoginMethod.Browser, brokenIo)), LoginResult.Failed)
  }

  test("device cancellation interrupts polling and runs its finalizer") {
    val finalized = new java.util.concurrent.atomic.AtomicBoolean(false)
    runZIO(for {
      started <- Promise.make[Nothing, Unit]
      auth = new FakeAuth {
        override def completeDevice(value: OpenAiCodexDeviceCode) =
          (started.succeed(()) *> ZIO.never).ensuring(ZIO.succeed(finalized.set(true)))
      }
      io = new FakeInteraction {
        override def awaitCancellation = started.await
      }
      result <- new SubscriptionLogin(auth).run(LoginMethod.Device, io)
      _ <- ZIO.succeed(assertEquals(result, LoginResult.Cancelled))
    } yield ())
    assert(finalized.get())
  }

  test("whole flow timeout interrupts pending input and polling") {
    val finalized = new java.util.concurrent.atomic.AtomicBoolean(false)
    val auth = new FakeAuth {
      override def completeDevice(value: OpenAiCodexDeviceCode) =
        ZIO.never.ensuring(ZIO.succeed(finalized.set(true)))
    }
    assertEquals(
      runZIO(new SubscriptionLogin(auth, 40.millis).run(LoginMethod.Device, new FakeInteraction)),
      LoginResult.TimedOut
    )
    assert(finalized.get())
    val io = new FakeInteraction {
      override def readRedirect = ZIO.never
    }
    assertEquals(
      runZIO(new SubscriptionLogin(new FakeAuth, 40.millis).run(LoginMethod.Browser, io)),
      LoginResult.TimedOut
    )
  }

  private def runZIO[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
