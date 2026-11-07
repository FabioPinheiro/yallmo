package fmgp.yallmo.view.web

import fmgp.yallmo.model.{AgentId, MessageId}
import fmgp.yallmo.orchestration.{AgentResult, EventOrigin, EventSession, SessionId, SessionSummary}
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.{*, given}
import java.time.Instant
import munit.FunSuite
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

final class ChatModelSuite extends FunSuite {
  private val assistant = AgentId("assistant")
  private val first = message('a', None, "user")
  private val tool = message('b', Some(first.id), "tool_call")
  private val output = message('c', Some(tool.id), "tool_output")
  private val reply = message('d', Some(output.id), "assistant")
  private def message(char: Char, parent: Option[MessageId], role: String): MessageSnapshot = MessageSnapshot(
    MessageId.unsafe("sha256:" + char.toString * 64),
    assistant,
    parent,
    Instant.parse("2026-01-01T00:00:00Z").plusSeconds(char.toLong),
    role,
    "same text",
    Chunk.empty
  )

  private class Harness {
    private val callbacks = mutable.Queue.empty[Runnable]
    given ExecutionContext = new ExecutionContext {
      def execute(runnable: Runnable): Unit = callbacks.enqueue(runnable)
      def reportFailure(error: Throwable): Unit = throw error
    }
    val socket = new OrchestratorWSClientSuite.Harness()
    val model = new ChatModel(socket.client)
    def flush(): Unit = while callbacks.nonEmpty do callbacks.dequeue().run()
    val initialization = socket.initialize(methods = List(TurnSubmit.method), configured = Some(Chunk(assistant)))
    flush()
    val info = initialization.value.get.get
    model.enter()
    model.configure(info)
    flush()
    def request(method: String, index: Int = -1): JsonRpc.Request[Json.Obj] = {
      val requests = socket.sent.filter(_.method == method)
      if index < 0 then requests.last else requests(index)
    }
    def params[A: JsonDecoder](request: JsonRpc.Request[Json.Obj]): A =
      request.params.toJson.fromJson[A].fold(fail(_), identity)
    def subscribeCount: Int = socket.sent.count(_.method == EventsSubscribe.method)
    def target: SessionId = params[EventsSubscribe.Params](request(EventsSubscribe.method)).scope match {
      case SubscriptionScope.Session(id) => id
      case _                             => fail("Chat must subscribe to one session")
    }
    private var sequence = 0L
    def baseline(messages: List[MessageSnapshot] = Nil, revision: Long = 1L): Unit = {
      flush()
      val request = this.request(EventsSubscribe.method)
      val p = params[EventsSubscribe.Params](request)
      sequence = 0
      socket.reply(
        request,
        EventsSubscribe.Result(
          "sub" + subscribeCount,
          "stream",
          p.nonce,
          0,
          Chunk.fromIterable(messages),
          if messages.isEmpty then Chunk.empty
          else Chunk(EventSession(target, Chunk(assistant), messages.size.toLong, Some(messages.last.id), revision)),
          Chunk.empty,
          p.scope
        )
      )
      flush()
    }
    def event(change: EventChange, origin: Boolean = true): Unit = {
      sequence += 1
      val p = params[EventsSubscribe.Params](request(EventsSubscribe.method))
      socket.sockets.last.events.message(
        JsonRpc
          .Notification(
            EventsUpdate.method,
            EventsUpdate.Params(
              "sub" + subscribeCount,
              "stream",
              p.nonce,
              sequence,
              change,
              Option.when(origin)(EventOrigin(target, Some("turn")))
            )
          )
          .toJson
      )
      flush()
    }
    def confirm(messages: List[MessageSnapshot], revision: Long): Unit = event(
      EventChange.Confirmed(
        "op",
        Chunk.fromIterable(messages),
        Chunk(EventSession(target, Chunk(assistant), messages.size.toLong, messages.lastOption.map(_.id), revision))
      )
    )
    def send(): JsonRpc.Request[Json.Obj] = {
      model.selectAgent("assistant")
      model.draft("captured prompt")
      model.send()
      flush()
      request(TurnSubmit.method)
    }
    def finish(turn: JsonRpc.Request[Json.Obj], messages: List[MessageSnapshot]): Unit = {
      val id = params[TurnSubmit.Params](turn).target match {
        case TurnSubmit.Target.New(Some(id)) => id
        case TurnSubmit.Target.Existing(id)  => id
        case _                               => fail("target ID must be known before Send")
      }
      socket.reply(
        turn,
        TurnOutcome.Succeeded(
          SessionSnapshot(id, Chunk(assistant), Chunk.fromIterable(messages)),
          AgentResult(assistant, "done"),
          Chunk.empty
        ): TurnOutcome
      )
      flush()
    }
    def ids: List[MessageId] = model.state.now().current.snapshot.toList.flatMap(_.messages.map(_.id))
    def close(): Unit = { model.leave(); socket.client.close(); flush() }
  }

