package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.Var
import fmgp.yallmo.clientharness.DictationMode
import fmgp.yallmo.model.{AgentId, MessagePayload}
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.timers.{SetIntervalHandle, clearInterval, setInterval}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

private[web] final case class VoiceDraftTarget(
    conversation: String,
    draft: String,
    version: Long,
    selection: Option[(Int, Int)]
)
private[web] final case class VoiceInsertion(conversation: String, text: String, cursor: Int)

private[web] enum VoicePhase {
  case Idle, Preparing, Recording, Stopping, Uploading, Transcribing
}
private[web] final case class VoiceState(
    available: Boolean = false,
    phase: VoicePhase = VoicePhase.Idle,
    seconds: Int = 0
) {
  def busy: Boolean = phase != VoicePhase.Idle
}

/** Browser-local dictation, not a special server agent path. Late callbacks are generation-guarded; only explicit
  * recording produces an upload and normal agents.invoke request. Discard never claims to cancel remote execution.
  */
private[web] final class VoiceModel(
    chat: ChatModel,
    recorder: VoiceRecorder,
    transfer: VoiceTransfer,
    notify: UiNotice => Unit,
    beforeRecord: () => Unit = () => (),
    now: () => Double = () => js.Date.now()
)(using ExecutionContext) {
  import VoicePhase.*
  val state = Var(VoiceState())
  def supported: Boolean = recorder.available
  def keyboardMode: DictationMode =
    if state.now().phase == Recording then DictationMode.Recording
    else if state.now().busy then DictationMode.Busy
    else DictationMode.Inactive
  private var identity = Option.empty[AgentId]
  private var generation = 0L
  private var capture = Option.empty[VoiceRecorder.Handle]
  private var job = Option.empty[transfer.Job]
  private var timer = Option.empty[SetIntervalHandle]

  def configure(info: Initialize.Result): Unit = {
    val enabled =
      info.identity.role == Role.Operator && info.configuredAgentIds.exists(_.contains(VoiceTransfer.Transcriber)) &&
        List(BlobsUpload.method, AgentsInvoke.method).forall(info.methods.contains)
    if !enabled || !identity.contains(info.identity.id) then cancel(false)
    identity = Option.when(enabled)(info.identity.id)
    state.update(_.copy(available = enabled))
  }

  def disable(): Unit = {
    cancel(false)
    identity = None
    state.update(_.copy(available = false))
  }

  def start(target: VoiceDraftTarget): Unit = {
    val s = chat.state.now()
    if state.now().available && !state.now().busy && supported && !s.creating && s.pending.isEmpty then {
      generation += 1
      val current = generation
      chat.setVoiceBusy(true)
      state.update(_.copy(phase = Preparing, seconds = 0))
      def live: Boolean = generation == current
      def failed(): Unit = if live then {
        cancel(false)
        notify(
          UiNotice(
            "Dictation failed. Draft retained. Check microphone access/connection; review saved messages before manually trying again.",
            "error"
          )
        )
      }
      try {
        beforeRecord()
        val handle = recorder.start(
          () =>
            if live && state.now().phase == Preparing then {
              val started = now()
              state.update(_.copy(phase = Recording))
              timer = Some(setInterval(200) {
                if live then {
                  val seconds = math.max(0, math.min(30, ((now() - started) / 1000).toInt))
                  if seconds != state.now().seconds then state.update(_.copy(seconds = seconds))
                }
              })
            },
          (bytes, limited) =>
            if live && (state.now().phase == Recording || state.now().phase == Stopping) then {
              capture = None
              timer.foreach(clearInterval)
              timer = None
              state.update(_.copy(phase = Uploading))
              if limited then notify(UiNotice("30-second limit reached — recording stopped; transcribing."))
              try {
                val running =
                  transfer.start(identity.get, bytes, () => if live then state.update(_.copy(phase = Transcribing)))
                job = Some(running)
                running.result.onComplete {
                  case Success(message) if live =>
                    message.payload match {
                      case MessagePayload.Assistant(text) =>
                        cancel(false)
                        chat.insertTranscript(target, text)
                        notify(UiNotice("Transcript inserted into the draft. Nothing was sent."))
                      case _ => failed()
                    }
                  case Failure(_: VoiceTransfer.Discarded) if live => cancel(false)
                  case Failure(_) if live                          => failed()
                  case _                                           => ()
                }
              } catch { case NonFatal(_) => failed() }
            },
          _ =>
            if state.now().phase == Preparing || state.now().phase == Recording || state.now().phase == Stopping then
              failed()
        )
        if live && state.now().phase != Uploading && state.now().phase != Transcribing then capture = Some(handle)
        else handle.cancel()
      } catch { case NonFatal(_) => failed() }
    }
  }

  def stop(): Unit = if state.now().phase == Recording then {
    state.update(_.copy(phase = Stopping))
    capture.foreach(_.stop())
  }

  def cancel(announce: Boolean = true): Unit = {
    val previous = state.now().phase
    generation += 1
    timer.foreach(clearInterval)
    timer = None
    capture.foreach(_.cancel())
    capture = None
    job.foreach(_.discard())
    job = None
    state.update(_.copy(phase = Idle, seconds = 0))
    chat.setVoiceBusy(false)
    if announce && previous != Idle then
      notify(
        UiNotice(
          if previous == Uploading || previous == Transcribing then
            "Dictation result discarded. Submitted work may continue; already saved recordings/messages are retained."
          else "Recording discarded; nothing uploaded."
        )
      )
  }
}
