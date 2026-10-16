package fmgp.yallmo.repl.terminal

import fmgp.yallmo.repl.ReplActiveAgent
import org.jline.terminal.Terminal
import org.jline.utils.AttributedString

/** A single display-column-bounded line; metadata wins over local hints when space is scarce. */
private[terminal] object TerminalStatusLayout {
  def sanitize(text: String): String = {
    val plain = AttributedString.stripAnsi(text)
    val result = new java.lang.StringBuilder
    plain.codePoints().forEach { code =>
      val kind = Character.getType(code)
      if !Character.isISOControl(code) && kind != Character.FORMAT &&
        kind != Character.LINE_SEPARATOR && kind != Character.PARAGRAPH_SEPARATOR
      then result.appendCodePoint(code)
    }
    result.toString
  }

  def render(left: String, active: Option[ReplActiveAgent], width: Int, terminal: Terminal = null): AttributedString = {
    val columns = width.max(0)
    def length(value: AttributedString): Int = value.columnLength(terminal)
    def truncate(text: String, available: Int): AttributedString = {
      val value = new AttributedString(sanitize(text))
      if length(value) <= available then value
      else if available <= 0 then AttributedString.EMPTY
      else new AttributedString(value.columnSubSequence(0, available - 1, terminal).toString + "…")
    }
    val hint = truncate(left, columns)
    active match {
      case None       => hint
      case Some(info) =>
        val metadata =
          s"${sanitize(info.agentId)} (${sanitize(info.provider)} - ${sanitize(info.model)} • ${info.thinkingLabel})"
        val right = truncate(metadata, columns)
        val room = columns - length(right)
        val local = truncate(left, (room - 1).max(0))
        new AttributedString(local.toString + (" " * (room - length(local))) + right.toString)
    }
  }
}
