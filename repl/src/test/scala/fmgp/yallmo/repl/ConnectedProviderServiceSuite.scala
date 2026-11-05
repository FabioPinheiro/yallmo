package fmgp.yallmo.repl

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.SessionId
import fmgp.yallmo.orchestration.config.ProviderId
import fmgp.yallmo.server.OrchestratorClient
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import java.util.concurrent.CopyOnWriteArrayList
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import zio.*

final class ConnectedProviderServiceSuite extends FunSuite {
  private val id = ProviderId.unsafe("account")
  private val callback = "http://localhost:1455/auth/callback?state=synthetic&code=private-code"
  private val started =
    ProvidersLoginStart.Result("attempt", "https://auth.openai.com/oauth/authorize?state=synthetic", Long.MaxValue)

  private[repl] class Client extends OrchestratorClient {
    val calls = new CopyOnWriteArrayList[String]()
    var restart = false
    def initialize(clientName: String) = ZIO.dieMessage("Unexpected initialization")
    override def listProviders = ZIO.succeed(
      Chunk(
        ProviderSummary("account", ProviderKind.ChatGPT, ProviderCredentialStatus.Configured),
        ProviderSummary("local", ProviderKind.OAI, ProviderCredentialStatus.NotApplicable)
      )
    )
    override def startProviderLogin(
        params: ProvidersLoginStart.Params
    ): IO[OrchestratorClient.Error, ProvidersLoginStart.Result] = ZIO.succeed {
      assertEquals(params.providerId, "account")
      calls.add("start"); started
    }
    override def completeProviderLogin(
        params: ProvidersLoginComplete.Params
    ): IO[OrchestratorClient.Error, ProvidersLoginComplete.Result] = ZIO.succeed {
      assertEquals(params, ProvidersLoginComplete.Params("attempt", callback))
      calls.add("complete"); ProvidersLoginComplete.Result(restart)
    }
    override def cancelProviderLogin(params: ProvidersLoginCancel.Params) = ZIO.succeed {
      assertEquals(params.attemptId, "attempt")
      calls.add("cancel"); Empty()
    }
    def listSessions = ZIO.dieMessage("Login must not access sessions")
    def loadSession(id: SessionId) = ZIO.dieMessage("Login must not access sessions")
    def listAgentIds = ZIO.dieMessage("Login must not select an agent")
    def listMessages = ZIO.dieMessage("Login must not access history")
    def listBlobIds = ZIO.dieMessage("Login must not access blobs")
    def readBlob(id: BlobId, offset: Long, maxBytes: Int) = ZIO.dieMessage("Login must not access blobs")
    def close = ZIO.dieMessage("Login must not close the shared connection")
  }
  private class Interaction(value: Option[String] = Some(callback)) extends LoginInteraction {
    val output = new CopyOnWriteArrayList[String]()
    def show(message: String): Task[Unit] = ZIO.succeed { output.add(message); () }
    def readRedirect: Task[Option[String]] = ZIO.succeed(value)
    def awaitCancellation: Task[Unit] = ZIO.never
  }

  test("remote listing uses presence-only projections with no endpoints or local catalog access") {
    run(new ConnectedProviderService(new Client).list.map { entries =>
      assertEquals(entries.map(_.displayId), Vector("account", "local"))
      assert(entries.forall(_.endpoint.isEmpty))
      assertEquals(entries.head.credentials, fmgp.yallmo.server.application.ProviderCatalog.CredentialStatus.Configured)
    })
  }

  test("server login completes once with private callback and distinguishes active from saved-only results") {
    for restart <- List(false, true) do {
      val client = new Client
      client.restart = restart
      val input = new Interaction
      val result = run(new ConnectedProviderService(client).run(id, LoginMethod.Browser, input))
      assertEquals(result, if restart then ProviderLoginService.Result.Saved else ProviderLoginService.Result.Activated)
      assertEquals(client.calls.asScala.toList, List("start", "complete", "cancel"))
      assert(!input.output.asScala.mkString.contains("private-code"))
      assert(!result.toString.contains(callback))
    }
  }

  test("empty/cancel/EOF callbacks never exchange and discard the pending attempt") {
    for value <- List(None, Some(""), Some("cancel")) do {
      val client = new Client
      val result = run(new ConnectedProviderService(client).run(id, LoginMethod.Browser, new Interaction(value)))
      assertEquals(result, ProviderLoginService.Result.Cancelled)
      assertEquals(client.calls.asScala.toList, List("start", "cancel"))
    }
  }