  test("unnamed subscription is read-only and ready before New(Some(id)); Send fabricates no message") {
    val h = new Harness
    h.model.selectAgent("assistant")
    h.model.draft("captured prompt")
    h.model.send()
    h.flush()
    assert(!h.socket.sent.exists(_.method == TurnSubmit.method))
    val intended = h.target
    assertEquals(h.model.state.now().sessions, Nil)
    h.baseline()
    val turn = h.send()
    assertEquals(h.params[TurnSubmit.Params](turn).target, TurnSubmit.Target.New(Some(intended)))
    assertEquals(h.model.state.now().active, intended.value)
    assertEquals(h.model.state.now().pending.map(_.conversation), Some(intended.value))
    assertEquals(h.ids, Nil)
    assertEquals(h.model.state.now().current.serverPending, Nil)
    assertEquals(h.model.state.now().current.draft, "")
    assert(h.model.state.now().pending.nonEmpty)
    h.close()
  }

  test("real prompt, tool call, tool output and reply are visible while terminal execution is blocked; IDs dedupe") {
    val h = new Harness
    h.baseline()
    val turn = h.send()
    h.model.draft("next draft")
    h.event(EventChange.Pending("prompt", Chunk(first)))
    assertEquals(h.ids, Nil)
    assertEquals(h.model.state.now().current.serverPending.map(_.id), List(first.id))
    assert(h.model.state.now().pending.nonEmpty)
    val stages = List(first, tool, output, reply)
    stages.indices.foreach { index =>
      h.confirm(stages.take(index + 1), index.toLong + 1)
      assertEquals(h.ids, stages.take(index + 1).map(_.id))
      assert(h.model.state.now().pending.nonEmpty)
    }
    assertEquals(h.model.state.now().current.serverPending, Nil)
    h.finish(turn, List(first)) // Deliberately older than the push-owned transcript.
    assertEquals(h.ids, stages.map(_.id))
    assertEquals(h.model.state.now().current.draft, "next draft")
    assert(h.model.state.now().pending.isEmpty)
    h.confirm(stages, 4)
    assertEquals(h.ids, stages.map(_.id))
    val next = h.send()
    assertEquals(h.params[TurnSubmit.Params](next).expectedHead, Some(reply.id))
    assertEquals(h.socket.sent.count(_.method == TurnSubmit.method), 2)
    assert(!h.socket.sent.exists(r => r.method == SessionsGet.method || r.method == MessagesList.method))
    h.close()
  }

  test(
    "terminal before first observation creates no transcript and waits for canonical membership before the next Send"
  ) {
    val h = new Harness
    h.baseline()
    val turn = h.send()
    h.model.draft("next draft")
    h.finish(turn, List(first))
    assertEquals(h.ids, Nil)
    assert(h.model.state.now().pending.isEmpty)
    assert(!h.model.state.now().canSend)
    h.baseline(List(first))
    assert(h.model.state.now().canSend)
    assertEquals(h.ids, List(first.id))
    h.close()
  }

