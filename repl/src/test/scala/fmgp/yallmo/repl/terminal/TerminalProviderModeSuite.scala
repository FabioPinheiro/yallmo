package fmgp.yallmo.repl.terminal

import fmgp.yallmo.orchestration.config.ProviderId
import fmgp.yallmo.repl.ReplProviderCatalog.*
import fmgp.yallmo.repl.LoginMethod
import munit.FunSuite
import org.jline.reader.{EndOfFileException, LineReader, Reference, UserInterruptException, Widget}
import org.jline.terminal.Size
import org.jline.terminal.impl.DumbTerminal
import org.jline.utils.{AttributedString, Display}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintWriter}
import java.nio.charset.StandardCharsets.UTF_8

final class TerminalProviderModeSuite extends FunSuite {
  import TerminalProviderMenu.*

  private val local = Entry(
    ProviderId.unsafe("local"),
    "local",
    ProviderType.OAI,
    "https://example.com/v1",
    CredentialStatus.NotApplicable
  )
  private val account = Entry(
    ProviderId.unsafe("account"),
    "account",
    ProviderType.ChatGPT,
    "https://chatgpt.com/codex",
    CredentialStatus.Configured
  )

  private final class MenuOutput extends ByteArrayOutputStream {
    var committedLines = 0
  }

  private def withMenu(
      input: String,
      entries: Vector[Entry] = Vector.empty,
      size: Size = new Size(160, 24)
  )(
      check: (StatusLineReader, TerminalProviderMode, MenuOutput) => Unit
  ): Unit = {
    val output = new MenuOutput()
    val recorded = output
    val terminal = new DumbTerminal(
      "test",
      "xterm",
      new ByteArrayInputStream(("draft\u001bhp" + input).getBytes(UTF_8)),
      output,
      UTF_8
    ) {
      private val trackedWriter = new PrintWriter(super.writer()) {
        // JLine commits accepted lines via write(String); Display cursor movement writes individual characters.
        override def write(text: String): Unit = {
          if text == "\n" then recorded.committedLines += 1
          super.write(text)
        }
      }
      override def writer(): PrintWriter = trackedWriter
    }
    terminal.setSize(size)
    val variables = new java.util.HashMap[String, Object]()
    variables.put(LineReader.AMBIGUOUS_BINDING, java.lang.Long.valueOf(50L))
    val reader = new StatusLineReader(terminal, variables)
    reader.getKeyMaps.put(LineReader.MAIN, reader.getKeyMaps.get(LineReader.VIINS))
    val menu = new TerminalProviderMode(reader)
    menu.install()
    reader.getWidgets.put(
      "test-provider-open",
      new Widget {
        override def apply(): Boolean = { menu.open(); true }
      }
    )
    reader.getKeyMaps.get(LineReader.VICMD).bind(new Reference("test-provider-open"), "p")
    try {
      assertEquals(reader.readLine("test> "), "")
      assertEquals(reader.getHistory.size(), 0)
      assertEquals(menu.takeAction(), Some(Action.Catalog(Operation.Refresh)))
      menu.complete(Right(Reply.Listed(entries)))
      check(reader, menu, output)
    } finally { menu.close(); terminal.close() }
  }

  private def displayed(reader: StatusLineReader): String =
    reader.getDisplayedBufferWithPrompts(new java.util.ArrayList[AttributedString]()).toString

  private def afterWidget(reader: StatusLineReader, name: String)(check: => Unit): Unit = {
    val original = reader.getWidgets.get(name)
    reader.getWidgets.put(
      name,
      new Widget {
        def apply(): Boolean = {
          val result = original.apply()
          check
          result
        }
      }
    )
  }

