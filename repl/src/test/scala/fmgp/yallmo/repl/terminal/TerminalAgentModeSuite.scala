package fmgp.yallmo.repl.terminal

import fmgp.yallmo.model.AgentId
import fmgp.yallmo.repl.{ReplActiveAgent, ReplAgentChoice}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import org.jline.reader.{LineReader, Widget}
import org.jline.terminal.Size
import org.jline.terminal.impl.DumbTerminal

final class TerminalAgentModeSuite extends FunSuite {
  private val choices = Vector("alpha", "beta", "gamma").map(id =>
    ReplAgentChoice(AgentId(id), "OAIAgent", Vector(s"$id-provider"), s"$id-model")
  )
  private val active = ReplActiveAgent("alpha", "alpha-provider", "alpha-model", None)

  private def withMenu(input: String, entries: Vector[ReplAgentChoice] = choices, size: Size = new Size(160, 24))(
      check: (StatusLineReader, TerminalAgentMode, ByteArrayOutputStream) => Unit
  ): Unit = {
    val output = new ByteArrayOutputStream()
    val terminal = new DumbTerminal(
      "test",
      "xterm",
      new ByteArrayInputStream(("draft\u001bhla" + input).getBytes(UTF_8)),
      output,
      UTF_8
    )
    terminal.setSize(size)
    val reader = new StatusLineReader(terminal, new java.util.HashMap[String, Object]())
    reader.getKeyMaps.put(LineReader.MAIN, reader.getKeyMaps.get(LineReader.VIINS))
    val menu = new TerminalAgentMode(reader)
    new TerminalMenus(reader, Some(active), openAgents = Some(() => menu.open(entries, Some(active)))).install()
    menu.install()
    try check(reader, menu, output)
    finally { menu.close(); terminal.close() }
  }

  test("arrows select a configured agent without submitting the chat draft, and successful handoff restores it") {
    for (down, up) <- List(("\u001b[B", "\u001b[A"), ("\u001bOB", "\u001bOA"), ("j", "k")) do
      withMenu(down + down + up + "\niX\n") { (reader, menu, output) =>
        assertEquals(reader.readLine("test> "), "")
        assertEquals(menu.takeSelection(), Some(AgentId("beta")))
        assertEquals(reader.getHistory.size(), 0)
        menu.complete(Right(()))
        assertEquals(reader.readLine("test> "), "drafXt")
        assertEquals(reader.getHistory.size(), 1)
        val text = output.toString(UTF_8)
        assert(text.contains("alpha [OAIAgent] alpha-provider · alpha-model [active]"))
        assert(text.contains("beta [OAIAgent] beta-provider · beta-model"))
        assertEquals("AGENTS — selected:".r.findAllIn(text).size, 1)
        assert(!text.contains("Up/Down or j/k: select"))
        assert(!text.contains("fresh conversation"))
      }
  }

  test("Esc and i leave the picker without selecting or modifying the draft") {
    for leave <- List("\u001bi", "i") do
      withMenu("j" + leave + "X\n") { (reader, menu, _) =>
        assertEquals(reader.readLine("test> "), "drafXt")
        assertEquals(menu.takeSelection(), None)
      }
  }

  test("failed selection stays in the picker, displays its error and preserves the suspended draft") {
    withMenu("j\n\u001biX\n") { (reader, menu, output) =>
      assertEquals(reader.readLine("test> "), "")
      assertEquals(menu.takeSelection(), Some(AgentId("beta")))
      menu.complete(Left("Invalid model"))
      var restored: Option[(String, Int, String)] = None
      val original = reader.getWidgets.get(LineReader.CALLBACK_INIT)
      reader.getWidgets.put(
        LineReader.CALLBACK_INIT,
        new Widget {
          override def apply(): Boolean = {
            val result = original.apply()
            restored = Some((reader.getBuffer.toString, reader.getBuffer.cursor(), reader.getKeyMap))
            result
          }
        }
      )
      assertEquals(reader.readLine("test> "), "drafXt")
      assertEquals(restored, Some(("draft", 4, TerminalMenus.AgentsKeyMap)))
      assert(output.toString(UTF_8).contains("Selection failed: Invalid model"))
    }
  }

  test("long agent lists keep the highlighted entry visible and reflow after resize") {
    val entries =
      (0 until 30).map(i => ReplAgentChoice(AgentId(s"agent-$i"), "OAIAgent", Vector("local"), "界" * 30)).toVector
    withMenu(("\u001b[B" * 25) + "!\u001biX\n", entries, new Size(42, 10)) { (reader, menu, _) =>
      var resized = false
      reader.getWidgets.put(
        "test-resize",
        new Widget {
          override def apply(): Boolean = {
            reader.getTerminal.setSize(new Size(24, 8))
            reader.getTerminal.raise(org.jline.terminal.Terminal.Signal.WINCH)
            val displayed =
              reader.getDisplayedBufferWithPrompts(new java.util.ArrayList[org.jline.utils.AttributedString]())
            val rows = displayed.toString.linesIterator.toVector.drop(1)
            assert(rows.size <= reader.availablePostRows)
            assert(rows.exists(_.startsWith("> agent-25")))
            assert(rows.forall(new org.jline.utils.AttributedString(_).columnLength(reader.getTerminal) <= 24))
            resized = true
            true
          }
        }
      )
      reader.getKeyMaps.get(TerminalMenus.AgentsKeyMap).bind(new org.jline.reader.Reference("test-resize"), "!")
      assertEquals(reader.readLine("test> "), "drafXt")
      assertEquals(menu.takeSelection(), None)
      assert(resized)
    }
  }

  test("empty catalogs and pasted controls cannot select agents or submit prompts") {
    withMenu("\n\u001biX\n", Vector.empty) { (reader, menu, output) =>
      assertEquals(reader.readLine("test> "), "drafXt")
      assertEquals(menu.takeSelection(), None)
      assert(output.toString(UTF_8).contains("No configured agents"))
    }
    withMenu("\u001b[200~i\nq\n\u001b[201~\u001biX\n") { (reader, menu, _) =>
      assertEquals(reader.readLine("test> "), "drafXt")
      assertEquals(menu.takeSelection(), None)
    }
  }
}
