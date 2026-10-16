package fmgp.yallmo.repl.terminal

import fmgp.yallmo.repl.ReplActiveAgent
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import org.jline.reader.{LineReader, Widget}
import org.jline.terminal.{Size, Terminal}
import org.jline.terminal.impl.DumbTerminal
import org.jline.utils.{AttributedString, Status}
import zio.*

final class TerminalStatusSuite extends FunSuite {
  private val active = ReplActiveAgent("openai-codex", "openai-codex", "configured-model", None)
  private val metadata = "openai-codex (openai-codex - configured-model • default)"

  test("wide line preserves each local hint and right-aligns configured metadata") {
    for hint <- List("[INSERT] edit", "[COMMAND] i: edit", "[RECORDING] stop", "[TRANSCRIBING] wait") do {
      val result = TerminalStatusLayout.render(hint, Some(active), 120)
      assert(result.toString.startsWith(hint))
      assert(result.toString.endsWith(metadata))
      assertEquals(result.columnLength(), 120)
    }
  }

  test("narrow, zero and negative widths bound metadata before hints") {
    for width <- -2 to 90 do {
      val result = TerminalStatusLayout.render("[INSERT] edit", Some(active), width)
      assert(result.columnLength() <= width.max(0))
      if width > 0 then assertEquals(result.columnLength(), width)
      if width < metadata.length && width > 0 then assert(!result.toString.contains("[INSERT]"))
    }
    assertEquals(TerminalStatusLayout.render("[INSERT] edit", None, 80).toString, "[INSERT] edit")
  }

  test("Unicode display columns, combining marks, and ANSI/control sanitization") {
    val unsafe = "\u001b[31m界e\u0301\u001b[0m\n\r\t\u0007\u009b31m\u202e"
    val cleaned = TerminalStatusLayout.sanitize(unsafe)
    assert(cleaned.startsWith("界e\u0301"))
    assert(!cleaned.codePoints().anyMatch(c => Character.isISOControl(c) || Character.getType(c) == Character.FORMAT))
    val info = active.copy(agentId = unsafe, model = "模型🙂")
    for width <- 0 to 100 do {
      val result = TerminalStatusLayout.render("[INSERT] 界", Some(info), width)
      assert(result.columnLength() <= width)
      assert(!result.toString.contains('\u001b'))
      assert(!result.toString.contains('\n'))
      if width >= 80 then assertEquals(result.columnLength(), width)
    }
  }

  private def run(effect: UIO[Unit]): Unit =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def statusLine(terminal: Terminal): AttributedString = {
    val field = classOf[Status].getDeclaredField("lines")
    field.setAccessible(true)
    field.get(Status.getStatus(terminal)).asInstanceOf[java.util.List[AttributedString]].get(0)
  }

  test("JLine redisplay reflows after resize, retains mode metadata and callbacks, and detaches on close") {
    val terminal = new DumbTerminal(
      "test",
      "xterm",
      new ByteArrayInputStream("\n".getBytes(UTF_8)),
      new ByteArrayOutputStream(),
      UTF_8
    )
    terminal.setSize(new Size(120, 24))
    val reader = new StatusLineReader(terminal, new java.util.HashMap[String, Object]())
    var callbackCalls = 0
    reader.getWidgets.put(
      LineReader.CALLBACK_INIT,
      new Widget {
        def apply(): Boolean = { callbackCalls += 1; true }
      }
    )
    val originalHandler = terminal.handle(Terminal.Signal.WINCH, Terminal.SignalHandler.SIG_IGN)
    val status = new TerminalStatus(reader, Some(active))
    try {
      status.install()
      assertEquals(reader.readLine("test> "), "")
      assertEquals(callbackCalls, 1)
      assertEquals(statusLine(terminal).columnLength(terminal), 120)
      status.recording()
      terminal.setSize(new Size(90, 24))
      reader.redisplay()
      assertEquals(statusLine(terminal).columnLength(terminal), 90)
      assert(statusLine(terminal).toString.startsWith("[RECORDING]"))
      assert(statusLine(terminal).toString.endsWith(metadata))
      status.transcribing()
      assert(statusLine(terminal).toString.startsWith("[TRANSCRIBING]"))
      reader.setKeyMap(LineReader.VICMD)
      status.updateForKeyMap()
      assert(statusLine(terminal).toString.startsWith("[COMMAND]"))
      assert(statusLine(terminal).toString.endsWith(metadata))
      assert(reader.getWidgets.containsKey(LineReader.VI_SUBSTITUTE))
      assertEquals(terminal.handle(Terminal.Signal.WINCH, originalHandler), Terminal.SignalHandler.SIG_IGN)
      run(status.close)
      var refreshed = false
      reader.onSizeChange(() => refreshed = true)
      run(status.close)
      terminal.setSize(new Size(100, 24))
      reader.redisplay()
      assert(!refreshed)
    } finally { run(status.close); terminal.close() }
  }

  test("active readLine handles WINCH through JLine and preserves editor input") {
    val terminal = new DumbTerminal(
      "test",
      "xterm",
      new ByteArrayInputStream("x\n".getBytes(UTF_8)),
      new ByteArrayOutputStream(),
      UTF_8
    )
    terminal.setSize(new Size(120, 24))
    val reader = new StatusLineReader(terminal, new java.util.HashMap[String, Object]())
    val status = new TerminalStatus(reader, Some(active))
    var resizedLine: Option[AttributedString] = None
    reader.getWidgets.put(
      "test-resize",
      new Widget {
        def apply(): Boolean = {
          terminal.setSize(new Size(80, 30))
          terminal.raise(Terminal.Signal.WINCH)
          resizedLine = Some(statusLine(terminal))
          reader.getBuffer.write("draft")
          true
        }
      }
    )
    reader.getKeyMaps.get(LineReader.MAIN).bind(new org.jline.reader.Reference("test-resize"), "x")
    try {
      status.install()
      assertEquals(reader.readLine("test> "), "draft")
      assertEquals(resizedLine.get.columnLength(terminal), 80)
      assert(resizedLine.get.toString.endsWith(metadata))
      assert(resizedLine.get.toString.startsWith("[INSERT]"))
    } finally { run(status.close); terminal.close() }
  }

  test("dumb status emits no cursor controls or metadata") {
    val output = new ByteArrayOutputStream()
    val terminal = new DumbTerminal(new ByteArrayInputStream(Array.emptyByteArray), output)
    val reader = new StatusLineReader(terminal, new java.util.HashMap[String, Object]())
    val status = new TerminalStatus(reader, Some(active))
    try {
      status.install()
      status.recording()
      status.transcribing()
      run(status.close)
      assertEquals(output.toString(UTF_8), "")
    } finally terminal.close()
  }
}
