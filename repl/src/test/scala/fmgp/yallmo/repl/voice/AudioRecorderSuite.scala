package fmgp.yallmo.repl.voice

import munit.FunSuite

final class AudioRecorderSuite extends FunSuite {
  test("JVM WAV metadata uses encoded size and captured frames without an external decoder") {
    val format = new javax.sound.sampled.AudioFormat(16000f, 16, 1, true, false)
    val audio = AudioRecorder.toWav(new Array[Byte](3200), 3200, format)
    assertEquals(audio.sizeBytes, audio.bytes.size.toLong)
    assertEquals(audio.durationSeconds, Some(0.1))
    assertEquals(audio.redacted.durationSeconds, Some(0.1))
    assert(audio.sizeBytes > 3200)
    assertEquals(audio.description, None)
  }

  test("voice duration is bounded") {
    assertEquals(AudioRecorder.validateDuration(1), Right(()))
    assertEquals(AudioRecorder.validateDuration(30), Right(()))
    assert(AudioRecorder.validateDuration(0).isLeft)
    assert(AudioRecorder.validateDuration(31).isLeft)
  }
}
