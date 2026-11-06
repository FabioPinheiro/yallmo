package fmgp.yallmo.view.web

import fmgp.yallmo.model.{AgentId, MessageId, UserId}
import fmgp.yallmo.orchestration.AgentResult
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import java.time.Instant
import munit.FunSuite
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.timers.setTimeout
import zio.Chunk

final class GraphBranchModelSuite extends FunSuite {
  private val agent = AgentId("assistant")
  private val actor = AgentId("web-user-human")
  private val parent = MessageId.sha256("parent")
  private val other = MessageId.sha256("other")
  private val info = Initialize.Result(
    "server",
    Identity(UserId("human"), Role.Operator, Chunk(AgentId("user-human"), actor)),
    List(BranchesSubmit.method),
    Nil,
    MaxBlobChunkBytes,
    Some(Chunk(agent))
  )

  private class Harness {
    var version = 0L
    var selected = Option(agent)
    var busy = false
    val requests = mutable.ArrayBuffer.empty[(BranchesSubmit.Params, Promise[BranchOutcome])]
    val model = new GraphBranchModel(
      params => {
        val p = Promise[BranchOutcome](); requests += ((params, p)); p.future
      },
      () => selected,
      () => busy,
      () => version
    )
    model.configure(info)
    def send(): Unit = { model.select(Some(parent)); model.draft("branch"); model.send() }
    def success: BranchOutcome.Succeeded = {
      val prompt =
        MessageSnapshot(MessageId.sha256("prompt"), actor, Some(parent), Instant.EPOCH, "user", "branch", Chunk.empty)
      val answer = prompt.copy(
        id = MessageId.sha256("answer"),
        agentId = agent,
        parentId = Some(prompt.id),
        role = "assistant",
        content = "answer"
      )
      BranchOutcome.Succeeded(parent, prompt.id, answer.id, AgentResult(agent, "answer"), Chunk(prompt, answer))
    }
  }

  test("capture exact parent/author/agent once; double Send is blocked, next draft survives and head is adopted") {
    val h = new Harness
    h.send()
    h.model.send()
    h.model.draft("next draft")
    assertEquals(h.requests.size, 1)
    assertEquals(h.requests.head._1, BranchesSubmit.Params(parent, agent, BranchesSubmit.Task("branch"), actor))
    assertEquals(h.model.state.now().pending.get.parent, parent)
    h.requests.head._2.success(h.success)
    tick().map { _ =>
      assertEquals(h.model.state.now().parent, Some(h.success.head))
      assertEquals(h.model.state.now().draft, "next draft")
      assertEquals(h.model.state.now().messages.map(_.id), h.success.newMessages.map(_.id).toList)
      assertEquals(h.model.state.now().pending, None)
      assert(!h.model.state.now().reviewRequired)
      assertEquals(h.model.state.now().notice, "")
    }
  }

  test("explicit terminal adoption is consumed once across snapshot updates and renderer remount") {
    val h = new Harness
    h.send()
    h.requests.head._2.success(h.success)
    tick().map { _ =>
      assertEquals(h.model.consumeAdoptedHead(), Some(h.success.head))
      assertEquals(h.model.consumeAdoptedHead(), None)
      h.model.draft("another draft")
      assertEquals(h.model.consumeAdoptedHead(), None)
      assertEquals(h.model.state.now().messages.size, 2)
    }
  }

  test("selection changes and route unmount do not reparent pending work or adopt its later head") {
    val h = new Harness
    h.send()
    h.model.select(Some(other))
    h.model.select(None) // Route unmount: controller stays at application root.
    assert(h.model.state.now().pending.nonEmpty)
    h.requests.head._2.success(h.success)
    tick().map { _ =>
      assertEquals(h.requests.head._1.parentId, parent)
      assertEquals(h.model.state.now().parent, None)
      assertEquals(h.model.state.now().adoptedHead, None)
      assertEquals(h.model.state.now().messages.size, 2)
    }
  }

