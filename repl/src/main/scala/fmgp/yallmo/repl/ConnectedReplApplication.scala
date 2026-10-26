package fmgp.yallmo.repl

import fmgp.yallmo.model.{AgentId, YallmoError}
import fmgp.yallmo.orchestration.{AgentTask, SessionId}
import fmgp.yallmo.repl.grammar.*
import fmgp.yallmo.server.OrchestratorClient
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import zio.*

/** One supplied connection, with negotiated submission and no local execution or provider state. */
final class ConnectedReplApplication private (
    client: OrchestratorClient,
    val userId: AgentId,
    override val selectedAgentId: Option[AgentId],
    val agentChoices: Vector[ReplAgentChoice],
    override val canSubmit: Boolean,
    override val canReadRequests: Boolean,
    logLevel: LogLevel
) extends ReplApplicationApi[Any] {
  val initialState = ReplState(logLevel = logLevel)
  val activeAgent = None
  val providerCatalog = None
  val providerLogin = None
  val transcriber = None
  override val remoteConnection = true

  def notice: String = if canSubmit then ConnectedReplApplication.OperatorNotice else ConnectedReplApplication.Notice

  def selectAgent(id: AgentId): IO[YallmoError, ConnectedReplApplication] =
    ConnectedReplApplication.make(client, userId, Some(id), initialState.logLevel)

  private def withSnapshot(state: ReplState, stored: SessionSnapshot): ReplState =
    state.copy(sessionId = Some(stored.id.value), remoteHeadMessageId = stored.messages.lastOption.map(_.id))

  private def remoteFailure(
      state: ReplState,
      error: OrchestratorClient.Error,
      creating: Boolean = false
  ): ReplTransition = {
    val message = error match {
      case OrchestratorClient.Error.Remote(error) if error.code == ErrorCode.Busy =>
        "Session is busy; no prompt accepted. Refresh /history before manually submitting again."
      case OrchestratorClient.Error.Remote(error) if error.code == ErrorCode.Conflict =>
        "Session head or identity conflict; refresh /history before manually submitting again."
      case OrchestratorClient.Error.Remote(error)
          if Set(ErrorCode.Unauthorized, ErrorCode.Forbidden, ErrorCode.NotFound, ErrorCode.Rejected, -32601, -32602)
            .contains(error.code) =>
        "Remote request rejected. Check session, configured agent and negotiated permissions; nothing was retried."
      case _ if creating =>
        "Remote create outcome is unknown. Inspect /session list before manually creating or submitting again; no automatic retry."
      case _ => ConnectedReplApplication.Uncertain
    }
    ReplTransition(state, Chunk(ReplEvent.AgentFailed(message)))
  }

  private def create(state: ReplState, name: Option[String]): UIO[ReplTransition] =
    ZIO
      .fromEither(name match {
        case None        => Right(None)
        case Some(value) => SessionId.from(value).map(Some(_))
      })
      .foldZIO(
        _ => ZIO.succeed(ReplTransition(state, Chunk(ReplEvent.SessionFailed("Invalid session ID.")))),
        id =>
          client
            .createSession(id)
            .fold(
              error => remoteFailure(state, error, creating = true),
              stored =>
                ReplTransition(
                  withSnapshot(state, stored).copy(remoteResetContinuation = false),
                  Chunk(ReplEvent.SessionReset)
                )
            )
      )

  private def prompt(state: ReplState, text: String): UIO[ReplTransition] = selectedAgentId match {
    case None =>
      ZIO.succeed(
        ReplTransition(
          state,
          Chunk(
            ReplEvent.AgentFailed(
              "No configured agent selected. Choose --agent <id> or press Esc, a before submitting a prompt."
            )
          )
        )
      )
    case Some(agent) =>
      // Retain create's returned ID before the single submit attempt, including all failure outcomes.
      val prepared = if state.sessionId.nonEmpty then ZIO.succeed(ReplTransition(state, Chunk.empty))
      else create(state, None)
      prepared.flatMap { created =>
        if created.state.sessionId.isEmpty then ZIO.succeed(created)
        else
          ZIO
            .fromEither(SessionId.from(created.state.sessionId.get))
            .foldZIO(
              _ => ZIO.succeed(ReplTransition(created.state, Chunk(ReplEvent.SessionFailed("Invalid session ID.")))),
              id =>
                client
                  .submitTurn(
                    id,
                    agent,
                    AgentTask(text),
                    created.state.remoteHeadMessageId,
                    created.state.remoteResetContinuation
                  )
                  .fold(
                    error => remoteFailure(created.state, error),
                    {
                      case TurnOutcome.Succeeded(stored, _, messages) =>
                        ReplTransition(
                          withSnapshot(created.state, stored).copy(remoteResetContinuation = false),
                          Chunk(ReplEvent.SnapshotDelta(messages))
                        )
                      case TurnOutcome.Failed(acceptance, stored, _) =>
                        val retained = stored.fold(created.state)(withSnapshot(created.state, _))
                        val message = acceptance match {
                          case PromptAcceptance.Accepted =>
                            "Remote execution failed after prompt acceptance; the prompt remains in history."
                          case PromptAcceptance.NotAccepted => "Remote execution failed; the prompt was not accepted."
                          case PromptAcceptance.Unknown     => ConnectedReplApplication.Uncertain
                        }
                        ReplTransition(
                          retained.copy(remoteResetContinuation = if acceptance == PromptAcceptance.Accepted then false
                          else retained.remoteResetContinuation),
                          stored.fold(Chunk.empty[ReplEvent])(s =>
                            Chunk(ReplEvent.SnapshotTranscript(s.messages, Some(s.id.value)))
                          ) :+
                            ReplEvent.AgentFailed(message)
                        )
                    }
                  )
            )
      }
  }

  def submit(state: ReplState, line: String): UIO[ReplTransition] = {
    def emit(event: ReplEvent) = ZIO.succeed(ReplTransition(state, Chunk(event)))
    def unsupported = emit(ReplEvent.Unavailable(if canSubmit then ConnectedReplApplication.OperatorUnsupported
    else ConnectedReplApplication.Unsupported))
    def requestCapture: UIO[ReplTransition] =
      state.sessionId match {
        case None     => emit(ReplEvent.ProviderRequestDocument(None))
        case Some(id) =>
          ZIO
            .fromEither(SessionId.from(id))
            .mapError(_ => "Invalid session ID.")
            .flatMap(sessionId =>
              client.providerRequest(sessionId).mapError {
                case OrchestratorClient.Error.Remote(error) if error.code == ErrorCode.Rejected =>
                  "Provider request capture is unavailable or exceeds the server diagnostic limit."
                case _ => "Could not read provider request from server. Check the connection and diagnostic permission."
              }
            )
            .fold(
              message => ReplTransition(state, Chunk(ReplEvent.Unavailable(message))),
              result => ReplTransition(state, Chunk(ReplEvent.ProviderRequestDocument(result.providerRequest)))
            )
      }
    def snapshot(id: String, resume: Boolean): UIO[ReplTransition] =
      ZIO
        .fromEither(SessionId.from(id))
        .mapError(_ => "Invalid session ID.")
        .flatMap(sessionId => client.loadSession(sessionId).mapError(_ => "Remote session read failed."))
        .fold(
          error => ReplTransition(state, Chunk(ReplEvent.SessionFailed(error))),
          stored =>
            ReplTransition(
              withSnapshot(state, stored).copy(remoteResetContinuation = resume || state.remoteResetContinuation),
              Chunk(ReplEvent.SnapshotTranscript(stored.messages, Some(stored.id.value)))
            )
        )
    ZIO.scoped {
      ZIO.logLevelScoped(state.logLevel) *> (ReplGrammar.parse(line) match {
        case Left(error)                       => emit(ReplEvent.InputRejected(error.copy(suggestions = Chunk.empty)))
        case Right(ReplInput.Empty)            => ZIO.succeed(ReplTransition(state, Chunk.empty))
        case Right(ReplInput.Command(command)) =>
          command match {
            case ReplCommand.Help => emit(ReplEvent.Help(ConnectedReplApplication.help(canSubmit, canReadRequests)))
            case ReplCommand.Exit => emit(ReplEvent.ExitRequested)
            case ReplCommand.Debug(DebugTarget.ProviderRequest) if canReadRequests => requestCapture
            case ReplCommand.Log(level)                                            =>
              ZIO.succeed(ReplTransition(state.copy(logLevel = level), Chunk(ReplEvent.LogLevelChanged(level))))
            case ReplCommand.Session(SessionCommand.List) =>
              client.listSessions.fold(
                _ => ReplTransition(state, Chunk(ReplEvent.SessionFailed("Remote session list failed."))),
                sessions =>
                  ReplTransition(state, Chunk(ReplEvent.SessionList(sessions.map(_.id.value), state.sessionId)))
              )
            case ReplCommand.Session(SessionCommand.Resume(id)) => snapshot(id, resume = true)
            case ReplCommand.History                            =>
              state.sessionId.fold(emit(ReplEvent.SnapshotTranscript(Chunk.empty, None)))(snapshot(_, resume = false))
            case ReplCommand.NewSession(name) if canSubmit => create(state, name)
            case _                                         => unsupported
          }
        case Right(ReplInput.Prompt(value)) if canSubmit => prompt(state, value.value)
        case Right(ReplInput.Prompt(_))                  => unsupported
      })
    }
  }
}

