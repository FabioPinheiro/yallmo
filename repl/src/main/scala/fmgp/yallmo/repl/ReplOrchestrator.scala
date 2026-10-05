package fmgp.yallmo.repl

import fmgp.yallmo.model.{Agent, AgentId, MessagePayload, YallmoError}
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import fmgp.yallmo.orchestration.*
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import zio.*

/** REPL-facing extension of [[Orchestrator]]. */
trait ReplOrchestrator[-R] extends Orchestrator[R] {
  def listReplSessionIds: IO[YallmoError, Chunk[String]]

  def newReplSession(name: Option[String]): IO[YallmoError, ReplState]

  def persistTurn(previous: ReplState, turn: ReplTurn): IO[YallmoError, ReplTurn]

  def resumeReplSession(id: String): IO[YallmoError, ReplState]
}

/** Adapts the application-facing orchestrator API to REPL state and session conventions. */
final class ReplOrchestratorLive[-R] private (
    orchestrator: Orchestrator[R],
    userAgent: Agent[R],
    assistantAgent: Agent[R]
) extends ReplOrchestrator[R] { // TODO rename/merge with ReplOrchestrator

  override def getAgent(id: AgentId): IO[YallmoError, Agent[R]] = orchestrator.getAgent(id)

  override def turn(id: AgentId, request: AgentTurnRequest): ZIO[R, YallmoError, AgentTurnResponse] =
    orchestrator.turn(id, request)

  override def listSessions: IO[YallmoError, Chunk[SessionSummary]] = orchestrator.listSessions

  override def createSession(id: SessionId, agentIds: Chunk[AgentId]): IO[YallmoError, Session] =
    orchestrator.createSession(id, agentIds)

  override def loadSession(id: SessionId): IO[YallmoError, Session] = orchestrator.loadSession(id)

  override def append(
      id: SessionId,
      agentId: AgentId,
      messages: Chunk[MessagePayload]
  ): IO[YallmoError, Session] = orchestrator.append(id, agentId, messages)

  override def fork(source: SessionId, target: SessionId): IO[YallmoError, Session] = orchestrator.fork(source, target)

  override def listReplSessionIds: IO[YallmoError, Chunk[String]] =
    listSessions.map(_.map(_.id.value))

  override def newReplSession(name: Option[String]): IO[YallmoError, ReplState] =
    sessionId(name).flatMap { id =>
      createSession(id, agentIds).as(ReplState(sessionId = Some(id.value)))
    }

  override def persistTurn(previous: ReplState, turn: ReplTurn): IO[YallmoError, ReplTurn] =
    if turn.newMessages.isEmpty then ZIO.succeed(turn)
    else
      for {
        id <- previous.sessionId match
          case Some(value) => sessionId(Some(value))
          case None        => sessionId(None).tap(createSession(_, agentIds))
        _ <- ZIO.foreachDiscard(turn.newMessages) { message =>
          append(id, agentIdFor(message), Chunk(message))
        }
      } yield turn.copy(state = turn.state.copy(sessionId = Some(id.value)))

  override def resumeReplSession(id: String): IO[YallmoError, ReplState] =
    for {
      sessionId <- sessionId(Some(id))
      session <- loadSession(sessionId)
      _ <- ZIO
        .fail(YallmoError.AgentNotInSession(sessionId, assistantAgent.id))
        .unless(session.agentIds.contains(assistantAgent.id))
    } yield ReplState(RunContext(session.messages.map(_.payload)), sessionId = Some(session.id.value))

  private val agentIds = Chunk(userAgent.id, assistantAgent.id)

  private def agentIdFor(message: MessagePayload): AgentId =
    message match
      case _: MessagePayload.User => userAgent.id
      case _                      => assistantAgent.id

  private def sessionId(name: Option[String]): IO[YallmoError, SessionId] =
    name match
      case Some(value) => ZIO.fromEither(SessionId.from(value).left.map(YallmoError.InvalidSessionId(value, _)))
      case None        => nextTimestampedSessionId

  private def nextTimestampedSessionId: IO[YallmoError, SessionId] = {
    def next: IO[YallmoError, SessionId] =
      val id = SessionId.unsafe(ReplOrchestratorLive.SessionNameFormatter.format(Instant.now()))
      loadSession(id).either.flatMap {
        case Left(YallmoError.StorageFailed(YallmoError.SessionNotFound(_))) => ZIO.succeed(id)
        case Right(_)                                                        => ZIO.sleep(1.second) *> next
        case Left(error)                                                     => ZIO.fail(error)
      }
    next
  }
}

object ReplOrchestratorLive {
  private[repl] val SessionNameFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("'main-'uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC)

  def make[R](
      orchestrator: Orchestrator[R],
      userAgent: Agent[R],
      assistantAgent: Agent[R]
  ): ReplOrchestrator[R] =
    new ReplOrchestratorLive(orchestrator, userAgent, assistantAgent)
}