  for live <- List(true, false) do
    test(s"prebaseline sidebar navigation clears a retained parent and prevents terminal adoption: live=$live") {
      val h = new Harness
      h.send()
      h.requests.head._2.success(h.success)
      tick().flatMap { _ =>
        h.model.consumeAdoptedHead()
        val baseline = Promise[EventsSubscribe.Result]()
        val graph = new ConversationGraphModel(_ => EventSubscription(baseline.future, () => ()), () => 0L)
        graph.enable()
        graph.refresh()
        if !live then graph.setLive(false)
        h.send()
        val navigated = mutable.ArrayBuffer.empty[String]
        ConversationGraphView.navigateSession(
          h.model,
          "not-loaded",
          id => {
            assertEquals(h.model.state.now().parent, None)
            navigated += id
            ()
          }
        )
        assertEquals(navigated.toList, List("not-loaded"))
        assert(graph.state.now().snapshot.isEmpty)
        assert(h.model.state.now().messages.nonEmpty)
        h.requests.last._2.success(h.success)
        tick().map { _ =>
          assertEquals(h.model.state.now().parent, None)
          assertEquals(h.model.state.now().adoptedHead, None)
          assertEquals(h.model.consumeAdoptedHead(), None)
          assertEquals(h.requests.size, 2)
          graph.disable()
        }
      }
    }

  test("accepted failure merges partial IDs, never overwrites a revised/emptied draft, and requires explicit review") {
    val h = new Harness
    h.send()
    h.model.draft("edited")
    h.model.draft("")
    h.requests.head._2.success(
      BranchOutcome.Failed(
        parent,
        PromptAcceptance.Accepted,
        Some(h.success.promptId),
        Some(h.success.promptId),
        Chunk(h.success.newMessages.head),
        ApplicationError("failed", "Inspect partial branch")
      )
    )
    tick().map { _ =>
      assertEquals(h.model.state.now().draft, "")
      assertEquals(h.model.state.now().messages.size, 1)
      assertEquals(h.model.state.now().parent, Some(parent))
      h.model.draft("new execution")
      h.model.send()
      assertEquals(h.requests.size, 1)
      assert(h.model.state.now().reviewRequired)
      h.model.reviewed()
      assertEquals(h.model.state.now().notice, "")
      h.model.send()
      assertEquals(h.requests.size, 2)
    }
  }

  test("unknown transport outcome restores untouched draft but does not automatically replay") {
    val h = new Harness
    h.send()
    h.requests.head._2.failure(new RuntimeException("lost reply"))
    tick().map { _ =>
      assertEquals(h.model.state.now().draft, "branch")
      assert(h.model.state.now().reviewRequired)
      h.model.send()
      assertEquals(h.requests.size, 1)
    }
  }

  test("known preacceptance rejection permits a new explicit send without inventing saved IDs") {
    val h = new Harness
    h.send()
    h.requests.head._2.failure(new OrchestratorWSClient.RpcException(JsonRpc.Error(ErrorCode.Forbidden, "Forbidden")))
    tick().map { _ =>
      assertEquals(h.model.state.now().draft, "branch")
      assert(!h.model.state.now().reviewRequired)
      assert(h.model.state.now().messages.isEmpty)
    }
  }

  test("disconnect/reconfigure rejects stale callbacks and retains unknown-outcome review gate") {
    val h = new Harness
    h.send()
    h.model.disable()
    h.version += 1
    h.model.configure(info)
    h.requests.head._2.success(h.success)
    tick().map { _ =>
      assert(h.model.state.now().messages.isEmpty)
      assertEquals(h.model.state.now().pending, None)
      assertEquals(h.model.state.now().draft, "branch")
      assert(h.model.state.now().reviewRequired)
      h.model.send()
      assertEquals(h.requests.size, 1)
    }
  }

  test("connection generation and mismatched reply parent cannot silently apply an unrelated result") {
    val h = new Harness
    h.send()
    h.version += 1
    h.requests.head._2.success(h.success)
    tick().flatMap { _ =>
      assert(h.model.state.now().messages.isEmpty)
      assert(!h.model.state.now().enabled)
      val fresh = new Harness
      fresh.send()
      fresh.requests.head._2.success(fresh.success.copy(parentId = other))
      tick().map { _ =>
        assert(fresh.model.state.now().messages.isEmpty)
        assert(fresh.model.state.now().reviewRequired)
      }
    }
  }

  test("agent allowlist, operator capability, parent, nonblank text and other mutation gates precede requests") {
    val h = new Harness
    h.model.draft("message")
    h.model.send()
    h.model.select(Some(parent))
    h.selected = Some(AgentId("not-configured"))
    h.model.send()
    h.selected = Some(agent)
    h.busy = true
    h.model.send()
    h.busy = false
    h.model.draft("  ")
    h.model.send()
    h.model.configure(info.copy(identity = info.identity.copy(role = Role.Viewer)))
    h.model.draft("message")
    h.model.send()
    assert(h.requests.isEmpty)
  }

  private def tick(): Future[Unit] = {
    val p = Promise[Unit]()
    setTimeout(0)(p.success(()))
    p.future
  }
}