object ConnectedReplApplication {
  val Notice =
    "Connected READ-ONLY: session list/resume/history and agent preference only. User name is unauthenticated metadata. No local providers, login or voice."
  val OperatorNotice =
    "Connected trusted local, unauthenticated: prompts and /new execute on the configured server. Claimed user names are impersonable; never expose publicly. No local providers, login or voice."
  val Unsupported =
    "Unavailable in connected read-only mode: prompts, /new, /tools, /debug, providers/login and voice/TTS."
  val OperatorUnsupported =
    "Unavailable remotely: /tools, /debug print|export, providers/login and voice/TTS. /debug request requires server diagnostic permission."
  val Uncertain =
    "Remote outcome is unknown; work may still complete. Refresh /history (or reconnect and resume the known session) before any manual resend. No automatic retry or cancellation."
  private[repl] val Commands = Set("/help", "/history", "/session", "/log", "/exit")
  private[repl] def commands(canSubmit: Boolean, canReadRequests: Boolean = false): Set[String] =
    Commands ++ Option.when(canSubmit)("/new") ++ Option.when(canReadRequests)("/debug")
  def help(canSubmit: Boolean = false, canReadRequests: Boolean = false): Chunk[CommandHelp] =
    ReplGrammar
      .help()
      .filter(entry => commands(canSubmit, canReadRequests).contains(entry.usage.takeWhile(!_.isWhitespace)))
      .map(entry =>
        if entry.usage.startsWith("/debug") then
          CommandHelp("/debug request", "Show the server's latest captured provider request (sensitive data).")
        else entry
      )

