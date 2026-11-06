package fmgp.yallmo.view.web

import fmgp.yallmo.model.{AgentId, MessageId}
import fmgp.yallmo.orchestration.{SessionId, SessionSummary}
import fmgp.yallmo.server.protocol.YallmoProtocol.{MessageSnapshot, SessionSnapshot}
import java.time.Instant
import munit.FunSuite
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.timers.setTimeout
import zio.Chunk

final class ConversationGraphModelSuite extends FunSuite {
  private val first = MessageSnapshot(
    MessageId.unsafe("sha256:" + "a" * 64),
    AgentId("human"),
    None,
    Instant.parse("2026-01-01T00:00:00Z"),
    "user",
    "hello",
    Chunk.empty
  )
  private val last = first.copy(id = MessageId.unsafe("sha256:" + "b" * 64), parentId = Some(first.id))
  private def snapshot(id: String, messages: MessageSnapshot*): SessionSnapshot =
    SessionSnapshot(SessionId.unsafe(id), Chunk.empty, Chunk.fromIterable(messages))
  private def summary(id: String): SessionSummary = SessionSummary(SessionId.unsafe(id), 0L, Chunk.empty)

  test("published head uses oldest-first history last, includes race-new messages, and keeps empty sessions") {
    val model = new ConversationGraphModel(
      () => Future.successful(List(first)),
      () => Future.successful(List(summary("main"), summary("shared"), summary("empty"))),
      id => Future.successful(if id == "empty" then snapshot(id) else snapshot(id, first, last)),
      () => 0L
    )
    model.enable()
    model.refresh()
    tick().map { _ =>
      val result = model.state.now().snapshot.get
      assertEquals(result.messages.map(_.id), List(first.id, last.id))
      assertEquals(
        result.sessions.map(_.head),
        List(Some(MessageId.value(last.id)), Some(MessageId.value(last.id)), None)
      )
      assert(result.sessions.forall(_.error.isEmpty))
      assert(!model.state.now().loading)
    }
  }

  test("individual session errors are explicit unknown heads, not empty history") {
    val model = new ConversationGraphModel(
      () => Future.successful(Nil),
      () => Future.successful(List(summary("broken"))),
      _ => Future.failed(new RuntimeException("Missing ancestor")),
      () => 0L
    )
    model.enable()
    model.refresh()
    tick().map { _ =>
      assertEquals(
        model.state.now().snapshot.get.sessions,
        List(GraphSession("broken", None, Some("Missing ancestor")))
      )
    }
  }

  test("loading and failed refresh keep the prior snapshot; out-of-order replies cannot replace the newest") {
    val requests = mutable.ArrayBuffer.empty[Promise[List[MessageSnapshot]]]
    val model = new ConversationGraphModel(
      () => { val p = Promise[List[MessageSnapshot]](); requests += p; p.future },
      () => Future.successful(Nil),
      id => Future.successful(snapshot(id)),
      () => 0L
    )
    model.enable()
    model.refresh()
    requests.head.success(List(first))
    tick()
      .flatMap { _ =>
        model.refresh()
        assertEquals(model.state.now().snapshot.get.messages, List(first))
        assert(model.state.now().loading)
        requests(1).failure(new RuntimeException("offline"))
        tick()
      }
      .flatMap { _ =>
        assertEquals(model.state.now().error, Some("offline"))
        assertEquals(model.state.now().snapshot.get.messages, List(first))
        model.refresh()
        model.refresh()
        requests(3).success(List(last))
        tick()
      }
      .flatMap { _ =>
        requests(2).success(List(first))
        tick()
      }
      .map { _ => assertEquals(model.state.now().snapshot.get.messages, List(last)) }
  }