  test("arrows update one managed menu region without appending full menu blocks to scrollback") {
    val arrows = ("\u001b[B\u001b[A" * 20) + "\u001b[B"
    withMenu(arrows + "\u001biX\n", Vector(account, local)) { (reader, menu, output) =>
      val selections = scala.collection.mutable.ListBuffer.empty[String]
      for direction <- List("up", "down") do
        afterWidget(reader, s"${TerminalProviderMode.KeyMapName}-$direction") {
          reader.redisplay()
          val live = displayed(reader)
          assertEquals("PROVIDER — configured providers".r.findAllIn(live).size, 1)
          selections += live.linesIterator.find(_.startsWith("> ")).get
        }
      assertEquals(reader.readLine("test> "), "draXft")
      assertEquals(selections.size, 41)
      assertEquals(selections.take(2).toList, List("> local [OAI]", "> account [ChatGPT]"))
      assertEquals(menu.takeAction(), None)
      assert(!displayed(reader).contains("PROVIDER"))
      val text = output.toString(UTF_8)
      assertEquals("PROVIDER — configured providers".r.findAllIn(text).size, 1)
      assertEquals("Catalog only:".r.findAllIn(text).size, 1)
      // A managed redisplay only updates the changed selection markers, not an append-only menu per key.
      assert(text.length < 6000, s"unexpected navigation output: ${text.length} bytes")
    }
  }

  test("provider actions erase the transient prompt without committing a newline; chat submission still commits") {
    withMenu(("\n\u001b" * 3) + "\u001biX\n", Vector(local)) { (reader, menu, output) =>
      val displayField = classOf[org.jline.reader.impl.LineReaderImpl].getDeclaredField("display")
      displayField.setAccessible(true)
      val cursorField = classOf[Display].getDeclaredField("cursorPos")
      cursorField.setAccessible(true)
      val originalErase = reader.isSet(LineReader.Option.ERASE_LINE_ON_FINISH)
      assertEquals(output.committedLines, 0, "opening PROVIDER must not commit a prompt")
      for _ <- 1 to 3 do {
        assertEquals(reader.readLine("test> "), "")
        assertEquals(output.committedLines, 0, "provider action must not commit a prompt")
        assertEquals(cursorField.getInt(displayField.get(reader)), 0, "resume at the same display origin")
        assertEquals(menu.takeAction(), Some(Action.Catalog(Operation.Inspect(local.id))))
        assertEquals(reader.getHistory.size(), 0)
        assertEquals(reader.isSet(LineReader.Option.ERASE_LINE_ON_FINISH), originalErase)
        menu.complete(Right(Reply.Loaded(Some(local))))
      }
      assertEquals(reader.readLine("test> "), "draXft")
      assertEquals(output.committedLines, 1, "ordinary chat must still commit its line")
      assertEquals(reader.getHistory.size(), 1)
    }
  }

  test("provider menu hides the chat cursor, then restores it when returning to NORMAL") {
    withMenu("\u001biX\n", Vector(local)) { (reader, _, output) =>
      var checkedMenu = false
      afterWidget(reader, LineReader.CALLBACK_INIT) {
        val text = output.toString(UTF_8)
        assert(text.lastIndexOf("\u001b[?25l") > text.lastIndexOf("\u001b[?25h"), "menu cursor must be hidden")
        checkedMenu = true
      }
      afterWidget(reader, s"${TerminalProviderMode.KeyMapName}-back") {
        val text = output.toString(UTF_8)
        assert(text.lastIndexOf("\u001b[?25h") > text.lastIndexOf("\u001b[?25l"), "chat cursor must be restored")
      }
      assertEquals(reader.readLine("test> "), "draXft")
      assert(checkedMenu)
    }
  }

