package fmgp.yallmo.view.web

import fmgp.yallmo.model.AgentId
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import munit.FunSuite
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.timers.setTimeout
import zio.Chunk

final class ProvidersModelSuite extends FunSuite {
  private val methods =
    List(ProvidersList.method, ProvidersLoginStart.method, ProvidersLoginComplete.method, ProvidersLoginCancel.method)
  private val info =
    Initialize.Result(Version, "server", Identity(AgentId("human"), Role.Operator), methods, Nil, MaxBlobChunkBytes)
  private val entries = Chunk(
    ProviderSummary("account", ProviderKind.ChatGPT, ProviderCredentialStatus.LoggedOut),
    ProviderSummary("local", ProviderKind.OAI, ProviderCredentialStatus.NotApplicable)
  )
  private val started =
    ProvidersLoginStart.Result("attempt", "https://auth.openai.com/oauth/authorize?state=synthetic", 1000L)

  private final class Harness {
    var now = 0L
    val listings = mutable.ArrayBuffer.empty[Promise[Chunk[ProviderSummary]]]
    val starts = mutable.ArrayBuffer.empty[(ProvidersLoginStart.Params, Promise[ProvidersLoginStart.Result])]
    val completions = mutable.ArrayBuffer.empty[(ProvidersLoginComplete.Params, Promise[ProvidersLoginComplete.Result])]
    val cancellations = mutable.ArrayBuffer.empty[ProvidersLoginCancel.Params]
    val model = new ProvidersModel(
      () => { val p = Promise[Chunk[ProviderSummary]](); listings += p; p.future },
      params => { val p = Promise[ProvidersLoginStart.Result](); starts += ((params, p)); p.future },
      params => { val p = Promise[ProvidersLoginComplete.Result](); completions += ((params, p)); p.future },
      params => { cancellations += params; Future.successful(Empty()) },
      () => now
    )
    model.configure(info)
    def load(): Future[Unit] = {
      model.reload()
      listings.last.success(entries)
      tick()
    }
    def login(): Future[Unit] = load().flatMap { _ =>
      model.begin("account")
      starts.last._2.success(started)
      tick()
    }
  }

  test("viewers, unsupported servers, unknown providers and OAI cannot start login") {
    val h = new Harness
    h.model.configure(info.copy(identity = Identity(AgentId("viewer"), Role.Viewer)))
    h.model.reload()
    h.model.begin("account")
    assert(h.listings.isEmpty)
    h.model.configure(info.copy(methods = methods.dropRight(1)))
    assert(!h.model.state.now().enabled)
    h.model.configure(info)
    h.load().map { _ =>
      h.model.begin("missing")
      h.model.begin("local")
      assert(h.starts.isEmpty)
    }
  }

  test("double clicks send once; completion clears callback text and announces active login without restart") {
    val h = new Harness
    h.load()
      .flatMap { _ =>
        h.model.begin("account")
        h.model.begin("account")
        assertEquals(h.starts.size, 1)
        h.starts.head._2.success(started)
        tick()
      }
      .flatMap { _ =>
        h.model.redirect("private-callback-code")
        h.model.finish()
        h.model.finish()
        assertEquals(
          h.completions.map(_._1).toList,
          List(ProvidersLoginComplete.Params("attempt", "private-callback-code"))
        )
        assertEquals(h.model.state.now().redirect, "")
        assert(!h.model.state.now().toString.contains("private-callback-code"))
        h.completions.head._2.success(ProvidersLoginComplete.Result(false))
        tick()
      }
      .map { _ =>
        assert(!h.model.state.now().busy)
        assertEquals(h.model.state.now().login, None)
        assert(h.model.state.now().notice.contains("Login saved and active"))
        assert(h.model.state.now().notice.contains("no server restart"))
        assertEquals(h.listings.size, 2)
        h.listings.last.success(entries)
      }
  }

  test("saved-only completion retains the restart-required fallback notice") {
    val h = new Harness
    h.login()
      .flatMap { _ =>
        h.model.redirect("private-callback")
        h.model.finish()
        h.completions.head._2.success(ProvidersLoginComplete.Result(true))
        tick()
      }
      .map { _ =>
        assert(h.model.state.now().notice.contains("Restart the Yallmo server"))
        assert(!h.model.state.now().notice.contains("saved and active"))
        h.listings.last.success(entries)
      }
  }

  test("cancel and expiry clear the private draft and discard the attempt") {
    val h = new Harness
    h.login().map { _ =>
      h.model.redirect("private-callback")
      h.model.clearRedirect()
      assertEquals(h.model.state.now().redirect, "")
      assert(h.model.state.now().login.nonEmpty)
      h.now = 1000L
      h.model.expire()
      assertEquals(h.cancellations.toList, List(ProvidersLoginCancel.Params("attempt")))
      assertEquals(h.model.state.now().login, None)
      assert(h.model.state.now().notice.contains("expired"))
      h.model.finish()
      assert(h.completions.isEmpty)
    }
  }

  test("disconnect invalidates late start and completion replies and never replays login") {
    val h = new Harness
    h.load()
      .flatMap { _ =>
        h.model.begin("account")
        h.model.disable()
        h.starts.head._2.success(started)
        tick()
      }
      .flatMap { _ =>
        assertEquals(h.model.state.now().login, None)
        assert(!h.model.state.now().enabled)
        h.model.configure(info)
        h.login()
      }
      .flatMap { _ =>
        h.model.redirect("private-callback")
        h.model.finish()
        h.model.disable()
        h.completions.head._2.success(ProvidersLoginComplete.Result(true))
        tick()
      }
      .map { _ =>
        assertEquals(h.completions.size, 1)
        assertEquals(h.model.state.now().redirect, "")
        assert(!h.model.state.now().notice.contains("Login saved"))
        assert(!h.model.state.now().enabled)
      }
  }

  test("untrusted authorization links are never displayed") {
    val h = new Harness
    h.load()
      .flatMap { _ =>
        h.model.begin("account")
        h.starts.head._2.success(started.copy(authorizationUrl = "javascript:alert(1)"))
        tick()
      }
      .map { _ =>
        assertEquals(h.model.state.now().login, None)
        assertEquals(h.cancellations.toList, List(ProvidersLoginCancel.Params("attempt")))
        assert(h.model.state.now().notice.contains("Invalid"))
      }
  }

  test("failed completion clears the consumed attempt and does not display exception contents") {
    val h = new Harness
    h.login()
      .flatMap { _ =>
        h.model.redirect("private-callback")
        h.model.finish()
        h.completions.head._2.failure(new Exception("private-code-and-token"))
        tick()
      }
      .map { _ =>
        assertEquals(h.model.state.now().login, None)
        assertEquals(h.model.state.now().redirect, "")
        assert(!h.model.state.now().notice.contains("private-code"))
        assert(!h.model.state.now().busy)
      }
  }

  test("out-of-order lists cannot overwrite newer results or repopulate disconnected state") {
    val h = new Harness
    h.model.reload()
    h.model.reload()
    h.listings(1).success(entries)
    tick()
      .flatMap { _ =>
        h.listings(0).success(Chunk.empty)
        tick()
      }
      .flatMap { _ =>
        assertEquals(h.model.state.now().entries, entries.toList)
        h.model.reload()
        h.model.disable()
        h.listings(2).success(entries)
        tick()
      }
      .map { _ => assertEquals(h.model.state.now().entries, Nil) }
  }

  private def tick(): Future[Unit] = {
    val p = Promise[Unit]()
    setTimeout(0)(p.success(()))
    p.future
  }
}
