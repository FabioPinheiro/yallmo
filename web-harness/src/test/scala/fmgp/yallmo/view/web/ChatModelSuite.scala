package fmgp.yallmo.view.web

import com.raquo.airstream.ownership.ManualOwner
import fmgp.yallmo.model.{AgentId, MessageId}
import fmgp.yallmo.orchestration.{AgentResult, SessionId, SessionSummary}
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.{*, given}
import java.time.Instant
import munit.FunSuite
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.timers.setTimeout
import zio.Chunk
import zio.json.*

final class ChatModelSuite extends FunSuite {
  private val assistant = AgentId("assistant")
  private val other = AgentId("other")
  private val empty = SessionSnapshot(SessionId.unsafe("chat"), Chunk.empty, Chunk.empty)
  private val message = MessageSnapshot(
    MessageId.unsafe("sha256:" + "a" * 64),
    assistant,
    None,
    Instant.parse("2026-01-01T00:00:00Z"),
    "assistant",
    "Persisted",
    Chunk.empty
  )
  private val completed = empty.copy(messages = Chunk(message))

  test("graph summary updates preserve active transcript, draft, continuation and pending work without new reads") {
    withModel { (h, model) =>
      val conversation = ChatConversation(snapshot = Some(completed), draft = "Chat draft", resetContinuation = false)
      val pending = ChatPendingMessage("chat", AgentId("human"), "Captured input")
      model.state.update(_.copy(active = "chat", conversations = Map("chat" -> conversation), pending = Some(pending)))
      val before = model.state.now()
      val requests = h.sent.size
      val summaries =
        List(SessionSummary(empty.id, 5L, Chunk.empty), SessionSummary(SessionId.unsafe("new"), 1L, Chunk.empty))
      model.updateSummaries(summaries)
      assertEquals(model.state.now(), before.copy(sessions = summaries, listError = ""))
      assertEquals(h.sent.size, requests)
      Future.successful(())
    }
  }

  test("graph-owned configure defers reads and gates saved history without changing draft or continuation") {
    withModel { (h, model) =>
      h.client.connect().flatMap { info =>
        val conversation = ChatConversation(
          snapshot = Some(completed),
          draft = "Preserved Chat draft",
          agent = Some(assistant),
          resetContinuation = false
        )
        model.state.update(_.copy(active = "chat", conversations = Map("chat" -> conversation)))
        val reads = h.sent.size
        model.configure(info, refreshSessions = false)
        assertEquals(h.sent.size, reads)
        assertEquals(model.state.now().current, conversation.copy(reloadRequired = true))
        assert(!model.state.now().canSend)
        model.send()
        assertEquals(h.sent.size, reads)
        model.reload()
        for {
          request <- h.awaitRequest(SessionsGet.method)
          _ = h.reply(request, completed)
          _ <- until(!model.state.now().current.loading)
          _ = assertEquals(model.state.now().current.draft, conversation.draft)
          _ = assertEquals(model.state.now().current.resetContinuation, false)
          _ = assert(model.state.now().canSend)
          lists = h.sent.count(_.method == SessionsList.method)
          _ = model.configure(info)
          _ <- h.awaitRequest(SessionsList.method, skip = lists)
        } yield assertEquals(
          h.sent.count(_.method == SessionsList.method),
          lists + 1,
          "default Chat configure still refreshes"
        )
      }
    }
  }

  test("graph-owned configure rejects earlier Chat list/history reads instead of publishing paused sidebar changes") {
    val h = new OrchestratorWSClientSuite.Harness()
    val model = new ChatModel(h.client)
    for {
      info <- h.initialize(methods = List(TurnSubmit.method), configured = Some(Chunk(assistant)))
      _ = model.configure(info)
      list <- h.awaitRequest(SessionsList.method)
      _ = model.resume("chat")
      read <- h.awaitRequest(SessionsGet.method)
      _ = model.draft("Retained during reconnect")
      _ = model.configure(info, refreshSessions = false)
      _ = h.reply(list, Chunk(SessionSummary(empty.id, 99L, Chunk.empty)))
      _ = h.reply(read, completed)
      _ <- {
        val settled = Promise[Unit]()
        setTimeout(0)(settled.success(()))
        settled.future
      }
    } yield {
      assertEquals(model.state.now().sessions, Nil)
      assertEquals(model.state.now().current.snapshot, None)
      assertEquals(model.state.now().current.draft, "Retained during reconnect")
      assert(model.state.now().current.reloadRequired)
      assert(!model.state.now().current.loading)
      h.client.close()
    }
  }