  test("named New collision preserves unsent intent and draft until explicit resume") {
    val h = new Harness
    h.model.newConversation("taken")
    h.model.selectAgent("assistant")
    h.model.draft("retain New draft")
    h.baseline(List(first))
    assert(h.model.state.now().current.creating)
    assert(h.model.state.now().current.nameCollision)
    assertEquals(h.model.state.now().current.draft, "retain New draft")
    assertEquals(h.ids, Nil)
    h.model.send()
    h.flush()
    assert(!h.socket.sent.exists(_.method == TurnSubmit.method))
    assert(h.model.state.now().current.notice.contains("Explicitly resume"))
    h.model.resume("taken")
    h.baseline(List(first))
    assert(!h.model.state.now().current.creating)
    assertEquals(h.model.state.now().conversations("").draft, "retain New draft")
    val turn = h.send()
    assertEquals(h.params[TurnSubmit.Params](turn).target, TurnSubmit.Target.Existing(SessionId.unsafe("taken")))
    h.close()
  }

  test("populated history gates a terminal head until one post-terminal baseline reconciles a competing rebase") {
    val h = new Harness
    h.model.resume("saved")
    h.baseline(List(first))
    val turn = h.send()
    h.model.draft("next draft")
    val subscriptions = h.subscribeCount
    h.finish(turn, List(first, tool))
    assertEquals(h.ids, List(first.id))
    assertEquals(h.model.state.now().current.terminalHead, Some(tool.id))
    assert(!h.model.state.now().canSend)
    assertEquals(h.subscribeCount, subscriptions + 1)
    h.model.send()
    h.flush()
    assertEquals(h.socket.sent.count(_.method == TurnSubmit.method), 1)
    // A competing canonical rebase need not retain the terminal head. Its fresh baseline is authoritative.
    h.baseline(List(first), revision = 3)
    assertEquals(h.model.state.now().current.terminalHead, None)
    assert(h.model.state.now().canSend)
    assertEquals(h.ids, List(first.id))
    val next = h.send()
    assertEquals(h.params[TurnSubmit.Params](next).expectedHead, Some(first.id))
    h.close()
  }

  for recoveryFails <- List(false, true) do
    test(
      s"unchanged-head publication failure reconciles provisional cards once after success: recoveryFails=$recoveryFails"
    ) {
      val h = new Harness
      h.model.resume("saved")
      h.baseline(List(first))
      val turn = h.send()
      h.model.draft("next draft")
      h.event(EventChange.Pending("tool", Chunk(tool)))
      h.event(EventChange.Confirmed("tool", Chunk(tool), Chunk.empty))
      h.confirm(List(first), revision = 2) // Failed publication recovered the unchanged canonical head.
      assertEquals(h.model.state.now().current.serverPending.map(_.id), List(tool.id))
      val subscriptions = h.subscribeCount
      h.finish(turn, List(first))
      assertEquals(h.model.state.now().current.terminalHead, None)
      assertEquals(h.model.state.now().current.serverPending, Nil)
      assertEquals(h.ids, List(first.id))
      assert(!h.model.state.now().canSend)
      assertEquals(h.subscribeCount, subscriptions + 1)
      if recoveryFails then {
        val request = h.request(EventsSubscribe.method)
        h.socket.sockets.last.events.message(JsonRpc.Failure(request.id, JsonRpc.Error.InternalError).toJson)
        h.flush()
        assert(!h.model.state.now().canSend)
      } else {
        h.baseline(List(first), revision = 3)
        assert(h.model.state.now().canSend)
      }
      assertEquals(h.model.state.now().current.serverPending, Nil)
      assertEquals(h.model.state.now().current.draft, "next draft")
      assertEquals(h.ids, List(first.id))
      assertEquals(h.subscribeCount, subscriptions + 1)
      assertEquals(h.socket.sent.count(_.method == TurnSubmit.method), 1)
      h.close()
    }

