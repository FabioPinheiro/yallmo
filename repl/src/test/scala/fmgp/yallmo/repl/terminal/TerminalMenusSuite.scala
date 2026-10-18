package fmgp.yallmo.repl.terminal

import fmgp.yallmo.repl.ReplActiveAgent
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import org.jline.reader.{LineReader, Widget}
import org.jline.terminal.Size
import org.jline.terminal.impl.DumbTerminal

final class TerminalMenusSuite extends FunSuite {
  private def withMenus(
      input: String
  )(check: (StatusLineReader, ByteArrayOutputStream) => Unit): Unit = {
    val output = new ByteArrayOutputStream()
    val terminal = new DumbTerminal("test", "xterm", new ByteArrayInputStream(input.getBytes(UTF_8)), output, UTF_8)
    terminal.setSize(new Size(160, 24))
    val variables = new java.util.HashMap[String, Object]()
    variables.put(LineReader.AMBIGUOUS_BINDING, java.lang.Long.valueOf(50L))
    val reader = new StatusLineReader(terminal, variables)
    reader.getKeyMaps.put(LineReader.MAIN, reader.getKeyMaps.get(LineReader.VIINS))
    val menus = new TerminalMenus(reader, Some(ReplActiveAgent("test-agent", "local", "test-model", None)))
    menus.install()
    try check(reader, output)
    finally terminal.close()
  }

  test("NORMAL p/a enter discoverable menus; Esc returns NORMAL without changing the draft or cursor") {
    withMenus("draft\u001bhp\u001ba\u001biX\n") { (reader, output) =>
      val transitions = scala.collection.mutable.ListBuffer.empty[(String, String, Int)]
      reader.getWidgets.put(
        LineReader.CALLBACK_KEYMAP,
        new Widget {
          override def apply(): Boolean = {
            transitions += ((reader.getKeyMap, reader.getBuffer.toString, reader.getBuffer.cursor()))
            true
          }
        }
      )
      assertEquals(reader.readLine("test> "), "draXft")
      val navigation = transitions
        .filter(t => Set(LineReader.VICMD, TerminalMenus.ProviderKeyMap, TerminalMenus.AgentsKeyMap).contains(t._1))
        .toList
      assertEquals(
        navigation.map(_._1),
        List(
          LineReader.VICMD,
          TerminalMenus.ProviderKeyMap,
          LineReader.VICMD,
          TerminalMenus.AgentsKeyMap,
          LineReader.VICMD
        )
      )
      assertEquals(navigation.tail.map(t => (t._2, t._3)), List.fill(4)(("draft", 3)))
      assert(output.toString(UTF_8).contains("catalog unavailable"))
      assert(output.toString(UTF_8).contains("selected: test-agent"))
      assert(output.toString(UTF_8).contains("No configured agents"))
    }
  }

  test("repeated Esc in NORMAL is a no-op and one i immediately resumes editing") {
    for escapes <- 2 to 4 do
      withMenus("draft\u001bhl" + ("\u001b" * escapes) + "iX\n") { (reader, _) =>
        assertEquals(reader.readLine("test> "), "drafXt")
      }
  }

  test("only i enters INSERT; other Vi editing keys and unrecognized characters leave the draft unchanged") {
    for key <- List("s", "S", "I", "A", "R", "c", "C", "o", "O", "d", "D", "x", "X", "r", "f", "t", "9", "z", "界", "🙂")
    do
      withMenus(s"draft\u001bhl${key}i!\n") { (reader, _) =>
        assertEquals(reader.readLine("test> "), "draf!t", s"NORMAL key: $key")
      }
  }

  test("Esc in NORMAL preserves mode, draft and cursor without swallowing the following key") {
    withMenus("draft\u001bhl~\u001b~\u001b\u001b~iX\n") { (reader, _) =>
      val snapshots = scala.collection.mutable.ListBuffer.empty[(String, String, Int)]
      reader.getWidgets.put(
        "test-snapshot",
        new Widget {
          override def apply(): Boolean = {
            snapshots += ((reader.getKeyMap, reader.getBuffer.toString, reader.getBuffer.cursor()))
            true
          }
        }
      )
      reader.getKeyMaps.get(LineReader.VICMD).bind(new org.jline.reader.Reference("test-snapshot"), "~")
      assertEquals(reader.readLine("test> "), "drafXt")
      assertEquals(snapshots.toList, List.fill(3)((LineReader.VICMD, "draft", 4)))
    }
  }

  test("p/a/l/d remain ordinary INSERT text") {
    withMenus("pald\n") { (reader, _) =>
      assertEquals(reader.readLine("test> "), "pald")
    }
  }

  test("i from either menu returns directly to editing; Enter in menus does not submit the draft") {
    for key <- List("p", "a") do
      withMenus(s"draft\u001b$key\niX\n") { (reader, _) =>
        assertEquals(reader.readLine("test> "), "drafXt")
      }
  }

  test("unavailable provider menu cannot dispatch global login or alter the draft and history") {
    withMenus("draft\u001bhpld\n\u001baiX\n") { (reader, output) =>
      assertEquals(reader.readLine("test> "), "draXft")
      assertEquals(reader.getHistory.size(), 1)
      assertEquals(reader.getHistory.get(0), "draXft")
      assert(!output.toString(UTF_8).contains("login"))
    }
  }

  test("hints advertise scoped ChatGPT login without legacy list actions") {
    val normal = TerminalMenus.hintForKeyMap(LineReader.VICMD)
    assert(normal.startsWith("[NORMAL]"))
    for key <- List("p: provider", "a: agents", "i: edit", "v: voice input", "q: exit") do assert(normal.contains(key))
    val provider = TerminalMenus.hintForKeyMap(TerminalMenus.ProviderKeyMap)
    assert(provider.startsWith("[PROVIDER]"))
    assert(provider.contains("Catalog unavailable"))
    assert(!provider.contains("login"))
    val catalog = TerminalMenus.hintForKeyMap(TerminalProviderMode.KeyMapName)
    assert(catalog.contains("ChatGPT details: login"))
    assert(!catalog.contains("legacy"))
    assert(!catalog.contains("l/d"))
    val agents = TerminalMenus.hintForKeyMap(TerminalMenus.AgentsKeyMap)
    assert(agents.startsWith("[AGENTS]"))
    for hint <- List(provider, agents) do {
      assert(hint.contains("Esc: NORMAL"))
      assert(hint.contains("i: edit"))
    }
    assertEquals(TerminalMenus.hintForKeyMap(LineReader.VIINS), "[INSERT]  Esc: NORMAL · Enter: submit")
  }
}