  test("scoped login yields clear managed post, then reloads the same details with fixed result") {
    withMenu("\n" + "\u001b[B\nl" + "\u001b\u001biX\n", Vector(account, local)) { (reader, menu, _) =>
      assertEquals(reader.readLine("test> "), "")
      assertEquals(menu.takeAction(), Some(Action.Catalog(Operation.Inspect(account.id))))
      menu.complete(Right(Reply.Loaded(Some(account))))
      assertEquals(reader.readLine("test> "), "")
      assertEquals(menu.takeAction(), Some(Action.Login(account.id, LoginMethod.Browser)))
      assert(!displayed(reader).contains("PROVIDER"))
      menu.completeLogin(fmgp.yallmo.repl.ProviderLoginService.Result.Cancelled)
      assertEquals(menu.takeAction(), Some(Action.Catalog(Operation.Inspect(account.id))))
      menu.complete(Right(Reply.Loaded(Some(account))))
      assertEquals(reader.readLine("test> "), "")
      assertEquals(menu.takeAction(), Some(Action.Login(account.id, LoginMethod.Browser)))
      menu.completeLogin(fmgp.yallmo.repl.ProviderLoginService.Result.Saved)
      menu.takeAction()
      menu.complete(Right(Reply.Loaded(Some(account))))
      var screen = ""
      afterWidget(reader, LineReader.CALLBACK_INIT) { screen = displayed(reader) }
      assertEquals(reader.readLine("test> "), "draXft")
      assert(screen.contains("ID: account"))
      assert(screen.contains("Restart required"))
      assertEquals(reader.getHistory.size(), 1)
    }
  }

  test("final close removes a live menu without altering the restored draft") {
    withMenu("iX\n", Vector(account)) { (reader, menu, output) =>
      afterWidget(reader, LineReader.CALLBACK_INIT) {
        assert(displayed(reader).contains("PROVIDER"))
        menu.close()
        assert(!displayed(reader).contains("PROVIDER"))
        assertEquals(reader.getBuffer.toString -> reader.getBuffer.cursor(), "draft" -> 3)
        val text = output.toString(UTF_8)
        assert(text.lastIndexOf("\u001b[?25h") > text.lastIndexOf("\u001b[?25l"), "close must restore the cursor")
        reader.setKeyMap(LineReader.VICMD)
      }
      assertEquals(reader.readLine("test> "), "draXft")
      assertEquals(menu.takeAction(), None)
    }
  }

  test("EOF and read interruption clear the managed region without accepting form text") {
    for interrupt <- List(false, true) do
      withMenu("\n\nprivate-form" + (if interrupt then "!" else "")) { (reader, menu, output) =>
        if interrupt then {
          reader.getWidgets.put(
            "test-interrupt",
            new Widget {
              def apply(): Boolean = throw new UserInterruptException(reader.getBuffer.toString)
            }
          )
          reader.getKeyMaps.get(TerminalProviderMode.KeyMapName).bind(new Reference("test-interrupt"), "!")
        }
        if interrupt then intercept[UserInterruptException](reader.readLine("test> "))
        else intercept[EndOfFileException](reader.readLine("test> "))
        assert(!displayed(reader).contains("New provider"))
        assertEquals(menu.takeAction(), None)
        assertEquals(reader.getBuffer.toString, "draft")
        assertEquals(reader.getHistory.size(), 0)
        val text = output.toString(UTF_8)
        assert(
          text.lastIndexOf("\u001b[?25h") > text.lastIndexOf("\u001b[?25l"),
          "read cleanup must restore the cursor"
        )
        menu.close()
        assert(!displayed(reader).contains("PROVIDER"))
      }
  }

  test("wrapped viewport keeps selection visible on short terminals and reflows on WINCH") {
    val entries = (0 until 30).map { i =>
      local.copy(id = ProviderId.unsafe(s"entry-$i"), displayId = s"entry-$i-" + "界" * 30)
    }.toVector
    withMenu(("\u001b[B" * 25) + "!\u001biX\n", entries, new Size(42, 10)) { (reader, _, _) =>
      val selected = scala.collection.mutable.ListBuffer.empty[String]
      afterWidget(reader, s"${TerminalProviderMode.KeyMapName}-down") {
        reader.redisplay()
        val rows = displayed(reader).linesIterator.toVector.drop(1)
        assert(rows.size <= reader.availablePostRows)
        assert(rows.forall(new AttributedString(_).columnLength(reader.getTerminal) <= 42))
        selected += rows.find(_.startsWith("> ")).get
      }
      var resized = false
      reader.getWidgets.put(
        "test-resize",
        new Widget {
          def apply(): Boolean = {
            reader.getTerminal.setSize(new Size(24, 8))
            reader.getTerminal.raise(org.jline.terminal.Terminal.Signal.WINCH)
            val rows = displayed(reader).linesIterator.toVector.drop(1)
            assert(rows.size <= reader.availablePostRows)
            assert(rows.exists(_.startsWith("> entry-25-")))
            assert(rows.forall(new AttributedString(_).columnLength(reader.getTerminal) <= 24))
            resized = true
            true
          }
        }
      )
      reader.getKeyMaps.get(TerminalProviderMode.KeyMapName).bind(new Reference("test-resize"), "!")
      assertEquals(reader.readLine("test> "), "draXft")
      assertEquals(selected.size, 25)
      assert(selected.last.startsWith("> entry-25-"))
      assert(resized)
      assert(!displayed(reader).contains("PROVIDER"))
    }
  }

