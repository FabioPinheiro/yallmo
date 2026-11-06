package fmgp.yallmo.view.web

import fmgp.yallmo.model.{AgentId, MessageId}
import fmgp.yallmo.orchestration.{EventSession as PublishedSession, SessionId, SessionSummary}
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import java.time.Instant
import munit.FunSuite
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Promise}
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
  private def session(id: String, head: MessageSnapshot, revision: Long): PublishedSession =
    PublishedSession(SessionId.unsafe(id), Chunk.empty, if head.id == first.id then 1L else 2L, Some(head.id), revision)

  private class Feed(callback: EventsUpdate.Params => Unit) {
    val result = Promise[EventsSubscribe.Result]()
    var cancelled = false
    def baseline(
        messages: List[MessageSnapshot] = List(first),
        sessions: List[PublishedSession] = Nil,
        pending: List[EventPending] = Nil
    ): Unit = result.success(
      EventsSubscribe.Result(
        "sub",
        "stream",
        "nonce",
        10,
        Chunk.fromIterable(messages),
        Chunk.fromIterable(sessions),
        Chunk.fromIterable(pending)
      )
    )
    def event(sequence: Long, change: EventChange, stream: String = "stream"): Unit =
      callback(EventsUpdate.Params("sub", stream, "nonce", sequence, change))
  }

  private class Harness {
    // Advance Future callbacks explicitly instead of sleeping or guessing how many microtasks must settle.
    private val callbacks = mutable.Queue.empty[Runnable]
    private val executionContext = new ExecutionContext {
      def execute(callback: Runnable): Unit = callbacks.enqueue(callback)
      def reportFailure(error: Throwable): Unit = throw error
    }
    val feeds = mutable.ArrayBuffer.empty[Feed]
    var connected = true
    var visible = true
    var version = 0L
    var summaries = List.empty[SessionSummary]
    val model = new ConversationGraphModel(
      callback => {
        val feed = new Feed(callback)
        feeds += feed
        EventSubscription(feed.result.future, () => feed.cancelled = true)
      },
      () => version,
      () => connected,
      () => visible,
      value => summaries = value
    )(using executionContext)
    model.enable()
    def flush(): Unit = while callbacks.nonEmpty do callbacks.dequeue().run()
    def start(): Feed = { model.refresh(); feeds.last }
    def ids: Set[MessageId] = model.state.now().snapshot.get.messages.map(_.id).toSet
  }

  test("notification before baseline and pre-registration pending reconcile through one subscription") {
    val h = new Harness
    val feed = h.start()
    feed.event(11, EventChange.Confirmed("op", Chunk(last), Chunk.empty))
    feed.baseline(pending = List(EventPending("op", Chunk(last))))
    h.flush()
    assertEquals(h.ids, Set(first.id, last.id))
    assert(!h.model.state.now().loading)
    assertEquals(h.feeds.size, 1)
  }

  test("baseline revision dominates buffered pointers; deltas preserve other sessions and A to B to A") {
    val h = new Harness
    val feed = h.start()
    feed.event(11, EventChange.Confirmed("old", Chunk.empty, Chunk(session("main", first, 1))))
    feed.baseline(sessions = List(session("main", last, 2), session("other", first, 1)))
    h.flush()
    assertEquals(h.summaries.size, 2)
    assertEquals(
      h.model.state.now().snapshot.get.sessions.find(_.id == "main").get.head,
      Some(MessageId.value(last.id))
    )
    feed.event(12, EventChange.Confirmed("new", Chunk.empty, Chunk(session("main", first, 3))))
    assertEquals(
      h.model.state.now().snapshot.get.sessions.find(_.id == "main").get.head,
      Some(MessageId.value(first.id))
    )
    assertEquals(h.summaries.size, 2)
    feed.event(13, EventChange.Confirmed("stale", Chunk.empty, Chunk(session("main", last, 2))))
    assertEquals(h.summaries.find(_.id.value == "main").get.messageCount, 1L)
  }

  test("pending confirmation and duplicate/late observations cannot undo confirmed messages") {
    val h = new Harness
    val feed = h.start()
    feed.baseline()
    h.flush()
    feed.event(11, EventChange.Pending("op", Chunk(last)))
    assert(h.ids.contains(last.id))
    assert(!h.model.isConfirmed(last.id))
    feed.event(12, EventChange.Confirmed("op", Chunk(last), Chunk.empty))
    assert(h.model.isConfirmed(last.id))
    feed.event(12, EventChange.Uncertain("op"))
    feed.event(13, EventChange.Pending("op", Chunk(last)))
    assertEquals(h.ids, Set(first.id, last.id))
    assertEquals(h.feeds.size, 1)
  }

  test("uncertainty removes only optimistic ghosts, preserves confirmed branches and resubscribes once") {
    val h = new Harness
    val feed = h.start()
    feed.baseline()
    h.flush()
    feed.event(11, EventChange.Pending("ghost", Chunk(last)))
    feed.event(12, EventChange.Uncertain("ghost"))
    assertEquals(h.ids, Set(first.id))
    assert(feed.cancelled)
    val recovery = h.feeds.last
    recovery.baseline(messages = Nil)
    h.flush()
    assertEquals(h.ids, Set(first.id))
    recovery.event(11, EventChange.Uncertain("again"))
    assertEquals(h.feeds.size, 2)
    assert(h.model.state.now().error.nonEmpty)
  }

  for pause <- List("live", "hidden", "disconnect", "unmount") do
    test(s"$pause freezes callbacks and sidebar including late baseline") {
      val h = new Harness
      val feed = h.start()
      pause match {
        case "live"       => h.model.setLive(false)
        case "hidden"     => h.visible = false; h.model.eligibilityChanged()
        case "disconnect" => h.connected = false; h.version += 1; h.model.eligibilityChanged()
        case _            => h.model.disable()
      }
      feed.baseline(sessions = List(session("main", first, 1)))
      feed.event(11, EventChange.Confirmed("op", Chunk(last), Chunk.empty))
      h.flush()
      assert(feed.cancelled)
      assert(h.model.state.now().snapshot.isEmpty)
      assertEquals(h.summaries, Nil)
    }

  test("live resume obtains a fresh baseline and ignores the old generation") {
    val h = new Harness
    val feed = h.start()
    feed.baseline()
    h.flush()
    h.model.setLive(false)
    h.model.refresh()
    assertEquals(h.feeds.size, 1)
    h.model.setLive(true)
    feed.event(11, EventChange.Pending("old", Chunk(last)))
    h.feeds.last.baseline()
    h.flush()
    assertEquals(h.ids, Set(first.id))
  }

  for mode <- List("gap", "stream") do
    test(s"$mode invalidates the stream with bounded read-only recovery") {
      val h = new Harness
      val feed = h.start()
      feed.baseline()
      h.flush()
      feed.event(
        if mode == "gap" then 12 else 11,
        EventChange.Pending("op", Chunk(last)),
        if mode == "stream" then "restart" else "stream"
      )
      assert(feed.cancelled)
      assertEquals(h.feeds.size, 2)
    }

  test("bootstrap event count overflow cancels the old generation and rejects its late result") {
    val h = new Harness
    val feed = h.start()
    (11L to 139L).foreach(i => feed.event(i, EventChange.Pending(i.toString, Chunk.empty)))
    assert(feed.cancelled)
    assertEquals(h.feeds.size, 2)
    feed.baseline(messages = List(last))
    h.feeds.last.baseline()
    h.flush()
    assertEquals(h.ids, Set(first.id))
  }

  test("bootstrap UTF8 byte overflow is bounded") {
    val h = new Harness
    val feed = h.start()
    val large = last.copy(content = "🦆" * 200000)
    (11L to 13L).foreach(i => feed.event(i, EventChange.Pending(i.toString, Chunk(large))))
    assert(feed.cancelled)
    assertEquals(h.feeds.size, 2)
  }

  test("active pending bound includes operation identities, not only message bytes") {
    val h = new Harness
    val feed = h.start()
    feed.baseline()
    h.flush()
    (11L to 13L).foreach(i => feed.event(i, EventChange.Pending("x" * 800000 + i, Chunk(last))))
    assert(feed.cancelled)
    assertEquals(h.feeds.size, 2)
    assertEquals(h.ids, Set(first.id))
  }

  test("failed resubscribe retains the last good graph and never starts a retry loop") {
    val h = new Harness
    h.start().baseline()
    h.flush()
    h.start().result.failure(new RuntimeException("busy"))
    h.flush()
    assertEquals(h.ids, Set(first.id))
    assertEquals(h.feeds.size, 2)
    assertEquals(h.model.state.now().error, Some("busy"))
  }
}
