package fmgp.yallmo.repl.grammar

import zio.LogLevel

/** A validated non-empty prompt submitted to the agent. */
opaque type UserPrompt = String

object UserPrompt {
  def from(value: String): Option[UserPrompt] = Option.when(value.nonEmpty)(value)

  extension (prompt: UserPrompt) def value: String = prompt
}

/** A destination for a debug snapshot, independent of a particular client implementation. */
enum DebugTarget {
  case Display
  case Export
  case ProviderRequest
}

/** An operation on durable storage-backed REPL sessions. */
enum SessionCommand {
  case List
  case Resume(id: String)
}

/** A typed command accepted by a REPL client. */
enum ReplCommand {
  case Help
  case Debug(target: DebugTarget)
  case History
  case Tools
  case Log(level: LogLevel)
  case NewSession(name: Option[String])
  case Session(command: SessionCommand)
  case Exit
}

/** A validated item submitted by a client to a REPL session. */
enum ReplInput {
  case Empty
  case Prompt(value: UserPrompt)
  case Command(value: ReplCommand)
}
