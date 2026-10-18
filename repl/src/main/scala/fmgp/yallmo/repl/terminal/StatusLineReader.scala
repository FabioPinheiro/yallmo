package fmgp.yallmo.repl.terminal

import org.jline.reader.LineReader
import org.jline.reader.impl.LineReaderImpl
import org.jline.terminal.Terminal
import org.jline.utils.{AttributedString, Status}
import org.jline.utils.InfoCmp.Capability
import java.util.Map
import java.util.function.Supplier

/** Use JLine's own resize/redisplay path, without taking ownership of WINCH or readLine's handlers. */
private[terminal] final class StatusLineReader(terminal: Terminal, variables: Map[String, Object])
    extends LineReaderImpl(terminal, "yallmo", variables) {
  private var refreshStatus: Runnable = null
  private var lastColumns = -1
  private var lastRows = -1
  private var eraseOnYield = false
  private var postCursorHidden = false

  /** Bounded control reads preserve JLine's macro/pushback buffer as well as terminal input. */
  def readControl(timeoutMillis: Long): Int = {
    val next = bindingReader.peekCharacter(timeoutMillis)
    if next >= 0 then readCharacter() else next
  }

  /** JLine owns wrapping, cursor movement and removal on readLine cleanup. Terminal-thread only. */
  def showPost(content: Supplier[AttributedString]): Unit = {
    post = content
    setPostCursorHidden(true)
  }

  def clearPost(): Unit = {
    post = null
    setPostCursorHidden(false)
  }

  /** Return a menu action to the effect boundary without committing the transient chat prompt to scrollback. */
  def yieldWithoutNewline(): Unit = {
    eraseOnYield = true
    callWidget(LineReader.ACCEPT_LINE)
  }

  /** Use JLine's own erase/cleanup path for one yielded read only; ordinary submitted chat still gets a newline. */
  override protected def cleanup(): Unit = {
    val originalErase = isSet(LineReader.Option.ERASE_LINE_ON_FINISH)
    try {
      if eraseOnYield then setOpt(LineReader.Option.ERASE_LINE_ON_FINISH)
      super.cleanup()
    } finally {
      if eraseOnYield && !originalErase then unsetOpt(LineReader.Option.ERASE_LINE_ON_FINISH)
      eraseOnYield = false
      setPostCursorHidden(false)
    }
  }

  private def setPostCursorHidden(hidden: Boolean): Unit =
    if hidden != postCursorHidden then {
      if getTerminal.getStringCapability(Capability.cursor_invisible) != null &&
        getTerminal.getStringCapability(Capability.cursor_normal) != null
      then {
        getTerminal.puts(if hidden then Capability.cursor_invisible else Capability.cursor_normal)
        getTerminal.flush()
      }
      postCursorHidden = hidden
    }

  /** Leave room for the prompt/draft and the independent terminal footer. */
  def availablePostRows: Int = {
    val columns = getTerminal.getWidth.max(1)
    val promptRows = new AttributedString(prompt.toString + getBuffer.toString)
      .columnSplitLength(columns, true, false, getTerminal)
      .size()
    val footerRows = Option(Status.getStatus(getTerminal, false)).fold(0)(_.size())
    (getTerminal.getHeight - footerRows - promptRows - 1).max(0)
  }

  def onSizeChange(callback: Runnable): Unit = {
    refreshStatus = callback
    lastColumns = -1
    lastRows = -1
  }

  override protected def redisplay(flush: Boolean): Unit = {
    // Superclass construction may invoke virtual methods before this subclass is initialized.
    if refreshStatus != null then {
      val size = getTerminal.getSize
      if size.getColumns != lastColumns || size.getRows != lastRows then {
        lastColumns = size.getColumns
        lastRows = size.getRows
        refreshStatus.run()
      }
    }
    // JLine's empty-prompt cleanup has no cursor target and can leave it below the old post.
    // Explicitly return to the display origin so the resumed read reuses the same prompt row.
    if eraseOnYield && !isReading then display.update(java.util.List.of(AttributedString.EMPTY), 0, flush)
    else super.redisplay(flush)
  }
}
