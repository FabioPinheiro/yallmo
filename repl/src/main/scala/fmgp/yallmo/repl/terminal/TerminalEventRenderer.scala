package fmgp.yallmo.repl.terminal

import fmgp.yallmo.model.MessagePayload
import fmgp.yallmo.repl.*
import fmgp.yallmo.repl.debug.ReplDebug
import fmgp.yallmo.repl.grammar.DebugTarget
import zio.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** Renders semantic REPL events for terminal clients without performing input or session control. */
private[terminal] object TerminalEventRenderer {
  def render(event: ReplEvent): UIO[Unit] =
    event match {
      case ReplEvent.Help(entries) =>
        val lines = entries.map(entry => f"  ${entry.usage}%-26s ${entry.description}")
        printLines(Chunk("Commands:") ++ lines ++ Chunk("Use // to send a prompt beginning with /."))
      case ReplEvent.DebugSnapshot(DebugTarget.Display, snapshot)  => printDebugSnapshot(snapshot)
      case ReplEvent.DebugSnapshot(DebugTarget.Export, snapshot)   => writeDebugLog(snapshot)
      case ReplEvent.DebugSnapshot(DebugTarget.ProviderRequest, _) => printProviderRequest(None)
      case ReplEvent.ProviderRequest(capture)                      => printProviderRequest(capture)
      case ReplEvent.Transcript(messages)    => printLines(TerminalMessageRenderer.render(messages))
      case ReplEvent.ToolAvailability(value) =>
        printLines(
          value.available.map(key => s"available[$key]") ++
            value.unavailable.map { case (key, reasons) => s"unavailable[$key]: ${reasons.mkString("; ")}" }
        )
      case ReplEvent.LogLevelChanged(level)     => Console.printLine(s"Log level set to $level.").orDie
      case ReplEvent.SessionReset               => Console.printLine("Session reset.").orDie
      case ReplEvent.SessionList(ids, activeId) =>
        if ids.isEmpty then Console.printLine("No durable sessions.").orDie
        else printLines(ids.map(id => s"${if activeId.contains(id) then "*" else " "} $id"))
      case ReplEvent.SessionResumed(id)             => Console.printLine(s"Resumed session $id.").orDie
      case ReplEvent.SessionFailed(message)         => Console.printLineError(s"Session command failed: $message").orDie
      case ReplEvent.AgentTurn(result, newMessages) =>
        for {
          _ <- printLines(TerminalMessageRenderer.render(newMessages.collect {
            case message @ MessagePayload.RagResult(_, _)  => message
            case message @ MessagePayload.ToolResult(_, _) => message
          }))
          _ <- Console.printLine(s"assistant> ${result.text}").orDie
        } yield ()
      case ReplEvent.InputRejected(error) => {
        val suggestions =
          if error.suggestions.isEmpty then Chunk.empty
          else Chunk(s"Try: ${error.suggestions.map(_.value).mkString(", ")}")
        printLines(Chunk(s"Command error: ${error.message}") ++ suggestions)
      }
      case ReplEvent.AgentFailed(message) => Console.printLineError(s"Agent failed: $message").orDie
      case ReplEvent.ExitRequested        => Console.printLine("Bye.").orDie
    }

  private def printDebugSnapshot(snapshot: fmgp.yallmo.repl.debug.ReplDebugSnapshot): UIO[Unit] = {
    val rendered = ReplDebug.renderJsonLines(snapshot.messages)
    if rendered.isEmpty then Console.printLine("").orDie else Console.print(rendered).orDie
  }

  private def writeDebugLog(snapshot: fmgp.yallmo.repl.debug.ReplDebugSnapshot): UIO[Unit] =
    LocalDebugExport
      .write(snapshot)
      .foldZIO(
        error => Console.printLineError(s"Could not write debug log: ${error.getMessage}").orDie,
        path => Console.printLine(s"Debug log written to $path").orDie
      )

  private def printProviderRequest(capture: Option[java.nio.file.Path]): UIO[Unit] =
    capture.fold(Console.printLine("No provider request has been captured in this session.").orDie) { path =>
      ZIO
        .attemptBlocking(Files.readString(path, UTF_8))
        .foldZIO(
          error => Console.printLineError(s"Could not read provider request $path: ${error.getMessage}").orDie,
          payload => Console.printLine(s"Provider request: $path").orDie *> Console.printLine(payload).orDie
        )
    }

  private def printLines(lines: Chunk[String]): UIO[Unit] =
    ZIO.foreachDiscard(lines)(line => Console.printLine(line).orDie)
}
