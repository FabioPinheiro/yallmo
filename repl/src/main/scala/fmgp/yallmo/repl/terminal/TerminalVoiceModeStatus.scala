package fmgp.yallmo.repl.terminal

import org.jline.reader.{LineReader, Widget}
import org.jline.utils.Status
import fmgp.yallmo.repl.ReplActiveAgent
import zio.*

import java.util.List

/** Persistent, terminal-capability-dependent feedback for the line editor's local modes. */
private[terminal] final class TerminalVoiceModeStatus(
    reader: LineReader,
    private var activeAgent: Option[ReplActiveAgent] = None
) extends TerminalMode {
  private val status = Option(Status.getStatus(reader.getTerminal))

  private var currentHint = TerminalMenus.hintForKeyMap(LineReader.VIINS)

  override def install(): Unit = {
    reader match {
      case line: StatusLineReader => line.onSizeChange(() => refresh())
      case _                      => ()
    }
    installCallback(LineReader.CALLBACK_INIT)(updateForKeyMap())
    installCallback(LineReader.CALLBACK_KEYMAP)(updateForKeyMap())
  }

  def selectAgent(value: Option[ReplActiveAgent]): Unit = {
    activeAgent = value
    refresh()
  }

  def recording(): Unit = update("[RECORDING]  ● Space: stop · Esc: cancel · maximum 30 seconds")

  def transcribing(): Unit = update("[TRANSCRIBING]  Processing local voice input…")

  def updateForKeyMap(): Unit =
    update(TerminalMenus.hintForKeyMap(reader.getKeyMap))

  def close: UIO[Unit] =
    ZIO.attemptBlocking {
      reader match {
        case line: StatusLineReader => line.onSizeChange(null)
        case _                      => ()
      }
      status.foreach(_.close())
    }.ignore

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
