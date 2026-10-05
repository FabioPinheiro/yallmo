package fmgp.yallmo.repl.voice

import munit.FunSuite

final class AudioRecorderSuite extends FunSuite {
  test("voice duration is bounded") {
    assertEquals(AudioRecorder.validateDuration(1), Right(()))
    assertEquals(AudioRecorder.validateDuration(30), Right(()))
    assert(AudioRecorder.validateDuration(0).isLeft)
    assert(AudioRecorder.validateDuration(31).isLeft)
  }
}