  test("connection changes and unmount reject stale replies; remount accepts a fresh snapshot") {
    var version = 0L
    val requests = mutable.ArrayBuffer.empty[Promise[List[MessageSnapshot]]]
    var sessionListings = 0
    val model = new ConversationGraphModel(
      () => { val p = Promise[List[MessageSnapshot]](); requests += p; p.future },
      () => { sessionListings += 1; Future.successful(Nil) },
      id => Future.successful(snapshot(id)),
      () => version
    )
    model.enable()
    model.refresh()
    version += 1
    requests.head.success(List(first))
    tick()
      .flatMap { _ =>
        assertEquals(model.state.now().snapshot, None)
        assert(model.state.now().error.nonEmpty)
        assertEquals(sessionListings, 0)
        model.refresh()
        model.disable()
        requests(1).success(List(first))
        tick()
      }
      .flatMap { _ =>
        assertEquals(model.state.now().snapshot, None)
        model.enable()
        model.refresh()
        requests(2).success(List(last))
        tick()
      }
      .map { _ => assertEquals(model.state.now().snapshot.get.messages, List(last)) }
  }

  test("session fetch concurrency is bounded to four and stale batches never start") {
    val pending = mutable.ArrayBuffer.empty[(String, Promise[SessionSnapshot])]
    var version = 0L
    val model = new ConversationGraphModel(
      () => Future.successful(Nil),
      () => Future.successful((1 to 11).map(i => summary(s"s$i")).toList),
      id => { val p = Promise[SessionSnapshot](); pending += ((id, p)); p.future },
      () => version
    )
    model.enable()
    model.refresh()
    tick()
      .flatMap { _ =>
        assertEquals(pending.size, 4)
        pending.take(3).foreach { case (id, p) => p.success(snapshot(id)) }
        tick()
      }
      .flatMap { _ =>
        assertEquals(pending.size, 4)
        pending(3)._2.success(snapshot(pending(3)._1))
        tick()
      }
      .flatMap { _ =>
        assertEquals(pending.size, 8)
        version += 1
        pending.drop(4).foreach { case (id, p) => p.success(snapshot(id)) }
        tick()
      }
      .map { _ =>
        assertEquals(pending.size, 8)
        assertEquals(model.state.now().snapshot, None)
        assert(!model.state.now().loading)
      }
  }

  test("overlapping manual refreshes share the four-session concurrency bound") {
    val pending = mutable.ArrayBuffer.empty[(String, Promise[SessionSnapshot])]
    val model = new ConversationGraphModel(
      () => Future.successful(Nil),
      () => Future.successful((1 to 5).map(i => summary(s"s$i")).toList),
      id => { val p = Promise[SessionSnapshot](); pending += ((id, p)); p.future },
      () => 0L
    )
    model.enable()
    model.refresh()
    tick()
      .flatMap { _ =>
        assertEquals(pending.size, 4)
        model.refresh()
        tick()
      }
      .flatMap { _ =>
        assertEquals(pending.size, 4)
        pending.take(4).foreach { case (id, p) => p.success(snapshot(id)) }
        tick()
      }
      .flatMap { _ =>
        assertEquals(pending.size, 8)
        pending.drop(4).foreach { case (id, p) => p.success(snapshot(id)) }
        tick()
      }
      .flatMap { _ =>
        assertEquals(pending.size, 9)
        pending.last._2.success(snapshot(pending.last._1))
        tick()
      }
      .map { _ =>
        assertEquals(model.state.now().snapshot.get.sessions.size, 5)
        assert(!model.state.now().loading)
      }
  }

  test("empty successful snapshot is distinct from loading or error") {
    val model = new ConversationGraphModel(
      () => Future.successful(Nil),
      () => Future.successful(Nil),
      id => Future.successful(snapshot(id)),
      () => 0L
    )
    model.enable()
    model.refresh()
    tick().map { _ =>
      assertEquals(model.state.now(), GraphLoadState(Some(GraphSnapshot(Nil, Nil))))
    }
  }