  def make(
      client: OrchestratorClient,
      userId: AgentId,
      selected: Option[AgentId] = None,
      logLevel: LogLevel = LogLevel.Warning
  ): IO[YallmoError, ConnectedReplApplication] =
    for {
      capabilities <- client.capabilities
      ids <- capabilities.flatMap(_.configuredAgentIds) match {
        case Some(configured) => ZIO.succeed(configured)
        case None => client.listAgentIds.mapError(_ => YallmoError.InvalidConfig("Remote agent list failed."))
      }
      _ <- ZIO
        .fail(YallmoError.InvalidConfig("Selected agent ID is not in the server configured agent list."))
        .when(selected.exists(id => !ids.contains(id)))
      diagnostic = capabilities.exists(info =>
        info.identity.role == Role.Operator && info.methods.contains(DiagnosticsRequest.method)
      )
      executable = capabilities.exists(info =>
        info.identity.role == Role.Operator &&
          info.methods.contains(SessionsCreate.method) && info.methods.contains(TurnSubmit.method)
      )
    } yield new ConnectedReplApplication(
      client,
      userId,
      selected,
      ids.map(id => ReplAgentChoice(id, "remote ID (model/provider unknown)", Vector.empty, None)).toVector,
      executable,
      diagnostic,
      logLevel
    )
}