  test("first Send validates agent, submits New once, retains the returned ID, and reuses authoritative head") {
    withModel { (h, model) =>
      model.draft("hello\nworld")
      model.send()
      assert(!h.sent.exists(_.method == "sessions.create"))
      model.selectAgent("historical-not-configured")
      model.send()
      assert(!h.sent.exists(_.method == "sessions.create"))
      model.selectAgent(AgentId.value(assistant))
      model.send()
      assertEquals(
        model.state.now().currentPending,
        Some(ChatPendingMessage("", AgentId("user-viewer"), "hello\nworld"))
      )
      assertEquals(model.state.now().current.snapshot, None)
      assertEquals(model.state.now().current.draft, "")
      model.send()
      for {
        turn <- h.awaitRequest(TurnSubmit.method)
        _ = assertEquals(model.state.now().active, "")
        _ = assertEquals(
          model.state.now().currentPending,
          Some(ChatPendingMessage("", AgentId("user-viewer"), "hello\nworld"))
        )
        _ = assertEquals(model.state.now().conversations("").draft, "")
        first = decode[TurnSubmit.Params](turn.params.toJson)
        _ = assertEquals(first.task.prompt, "hello\nworld")
        _ = assertEquals(first.target, TurnSubmit.Target.New())
        _ = assert(first.resetContinuation)
        _ = model.send()
        _ = h.reply(
          turn,
          TurnOutcome.Succeeded(completed, AgentResult(assistant, "not used as transcript"), Chunk.empty): TurnOutcome
        )
        _ <- until(model.state.now().pending.isEmpty)
        _ = assertEquals(model.state.now().current.snapshot, Some(completed))
        _ = assertEquals(model.state.now().current.draft, "")
        _ = model.draft("next")
        _ = model.send()
        next <- h.awaitRequest(TurnSubmit.method, skip = 1)
        params = decode[TurnSubmit.Params](next.params.toJson)
        _ = h.reply(next, TurnOutcome.Succeeded(completed, AgentResult(assistant, "done"), Chunk.empty): TurnOutcome)
        _ <- until(model.state.now().pending.isEmpty)
        _ = model.draft("switch agent")
        _ = model.selectAgent(AgentId.value(other))
        _ = assertEquals(model.state.now().current.snapshot, Some(completed))
        _ = assertEquals(model.state.now().current.draft, "switch agent")
        _ = assert(model.state.now().current.resetContinuation)
      } yield {
        assertEquals(params.target, TurnSubmit.Target.Existing(empty.id))
        assertEquals(params.expectedHead, Some(message.id))
        assert(!params.resetContinuation)
        assertEquals(h.sent.count(_.method == "sessions.create"), 0)
        assertEquals(h.sent.count(_.method == TurnSubmit.method), 2)
      }
    }
  }

  test("sending clears the composer and preserves the next draft through submission, success and failure") {
    withModel { (h, model) =>
      model.draft("first prompt")
      model.selectAgent(AgentId.value(assistant))
      model.send()
      assertEquals(model.state.now().current.draft, "")
      model.draft("typed during creation")
      for {
        first <- h.awaitRequest(TurnSubmit.method)
        _ = assertEquals(model.state.now().current.draft, "typed during creation")
        _ = assertEquals(decode[TurnSubmit.Params](first.params.toJson).task.prompt, "first prompt")
        // Even a new draft identical to the submitted prompt must not be cleared by completion.
        _ = model.draft("first prompt")
        _ = model.send()
        _ = assertEquals(h.sent.count(_.method == TurnSubmit.method), 1)
        _ = h.reply(first, TurnOutcome.Succeeded(completed, AgentResult(assistant, "done"), Chunk.empty): TurnOutcome)
        _ <- until(model.state.now().pending.isEmpty)
        _ = assertEquals(model.state.now().current.draft, "first prompt")
        _ = assertEquals(model.state.now().current.notice, "")
        _ = model.send()
        _ = assertEquals(model.state.now().current.draft, "")
        second <- h.awaitRequest(TurnSubmit.method, skip = 1)
        _ = model.draft("keep this next draft")
        _ = h.sockets.last.events.message(JsonRpc.Failure(second.id, JsonRpc.Error(ErrorCode.Busy, "Busy")).toJson)
        _ <- until(model.state.now().pending.isEmpty)
      } yield {
        assertEquals(model.state.now().current.draft, "keep this next draft")
        assert(model.state.now().current.reloadRequired)
      }
    }
  }

