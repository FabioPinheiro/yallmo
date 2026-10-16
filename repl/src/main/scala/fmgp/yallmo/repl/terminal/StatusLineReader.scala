package fmgp.yallmo.repl.terminal

import org.jline.reader.impl.LineReaderImpl
import org.jline.terminal.Terminal
import java.util.Map

/** Use JLine's own resize/redisplay path, without taking ownership of WINCH or readLine's handlers. */
private[terminal] final class StatusLineReader(terminal: Terminal, variables: Map[String, Object])
    extends LineReaderImpl(terminal, "yallmo", variables) {
  private var refreshStatus: Runnable = null
  private var lastColumns = -1
  private var lastRows = -1

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
    super.redisplay(flush)
  }
}
