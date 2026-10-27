package fmgp.yallmo.view.web

import fmgp.yallmo.clientharness.ControlAction
import munit.FunSuite

final class WebKeyboardSuite extends FunSuite {
  private val commands = List(
    WebCommand(ControlAction.Chat, () => ()),
    WebCommand(ControlAction.Agents, () => ()),
    WebCommand(ControlAction.NewConversation, () => ())
  )

  test("command search is case insensitive, supports multiple words and includes shortcuts") {
    assertEquals(WebKeyboard.filter(commands, ""), commands)
    assertEquals(WebKeyboard.filter(commands, "   "), commands)
    assertEquals(WebKeyboard.filter(commands, " AGENT configured ").map(_.key), List("a"))
    assertEquals(WebKeyboard.filter(commands, "conversation new").map(_.key), List("n"))
    assertEquals(WebKeyboard.filter(commands, "1").map(_.label), List("Go to Chat"))
    assertEquals(WebKeyboard.filter(commands, "missing"), Nil)
  }

  test("web rendering uses web labels rather than compact terminal labels") {
    val command = WebCommand(ControlAction.Dictation, () => ())
    assertEquals(command.label, "Focus dictation / Stop recording")
    assertNotEquals(command.label, command.action.terminalLabel)
  }

  test("web command metadata comes directly from the shared catalog") {
    commands.foreach { command =>
      assertEquals(command.label, command.action.webLabel)
      assertEquals(command.key, command.action.shortcut.getOrElse(""))
    }
  }
}