  test("CSI and terminal/SS3 arrows move selection; Enter emits inspection, never the suspended chat") {
    for (down, up) <- Vector(("\u001b[B", "\u001b[A"), ("\u001bOB", "\u001bOA")) do
      withMenu(down + up + down + "\n", Vector(account, local)) { (reader, menu, output) =>
        var restored: Option[(String, Int)] = None
        val init = reader.getWidgets.get(LineReader.CALLBACK_INIT)
        reader.getWidgets.put(
          LineReader.CALLBACK_INIT,
          new Widget {
            override def apply(): Boolean = {
              val result = init.apply()
              restored = Some(reader.getBuffer.toString -> reader.getBuffer.cursor())
              result
            }
          }
        )
        assertEquals(reader.readLine("test> "), "")
        assertEquals(restored, Some("draft" -> 3))
        assertEquals(menu.takeAction(), Some(Action.Catalog(Operation.Inspect(local.id))))
        assertEquals(reader.getHistory.size(), 0)
        assert(output.toString(UTF_8).contains("local [OAI]"))
      }
  }

  test("form editing, arrow type picker, backspace and explicit save use no REPL buffer or history") {
    withMenu("\n\u001b[B\nnew-acctX\u007f\n\n") { (reader, menu, output) =>
      val observedBuffers = scala.collection.mutable.ListBuffer.empty[(String, Int)]
      reader.getWidgets.put(
        LineReader.CALLBACK_KEYMAP,
        new Widget {
          override def apply(): Boolean = {
            observedBuffers += ((reader.getBuffer.toString, reader.getBuffer.cursor()))
            true
          }
        }
      )
      assertEquals(reader.readLine("test> "), "")
      assertEquals(menu.takeAction(), Some(Action.Catalog(Operation.Save("new-acct", Creation.ChatGPT))))
      assertEquals(reader.getHistory.size(), 0)
      assert(observedBuffers.nonEmpty)
      assert(observedBuffers.forall(_ == (("draft", 3))))
      assert(output.toString(UTF_8).contains("Credentials: logged out"))
    }
  }

  test("OAI URL input and review hide credentials; Escape back/cancel preserves exact chat text and cursor") {
    val url = "https://user:synthetic-password@example.com/v1?key=synthetic-query#synthetic-fragment"
    // Enter form, ID, endpoint, review; back through every page, leave menu, insert into original chat.
    withMenu("\n\nnew-id\n" + url + "\n\u001b\u001b\u001b\u001b\u001biX\n") { (reader, menu, output) =>
      assertEquals(reader.readLine("test> "), "draXft")
      assertEquals(menu.takeAction(), None)
      assertEquals(reader.getHistory.size(), 1)
      assertEquals(reader.getHistory.get(0), "draXft")
      val text = output.toString(UTF_8)
      assert(text.contains("https://example.com/v1"))
      assert(text.contains("Base URL: [hidden]"))
      for secret <- Vector("synthetic-password", "synthetic-query", "synthetic-fragment") do
        assert(!text.contains(secret))
    }
  }

