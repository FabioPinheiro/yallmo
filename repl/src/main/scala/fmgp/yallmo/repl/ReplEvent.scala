package fmgp.yallmo.repl

import fmgp.yallmo.orchestration.*
import fmgp.yallmo.model.MessagePayload
import fmgp.yallmo.repl.debug.ReplDebugSnapshot
import fmgp.yallmo.repl.grammar.*
import java.nio.file.Path
import zio.*

/** A semantic result for a REPL client to render or otherwise handle. */
enum ReplEvent {
  case Help(entries: Chunk[CommandHelp])
  case DebugSnapshot(target: DebugTarget, snapshot: ReplDebugSnapshot)
  case ProviderRequest(capture: Option[Path])
  case Transcript(messages: Chunk[MessagePayload])
  case ToolAvailability(value: ReplToolAvailability)
  case LogLevelChanged(level: LogLevel)
  case SessionReset
  case SessionList(ids: Chunk[String], activeId: Option[String])
  case SessionResumed(id: String)
  case SessionFailed(message: String)
  case AgentTurn(result: AgentResult, newMessages: Chunk[MessagePayload])
  case InputRejected(error: ReplSyntaxError)
  case AgentFailed(message: String)
  case ExitRequested
}
