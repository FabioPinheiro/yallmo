package fmgp.yallmo.repl

import fmgp.yallmo.model.{AgentId, YallmoError}
import fmgp.yallmo.repl.voice.Transcriber
import fmgp.yallmo.server.application.ProviderCatalog
import zio.*

/** UI boundary shared by the local application and the capability-negotiated connected client. */
trait ReplApplicationApi[R] {
  def initialState: ReplState
  def userId: AgentId
  def activeAgent: Option[ReplActiveAgent]
  def selectedAgentId: Option[AgentId] = activeAgent.map(a => AgentId(a.agentId))
  def agentChoices: Vector[ReplAgentChoice]
  def providerCatalog: Option[ProviderCatalog]
  def providerLogin: Option[ProviderLoginService]
  def transcriber: Option[Transcriber]
  def remoteConnection: Boolean = false
  def canSubmit: Boolean = true
  def canReadRequests: Boolean = false
  def connectedReadOnly: Boolean = remoteConnection && !canSubmit
  def submit(state: ReplState, line: String): ZIO[R, Nothing, ReplTransition]
  def selectAgent(id: AgentId): IO[YallmoError, ReplApplicationApi[R]]
}
