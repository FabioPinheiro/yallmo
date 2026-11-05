package fmgp.yallmo.clientharness

import munit.FunSuite

final class KeyboardControlsSuite extends FunSuite {
  private val actions = ControlAction.values.toList
  private def action(input: KeyInput, mode: InputMode = InputMode.Normal): Option[ControlAction] =
    KeyboardControls.action(input, mode, actions)

  test("catalog shortcuts select the same actions on JVM and JS, without ambiguous NORMAL bindings") {
    val keyed = actions.filter(_.shortcut.nonEmpty)
    assertEquals(keyed.flatMap(_.shortcut).distinct.size, keyed.size)
    keyed.foreach(control => assertEquals(action(KeyInput(control.shortcut.get)), Some(control)))
    assertEquals(action(KeyInput("x")), None)
    assertEquals(action(KeyInput("I")), None)
  }

  test("stable machine IDs are unique and separate from platform labels") {
    assertEquals(actions.map(_.id).distinct.size, actions.size)
    assert(actions.forall(_.id.matches("[a-z]+(-[a-z]+)*")))
    assert(actions.forall(action => action.webLabel.nonEmpty && action.terminalLabel.nonEmpty))
    assertEquals(ControlAction.Edit.id, "edit")
    assertEquals(ControlAction.Agents.id, "agents")
    assertEquals(ControlAction.Dictation.id, "dictation")
    assertEquals(ControlAction.Sdlc.id, "sdlc")
    assertEquals(ControlAction.Sdlc.shortcut, Some("8"))
    assertEquals(ControlAction.Sdlc.webLabel, "Go to SDLC")
    assertEquals(ControlAction.Edit.webLabel, "Edit message")
    assertEquals(ControlAction.Edit.terminalLabel, "edit")
    assertEquals(ControlAction.Dictation.webLabel, "Focus dictation / Stop recording")
    assertEquals(ControlAction.Dictation.terminalLabel, "browser mic")
    assertEquals(ControlAction.Edit.terminalHint, "i: edit")
    assertEquals(ControlAction.Normal.terminalHint, "Esc: NORMAL")
    assertEquals(ControlAction.Reconnect.terminalHint, "reconnect")
  }

  test("web search uses web labels, not terminal labels or machine IDs") {
    val values = List(ControlAction.Dictation, ControlAction.AgentRegistry)
    assertEquals(KeyboardControls.search(values, "dictation"), List(ControlAction.Dictation))
    assertEquals(KeyboardControls.search(values, "browser mic"), Nil)
    assertEquals(KeyboardControls.search(values, "agent-registry"), Nil)
  }

  test("typing, modifiers, composition and repeats cannot trigger NORMAL actions") {
    for control <- List(ControlAction.Edit, ControlAction.Agents, ControlAction.Next, ControlAction.Previous) do {
      val input = KeyInput(control.shortcut.get)
      assertEquals(action(input, InputMode.Insert), None)
      assertEquals(action(input, InputMode.Menu), None)
      val normalOnly = actions.filterNot(_ == ControlAction.Commands)
      assertEquals(KeyboardControls.action(input.copy(ctrl = true), InputMode.Normal, normalOnly), None)
      assertEquals(KeyboardControls.action(input.copy(meta = true), InputMode.Normal, normalOnly), None)
      assertEquals(action(input.copy(alt = true)), None)
      assertEquals(action(input.copy(composing = true)), None)
      assertEquals(action(input.copy(repeat = true)), None)
    }
    assertEquals(action(KeyInput("?"), InputMode.Insert), None)
  }

  test("only registered actions are available; metadata never enables unsupported client capabilities") {
    assertEquals(KeyboardControls.action(KeyInput("q"), InputMode.Normal, List(ControlAction.Edit)), None)
    assertEquals(KeyboardControls.action(KeyInput("k", ctrl = true), InputMode.Insert, Nil), None)
    assertEquals(KeyboardControls.action(KeyInput("Escape"), InputMode.Normal, Nil), None)
  }

  test("Ctrl/Cmd+K opens commands globally; modified browser shortcuts are untouched") {
    InputMode.values.foreach { mode =>
      assertEquals(action(KeyInput("k", ctrl = true), mode), Some(ControlAction.Commands))
      assertEquals(action(KeyInput("K", meta = true), mode), Some(ControlAction.Commands))
    }
    assertEquals(action(KeyInput("k", ctrl = true, alt = true)), None)
    assertEquals(action(KeyInput("k", meta = true, shift = true)), None)
    assertEquals(action(KeyInput("k", ctrl = true, repeat = true)), None)
    assertEquals(action(KeyInput("k", ctrl = true, composing = true)), None)
    assertEquals(action(KeyInput("a", ctrl = true)), None)
  }

  test("Escape leaves INSERT, but native menu cancellation remains adapter-owned") {
    assertEquals(action(KeyInput("Escape"), InputMode.Insert), Some(ControlAction.Normal))
    assertEquals(action(KeyInput("Escape"), InputMode.Menu), None)
  }

  test("only plain Enter submits; Shift/newlines, modifiers and IME retain native behavior") {
    val enter = KeyInput("Enter")
    assert(KeyboardControls.shouldSubmit(enter))
    for input <- List(
        enter.copy(shift = true),
        enter.copy(ctrl = true),
        enter.copy(meta = true),
        enter.copy(alt = true),
        enter.copy(composing = true),
        KeyInput("a")
      )
    do assert(!KeyboardControls.shouldSubmit(input))
  }

  test("menu arrows and Tab share direction policy and permit held navigation keys") {
    assertEquals(KeyboardControls.menuDirection(KeyInput("ArrowDown", repeat = true)), Some(1))
    assertEquals(KeyboardControls.menuDirection(KeyInput("ArrowUp")), Some(-1))
    assertEquals(KeyboardControls.menuDirection(KeyInput("Tab")), Some(1))
    assertEquals(KeyboardControls.menuDirection(KeyInput("Tab", shift = true)), Some(-1))
    assertEquals(KeyboardControls.menuDirection(KeyInput("ArrowDown", ctrl = true)), None)
    assertEquals(KeyboardControls.menuDirection(KeyInput("ArrowUp", composing = true)), None)
  }

  test("search is case insensitive, supports multiple words and includes shortcuts") {
    val values = List(ControlAction.Chat, ControlAction.Agents, ControlAction.NewConversation)
    assertEquals(KeyboardControls.search(values, "   "), values)
    assertEquals(KeyboardControls.search(values, " AGENT configured "), List(ControlAction.Agents))
    assertEquals(KeyboardControls.search(values, "conversation new"), List(ControlAction.NewConversation))
    assertEquals(KeyboardControls.search(values, "1"), List(ControlAction.Chat))
    assertEquals(KeyboardControls.search(values, "missing"), Nil)
  }

  test("terminal bytes normalize to the same named keys and command catalog") {
    assertEquals(KeyInput.fromTerminal(27), KeyInput("Escape"))
    assertEquals(KeyInput.fromTerminal(32), KeyInput(" "))
    assertEquals(KeyInput.fromTerminal(13), KeyInput("Enter"))
    assertEquals(KeyInput.fromTerminal(10), KeyInput("Enter"))
    assertEquals(KeyInput.fromTerminal(-1), KeyInput(""))
    assertEquals(ControlAction.Normal.terminalKey, Some("\u001b"))
    assertEquals(ControlAction.Voice.terminalKey, Some("v"))
    assertEquals(ControlAction.Dictation.terminalKey, Some("m"))
    assertEquals(ControlAction.Reconnect.terminalKey, None)
  }
}
