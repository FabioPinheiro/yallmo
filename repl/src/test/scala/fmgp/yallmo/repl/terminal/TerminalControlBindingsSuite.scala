package fmgp.yallmo.repl.terminal

import fmgp.yallmo.clientharness.ControlAction
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import org.jline.keymap.KeyMap
import org.jline.reader.{Binding, LineReader, Reference}
import org.jline.terminal.impl.DumbTerminal

final class TerminalControlBindingsSuite extends FunSuite {
  private def withReader(check: StatusLineReader => Unit): Unit = {
    val terminal = new DumbTerminal(
      "test",
      "xterm",
      new ByteArrayInputStream(Array.emptyByteArray),
      new ByteArrayOutputStream(),
      UTF_8
    )
    val reader = new StatusLineReader(terminal, new java.util.HashMap[String, Object]())
    try check(reader)
    finally terminal.close()
  }

  test("widget identity uses the stable action ID, scoped by keymap, never a display label") {
    withReader { reader =>
      val menu = TerminalMenus.AgentsKeyMap
      reader.getKeyMaps.put(menu, new KeyMap[Binding]())
      var normalEdits = 0
      var menuEdits = 0
      TerminalControlBindings.bind(reader, LineReader.VICMD, ControlAction.Edit) { normalEdits += 1 }
      TerminalControlBindings.bind(reader, menu, ControlAction.Edit) { menuEdits += 1 }
      val normalBinding = reader.getKeyMaps.get(LineReader.VICMD).getBound("i").asInstanceOf[Reference].name()
      val menuBinding = reader.getKeyMaps.get(menu).getBound("i").asInstanceOf[Reference].name()
      assertEquals(normalBinding, s"yallmo-${LineReader.VICMD}-edit")
      assertEquals(menuBinding, s"yallmo-$menu-edit")
      assertNotEquals(normalBinding, menuBinding)
      assert(!normalBinding.contains(ControlAction.Edit.webLabel))
      assert(!reader.getWidgets.containsKey("normal-insert"))
      assert(reader.getWidgets.get(normalBinding).apply())
      assertEquals((normalEdits, menuEdits), (1, 0))
      assert(reader.getWidgets.get(menuBinding).apply())
      assertEquals((normalEdits, menuEdits), (1, 1))
    }
  }

  test("native aliases bind to one catalog identity without changing the action shortcut") {
    withReader { reader =>
      val keys = List("j", "\u001b[B", "\u001bOB")
      var invoked = 0
      TerminalControlBindings.bindKeys(reader, LineReader.VICMD, ControlAction.Next, keys) { invoked += 1 }
      val names = keys.map(key => reader.getKeyMaps.get(LineReader.VICMD).getBound(key).asInstanceOf[Reference].name())
      assertEquals(names.distinct, List(s"yallmo-${LineReader.VICMD}-next"))
      keys.foreach { key =>
        val name = reader.getKeyMaps.get(LineReader.VICMD).getBound(key).asInstanceOf[Reference].name()
        assert(reader.getWidgets.get(name).apply())
      }
      assertEquals(invoked, 3)
      assertEquals(ControlAction.Next.shortcut, Some("j"))
    }
  }

  test("the local footer renders the catalog's terminal labels") {
    val hints = TerminalMenus.hintForKeyMap(LineReader.VICMD)
    List(
      ControlAction.Providers,
      ControlAction.Agents,
      ControlAction.Edit,
      ControlAction.Voice,
      ControlAction.Dictation,
      ControlAction.Speak,
      ControlAction.Exit
    ).foreach(control => assert(hints.contains(control.terminalHint)))
    assert(!hints.contains(ControlAction.Dictation.webLabel))
  }
}
