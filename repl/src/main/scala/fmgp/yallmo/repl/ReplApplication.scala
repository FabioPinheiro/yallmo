package fmgp.yallmo.repl

import fmgp.yallmo.repl.grammar.*
import fmgp.yallmo.model.{AgentId, YallmoError}
import fmgp.yallmo.orchestration.SessionId
import fmgp.yallmo.provider.Transcriber
import zio.*

/** The next immutable session state and the events produced while reaching it. */
final case class ReplTransition(state: ReplState, events: Chunk[ReplEvent]) {
  def exitsClient: Boolean = events.contains(ReplEvent.ExitRequested)
}

/** Display-only metadata from the startup configuration; selecting an entry validates and resolves its runtime lazily.
  */
final case class ReplAgentChoice(id: AgentId, agentType: String, providers: Vector[String], model: String)

/** Client-independent REPL application service.
  *
  * It owns command semantics and delegates agent work to [[ReplSession]]. Clients own input, presentation, local
  * history, and any filesystem export destination.
  *
  * @param userId
  *   configured human identity used for input labels
  */
final class ReplApplication[R](
    session: ReplSession[R],
    toolAvailability: ReplToolAvailability,
    replOrchestrator: ReplOrchestrator[R],
    val initialState: ReplState = ReplState(),
    val activeAgent: Option[ReplActiveAgent] = None,
    val providerCatalog: Option[ReplProviderCatalog] = None,
    val providerLogin: Option[ProviderLoginService] = None,
    val agentChoices: Vector[ReplAgentChoice] = Vector.empty,
    agentSelector: Option[AgentId => IO[YallmoError, ReplApplication[R]]] = None,
    val transcriber: Option[Transcriber] = None,
    val userId: AgentId = AgentId("user")
) {

  /** Resolves a new application sharing storage and runtime caches. The current session and conversation carry on
    * unchanged; failures leave the current application untouched.
    */
  def selectAgent(id: AgentId): IO[YallmoError, ReplApplication[R]] =
    agentSelector.fold[IO[YallmoError, ReplApplication[R]]](
      ZIO.fail(YallmoError.InvalidConfig("Agent selection is unavailable in this application"))
    )(_(id))

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
      case ReplCommand.Help =>
        ZIO.succeed(ReplTransition(state, Chunk(ReplEvent.Help(ReplGrammar.help()))))
      case ReplCommand.Debug(DebugTarget.ProviderRequest) =>
        ZIO.succeed(ReplTransition(state, Chunk(ReplEvent.ProviderRequest(state.lastProviderRequest))))
      case ReplCommand.Debug(target) =>
        ZIO.succeed(ReplTransition(state, Chunk(ReplEvent.DebugSnapshot(target, session.debugSnapshot(state)))))
      case ReplCommand.History =>
        state.sessionId match {
          case None     => ZIO.succeed(ReplTransition(state, Chunk(ReplEvent.Transcript(Chunk.empty, None))))
          case Some(id) =>
            ZIO
              .fromEither(SessionId.from(id).left.map(YallmoError.InvalidSessionId(id, _)))
              .flatMap(replOrchestrator.loadSession)
              .fold(
                error => ReplTransition(state, Chunk(ReplEvent.SessionFailed(error.toString))),
                stored => ReplTransition(state, Chunk(ReplEvent.Transcript(stored.messages, Some(stored.id.value))))
              )
        }
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