  test("busy polls do not queue or invalidate snapshots; next tick reads the entire new inventory and pointers") {
    val requests = mutable.ArrayBuffer.empty[Promise[List[MessageSnapshot]]]
    var head = first
    var listings = 0
    var summaries = List.empty[SessionSummary]
    val model = new ConversationGraphModel(
      () => { val p = Promise[List[MessageSnapshot]](); requests += p; p.future },
      () => { listings += 1; Future.successful(List(summary("main"))) },
      id => Future.successful(snapshot(id, head)),
      () => 0L,
      updateSummaries = values => summaries = values
    )
    model.enable()
    model.poll()
    (1 to 10).foreach(_ => model.poll())
    assertEquals(requests.size, 1)
    requests.head.success(List(first))
    tick()
      .flatMap { _ =>
        assertEquals(model.state.now().snapshot.get.messages, List(first))
        assertEquals(summaries.map(_.id.value), List("main"))
        assertEquals(listings, 1)
        head = last
        model.poll()
        requests(1).success(List(first, last))
        tick()
      }
      .map { _ =>
        assertEquals(requests.size, 2)
        assertEquals(listings, 2)
        assertEquals(model.state.now().snapshot.get.messages, List(first, last))
        assertEquals(model.state.now().snapshot.get.sessions.head.head, Some(MessageId.value(last.id)))
      }
  }

  test("polling pauses hidden/disconnected, resumes promptly, retains failed snapshots and recovers without writes") {
    var connected = false
    var visible = true
    var reads = 0
    var fail = false
    val model = new ConversationGraphModel(
      () => {
        reads += 1; if fail then Future.failed(new RuntimeException("offline")) else Future.successful(List(first))
      },
      () => Future.successful(Nil),
      id => Future.successful(snapshot(id)),
      () => 0L,
      () => connected,
      () => visible
    )
    model.enable()
    model.poll()
    assertEquals(reads, 0)
    connected = true
    visible = false
    model.poll()
    assertEquals(reads, 0)
    visible = true
    model.poll()
    tick()
      .flatMap { _ =>
        assertEquals(reads, 1)
        fail = true
        model.poll()
        tick()
      }
      .flatMap { _ =>
        assertEquals(model.state.now().snapshot.get.messages, List(first))
        assertEquals(model.state.now().error, Some("offline"))
        fail = false
        connected = false
        model.poll()
        assertEquals(reads, 2)
        connected = true
        model.poll()
        assertEquals(model.state.now().error, Some("offline"))
        tick()
      }
      .map { _ =>
        assertEquals(reads, 3)
        assertEquals(model.state.now().error, None)
        model.disable()
        model.poll()
        assertEquals(reads, 3)
      }
  }

  test("hiding during a snapshot stops subsequent automatic reads; unmount rejects summaries too") {
    var visible = true
    var listings = 0
    var published = 0
    val requests = mutable.ArrayBuffer.empty[Promise[List[MessageSnapshot]]]
    val model = new ConversationGraphModel(
      () => { val p = Promise[List[MessageSnapshot]](); requests += p; p.future },
      () => { listings += 1; Future.successful(Nil) },
      id => Future.successful(snapshot(id)),
      () => 0L,
      visible = () => visible,
      updateSummaries = _ => published += 1
    )
    model.enable()
    model.poll()
    visible = false
    requests.head.success(List(first))
    tick()
      .flatMap { _ =>
        assertEquals(listings, 0)
        assertEquals(published, 0)
        visible = true
        model.poll()
        model.disable()
        requests(1).success(List(last))
        tick()
      }
      .map { _ =>
        assertEquals(listings, 0)
        assertEquals(published, 0)
        assertEquals(model.state.now().snapshot, None)
      }
  }

  test("visibility return while busy does not supersede a slow session batch") {
    var visible = true
    var inventories = 0
    val pending = Promise[SessionSnapshot]()
    val model = new ConversationGraphModel(
      () => { inventories += 1; Future.successful(List(first)) },
      () => Future.successful(List(summary("main"))),
      _ => pending.future,
      () => 0L,
      visible = () => visible
    )
    model.enable()
    model.poll()
    tick()
      .flatMap { _ =>
        visible = false
        model.poll()
        visible = true
        model.poll()
        assertEquals(inventories, 1)
        pending.success(snapshot("main", first, last))
        tick()
      }
      .map { _ =>
        assertEquals(model.state.now().snapshot.get.sessions.head.head, Some(MessageId.value(last.id)))
        assert(!model.state.now().loading)
      }
  }