  test(
    "resume, draft and agent changes preserve history; late reads and turn results never replace another conversation"
  ) {
    withModel { (h, model) =>
      model.draft("unsent initial draft")
      model.resume("chat")
      model.resume("")
      assertEquals(model.state.now().current.draft, "unsent initial draft")
      for {
        read <- h.awaitRequest(SessionsGet.method)
        _ = model.resume("second")
        second <- h.awaitRequest(SessionsGet.method, skip = 1)
        _ = h.reply(second, empty.copy(id = SessionId.unsafe("second")))
        _ = h.reply(read, completed)
        _ <- until(!model.state.now().current.loading)
        _ = assertEquals(model.state.now().active, "second")
        _ = model.draft("second draft")
        _ = model.resume("chat")
        reread <- h.awaitRequest(SessionsGet.method, skip = 2)
        _ = h.reply(reread, completed)
        _ <- until(!model.state.now().current.loading)
        _ = model.draft("chat draft")
        _ = model.selectAgent(AgentId.value(other))
        _ = assertEquals(model.state.now().current.snapshot, Some(completed))
        _ = model.send()
        turn <- h.awaitRequest(TurnSubmit.method)
        _ = assert(decode[TurnSubmit.Params](turn.params.toJson).resetContinuation)
        _ = assertEquals(model.state.now().currentPending.map(_.prompt), Some("chat draft"))
        _ = model.resume("second")
        _ = assertEquals(model.state.now().currentPending, None)
        _ = assertEquals(model.state.now().pending.map(_.conversation), Some("chat"))
        _ = h.reply(turn, TurnOutcome.Succeeded(completed, AgentResult(other, "done"), Chunk.empty): TurnOutcome)
        _ <- until(model.state.now().pending.isEmpty)
      } yield {
        assertEquals(model.state.now().active, "second")
        assertEquals(model.state.now().current.draft, "second draft")
        assertEquals(model.state.now().current.snapshot.map(_.id.value), Some("second"))
        assertEquals(model.state.now().conversations("chat").draft, "")
      }
    }
  }

  test("busy/conflict and uncertain transport retain drafts, require history reload and never replay") {
    withModel { (h, model) =>
      model.resume("chat")
      for {
        read <- h.awaitRequest(SessionsGet.method)
        _ = h.reply(read, completed)
        _ <- until(!model.state.now().current.loading)
        _ = model.selectAgent(AgentId.value(assistant))
        _ = model.draft("recoverable")
        _ = model.send()
        turn <- h.awaitRequest(TurnSubmit.method)
        _ = h.sockets.last.events.message(JsonRpc.Failure(turn.id, JsonRpc.Error(ErrorCode.Busy, "Busy")).toJson)
        _ <- until(model.state.now().pending.isEmpty)
        _ = assert(model.state.now().current.reloadRequired)
        _ = assertEquals(model.state.now().current.snapshot, Some(completed))
        _ = assertEquals(model.state.now().currentPending, None)
        _ = assertEquals(model.state.now().current.draft, "recoverable")
        _ = model.send()
        _ = assertEquals(h.sent.count(_.method == TurnSubmit.method), 1)
        _ = model.reload()
        reload <- h.awaitRequest(SessionsGet.method, skip = 1)
        _ = h.reply(reload, completed)
        _ <- until(!model.state.now().current.loading)
        _ = model.send()
        _ <- h.awaitRequest(TurnSubmit.method, skip = 1)
        _ = h.client.close()
        _ <- until(model.state.now().pending.isEmpty)
        _ = assert(model.state.now().current.notice.contains("may still be running"))
        _ <- h.initialize(methods = List(TurnSubmit.method))
      } yield {
        assertEquals(model.state.now().current.draft, "recoverable")
        assert(model.state.now().current.reloadRequired)
        assertEquals(h.sent.count(_.method == TurnSubmit.method), 2)
        assertEquals(h.sent.count(_.method == "sessions.create"), 0)
      }
    }
  }