  private def paste(text: String): String = "\u001b[200~" + text + "\u001b[201~"

  test("bracketed URL paste is field text without markers, echo, chat edits or history") {
    val url = "https://user:synthetic-password@example.com/v1?key=synthetic-query#synthetic-fragment"
    withMenu("\n\nnew-id\n" + paste(url) + "\n\n\u0003\u001biX\n") { (reader, menu, output) =>
      var pasteDisplay = ""
      afterWidget(reader, s"${TerminalProviderMode.KeyMapName}-paste") { pasteDisplay = displayed(reader) }
      assertEquals(reader.readLine("test> "), "")
      assert(pasteDisplay.contains("Base URL: [hidden]"))
      assertEquals(menu.takeAction(), Some(Action.Catalog(Operation.Save("new-id", Creation.OAI(url)))))
      assertEquals(reader.getHistory.size(), 0)
      menu.complete(Left(Error.SaveFailed))
      assertEquals(reader.readLine("test> "), "draXft")
      assertEquals(menu.takeAction(), None)
      assertEquals(reader.getHistory.size(), 1)
      assertEquals(reader.getHistory.get(0), "draXft")
      val text = output.toString(UTF_8)
      assert(text.contains("[hidden]"))
      assert(text.contains("https://example.com/v1"))
      for secret <- Vector("synthetic-password", "synthetic-query", "synthetic-fragment") do
        assert(!text.contains(secret))
    }
  }

  test("multiline bracketed ID paste cannot advance fields or save without confirmation") {
    withMenu("\n\n" + paste("my-id\nhttps://example.com\n\n") + "kept\nhttps://example.net\n\n") { (reader, menu, _) =>
      assertEquals(reader.readLine("test> "), "")
      assertEquals(menu.takeAction(), Some(Action.Catalog(Operation.Save("kept", Creation.OAI("https://example.net")))))
      assertEquals(reader.getHistory.size(), 0)
    }
  }

  test("bracketed paste in list and type picker cannot invoke shortcuts, navigation or login") {
    val browsePaste = Vector("l", "d", "r", "j", "k", "\n").map(paste).mkString
    val typePaste = Vector("j", "k", "\n").map(paste).mkString
    withMenu(browsePaste + "\n" + typePaste + "\nnew-id\nhttps://example.net\n\n") { (reader, menu, _) =>
      assertEquals(reader.readLine("test> "), "")
      assertEquals(
        menu.takeAction(),
        Some(Action.Catalog(Operation.Save("new-id", Creation.OAI("https://example.net"))))
      )
      assertEquals(reader.getHistory.size(), 0)
    }
  }

  test("bracketed paste in confirmation cannot save or change the selected action") {
    for (payload, suffix, expected) <- Vector(
        ("\n", "\u0003\u001biX\n", None),
        ("j", "\n", Some(Action.Catalog(Operation.Save("new-id", Creation.OAI("https://example.net")))))
      )
    do
      withMenu("\n\nnew-id\nhttps://example.net\n" + paste(payload) + suffix) { (reader, menu, _) =>
        assertEquals(reader.readLine("test> "), if expected.isEmpty then "draXft" else "")
        assertEquals(menu.takeAction(), expected)
        assertEquals(reader.getHistory.size(), if expected.isEmpty then 1 else 0)
      }
  }

  test("oversized and control-containing bracketed pastes are rejected atomically and fully drained") {
    val rejected = Vector(
      "x" * 50000 + "\nhttps://example.com\n\nl\u0003",
      "partial\tvalue",
      "partial\u0003\n\n",
      "partial\u001b[A\n\n",
      "partial\u001b[201X\n\n",
      "partial\u007fvalue",
      "partial\u2028value"
    )
    for payload <- rejected do
      withMenu("\n\npre" + paste(payload) + "kept\nhttps://example.net\n\n") { (reader, menu, _) =>
        assertEquals(reader.readLine("test> "), "")
        assertEquals(
          menu.takeAction(),
          Some(Action.Catalog(Operation.Save("prekept", Creation.OAI("https://example.net"))))
        )
        assertEquals(reader.getHistory.size(), 0)
      }
  }