  test(
    "terminal after switching conversations cannot reconcile against a pre-terminal baseline or gate unrelated drafts"
  ) {
    val h = new Harness
    h.model.resume("origin")
    h.baseline(List(first))
    val turn = h.send()
    h.model.draft("captured next draft")
    h.model.resume("other")
    h.baseline(List(first))
    h.model.selectAgent("assistant")
    h.model.draft("other draft")
    val subscriptions = h.subscribeCount
    h.finish(turn, List(first, tool))
    assertEquals(h.subscribeCount, subscriptions)
    assert(h.model.state.now().canSend)
    assertEquals(h.model.state.now().current.draft, "other draft")
    assertEquals(h.model.state.now().conversations("origin").terminalHead, Some(tool.id))
    h.model.resume("origin")
    assert(!h.model.state.now().canSend)
    h.baseline(List(first, tool, output), revision = 3)
    assert(h.model.state.now().canSend)
    assertEquals(h.model.state.now().current.draft, "captured next draft")
    assertEquals(h.ids, List(first.id, tool.id, output.id))
    assertEquals(h.socket.sent.count(_.method == TurnSubmit.method), 1)
    h.close()
  }

  test("named draft subscribes to the requested ID without creating persisted inventory") {
    val h = new Harness
    h.model.newConversation("named")
    h.flush()
    assertEquals(h.target, SessionId.unsafe("named"))
    h.baseline()
    val turn = h.send()
    assertEquals(h.params[TurnSubmit.Params](turn).target, TurnSubmit.Target.New(Some(SessionId.unsafe("named"))))
    assertEquals(h.model.state.now().sessions, Nil)
    h.close()
  }

  test(
    "other-client canonical changes merge inventory without transcript reads; branches and stale revisions excluded"
  ) {
    val h = new Harness
    h.model.resume(h.target.value)
    h.baseline(List(first))
    h.model.updateSummaries(List(SessionSummary(SessionId.unsafe("other"), 7L, Chunk.empty)))
    h.event(EventChange.Pending("branch", Chunk(tool)), origin = false)
    assertEquals(h.model.state.now().current.serverPending, Nil)
    h.event(EventChange.Confirmed("branch", Chunk(tool), Chunk.empty))
    assertEquals(h.ids, List(first.id))
    h.confirm(List(first, tool), 2)
    assertEquals(h.ids, List(first.id, tool.id))
    h.confirm(List(first), 3) // A -> B -> A is a new canonical revision.
    assertEquals(h.ids, List(first.id))
    h.confirm(List(first, tool), 2)
    assertEquals(h.ids, List(first.id))
    assert(h.model.state.now().sessions.exists(_.id.value == "other"))
    assert(!h.socket.sent.exists(_.method == SessionsGet.method))
    h.close()
  }

  test("terminal after route switch updates only captured draft and does not bypass paused Graph inventory") {
    val h = new Harness
    h.model.resume(h.target.value)
    h.baseline(List(first))
    val origin = h.target.value
    val turn = h.send()
    h.model.draft("next original")
    h.model.resume("other")
    h.flush()
    h.baseline(List(first))
    h.model.draft("unrelated draft")
    h.model.leave()
    h.flush()
    val reads = h.socket.sent.size
    h.finish(turn, List(first))
    assertEquals(h.model.state.now().active, "other")
    assertEquals(h.model.state.now().current.draft, "unrelated draft")
    assertEquals(h.model.state.now().conversations(origin).draft, "next original")
    assertEquals(h.socket.sent.size, reads)
    assert(!h.model.state.now().current.feedReady)
    h.model.enter()
    h.flush()
    assert(h.model.state.now().current.loading)
    h.baseline(List(first))
    assertEquals(h.model.state.now().current.draft, "unrelated draft")
    assert(h.model.state.now().current.resetContinuation)
    h.close()
  }

