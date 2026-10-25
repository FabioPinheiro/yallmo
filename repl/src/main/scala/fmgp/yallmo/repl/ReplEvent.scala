package fmgp.yallmo.repl

import fmgp.yallmo.server.application.{ToolAvailability as ApplicationToolAvailability}
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.model.{Message, MessagePayload}
import fmgp.yallmo.repl.debug.ReplDebugSnapshot
import fmgp.yallmo.repl.grammar.*
import java.nio.file.Path
import zio.*

/** A semantic result for a REPL client to render or otherwise handle. */
enum ReplEvent {
  case Help(entries: Chunk[CommandHelp])
  case DebugSnapshot(target: DebugTarget, snapshot: ReplDebugSnapshot)
  case ProviderRequest(capture: Option[Path])

  /** Stored conversation messages, including their original authors and optional session name. */
  case Transcript(messages: Chunk[Message], sessionId: Option[String])
  case SnapshotTranscript(
      messages: Chunk[fmgp.yallmo.server.protocol.YallmoProtocol.MessageSnapshot],
      sessionId: Option[String]
  )

  /** Actual durable suffix from remote execution, not a reconstructed local transcript. */
  case SnapshotDelta(messages: Chunk[fmgp.yallmo.server.protocol.YallmoProtocol.MessageSnapshot])
  case Unavailable(message: String)
  case ToolAvailability(value: ApplicationToolAvailability)
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
