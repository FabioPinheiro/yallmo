package fmgp.yallmo.view.web

import munit.FunSuite

final class ChatViewSuite extends FunSuite {
  test("only plain Enter sends; Shift+Enter, IME and former Ctrl/Cmd shortcuts do not") {
    def sends(
        key: String = "Enter",
        shift: Boolean = false,
        ctrl: Boolean = false,
        meta: Boolean = false,
        alt: Boolean = false,
        composing: Boolean = false
    ): Boolean =
      ChatView.shouldSend(key, shift, ctrl, meta, alt, composing)

    assert(sends())
    assert(!sends(shift = true))
    assert(!sends(composing = true))
    assert(!sends(ctrl = true))
    assert(!sends(meta = true))
    assert(!sends(alt = true))
    assert(!sends(key = "a"))
  }
}
