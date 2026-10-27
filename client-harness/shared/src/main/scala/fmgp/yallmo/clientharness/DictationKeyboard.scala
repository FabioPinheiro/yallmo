package fmgp.yallmo.clientharness

/** Only distinctions relevant to input policy; capture/upload phases remain client-owned. */
enum DictationMode {
  case Inactive, Recording, Busy
}

/** Shared dictation key lifecycle. Effects are supplied by the browser or terminal adapter. */
final class DictationKeyboard(mode: () => DictationMode, stop: () => Unit, cancel: () => Unit) {
  private var spaceHeld = false

  /** True means suppress native editing, scrolling and button activation, even when no action is dispatched. */
  def keyDown(input: KeyInput): Boolean =
    if input.key == " " && spaceHeld then true
    else if input.plain && !input.composing then {
      val current = mode()
      if input.key == " " && current == DictationMode.Recording then {
        spaceHeld = true
        if !input.repeat then stop()
        true
      } else if input.key == "Escape" && current != DictationMode.Inactive then {
        if !input.repeat then cancel()
        true
      } else false
    } else false

  /** Consume the matching release: stopping must not click a focused button or start another recording. */
  def keyUp(key: String): Boolean =
    if key == " " && spaceHeld then {
      spaceHeld = false
      true
    } else false

  def reset(): Unit = spaceHeld = false
}