  test("failed terminal outcomes render only supplied snapshots and keep recoverable draft behind a reload gate") {
    withModel { (h, model) =>
      val storedPrompt =
        empty.copy(messages = Chunk(message.copy(role = "user", agentId = AgentId("user-viewer"), content = "keep me")))
      model.draft("keep me")
      model.selectAgent(AgentId.value(assistant))
      model.send()
      for {
        turn <- h.awaitRequest(TurnSubmit.method)
        _ = h.reply(
          turn,
          TurnOutcome.Failed(
            PromptAcceptance.Unknown,
            Some(storedPrompt),
            ApplicationError("failed", "Provider failed")
          ): TurnOutcome
        )
        _ <- until(model.state.now().pending.isEmpty)
      } yield {
        assertEquals(model.state.now().active, "chat")
        assertEquals(model.state.now().current.snapshot, Some(storedPrompt))
        assertEquals(model.state.now().current.draft, "keep me")
        assertEquals(model.state.now().currentPending, None)
        assert(model.state.now().current.reloadRequired)
        assert(!model.state.now().canSend)
      }
    }
  }

  test("identical successive prompts keep their own preview until the authoritative snapshot replaces it atomically") {
    withModel { (h, model) =>
      val prompt = "same\n<plain text>"
      val user = message.copy(agentId = AgentId("user-viewer"), role = "user", content = prompt)
      val firstSnapshot = empty.copy(messages = Chunk(user))
      val secondSnapshot = empty.copy(messages = Chunk(user, user.copy(id = MessageId.unsafe("sha256:" + "b" * 64))))
      val owner = new ManualOwner
      val observed = scala.collection.mutable.ListBuffer.empty[ChatState]
      model.state.signal.foreach(s => { observed += s; () })(using owner)
      model.draft(prompt)
      model.selectAgent(AgentId.value(assistant))
      model.send()
      val result = for {
        first <- h.awaitRequest(TurnSubmit.method)
        _ = h.reply(
          first,
          TurnOutcome.Succeeded(firstSnapshot, AgentResult(assistant, "done"), Chunk.empty): TurnOutcome
        )
        _ <- until(model.state.now().pending.isEmpty)
        _ = assertEquals(model.state.now().current.snapshot, Some(firstSnapshot))
        _ = model.draft(prompt)
        _ = model.send()
        _ = assertEquals(model.state.now().currentPending.map(_.prompt), Some(prompt))
        _ = assertEquals(model.state.now().current.snapshot, Some(firstSnapshot))
        second <- h.awaitRequest(TurnSubmit.method, skip = 1)
        _ = h.reply(
          second,
          TurnOutcome.Succeeded(secondSnapshot, AgentResult(assistant, "done"), Chunk.empty): TurnOutcome
        )
        _ <- until(model.state.now().pending.isEmpty)
      } yield {
        assertEquals(model.state.now().current.snapshot, Some(secondSnapshot))
        assertEquals(model.state.now().currentPending, None)
        assertEquals(model.state.now().current.draft, "")
        val terminalTransitions = observed.toList
          .sliding(2)
          .filter { pair =>
            pair.size == 2 && pair.head.pending.nonEmpty && pair.last.pending.isEmpty
          }
          .toList
        assertEquals(terminalTransitions.map(_.last.current.snapshot), List(Some(firstSnapshot), Some(secondSnapshot)))
        assertEquals(h.sent.count(_.method == TurnSubmit.method), 2)
      }
      result.andThen { case _ => owner.killSubscriptions() }
    }
  }

  test("New prepares a named draft without RPC; first rejection retains its draft without a phantom session") {
    withModel { (h, model) =>
      model.selectAgent(AgentId.value(assistant))
      model.newConversation("named")
      assertEquals(model.state.now().current.name, Some(SessionId.unsafe("named")))
      assertEquals(model.state.now().current.snapshot, None)
      assert(!h.sent.exists(_.method == TurnSubmit.method))
      model.draft("recoverable")
      model.send()
      for {
        turn <- h.awaitRequest(TurnSubmit.method)
        _ = assertEquals(
          decode[TurnSubmit.Params](turn.params.toJson).target,
          TurnSubmit.Target.New(Some(SessionId.unsafe("named")))
        )
        _ = h.sockets.last.events
          .message(JsonRpc.Failure(turn.id, JsonRpc.Error(ErrorCode.Conflict, "Duplicate name")).toJson)
        _ <- until(model.state.now().pending.isEmpty)
      } yield {
        assertEquals(model.state.now().current.snapshot, None)
        assertEquals(model.state.now().current.draft, "recoverable")
        assert(!model.state.now().current.reloadRequired)
        assert(!h.sent.exists(_.method == "sessions.create"))
      }
    }
  }

