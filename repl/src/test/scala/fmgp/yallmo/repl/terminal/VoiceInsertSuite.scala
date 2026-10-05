package fmgp.yallmo.repl.terminal

import munit.FunSuite

final class VoiceInsertSuite extends FunSuite {
  test("voice insertion uses the single-key Vi command binding") {
    assertEquals(VoiceInsert.VoiceCommand, "v")
  }

  test("voice insertion resumes at the logical text position after Vi command mode") {
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
