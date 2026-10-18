package fmgp.yallmo.repl.terminal

import fmgp.yallmo.provider.Transcriber
import fmgp.yallmo.repl.voice.{AudioRecorder, VoiceRecordingTermination}
import org.jline.reader.impl.LineReaderImpl
import org.jline.reader.{LineReader, Reference, Widget}
import zio.*

/** Owns the line-editor-only voice workflow. It never submits text to [[fmgp.yallmo.repl.ReplApplication]]. */
private[terminal] final class VoiceInsert(
    reader: LineReader,
    recorder: AudioRecorder,
    transcriber: Transcriber,
    terminalStatus: TerminalVoiceModeStatus
) {
  import VoiceInsert.*

  def install(): Unit = {
    reader.getWidgets.put(
      WidgetName,
      new Widget {
        override def apply(): Boolean = insertVoice()
      }
    )
    reader.getKeyMaps.get(LineReader.VICMD).bind(new Reference(WidgetName), VoiceCommand)
  }

  private def insertVoice(): Boolean =
    try insertVoiceSafely()
    catch {
      case error: Throwable =>
        showFailure(error)
        terminalStatus.updateForKeyMap()
        true
    }

  private def insertVoiceSafely(): Boolean = {
    // NORMAL (the Vi command map) places the cursor on the preceding character. Insert after that character so Esc, v preserves
    // the logical insertion position from text mode; NORMAL movement intentionally selects a new insertion point.
    val insertionPoint = insertionCursor
    val termination = run(Promise.make[Nothing, VoiceRecordingTermination])
    // Each `run` completes before this widget waits for the stop key. Use a daemon fiber so that completion does not
    // immediately interrupt the microphone capture as it would with a child `fork`.
    val recording = run(recorder.recordUntil(termination.await).forkDaemon)

    terminalStatus.recording()
    val end = awaitTermination()
    run(termination.succeed(end))

    val inserted = run(recording.join.either) match {
      case Left(error) =>
        showFailure(error)
        false
      case Right(None)        => false
      case Right(Some(audio)) =>
        terminalStatus.transcribing()
        run(transcriber.transcribe(audio).either) match {
          case Left(error) =>
            showFailure(error)
            false
          case Right(value) => insertTranscript(value, insertionPoint)
        }
    }

    if !inserted then reader.getBuffer.cursor(insertionPoint)
    terminalStatus.updateForKeyMap()
    true
  }

  /** Reads only the transient recording controls through JLine's own input reader. All other keys are ignored. */
  private def awaitTermination(): VoiceRecordingTermination = {
    val input = reader.asInstanceOf[LineReaderImpl]

    @annotation.tailrec
    def loop(): VoiceRecordingTermination =
      input.readCharacter() match {
        case Space  => VoiceRecordingTermination.Stop
        case Escape => VoiceRecordingTermination.Cancel
        case -1     => VoiceRecordingTermination.Cancel
        case _      => loop()
      }

    loop()
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
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}

private[terminal] object VoiceInsert {
  val VoiceCommand = "v"
  private val WidgetName = "voice-insert"
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
