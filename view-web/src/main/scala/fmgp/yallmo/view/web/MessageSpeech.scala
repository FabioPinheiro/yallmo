package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.scalajs.js.timers.{SetTimeoutHandle, clearTimeout, setTimeout}
import scala.util.control.NonFatal

/** Application-owned, browser-only playback. No agent, RPC, session or microphone is involved. */
private[web] final class MessageSpeech(
    playback: MessageSpeech.Playback,
    notify: UiNotice => Unit,
    startTimeoutMillis: Int = 4500,
    completionTimeoutMillis: String => Double = text => math.max(120000.0, text.length.toDouble * 200)
) {
  private[web] val active = Var(Option.empty[AnyRef])
  private val captureActive = Var(false)

  /** Avoid recording synthesized speech as microphone input; playback remains independent of agent availability. */
  def setCaptureActive(recording: Boolean): Unit = {
    if recording && !captureActive.now() then stop()
    captureActive.set(recording)
  }
  private var generation = 0L
  private var cancel: () => Unit = () => ()
  private var timer = Option.empty[SetTimeoutHandle]

  /** Each mounted control owns its playback, even when the same message appears in several views. */
  def buttonFor(text: Signal[String]): HtmlElement = {
    val owner = new Object
    val reading = active.signal.map(_.contains(owner))
    button(
      typ := "button",
      cls := "message-read",
      child.text <-- reading.map(if _ then "■ Stop" else "🔊 Read"),
      aria.label <-- reading.map(if _ then "Stop reading message" else "Read message aloud"),
      title <-- reading.combineWith(captureActive.signal).map { (running, recording) =>
        if recording then "Finish or discard recording before reading aloud"
        else if !playback.available then "Read aloud is not supported by this browser."
        else if running then "Stop reading this message"
        else "Read the full message verbatim using the browser's default voice"
      },
      disabled <-- text
        .combineWith(captureActive.signal)
        .map((value, recording) => recording || !playback.available || value.trim.isEmpty),
      onClick.compose(_.withCurrentValueOf(text).map(_._2)) --> (value => toggle(owner, MessageText.forSpeech(value))),
      onUnmountCallback(_ => stop(owner))
    )
  }

  private[web] def toggle(owner: AnyRef, text: String): Unit =
    if active.now().contains(owner) then stop()
    else {
      stop()
      if !captureActive.now() && playback.available && text.trim.nonEmpty then {
        val current = generation
        var started = false
        active.set(Some(owner))
        def finish(error: Option[String]): Unit =
          if generation == current then {
            stop()
            error.foreach(message => notify(UiNotice(message, "error")))
          }
        timer = Some(setTimeout(startTimeoutMillis) {
          finish(Some("Browser speech did not start. Focus this tab and click Read to try again."))
        })
        try {
          val handle = playback.speak(
            text,
            () =>
              if generation == current && !started then {
                started = true
                timer.foreach(clearTimeout)
                timer = Some(setTimeout(completionTimeoutMillis(text)) {
                  finish(Some("Browser speech timed out. Click Read to try again."))
                })
              },
            () => finish(None),
            () => finish(Some("Browser speech failed. Focus this tab and click Read to try again."))
          )
          // Some browser implementations can report failure synchronously inside speak.
          if generation == current then cancel = handle else handle()
        } catch {
          case NonFatal(_) => finish(Some("Browser speech is unavailable. Click Read to try again."))
        }
      }
    }

  private[web] def stop(owner: AnyRef): Unit =
    if active.now().contains(owner) then stop()

  def stop(): Unit = {
    generation += 1
    timer.foreach(clearTimeout)
    timer = None
    val previous = cancel
    cancel = () => ()
    active.set(None)
    try previous()
    catch { case NonFatal(_) => notify(UiNotice("The browser could not stop speech playback.", "error")) }
  }
}

private[web] object MessageSpeech {

  /** A cancellable retained utterance; callbacks describe browser events, not proof of audible sound. */
  trait Playback {
    def available: Boolean
    def speak(text: String, started: () => Unit, ended: () => Unit, failed: () => Unit): () => Unit
  }

  // scalajs-dom does not expose these speech APIs; keep native interop at this boundary.
  @js.native
  @JSGlobal("SpeechSynthesisUtterance")
  private class Utterance(@scala.annotation.unused text: String) extends js.Object {
    var onstart: js.Function1[js.Any, Unit] = js.native
    var onend: js.Function1[js.Any, Unit] = js.native
    var onerror: js.Function1[js.Any, Unit] = js.native
  }

  @js.native
  private trait Synthesis extends js.Object {
    def cancel(): Unit = js.native
    def speak(utterance: Utterance): Unit = js.native
  }

  def browserPlayback: Playback = new Playback {
    def available: Boolean =
      js.typeOf(js.Dynamic.global.speechSynthesis) != "undefined" &&
        js.typeOf(js.Dynamic.global.SpeechSynthesisUtterance) == "function"

    def speak(text: String, started: () => Unit, ended: () => Unit, failed: () => Unit): () => Unit = {
      val synthesis = js.Dynamic.global.speechSynthesis.asInstanceOf[Synthesis]
      synthesis.cancel()
      val utterance = new Utterance(text)
      utterance.onstart = _ => started()
      utterance.onend = _ => ended()
      utterance.onerror = _ => failed()
      // This closure retains the utterance until completion/cancellation and detaches before cancel events.
      val cancel = () => {
        utterance.onstart = null
        utterance.onend = null
        utterance.onerror = null
        synthesis.cancel()
      }
      try synthesis.speak(utterance)
      catch {
        case NonFatal(error) => cancel(); throw error
      }
      cancel
    }
  }
}
