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

  def append(id: SessionId, agentId: AgentId, messages: Chunk[MessagePayload]): IO[YallmoError, Session]

  def fork(source: SessionId, target: SessionId): IO[YallmoError, Session]
}
