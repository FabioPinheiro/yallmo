package fmgp.yallmo.repl.terminal

import org.jline.reader.{LineReader, Widget}
import org.jline.utils.Status
import fmgp.yallmo.repl.ReplActiveAgent
import zio.*

import java.util.List

/** Persistent, terminal-capability-dependent feedback for the line editor's local modes. */
private[terminal] final class TerminalStatus(reader: LineReader, activeAgent: Option[ReplActiveAgent] = None) {
  private val status = Option(Status.getStatus(reader.getTerminal))

  private var currentHint = "[INSERT]  Esc: command mode · Enter: submit"

  def install(): Unit = {
    reader match {
      case line: StatusLineReader => line.onSizeChange(() => refresh())
      case _                      => ()
    }
    installMissingViSubstituteWidget()
    installCallback(LineReader.CALLBACK_INIT)(updateForKeyMap())
    installCallback(LineReader.CALLBACK_KEYMAP)(updateForKeyMap())
  }

  def recording(): Unit = update("[RECORDING]  ● Space: stop · Esc: cancel · maximum 30 seconds")

  def transcribing(): Unit = update("[TRANSCRIBING]  Processing local voice input…")

  def updateForKeyMap(): Unit =
    if reader.getKeyMap == LineReader.VICMD then update("[COMMAND]  i: edit · v: voice input · q: exit")
    else update("[INSERT]  Esc: command mode · Enter: submit")

  def close: UIO[Unit] =
    ZIO.attemptBlocking {
      reader match {
        case line: StatusLineReader => line.onSizeChange(null)
        case _                      => ()
      }
      status.foreach(_.close())
    }.ignore

  /** JLine 4.0.14 binds `s` to vi-substitute but omits that widget. Restore the expected delete-and-insert behavior. */
  private def installMissingViSubstituteWidget(): Unit =
    if !reader.getWidgets.containsKey(LineReader.VI_SUBSTITUTE) then
      reader.getWidgets.put(
        LineReader.VI_SUBSTITUTE,
        new Widget {
          override def apply(): Boolean = {
            reader.getBuffer.delete()
            reader.setKeyMap(LineReader.VIINS)
            true
          }
        }
      )

  private def installCallback(name: String)(onCall: => Unit): Unit = {
    val existing = Option(reader.getWidgets.get(name))
    reader.getWidgets.put(
      name,
      new Widget {
        override def apply(): Boolean = {
          val result = existing.forall(_.apply())
          onCall
          result
        }
      }
    )
  }

  private def update(text: String): Unit = {
    currentHint = text
    refresh()
  }

  private def refresh(): Unit =
    status.foreach { bar =>
      bar.resize()
      bar.update(
        List.of(TerminalStatusLayout.render(currentHint, activeAgent, reader.getTerminal.getWidth, reader.getTerminal))
      )
    }
}
