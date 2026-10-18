package fmgp.yallmo.repl.terminal

import fmgp.yallmo.model.AgentId

/** Terminal-only labels. Identity text is sanitized without changing stored IDs or conversation payloads. */
private[terminal] object TerminalPrompt {
  private def name(value: String): String = {
    val cleaned = TerminalStatusLayout.sanitize(value)
    if cleaned.trim.nonEmpty then cleaned else "unknown"
  }

  def user(id: AgentId, harness: String = "yallmo"): String =
    s"${name(harness)}(${name(AgentId.value(id))})> "

  def assistant(id: AgentId): String = s"assistant(${name(AgentId.value(id))})> "
}