  test("cancellation while waiting for callback is known, but after completion admission is uncertain") {
    run(for {
      reading <- Promise.make[Nothing, Unit]
      cancel <- Promise.make[Nothing, Unit]
      first = new Client
      interaction = new Interaction {
        override def readRedirect = reading.succeed(()) *> ZIO.never
        override def awaitCancellation = cancel.await
      }
      waiting <- new ConnectedProviderService(first).run(id, LoginMethod.Browser, interaction).fork
      _ <- reading.await
      _ <- cancel.succeed(())
      known <- waiting.join
      sending <- Promise.make[Nothing, Unit]
      second = new Client {
        override def completeProviderLogin(params: ProvidersLoginComplete.Params) =
          ZIO.succeed { calls.add("complete"); () } *> sending.succeed(()) *> ZIO.never
      }
      other = new Interaction {
        override def awaitCancellation = sending.await
      }
      uncertain <- new ConnectedProviderService(second).run(id, LoginMethod.Browser, other)
    } yield {
      assertEquals(known, ProviderLoginService.Result.Cancelled)
      assertEquals(first.calls.asScala.toList, List("start", "cancel"))
      assertEquals(uncertain, ProviderLoginService.Result.Uncertain)
      assertEquals(second.calls.asScala.toList, List("start", "complete", "cancel"))
    })
  }

  test("lost completion, oversized input and unsafe server URLs are sanitized without retry") {
    val broken = new Client {
      override def completeProviderLogin(params: ProvidersLoginComplete.Params) =
        ZIO.succeed { calls.add("complete"); () } *> ZIO.fail(
          OrchestratorClient.Error.Transport(new Exception("private-code-and-token"))
        )
    }
    val input = new Interaction
    assertEquals(
      run(new ConnectedProviderService(broken).run(id, LoginMethod.Browser, input)),
      ProviderLoginService.Result.Uncertain
    )
    assertEquals(broken.calls.asScala.toList, List("start", "complete", "cancel"))
    assert(!input.output.asScala.mkString.contains("private-code"))
    val oversized = new Client
    assert(
      run(
        new ConnectedProviderService(oversized)
          .run(id, LoginMethod.Browser, new Interaction(Some("x" * (ProvidersLoginComplete.MaxRedirectChars + 1))))
      ).isInstanceOf[ProviderLoginService.Result.Failed]
    )
    assertEquals(oversized.calls.asScala.toList, List("start", "cancel"))
    val unsafe = new Client {
      override def startProviderLogin(params: ProvidersLoginStart.Params) =
        super
          .startProviderLogin(params)
          .map(_.copy(authorizationUrl = "https://auth.openai.com/oauth/authorize?\u001b[2J"))
    }
    val unsafeInput = new Interaction
    assert(
      run(new ConnectedProviderService(unsafe).run(id, LoginMethod.Browser, unsafeInput))
        .isInstanceOf[ProviderLoginService.Result.Failed]
    )
    assertEquals(unsafe.calls.asScala.toList, List("start", "cancel"))
    assert(unsafeInput.output.isEmpty)
  }

  test("timeout and parent interruption clean up known pending attempts") {
    val waiting = new Client {
      override def startProviderLogin(params: ProvidersLoginStart.Params) = ZIO.never
    }
    assertEquals(
      run(new ConnectedProviderService(waiting, Duration.Zero).run(id, LoginMethod.Browser, new Interaction)),
      ProviderLoginService.Result.TimedOut
    )
    run(for {
      reading <- Promise.make[Nothing, Unit]
      client = new Client
      input = new Interaction {
        override def readRedirect = reading.succeed(()) *> ZIO.never
      }
      fiber <- new ConnectedProviderService(client).run(id, LoginMethod.Browser, input).fork
      _ <- reading.await
      _ <- fiber.interrupt
    } yield assertEquals(client.calls.asScala.toList, List("start", "cancel")))
  }

  private def run[A](effect: ZIO[Any, Any, A]): A = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe
      .run(effect.timeoutFail(new Exception("Connected login test timeout"))(5.seconds))
      .getOrThrowFiberFailure()
  }
}
