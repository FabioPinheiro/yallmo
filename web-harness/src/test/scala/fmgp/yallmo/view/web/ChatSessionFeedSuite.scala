package fmgp.yallmo.view.web

import fmgp.yallmo.model.{AgentId, MessageId}
import fmgp.yallmo.orchestration.{EventOrigin, EventSession, SessionId}
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import java.time.Instant
import munit.FunSuite
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Promise}
import zio.Chunk

final class ChatSessionFeedSuite extends FunSuite {
  private val id = SessionId.unsafe("chat")
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
  private def session(head: MessageSnapshot, revision: Long): EventSession =
    EventSession(id, Chunk.empty, if head.id == first.id then 1L else 2L, Some(head.id), revision)
  private class Feed(callback: EventsUpdate.Params => Unit) {
    val result = Promise[EventsSubscribe.Result]()
    var cancelled = false
    def baseline(): Unit = result.success(
      EventsSubscribe.Result(
        "sub",
        "stream",
        "nonce",
        10,
        Chunk(first),
        Chunk(session(first, 1)),
        Chunk.empty,
        SubscriptionScope.Session(id)
      )
    )
    def event(sequence: Long, change: EventChange, stream: String = "stream"): Unit =
      callback(EventsUpdate.Params("sub", stream, "nonce", sequence, change, Some(EventOrigin(id))))
  }
  private class Harness {
    private val callbacks = mutable.Queue.empty[Runnable]
    given ExecutionContext = new ExecutionContext {
      def execute(callback: Runnable): Unit = callbacks.enqueue(callback)
      def reportFailure(error: Throwable): Unit = throw error
    }
    val feeds = mutable.ArrayBuffer.empty[Feed]
    var state = ChatFeedState()
    val model = new ChatSessionFeed(
      id,
      (scope, callback) => {
        assertEquals(scope, SubscriptionScope.Session(id))
        val feed = new Feed(callback)
        feeds += feed
        EventSubscription(feed.result.future, () => feed.cancelled = true)
      },
      value => state = value
    )
    def flush(): Unit = while callbacks.nonEmpty do callbacks.dequeue().run()
    def start(): Feed = { model.start(); feeds.last }
    def ids: List[MessageId] = state.snapshot.toList.flatMap(_.messages.map(_.id))
  }

  test("early pending replay and canonical confirmation dedupe by real ID, duplicate sequence ignored") {
    val h = new Harness
    val f = h.start()
    f.event(11, EventChange.Pending("op", Chunk(last)))
    f.baseline()
    h.flush()
    assertEquals(h.state.pending.map(_.id), List(last.id))
    f.event(12, EventChange.Confirmed("op", Chunk(first, last), Chunk(session(last, 2))))
    assertEquals(h.ids, List(first.id, last.id))
    assertEquals(h.state.pending, Nil)
    f.event(12, EventChange.Uncertain("duplicate"))
    assertEquals(h.feeds.size, 1)
  }

  test("object persistence keeps pending publication visible until canonical CAS, and rebase prunes conflicts") {
    val h = new Harness
    val f = h.start()
    f.baseline()
    h.flush()
    f.event(11, EventChange.Pending("tool", Chunk(last)))
    f.event(12, EventChange.Confirmed("tool", Chunk(last), Chunk.empty))
    assertEquals(h.state.pending.map(_.id), List(last.id))
    assertEquals(h.ids, List(first.id))
    f.event(13, EventChange.Confirmed("cas", Chunk(first, last), Chunk(session(last, 2))))
    assertEquals(h.state.pending, Nil)
    assertEquals(h.ids, List(first.id, last.id))
    val branch = last.copy(id = MessageId.unsafe("sha256:" + "c" * 64))
    f.event(14, EventChange.Pending("conflict", Chunk(branch)))
    f.event(15, EventChange.Confirmed("conflict", Chunk(branch), Chunk.empty))
    assertEquals(h.state.pending.map(_.id), List(branch.id))
    f.event(16, EventChange.Confirmed("rebase", Chunk(first, last), Chunk(session(last, 3))))
    assertEquals(h.state.pending, Nil)
    assertEquals(h.ids, List(first.id, last.id))
  }

  for mode <- List("uncertain", "gap", "identity", "ancestry", "overflow") do
    test(s"$mode removes provisional ghosts and permits only one bounded read-only recovery") {
      val h = new Harness
      val f = h.start()
      f.baseline()
      h.flush()
      f.event(11, EventChange.Pending("ghost", Chunk(last)))
      mode match {
        case "gap"      => f.event(13, EventChange.Pending("x", Chunk(last)))
        case "identity" => f.event(12, EventChange.Pending("x", Chunk(last)), "wrong")
        case "ancestry" =>
          val missing = last.copy(parentId = Some(MessageId.unsafe("sha256:" + "c" * 64)))
          f.event(12, EventChange.Confirmed("x", Chunk(missing), Chunk(session(missing, 2))))
        case "overflow" =>
          f.event(12, EventChange.Pending("large", Chunk(last.copy(content = "x" * (2 * 1024 * 1024 + 1)))))
        case _ => f.event(12, EventChange.Uncertain("x"))
      }
      assert(f.cancelled)
      assertEquals(h.state.pending, Nil)
      assertEquals(h.ids, List(first.id))
      assertEquals(h.feeds.size, 2)
      val recovery = h.feeds.last
      recovery.baseline()
      h.flush()
      recovery.event(11, EventChange.Uncertain("again"))
      assertEquals(h.feeds.size, 2)
      assert(h.state.error.nonEmpty)
    }

  test("cancel before baseline retires queued callbacks and preserves new scope") {
    val h = new Harness
    val old = h.start()
    h.model.stop()
    val next = h.start()
    old.baseline()
    old.event(11, EventChange.Pending("old", Chunk(last)))
    next.baseline()
    h.flush()
    assert(old.cancelled)
    assertEquals(h.ids, List(first.id))
    assertEquals(h.state.pending, Nil)
  }

  test("bootstrap overflow cancels before acknowledgement without admitting late baseline") {
    val h = new Harness
    val f = h.start()
    (1 to 129).foreach(index => f.event(index.toLong, EventChange.Pending("op", Chunk(last))))
    assert(f.cancelled)
    assertEquals(h.feeds.size, 2)
    f.baseline()
    h.flush()
    assertEquals(h.ids, Nil)
  }
}