  test("explicit refresh after reconnect supersedes busy old inventory and never publishes stale sidebar summaries") {
    val requests = mutable.ArrayBuffer.empty[Promise[List[MessageSnapshot]]]
    var version = 0L
    var published = 0
    val model = new ConversationGraphModel(
      () => { val p = Promise[List[MessageSnapshot]](); requests += p; p.future },
      () => Future.successful(Nil),
      id => Future.successful(snapshot(id)),
      () => version,
      updateSummaries = _ => published += 1
    )
    model.enable()
    model.poll()
    version += 1
    model.refresh()
    requests(1).success(List(last))
    tick()
      .flatMap { _ =>
        requests.head.success(List(first))
        tick()
      }
      .map { _ =>
        assertEquals(model.state.now().snapshot.get.messages, List(last))
        assertEquals(published, 1)
        assertEquals(model.state.now().error, None)
      }
  }

  test("Live off invalidates in-flight inventory, gates refresh/reconnect/ticks, and resumes immediately once") {
    val requests = mutable.ArrayBuffer.empty[Promise[List[MessageSnapshot]]]
    var version = 0L
    var listings = 0
    var published = 0
    val model = new ConversationGraphModel(
      () => { val p = Promise[List[MessageSnapshot]](); requests += p; p.future },
      () => { listings += 1; Future.successful(Nil) },
      id => Future.successful(snapshot(id)),
      () => version,
      updateSummaries = _ => published += 1
    )
    model.enable()
    model.poll()
    model.setLive(false)
    assert(!model.state.now().loading)
    version += 1
    model.refresh()
    model.poll()
    model.setLive(false)
    assertEquals(requests.size, 1)
    requests.head.success(List(first))
    tick()
      .flatMap { _ =>
        assertEquals(model.state.now().snapshot, None)
        assertEquals(listings, 0)
        assertEquals(published, 0)
        model.setLive(true)
        model.setLive(true)
        model.poll()
        assertEquals(requests.size, 2)
        requests(1).success(List(last))
        tick()
      }
      .map { _ =>
        assertEquals(model.state.now().snapshot.get.messages, List(last))
        assertEquals(published, 1)
      }
  }

  test("Live off during session reads freezes graph/sidebar and preserves prior snapshot until resume") {
    val reads = mutable.ArrayBuffer.empty[Promise[SessionSnapshot]]
    var published = 0
    val model = new ConversationGraphModel(
      () => Future.successful(List(first)),
      () => Future.successful(List(summary("main"))),
      _ => { val p = Promise[SessionSnapshot](); reads += p; p.future },
      () => 0L,
      updateSummaries = _ => published += 1
    )
    model.enable()
    model.poll()
    tick()
      .flatMap { _ =>
        reads.head.success(snapshot("main", first))
        tick()
      }
      .flatMap { _ =>
        model.poll()
        tick()
      }
      .flatMap { _ =>
        assertEquals(reads.size, 2)
        model.setLive(false)
        reads(1).success(snapshot("main", first, last))
        tick()
      }
      .flatMap { _ =>
        assertEquals(model.state.now().snapshot.get.sessions.head.head, Some(MessageId.value(first.id)))
        assertEquals(published, 1)
        model.setLive(true)
        model.poll()
        tick()
      }
      .flatMap { _ =>
        assertEquals(reads.size, 3)
        reads(2).success(snapshot("main", first, last))
        tick()
      }
      .map { _ =>
        assertEquals(model.state.now().snapshot.get.sessions.head.head, Some(MessageId.value(last.id)))
        assertEquals(published, 2)
      }
  }

  private def tick(): Future[Unit] = {
    val promise = Promise[Unit]()
    setTimeout(0)(promise.success(()))
    promise.future
  }
}
