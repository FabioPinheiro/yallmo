package fmgp.yallmo.repl.voice

import munit.FunSuite

final class AudioRecorderSuite extends FunSuite {
  test("JVM WAV capture keeps encoded bytes separate from the audio message") {
    val format = new javax.sound.sampled.AudioFormat(16000f, 16, 1, true, false)
    val audio = AudioRecorder.toWav(new Array[Byte](3200), 3200, format)
    assertEquals(audio.sizeBytes, audio.bytes.size.toLong)
    assert(audio.sizeBytes > 3200)
    assertEquals(audio.input.description, None)
  }

  test("voice duration is bounded") {
    assertEquals(AudioRecorder.validateDuration(1), Right(()))
    assertEquals(AudioRecorder.validateDuration(30), Right(()))
    assert(AudioRecorder.validateDuration(0).isLeft)
    assert(AudioRecorder.validateDuration(31).isLeft)
  }
}