  test("bracketed paste preserves the field limit including existing text") {
    for (initial, payload) <- Vector("" -> ("x" * 4096), "x" -> ("x" * 4095)) do
      withMenu("\n\n" + initial + paste(payload) + "\nhttps://example.net\n\n") { (reader, menu, _) =>
        assertEquals(reader.readLine("test> "), "")
        assertEquals(
          menu.takeAction(),
          Some(Action.Catalog(Operation.Save("x" * 4096, Creation.OAI("https://example.net"))))
        )
      }
    withMenu("\n\nprefix" + paste("x" * 4096) + "\nhttps://example.net\n\n") { (reader, menu, _) =>
      assertEquals(reader.readLine("test> "), "")
      assertEquals(
        menu.takeAction(),
        Some(Action.Catalog(Operation.Save("prefix", Creation.OAI("https://example.net"))))
      )
    }
  }

  test("EOF before the paste terminator discards all payload without actions or chat changes") {
    for payload <- Vector("partial\n\nl", "x" * 50000 + "\n\n", "partial\u001b[201") do
      withMenu("\n\n\u001b[200~" + payload) { (reader, menu, _) =>
        var afterPaste: Option[(String, Int)] = None
        val name = s"${TerminalProviderMode.KeyMapName}-paste"
        val original = reader.getWidgets.get(name)
        reader.getWidgets.put(
          name,
          new Widget {
            override def apply(): Boolean =
              try original.apply()
              finally afterPaste = Some(reader.getBuffer.toString -> reader.getBuffer.cursor())
          }
        )
        intercept[EndOfFileException](reader.readLine("test> "))
        assertEquals(menu.takeAction(), None)
        // JLine moves the cursor to the end during EOF cleanup; inspect it at the widget boundary instead.
        assertEquals(afterPaste, Some("draft" -> 3))
        assertEquals(reader.getBuffer.toString, "draft")
        assertEquals(reader.getHistory.size(), 0)
        assert(!displayed(reader).contains("New provider"))
      }
  }

  test("bracketed paste in ChatGPT details cannot invoke either scoped login or change selection") {
    withMenu("\n" + paste("ld\n\u001b[B") + "\n", Vector(account)) { (reader, menu, _) =>
      assertEquals(reader.readLine("test> "), "")
      menu.takeAction()
      menu.complete(Right(Reply.Loaded(Some(account))))
      assertEquals(reader.readLine("test> "), "")
      assertEquals(menu.takeAction(), Some(Action.Login(account.id, LoginMethod.Browser)))
      assertEquals(reader.getHistory.size(), 0)
    }
  }

  test("failed save retains editable form; success drains a refresh before resuming and restores chat") {
    withMenu("\n\u001b[B\nnew\n\n\n\u001biX\n") { (reader, menu, output) =>
      assertEquals(reader.readLine("test> "), "")
      assertEquals(menu.takeAction(), Some(Action.Catalog(Operation.Save("new", Creation.ChatGPT))))
      menu.complete(Left(Error.SaveFailed))
      assertEquals(reader.readLine("test> "), "")
      assertEquals(menu.takeAction(), Some(Action.Catalog(Operation.Save("new", Creation.ChatGPT))))
      val saved =
        account.copy(id = ProviderId.unsafe("new"), displayId = "new", credentials = CredentialStatus.LoggedOut)
      menu.complete(Right(Reply.Saved(saved)))
      assertEquals(menu.takeAction(), Some(Action.Catalog(Operation.Refresh)))
      menu.complete(Right(Reply.Listed(Vector(saved))))
      assertEquals(menu.takeAction(), None)
      assertEquals(reader.readLine("test> "), "draXft")
      assertEquals(reader.getHistory.size(), 1)
      val text = output.toString(UTF_8)
      assert(text.contains("Form retained"))
      assert(text.contains("new [ChatGPT]"))
    }
  }
}
