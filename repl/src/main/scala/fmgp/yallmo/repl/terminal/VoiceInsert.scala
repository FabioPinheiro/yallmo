package fmgp.yallmo.repl.terminal

import zio.*
import org.jline.reader.{LineReader, Reference, Widget}
import fmgp.yallmo.repl.voice.CapturedAudio
import fmgp.yallmo.repl.voice.Transcriber
import fmgp.yallmo.repl.voice.{AudioRecorder, VoiceRecordingTermination}

/** Owns the line-editor-only voice workflow. It never submits text to [[fmgp.yallmo.repl.ReplApplication]]. */
private[terminal] final class VoiceInsert(
    reader: StatusLineReader,
    recorder: AudioRecorder,
    transcriber: Option[Transcriber],
    terminalStatus: TerminalVoiceModeStatus,
    command: String = VoiceInsert.VoiceCommand,
    recordingStarted: UIO[Boolean] = ZIO.succeed(true),
    runtime: Runtime[Any] = Runtime.default
) {
  import VoiceInsert.*

  def install(): Unit = {
    reader.getWidgets.put(
      widgetName,
      new Widget {
        override def apply(): Boolean = insertVoice()
      }
    )
    reader.getKeyMaps.get(LineReader.VICMD).bind(new Reference(widgetName), command)
  }

  private val widgetName = s"voice-insert-$command"

  private def insertVoice(): Boolean = {
    val originalCursor = reader.getBuffer.cursor()
    try {
      if !insertVoiceSafely() then reader.getBuffer.cursor(originalCursor)
      true
    } catch {
      case error: Throwable =>
        reader.getBuffer.cursor(originalCursor)
        showFailure(error)
        true
    } finally {
      reader.setKeyMap(LineReader.VICMD)
      terminalStatus.updateForKeyMap()
    }
  }

  private def insertVoiceSafely(): Boolean = {
    // NORMAL (the Vi command map) places the cursor on the preceding character. Insert after that character so Esc, v preserves
    // the logical insertion position from text mode; NORMAL movement intentionally selects a new insertion point.
    val insertionPoint = insertionCursor
    val voice = transcriber match {
      case Some(value) => value
      case None        =>
        reader.printAbove(Transcriber.DisabledMessage)
        return false
    }
    val termination = run(Promise.make[Nothing, VoiceRecordingTermination])
    // Each `run` completes before this widget waits for the stop key. Use a daemon fiber so that completion does not
    // immediately interrupt the microphone capture as it would with a child `fork`.
    val recording = run(recorder.recordUntil(termination.await).forkDaemon)

    val result = try {
      awaitTermination(recording, termination)
      run(recording.join.either)
    } finally run(recording.interrupt)

    val inserted = result match {
      case Left(error) =>
        showFailure(error)
        false
      case Right(None)        => false
      case Right(Some(audio)) =>
        terminalStatus.transcribing()
        run(voice.transcribe(audio).either) match {
          case Left(error) =>
            showFailure(error)
            false
          case Right(value) => insertTranscript(value, insertionPoint)
        }
    }

    inserted
  }

  /** Recording completion/errors must release the widget even when no further key arrives. */
  private def awaitTermination(
      recording: Fiber[Throwable, Option[CapturedAudio]],
      termination: Promise[Nothing, VoiceRecordingTermination]
  ): Unit = {
    var waiting = true
    var started = false
    terminalStatus.waitingForRecording()
    while waiting && run(recording.poll).isEmpty do {
      if !started && run(recordingStarted) then {
        started = true
        terminalStatus.recording()
      }
      val end = reader.readControl(100L) match {
        case Space  => Some(VoiceRecordingTermination.Stop)
        case Escape => Some(VoiceRecordingTermination.Cancel)
        case -1     => Some(VoiceRecordingTermination.Cancel)
        case _      => None
      }
      end.foreach { value =>
        run(termination.succeed(value))
        waiting = false
      }
    }
  }

  private def insertionCursor: Int = {
    val buffer = reader.getBuffer
    insertionPoint(buffer.cursor(), buffer.currChar())
  }

  private def insertTranscript(value: String, insertionPoint: Int): Boolean = {
    val transcript = value.trim
    if transcript.isEmpty then {
      reader.printAbove("Voice input produced no transcript.")
      false
    } else {
      val buffer = reader.getBuffer
      buffer.cursor(insertionPoint)
      buffer.write(insertion(buffer.prevChar(), buffer.currChar(), transcript))
      true
    }
  }

  private def showFailure(error: Throwable): Unit =
    reader.printAbove(
      s"Voice input failed: ${Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)}"
    )

  private def run[A](effect: UIO[A]): A =
    Unsafe.unsafe { unsafe ?=> runtime.unsafe.run(effect).getOrThrowFiberFailure() }
}

private[terminal] object VoiceInsert {
  val VoiceCommand = "v"
  private val Space = ' '.toInt
  private val Escape = 27

  /** Maps a NORMAL cursor to the following text insertion point. */
  private[terminal] def insertionPoint(cursor: Int, currentCharacter: Int): Int =
    if currentCharacter >= 0 then cursor + 1 else cursor

  /** Inserts separating whitespace only when the transcript would otherwise merge two adjacent words. */
  private[terminal] def insertion(before: Int, after: Int, transcript: String): String = {
    val leadingSpace = if isWordCharacter(before) then " " else ""
    val trailingSpace = if isWordCharacter(after) then " " else ""
    s"$leadingSpace$transcript$trailingSpace"
  }

  private def isWordCharacter(character: Int): Boolean =
    character >= 0 && Character.isLetterOrDigit(character)
}
