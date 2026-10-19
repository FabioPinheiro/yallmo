package fmgp.yallmo.repl.terminal

import fmgp.yallmo.model.{AudioFormat, AudioInput, YallmoError}
import fmgp.yallmo.repl.voice.Transcriber
import fmgp.yallmo.repl.voice.{AudioRecorder, VoiceRecordingTermination}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PipedInputStream, PipedOutputStream}
import java.util.concurrent.{CompletableFuture, CountDownLatch, TimeUnit}
import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import org.jline.reader.{LineReader, Reference, Widget}
import org.jline.terminal.Size
import org.jline.terminal.impl.DumbTerminal
import zio.*

final class VoiceInsertSuite extends FunSuite {
  test("disabled voice reports configuration before either recorder starts and preserves draft and cursor") {
    for command <- List("v", "m") do {
      val output = new ByteArrayOutputStream()
      val terminal = new DumbTerminal(
        "test",
        "xterm",
        new ByteArrayInputStream(s"draft\u001bhh${command}iX\n".getBytes(UTF_8)),
        output,
        UTF_8
      )
      terminal.setSize(new Size(120, 24))
      val reader = new StatusLineReader(terminal, new java.util.HashMap[String, Object]())
      reader.getKeyMaps.put(LineReader.MAIN, reader.getKeyMaps.get(LineReader.VIINS))
      val status = new TerminalVoiceModeStatus(reader)
      val recorder = new AudioRecorder {
        def record(seconds: Int): Task[AudioInput] = ZIO.dieMessage("Disabled voice must not record")
        def recordUntil(termination: UIO[VoiceRecordingTermination]): Task[Option[AudioInput]] =
          ZIO.dieMessage("Disabled voice must not record")
      }
      new TerminalMenus(reader, None).install()
      new VoiceInsert(reader, recorder, None, status, command).install()
      try {
        assertEquals(reader.readLine("test> "), "drXaft")
        assert(output.toString(UTF_8).contains("configure transcriberAgent"))
      } finally terminal.close()
    }
  }

  test("voice completion, cancellation and failure stay NORMAL until i is pressed") {
    for
      command <- List("v", "m")
      scenario <- List("success", "cancel", "recording-error", "transcription-error", "empty")
    do {
      val stop = if scenario == "cancel" then "\u001b" else " "
      val terminal = new DumbTerminal(
        "test",
        "xterm",
        new ByteArrayInputStream(s"draft\u001bhl${command}${stop}~i\n".getBytes(UTF_8)),
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
      val transcriber = new Transcriber {
        def transcribe(audio: AudioInput): IO[YallmoError, String] =
          if scenario == "transcription-error" then
            ZIO.fail(YallmoError.Unexpected(new IllegalStateException("Offline")))
          else ZIO.succeed(if scenario == "empty" then " " else "hello")
      }
      var observedMode: Option[String] = None
      status.install()
      new TerminalMenus(reader, None).install()
      new VoiceInsert(reader, recorder, Some(transcriber), status, command).install()
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

  test("both microphones return on recorder failure, timeout or natural completion without another key") {
    for
      command <- List("v", "m")
      result <- List("error", "timeout", "complete")
    do {
      val input = new PipedInputStream(4096)
      val writer = new PipedOutputStream(input)
      val terminal = new DumbTerminal("test", "xterm", input, new ByteArrayOutputStream(), UTF_8)
      terminal.setSize(new Size(120, 24))
      val reader = new StatusLineReader(terminal, new java.util.HashMap[String, Object]())
      reader.getKeyMaps.put(LineReader.MAIN, reader.getKeyMaps.get(LineReader.VIINS))
      val status = new TerminalVoiceModeStatus(reader)
      val audio = AudioInput(Chunk(1.toByte), AudioFormat.Wav)
      var transcriptions = 0
      val transcriber = new Transcriber {
        def transcribe(audio: AudioInput): IO[YallmoError, String] =
          ZIO.succeed { transcriptions += 1; "hello" }
      }
      val recorder = new AudioRecorder {
        def record(seconds: Int): Task[AudioInput] = ZIO.succeed(audio)
        def recordUntil(termination: UIO[VoiceRecordingTermination]): Task[Option[AudioInput]] =
          ZIO.sleep(150.millis) *> (if result == "complete" then ZIO.some(audio)
                                    else ZIO.fail(new IllegalStateException(result)))
      }
      new TerminalMenus(reader, None).install()
      new VoiceInsert(reader, recorder, Some(transcriber), status, command).install()
      val completed = new CountDownLatch(1)
      val original = reader.getWidgets.get(s"voice-insert-$command")
      reader.getWidgets.put(
        s"voice-insert-$command",
        new Widget {
          def apply(): Boolean = try original.apply()
          finally completed.countDown()
        }
      )
      def send(text: String): Unit = { writer.write(text.getBytes(UTF_8)); writer.flush() }
      val line = CompletableFuture.supplyAsync(() => reader.readLine("test> "))
      try {
        send(s"draft\u001bhh$command")
        assert(completed.await(3, TimeUnit.SECONDS), s"$command/$result required another key")
        assert(!line.isDone, "voice must not auto-submit")
        assertEquals(reader.getKeyMap, LineReader.VICMD)
        assertEquals(reader.getBuffer.toString, if result == "complete" then "dra hello ft" else "draft")
        assertEquals(reader.getBuffer.cursor(), if result == "complete" then 10 else 2)
        assertEquals(transcriptions, if result == "complete" then 1 else 0)
        send("iX\n")
        assertEquals(line.get(3, TimeUnit.SECONDS), if result == "complete" then "dra hello Xft" else "drXaft")
      } finally { writer.close(); terminal.close() }
    }
  }

  test("repeated cancellation, failures and empty transcripts preserve the exact NORMAL cursor and draft") {
    for
      command <- List("v", "m")
      scenario <- List("cancel", "recording-error", "transcription-error", "empty")
    do {
      val stop = if scenario == "cancel" then "\u001b" else " "
      val keys = s"draft\u001bhh${command}${stop}~${command}${stop}~iX\n"
      val terminal = new DumbTerminal(
        "test",
        "xterm",
        new ByteArrayInputStream(keys.getBytes(UTF_8)),
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
      val transcriber = new Transcriber {
        def transcribe(audio: AudioInput): IO[YallmoError, String] =
          if scenario == "transcription-error" then
            ZIO.fail(YallmoError.Unexpected(new IllegalStateException("Offline")))
          else ZIO.succeed(" ")
      }
      new TerminalMenus(reader, None).install()
      new VoiceInsert(reader, recorder, Some(transcriber), status, command).install()
      var snapshots = List.empty[(String, Int, String)]
      reader.getWidgets.put(
        "test-preservation",
        new Widget {
          def apply(): Boolean = {
            snapshots = snapshots :+ ((reader.getBuffer.toString, reader.getBuffer.cursor(), reader.getKeyMap))
            true
          }
        }
      )
      reader.getKeyMaps.get(LineReader.VICMD).bind(new Reference("test-preservation"), "~")
      try {
        assertEquals(reader.readLine("test> "), "drXaft", s"$command/$scenario")
        assertEquals(snapshots, List.fill(2)(("draft", 2, LineReader.VICMD)), s"$command/$scenario")
      } finally terminal.close()
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
