package fmgp.yallmo.view.web

import munit.FunSuite
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.timers.setTimeout

final class MessageSpeechSuite extends FunSuite {
  private final class Request(
      val text: String,
      val started: () => Unit,
      val ended: () => Unit,
      val failed: () => Unit
  ) {
    var cancelled = false
  }

  private final class Harness(
      supported: Boolean = true,
      startTimeout: Int = 4500,
      completionTimeout: Double = 120000
  ) {
    val requests = mutable.ListBuffer.empty[Request]
    val notices = mutable.ListBuffer.empty[UiNotice]
    var throwOnSpeak = false
    var failImmediately = false
    var errorOnCancel = false
    val playback = new MessageSpeech.Playback {
      def available: Boolean = supported
      def speak(text: String, started: () => Unit, ended: () => Unit, failed: () => Unit): () => Unit = {
        if throwOnSpeak then throw new IllegalStateException("speech rejected")
        val request = new Request(text, started, ended, failed)
        requests += request
        if failImmediately then failed()
        () => {
          request.cancelled = true
          if errorOnCancel then failed()
        }
      }
    }
    val speech = new MessageSpeech(playback, notice => { notices += notice; () }, startTimeout, _ => completionTimeout)
  }

  private def waitForTimers(): Future[Unit] = {
    val result = Promise[Unit]()
    val _ = setTimeout(60) { result.success(()); () }
    result.future
  }

  test("all text is prepared verbatim, including Markdown, code, JSON and whitespace") {
    List("  **Hello**\n```scala\nval x = 1\n```\n", "{\"ok\":true}", "<script>text</script>", "", " \n ")
      .foreach(text => assertEquals(MessageText.forSpeech(text), text))
  }

  test("reading requires neither agents nor a connection and retains the full text") {
    val h = new Harness()
    val owner = new Object
    val text = "  **Verbatim**\n" + "long text\n" * 1500
    h.speech.toggle(owner, text)
    assertEquals(h.requests.map(_.text).toList, List(text))
    assert(h.speech.active.now().contains(owner))
    h.requests.last.started()
    h.requests.last.ended()
    assertEquals(h.speech.active.now(), None)
    assert(h.notices.isEmpty)
  }

  test("clicking the same control stops; clicking another replaces playback without queueing") {
    val h = new Harness()
    val first = new Object
    val second = new Object
    h.speech.toggle(first, "first")
    h.speech.toggle(first, "first")
    assertEquals(h.requests.size, 1)
    assert(h.requests.head.cancelled)
    assertEquals(h.speech.active.now(), None)
    h.speech.toggle(first, "first again")
    h.speech.toggle(second, "second")
    assert(h.requests(1).cancelled)
    assert(h.speech.active.now().contains(second))
    h.speech.stop()
    assert(h.requests.last.cancelled)
  }

  test("late start/end/error events from a replaced or stopped utterance cannot affect new playback") {
    val h = new Harness()
    val owner = new Object
    h.errorOnCancel = true
    h.speech.toggle(owner, "first")
    val old = h.requests.head
    h.speech.toggle(owner, "stop")
    h.speech.toggle(owner, "next")
    old.started()
    old.ended()
    old.failed()
    assert(h.speech.active.now().contains(owner))
    assert(!h.requests.last.cancelled)
    assert(h.notices.isEmpty)
    h.speech.stop()
  }

  test("unmounting only stops the playback owned by that control") {
    val h = new Harness()
    val first = new Object
    val second = new Object
    h.speech.toggle(second, "second")
    h.speech.stop(first)
    assert(h.speech.active.now().contains(second))
    assert(!h.requests.last.cancelled)
    h.speech.stop(second)
    assertEquals(h.speech.active.now(), None)
    assert(h.requests.last.cancelled)
  }

  test("unsupported speech and blank text never enqueue playback") {
    val h = new Harness(supported = false)
    h.speech.toggle(new Object, "hello")
    assert(h.requests.isEmpty)
    assertEquals(h.speech.active.now(), None)
    val supported = new Harness()
    supported.speech.toggle(new Object, " \n\t ")
    assert(supported.requests.isEmpty)
    assertEquals(supported.speech.active.now(), None)
  }

  test("microphone capture stops and temporarily blocks playback without any Transcriber dependency") {
    val h = new Harness()
    val owner = new Object
    h.speech.toggle(owner, "read")
    h.speech.setCaptureActive(true)
    assert(h.requests.head.cancelled)
    h.speech.toggle(owner, "must not become microphone input")
    assertEquals(h.requests.size, 1)
    h.speech.setCaptureActive(false)
    h.speech.toggle(owner, "read again")
    assertEquals(h.requests.size, 2)
    h.speech.stop()
  }

  test("speech errors clear playback, notify once and do not replay text") {
    val h = new Harness()
    h.speech.toggle(new Object, "sensitive message")
    val request = h.requests.last
    request.failed()
    request.failed()
    request.ended()
    assertEquals(h.speech.active.now(), None)
    assert(request.cancelled)
    assertEquals(h.notices.size, 1)
    assert(!h.notices.head.message.contains("sensitive message"))
    assertEquals(h.requests.size, 1)
  }

  test("synchronous exceptions and synchronous failure callbacks leave no retained playback") {
    val h = new Harness()
    h.throwOnSpeak = true
    h.speech.toggle(new Object, "rejected")
    assertEquals(h.speech.active.now(), None)
    assertEquals(h.notices.size, 1)
    h.throwOnSpeak = false
    h.failImmediately = true
    h.speech.toggle(new Object, "also rejected")
    assertEquals(h.speech.active.now(), None)
    assert(h.requests.last.cancelled)
    assertEquals(h.notices.size, 2)
    h.failImmediately = false
    h.speech.toggle(new Object, "manual retry")
    assert(h.speech.active.now().nonEmpty)
    h.speech.stop()
  }

  test("a missing start event times out without automatic retry") {
    val h = new Harness(startTimeout = 10)
    h.speech.toggle(new Object, "never starts")
    waitForTimers().map { _ =>
      assertEquals(h.speech.active.now(), None)
      assert(h.requests.last.cancelled)
      assertEquals(h.requests.size, 1)
      assertEquals(h.notices.size, 1)
    }
  }

  test("a stalled utterance times out after an actual start") {
    val h = new Harness(completionTimeout = 10)
    h.speech.toggle(new Object, "never finishes")
    h.requests.last.started()
    waitForTimers().map { _ =>
      assertEquals(h.speech.active.now(), None)
      assert(h.requests.last.cancelled)
      assertEquals(h.notices.size, 1)
    }
  }

  test("stop clears watchdogs and does not report deliberate cancellation as failure") {
    val h = new Harness(startTimeout = 10, completionTimeout = 10)
    h.errorOnCancel = true
    h.speech.toggle(new Object, "stopped")
    h.requests.last.started()
    h.speech.stop()
    waitForTimers().map { _ =>
      assertEquals(h.speech.active.now(), None)
      assert(h.notices.isEmpty)
    }
  }
}