  test("a lost first submit result blocks another New submission and directs recovery through the session list") {
    withModel { (h, model) =>
      model.selectAgent(AgentId.value(assistant))
      model.draft("only once")
      model.send()
      for {
        _ <- h.awaitRequest(TurnSubmit.method)
        _ = h.client.close()
        _ <- until(model.state.now().pending.isEmpty)
        _ = model.send()
      } yield {
        assert(model.state.now().current.reloadRequired)
        assertEquals(model.state.now().current.draft, "only once")
        assert(model.state.now().current.notice.contains("session list"))
        assertEquals(h.sent.count(_.method == TurnSubmit.method), 1)
      }
    }
  }

  test("an internal RPC error on first submit blocks a second New request") {
    withModel { (h, model) =>
      model.selectAgent(AgentId.value(assistant))
      model.draft("once")
      model.send()
      for {
        turn <- h.awaitRequest(TurnSubmit.method)
        _ = h.sockets.last.events.message(JsonRpc.Failure(turn.id, JsonRpc.Error.InternalError).toJson)
        _ <- until(model.state.now().pending.isEmpty)
        _ = model.send()
      } yield {
        assert(model.state.now().current.reloadRequired)
        assertEquals(h.sent.count(_.method == TurnSubmit.method), 1)
      }
    }
  }

  test("blocked send shares the button gate and reports a useful warning without creating a session") {
    val notices = scala.collection.mutable.ListBuffer.empty[UiNotice]
    withModel(
      { (h, model) =>
        model.draft("ready to send")
        assert(model.state.now().needsAgent)
        assert(!model.state.now().canSend)
        val reason = model.state.now().sendBlockedReason.get
        model.send()
        assertEquals(notices.toList, List(UiNotice(reason, "warn")))
        assertEquals(reason, "Choose an agent before sending.")
        assert(!h.sent.exists(_.method == "sessions.create"))
        model.selectAgent(AgentId.value(assistant))
        assert(model.state.now().canSend)
        model.disable()
        model.send()
        assert(notices.last.message.contains("Reconnect"))
        assertEquals(model.state.now().current.draft, "ready to send")
        Future.successful(())
      },
      notice => { notices += notice; () }
    )
  }

  test("disconnect or identity replacement ignores old read failures without losing drafts") {
    val notices = scala.collection.mutable.ListBuffer.empty[UiNotice]
    withModel(
      { (h, model) =>
        model.resume("chat")
        model.draft("retained")
        for {
          oldRead <- h.awaitRequest(SessionsGet.method)
          _ = model.disable()
          _ = assert(!model.state.now().current.loading)
          _ = h.sockets.last.events.message(
            JsonRpc.Failure(oldRead.id, JsonRpc.Error(ErrorCode.Busy, "Connection closed")).toJson
          )
          _ = model.reload()
          newRead <- h.awaitRequest(SessionsGet.method, skip = 1)
          _ = h.reply(newRead, completed)
          _ <- until(model.state.now().current.snapshot.nonEmpty)
        } yield {
          assertEquals(model.state.now().current.draft, "retained")
          assertEquals(notices.toList, Nil)
          assert(!model.state.now().current.reloadRequired)
        }
      },
      notice => { notices += notice; () }
    )
  }

  private def withModel(
      test: (OrchestratorWSClientSuite.Harness, ChatModel) => Future[Unit],
      notify: UiNotice => Unit = _ => ()
  ): Future[Unit] = {
    val h = new OrchestratorWSClientSuite.Harness()
    val model = new ChatModel(h.client, notify)
    h.onRequest = request => if request.method == SessionsList.method then h.reply(request, zio.json.ast.Json.Arr())
    h.initialize(methods = List(TurnSubmit.method), configured = Some(Chunk(assistant, other)))
      .flatMap { info => model.configure(info); test(h, model) }
      .andThen { case _ => h.client.close() }
  }

  private def until(condition: => Boolean): Future[Unit] = {
    val result = Promise[Unit]()
    def poll(remaining: Int): Unit =
      if condition then { result.trySuccess(()); () }
      else if remaining > 0 then { setTimeout(2)(poll(remaining - 1)); () }
      else { result.tryFailure(new AssertionError("Chat transition did not complete")); () }
    poll(250)
    result.future
  }

  private def decode[A: JsonDecoder](text: String): A = text.fromJson[A].fold(fail(_), identity)
}
