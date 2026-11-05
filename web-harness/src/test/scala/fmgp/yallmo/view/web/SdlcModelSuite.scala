package fmgp.yallmo.view.web

import munit.FunSuite
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.timers.setTimeout

final class SdlcModelSuite extends FunSuite {
  private final class Harness {
    val requests = mutable.ArrayBuffer.empty[(SdlcCollection, Promise[List[SdlcRecord]])]
    val model = new SdlcModel(collection => {
      val promise = Promise[List[SdlcRecord]]()
      requests += ((collection, promise))
      promise.future
    })
  }
  private val records = SdlcRecord.decode("""[{"id":"task-1","text":"First\nDetails","unknown":42}]""").toOption.get

  test("construction does not fetch; mount loads tasks and intents independently without RPC initialization") {
    val h = new Harness
    h.model.refresh()
    assertEquals(h.requests.size, 0)
    h.model.mount()
    assertEquals(h.requests.map(_._1).toList, List(SdlcCollection.Tasks, SdlcCollection.Intents))
    assertEquals(h.model.state.now(), SdlcState())
    h.requests(0)._2.success(records)
    tick().flatMap { _ =>
      assertEquals(h.model.state.now().tasks, SdlcLoadState.Ready(records))
      assertEquals(h.model.state.now().intents, SdlcLoadState.Loading)
      h.requests(1)._2.success(Nil)
      tick().map { _ => assertEquals(h.model.state.now().intents, SdlcLoadState.Ready(Nil)) }
    }
  }

  test("one failed section leaves the other visible; refresh resets both and recovers") {
    val h = new Harness
    h.model.mount()
    h.requests(0)._2.failure(new RuntimeException("HTTP 503"))
    h.requests(1)._2.success(records)
    tick().flatMap { _ =>
      assertEquals(h.model.state.now(), SdlcState(SdlcLoadState.Failed("HTTP 503"), SdlcLoadState.Ready(records)))
      h.model.refresh()
      assertEquals(h.model.state.now(), SdlcState())
      assertEquals(h.requests.size, 4)
      h.requests(2)._2.success(Nil)
      h.requests(3)._2.success(Nil)
      tick().map { _ =>
        assertEquals(h.model.state.now(), SdlcState(SdlcLoadState.Ready(Nil), SdlcLoadState.Ready(Nil)))
      }
    }
  }

  test("refresh suppresses stale success and stale failure from the previous generation") {
    val h = new Harness
    h.model.mount()
    h.model.refresh()
    h.requests(2)._2.success(Nil)
    h.requests(3)._2.success(Nil)
    tick().flatMap { _ =>
      val current = h.model.state.now()
      h.requests(0)._2.success(records)
      h.requests(1)._2.failure(new RuntimeException("old error"))
      tick().map { _ => assertEquals(h.model.state.now(), current) }
    }
  }

  test("unmount prevents all state updates and refreshes; remount rejects the old generation") {
    val h = new Harness
    h.model.mount()
    h.model.unmount()
    val unmounted = h.model.state.now()
    h.model.refresh()
    assertEquals(h.requests.size, 2)
    h.requests(0)._2.success(records)
    tick().flatMap { _ =>
      assertEquals(h.model.state.now(), unmounted)
      h.model.mount()
      h.requests(1)._2.failure(new RuntimeException("previous mount"))
      h.requests(2)._2.success(Nil)
      h.requests(3)._2.success(Nil)
      tick().map { _ =>
        assertEquals(h.model.state.now(), SdlcState(SdlcLoadState.Ready(Nil), SdlcLoadState.Ready(Nil)))
      }
    }
  }

  test("synchronous transport failures become visible section errors, not thrown mount errors") {
    val model = new SdlcModel(_ => throw new RuntimeException("Network unavailable"))
    model.mount()
    tick().map { _ =>
      assertEquals(
        model.state.now(),
        SdlcState(
          SdlcLoadState.Failed("Network unavailable"),
          SdlcLoadState.Failed("Network unavailable")
        )
      )
    }
  }

  private def tick(): Future[Unit] = {
    val result = Promise[Unit]()
    val _ = setTimeout(20)(result.success(()))
    result.future
  }
}
