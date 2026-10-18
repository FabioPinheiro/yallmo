package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import zio.*

/** Application-facing API for registered agents and durable sessions.
  *
  * Implementations resolve agents and delegate transcript persistence to an internal [[Storage]]. Callers do not use
  * storage directly.
  */
trait Orchestrator[-R] {
  def getAgent(id: AgentId): IO[YallmoError, Agent[R]]

  def turn(id: AgentId, request: AgentTurnRequest): ZIO[R, YallmoError, AgentTurnResponse]

  def listSessions: IO[YallmoError, Chunk[SessionSummary]]

  def createSession(id: SessionId, agentIds: Chunk[AgentId]): IO[YallmoError, Session]

  def loadSession(id: SessionId): IO[YallmoError, Session]

  /** Adds a registered message author without changing the session ID or transcript. Idempotent. Fixed-membership
    * implementations retain their previous behavior unless they override this operation.
    */
  def addSessionAgent(id: SessionId, agentId: AgentId): IO[YallmoError, Session] =
    getAgent(agentId) *> loadSession(id).flatMap { session =>
      if session.agentIds.contains(agentId) then ZIO.succeed(session)
      else ZIO.fail(YallmoError.AgentNotInSession(id, agentId))
    }

  def append(id: SessionId, agentId: AgentId, messages: Chunk[MessagePayload]): IO[YallmoError, Session]

  def fork(source: SessionId, target: SessionId): IO[YallmoError, Session]
}