  test("failure retains confirmed history and next draft, removes pending observations, and requires explicit review") {
    val h = new Harness
    h.model.resume(h.target.value)
    h.baseline(List(first))
    val turn = h.send()
    h.model.draft("keep next draft")
    h.event(EventChange.Pending("ghost", Chunk(tool)))
    h.socket.reply(
      turn,
      TurnOutcome.Failed(PromptAcceptance.Unknown, None, ApplicationError("conflict", "rebase")): TurnOutcome
    )
    h.flush()
    assertEquals(h.ids, List(first.id))
    assertEquals(h.model.state.now().current.serverPending, Nil)
    assertEquals(h.model.state.now().current.draft, "keep next draft")
    assert(h.model.state.now().current.reviewRequired)
    assert(!h.model.state.now().canSend)
    h.model.reload()
    h.flush()
    h.baseline(List(first))
    assert(!h.model.state.now().current.reviewRequired)
    assertEquals(h.socket.sent.count(_.method == TurnSubmit.method), 1)
    h.close()
  }

  test("disconnect restores captured draft, explicit reconnect keeps review gate until reload, no replay") {
    val h = new Harness
    h.model.resume(h.target.value)
    h.baseline(List(first))
    h.send()
    h.model.disable()
    h.socket.client.close()
    h.flush()
    assertEquals(h.model.state.now().current.draft, "captured prompt")
    assert(h.model.state.now().current.reviewRequired)
    val init = h.socket.initialize(methods = List(TurnSubmit.method), configured = Some(Chunk(assistant)))
    h.flush()
    h.model.configure(init.value.get.get)
    h.flush()
    h.baseline(List(first))
    assert(h.model.state.now().current.reviewRequired)
    h.model.reload()
    h.flush()
    h.baseline(List(first))
    assert(!h.model.state.now().current.reviewRequired)
    assertEquals(h.socket.sent.count(_.method == TurnSubmit.method), 1)
    h.close()
  }

  test("hidden and old cancellation before baseline cannot overwrite a new active scope") {
    val h = new Harness
    val old = h.request(EventsSubscribe.method)
    val p = h.params[EventsSubscribe.Params](old)
    h.model.visibilityChanged(false)
    h.model.visibilityChanged(true)
    h.flush()
    h.baseline(List(first))
    val before = h.model.state.now()
    h.socket.reply(
      old,
      EventsSubscribe.Result("old", "stream", p.nonce, 0, Chunk.empty, Chunk.empty, Chunk.empty, p.scope)
    )
    h.flush()
    assertEquals(h.model.state.now(), before)
    assertEquals(h.params[EventsUnsubscribe.Params](h.request(EventsUnsubscribe.method)).subscriptionId, "old")
    h.close()
  }

  test("late aggregate inventory read preserves live session summaries and other sessions") {
    val h = new Harness
    val list = h.request(SessionsList.method)
    h.baseline(List(first))
    h.confirm(List(first, tool), 2)
    h.socket.reply(
      list,
      Chunk(
        SessionSummary(h.target, 0L, Chunk.empty),
        SessionSummary(SessionId.unsafe("elsewhere"), 8L, Chunk.empty)
      )
    )
    h.flush()
    assertEquals(h.model.state.now().sessions.find(_.id == h.target).get.messageCount, 2L)
    assert(h.model.state.now().sessions.exists(_.id.value == "elsewhere"))
    h.close()
  }

  test("known rejection of a new turn restores captured text without a phantom persisted session") {
    val h = new Harness
    h.baseline()
    val turn = h.send()
    h.socket.sockets.last.events.message(JsonRpc.Failure(turn.id, JsonRpc.Error(-32602, "bad request")).toJson)
    h.flush()
    assertEquals(h.model.state.now().current.draft, "captured prompt")
    assertEquals(h.model.state.now().sessions, Nil)
    assert(h.model.state.now().current.reviewRequired)
    h.model.reload()
    h.flush()
    h.baseline()
    assert(h.model.state.now().canSend)
    assertEquals(h.socket.sent.count(_.method == TurnSubmit.method), 1)
    h.close()
  }

  test("read-only viewers subscribe independently of author capability") {
    val h = new Harness
    h.model.configure(h.info.copy(methods = Nil))
    h.model.resume(h.target.value)
    h.baseline(List(first))
    assertEquals(h.ids, List(first.id))
    assert(!h.model.state.now().enabled)
    h.close()
  }
}
