package fmgp.yallmo.repl.terminal

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.llm.{AudioFormat, AudioInput, LlmProviderAudio}
import fmgp.yallmo.provider.Transcriber
import fmgp.yallmo.repl.voice.{AudioRecorder, VoiceRecordingTermination}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import org.jline.reader.{LineReader, Reference, Widget}
import org.jline.terminal.Size
import org.jline.terminal.impl.DumbTerminal
import zio.*

final class VoiceInsertSuite extends FunSuite {
  test("voice completion, cancellation and failure stay NORMAL until i is pressed") {
    for scenario <- List("success", "cancel", "recording-error", "transcription-error", "empty") do {
      val stop = if scenario == "cancel" then "\u001b" else " "
      val terminal = new DumbTerminal(
        "test",
        "xterm",
        new ByteArrayInputStream(s"draft\u001bhlv${stop}~i\n".getBytes(UTF_8)),
        new ByteArrayOutputStream(),
        UTF_8
      )
      terminal.setSize(new Size(120, 24))
      val reader = new StatusLineReader(terminal, new java.util.HashMap[String, Object]())
      reader.getKeyMaps.put(LineReader.MAIN, reader.getKeyMaps.get(LineReader.VIINS))
      val status = new TerminalVoiceModeStatus(reader)
      val audio = AudioInput(Chunk(1.toByte), AudioFormat.Wav)
      val recorder = new AudioRecorder {
        def record(seconds: Int): Task[AudioInput] = ZIO.succeed(audio)
        def recordUntil(termination: UIO[VoiceRecordingTermination]): Task[Option[AudioInput]] =
          termination.flatMap {
            case VoiceRecordingTermination.Cancel => ZIO.none
            case VoiceRecordingTermination.Stop   =>
              if scenario == "recording-error" then ZIO.fail(new IllegalStateException("No microphone"))
              else ZIO.some(audio)
          }
      }
      val transcriber = new Transcriber(new LlmProviderAudio {
        def inferenceWithAudio(prompt: String, audio: AudioInput, model: Option[String]): IO[YallmoError, String] =
          if scenario == "transcription-error" then
            ZIO.fail(YallmoError.Unexpected(new IllegalStateException("Offline")))
          else ZIO.succeed(if scenario == "empty" then " " else "hello")
      })
      var observedMode: Option[String] = None
      status.install()
      new TerminalMenus(reader, None).install()
      new VoiceInsert(reader, recorder, transcriber, status).install()
      reader.getWidgets.put(
        "test-after-voice",
        new Widget {
          override def apply(): Boolean = { observedMode = Some(reader.getKeyMap); true }
        }
      )
      reader.getKeyMaps.get(LineReader.VICMD).bind(new Reference("test-after-voice"), "~")
      try {
        assertEquals(reader.readLine("test> "), if scenario == "success" then "draft hello" else "draft", scenario)
        assertEquals(observedMode, Some(LineReader.VICMD), scenario)
      } finally {
        Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(status.close).getOrThrowFiberFailure() }
        terminal.close()
      }
    }
  }

  test("voice insertion uses the single-key NORMAL binding") {
    assertEquals(VoiceInsert.VoiceCommand, "v")
  }

  test("voice insertion resumes at the logical text position after NORMAL") {
    assertEquals(VoiceInsert.insertionPoint(cursor = 2, currentCharacter = 'c'), 3)
    assertEquals(VoiceInsert.insertionPoint(cursor = 0, currentCharacter = -1), 0)
  }

  test("voice insertion keeps words separate at an in-word cursor") {
    assertEquals(VoiceInsert.insertion('o', 'w', "hello"), " hello ")
  }

  test("voice insertion does not add whitespace at an existing boundary") {
    assertEquals(VoiceInsert.insertion(' ', -1, "hello"), "hello")
    assertEquals(VoiceInsert.insertion(-1, '.', "hello"), "hello")
  }
}
