package fmgp.yallmo.repl

import fmgp.yallmo.repl.grammar.*
import zio.*

/** The next immutable session state and the events produced while reaching it. */
final case class ReplTransition(state: ReplState, events: Chunk[ReplEvent]) {
  def exitsClient: Boolean = events.contains(ReplEvent.ExitRequested)
}

/** Client-independent REPL application service.
  *
  * It owns command semantics and delegates agent work to [[ReplSession]]. Clients own input, presentation, local
  * history, and any filesystem export destination.
  */
final class ReplApplication[R](
    session: ReplSession[R],
    toolAvailability: ReplToolAvailability,
    replOrchestrator: ReplOrchestrator[R],
    val initialState: ReplState = ReplState()
) {
  def submit(state: ReplState, line: String): ZIO[R, Nothing, ReplTransition] =
    ZIO.scoped {
      ZIO.logLevelScoped(state.logLevel) *>
        ReplGrammar
          .parse(line)
          .fold(
            error => ZIO.succeed(ReplTransition(state, Chunk(ReplEvent.InputRejected(error)))),
            handle(state, _)
          )
    }

  def handle(state: ReplState, input: ReplInput): ZIO[R, Nothing, ReplTransition] =
    input match {
      case ReplInput.Empty          => ZIO.succeed(ReplTransition(state, Chunk.empty))
      case ReplInput.Prompt(prompt) =>
        session.submit(state, prompt.value).either.map {
          case Left(error) => ReplTransition(state, Chunk(ReplEvent.AgentFailed(error.getMessage)))
          case Right(turn) =>
            ReplTransition(turn.state, Chunk(ReplEvent.AgentTurn(turn.result, turn.newMessages)))
        }
      case ReplInput.Command(command) => handleCommand(state, command)
    }

  private def handleCommand(state: ReplState, command: ReplCommand): ZIO[R, Nothing, ReplTransition] =
    command match {
      case ReplCommand.Help => ZIO.succeed(ReplTransition(state, Chunk(ReplEvent.Help(ReplGrammar.help))))
      case ReplCommand.Debug(DebugTarget.ProviderRequest) =>
        ZIO.succeed(ReplTransition(state, Chunk(ReplEvent.ProviderRequest(state.lastProviderRequest))))
      case ReplCommand.Debug(target) =>
        ZIO.succeed(ReplTransition(state, Chunk(ReplEvent.DebugSnapshot(target, session.debugSnapshot(state)))))
      case ReplCommand.History =>
        ZIO.succeed(ReplTransition(state, Chunk(ReplEvent.Transcript(state.runContext.messages))))
      case ReplCommand.Tools => ZIO.succeed(ReplTransition(state, Chunk(ReplEvent.ToolAvailability(toolAvailability))))
      case ReplCommand.Log(level) =>
        ZIO.succeed(ReplTransition(state.copy(logLevel = level), Chunk(ReplEvent.LogLevelChanged(level))))
      case ReplCommand.NewSession(name) =>
        replOrchestrator.newReplSession(name).either.map {
          case Left(error) => ReplTransition(state, Chunk(ReplEvent.SessionFailed(error.toString)))
          case Right(next) =>
            ReplTransition(next.copy(logLevel = state.logLevel), Chunk(ReplEvent.SessionReset))
        }
      case ReplCommand.Session(SessionCommand.List) =>
        replOrchestrator.listReplSessionIds.either.map {
          case Left(error) => ReplTransition(state, Chunk(ReplEvent.SessionFailed(error.toString)))
          case Right(ids)  => ReplTransition(state, Chunk(ReplEvent.SessionList(ids, state.sessionId)))
        }
      case ReplCommand.Session(SessionCommand.Resume(id)) =>
        replOrchestrator.resumeReplSession(id).either.map {
          case Left(error)     => ReplTransition(state, Chunk(ReplEvent.SessionFailed(error.toString)))
          case Right(restored) =>
            val next = restored.copy(logLevel = state.logLevel)
            ReplTransition(next, Chunk(ReplEvent.SessionResumed(next.sessionId.getOrElse("main"))))
        }
      case ReplCommand.Exit => ZIO.succeed(ReplTransition(state, Chunk(ReplEvent.ExitRequested)))
    }
}
