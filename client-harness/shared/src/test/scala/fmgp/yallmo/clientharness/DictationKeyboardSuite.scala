package fmgp.yallmo.clientharness

import munit.FunSuite

final class DictationKeyboardSuite extends FunSuite {
  private final class Fixture {
    var phase = DictationMode.Inactive
    var stops = 0
    var cancels = 0
    val keyboard = new DictationKeyboard(
      () => phase,
      () => { stops += 1; phase = DictationMode.Busy },
      () => { cancels += 1; phase = DictationMode.Inactive }
    )
    def down(key: String, modified: Boolean = false, composing: Boolean = false, repeat: Boolean = false): Boolean =
      keyboard.keyDown(KeyInput(key, ctrl = modified, composing = composing, repeat = repeat))
  }

  test("first Space retains native activation; second Space stops recording and consumes its release") {
    val f = new Fixture
    assert(!f.down(" "))
    assert(!f.keyboard.keyUp(" "))
    f.phase = DictationMode.Recording
    assert(f.down(" "))
    assertEquals(f.stops, 1)
    assertEquals(f.phase, DictationMode.Busy)
    assert(f.down(" ", repeat = true))
    assert(f.keyboard.keyUp(" "))
    assertEquals(f.stops, 1)
    f.phase = DictationMode.Inactive
    assert(!f.down(" "))
    assert(!f.keyboard.keyUp(" "))
  }

  test("Escape cancels every active dictation phase, never starts or stops capture") {
    DictationMode.values.filter(_ != DictationMode.Inactive).foreach { phase =>
      val f = new Fixture
      f.phase = phase
      assert(f.down("Escape"))
      assertEquals(f.cancels, 1)
      assertEquals(f.stops, 0)
      assertEquals(f.phase, DictationMode.Inactive)
      assert(!f.down("Escape"))
    }
  }

  test("modifiers, composition and held keys cannot stop or cancel dictation") {
    for key <- List(" ", "Escape") do {
      val f = new Fixture
      f.phase = DictationMode.Recording
      assert(!f.down(key, modified = true))
      assert(!f.down(key, composing = true))
      assert(f.down(key, repeat = true))
      assertEquals(f.stops, 0)
      assertEquals(f.cancels, 0)
    }
  }

  test("Space outside recording never stops; unrelated keys remain available during dictation") {
    DictationMode.values.filter(_ != DictationMode.Recording).foreach { phase =>
      val f = new Fixture
      f.phase = phase
      assert(!f.down(" "))
      assertEquals(f.stops, 0)
    }
    val f = new Fixture
    f.phase = DictationMode.Recording
    assert(!f.down("a"))
    assert(!f.down("Enter"))
  }

  test("losing window focus resets the consumed Space latch") {
    val f = new Fixture
    f.phase = DictationMode.Recording
    assert(f.down(" "))
    f.keyboard.reset()
    assert(!f.keyboard.keyUp(" "))
    f.phase = DictationMode.Inactive
    assert(!f.down(" "))
  }
}
