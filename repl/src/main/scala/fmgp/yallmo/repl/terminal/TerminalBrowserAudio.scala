package fmgp.yallmo.repl.terminal

import fmgp.yallmo.model.MessagePayload
import fmgp.yallmo.repl.ReplState
import fmgp.yallmo.repl.browser.{BrowserAudioBridge, BrowserAudioError}
import fmgp.yallmo.repl.voice.{AudioRecorder, CapturedAudio, VoiceRecordingTermination}
import zio.*

/** Single-flight lazy construction in the enclosing REPL scope, never in a widget-sized scope. */
private[terminal] final class TerminalBrowserAudio private (
    bridge: IO[BrowserAudioError, BrowserAudioBridge],
    val recordingStarted: UIO[Boolean]
) {
  val recorder: AudioRecorder = new AudioRecorder {
    def record(seconds: Int): Task[CapturedAudio] = bridge.flatMap(_.recorder.record(seconds))
    def recordUntil(termination: UIO[VoiceRecordingTermination]): Task[Option[CapturedAudio]] =
      bridge.flatMap(_.recorder.recordUntil(termination))
  }

  def speak(state: ReplState): IO[BrowserAudioError, Unit] =
    bridge.flatMap { audio =>
      TerminalBrowserAudio.latestAssistant(state) match {
        case Some(text) => audio.speak(text)
        case None => ZIO.fail(BrowserAudioError("no_response", "No assistant response in the current conversation."))
      }
    }
}

private[terminal] object TerminalBrowserAudio {
  def make(
      showSetup: String => UIO[Unit],
      create: ZIO[Scope, BrowserAudioError, BrowserAudioBridge] = BrowserAudioBridge.make
  ): ZIO[Scope, Nothing, TerminalBrowserAudio] =
    for {
      scope <- ZIO.service[Scope]
      cached <- Ref.Synchronized.make(Option.empty[BrowserAudioBridge])
      get = cached.modifyZIO {
        case Some(bridge) => ZIO.succeed((bridge, Some(bridge)))
        case None         => scope.extend(create).map(bridge => (bridge, Some(bridge)))
      }
    } yield new TerminalBrowserAudio(
      get.tap { bridge =>
        for {
          status <- bridge.status
          _ <- ZIO.unless(status.connected)(showSetup(bridge.setupUrl))
        } yield ()
      },
      cached.get.flatMap(_.fold[UIO[Boolean]](ZIO.succeed(false))(_.status.map(_.recordingStarted)))
    )

  def latestAssistant(state: ReplState): Option[String] =
    state.runContext.messages.reverseIterator.collectFirst {
      case MessagePayload.Assistant(text) if text.trim.nonEmpty => text
    }
}
