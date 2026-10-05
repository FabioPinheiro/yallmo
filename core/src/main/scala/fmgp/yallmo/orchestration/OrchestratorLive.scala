package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import fmgp.yallmo.orchestration.storage.{StorageFS, StorageInMemory}
import java.nio.file.Path
import zio.*

/** Default [[Orchestrator]] implementation backed by an internal [[Storage]]. */
final class OrchestratorLive[-R] private (
    storage: Storage,
    agents: Map[AgentId, Agent[R]]
) extends Orchestrator[R] {

  override def getAgent(id: AgentId): IO[YallmoError, Agent[R]] =
    ZIO.fromOption(agents.get(id)).orElseFail(YallmoError.UnknownAgent(id))

  override def turn(id: AgentId, request: AgentTurnRequest): ZIO[R, YallmoError, AgentTurnResponse] =
    getAgent(id).flatMap(_.turn(request))

  override def listSessions: IO[YallmoError, Chunk[SessionSummary]] =
    storage.listSessions.mapError(YallmoError.StorageFailed.apply)

  override def createSession(id: SessionId, agentIds: Chunk[AgentId]): IO[YallmoError, Session] =
    validateAgents(agentIds) *> storage.createSession(id, agentIds).mapError(YallmoError.StorageFailed.apply)

  override def loadSession(id: SessionId): IO[YallmoError, Session] =
    storage.loadSession(id).mapError(YallmoError.StorageFailed.apply)

  override def append(
      id: SessionId,
      agentId: AgentId,
      messages: Chunk[MessagePayload]
  ): IO[YallmoError, Session] =
    getAgent(agentId) *> storage.append(id, agentId, messages).mapError(YallmoError.StorageFailed.apply)

  override def fork(source: SessionId, target: SessionId): IO[YallmoError, Session] =
    storage.fork(source, target).mapError(YallmoError.StorageFailed.apply)

  private def validateAgents(agentIds: Chunk[AgentId]): IO[YallmoError, Unit] =
    ZIO.foreachDiscard(agentIds)(getAgent)
}

object OrchestratorLive {

  /** Creates an orchestrator backed by ephemeral, process-local storage. */
  def inMemory[R](agents: Chunk[Agent[R]]): UIO[Orchestrator[R]] =
    StorageInMemory.make.map(storage => new OrchestratorLive(storage, agents.map(agent => agent.id -> agent).toMap))

  /** Opens an orchestrator backed by a filesystem storage root. */
  def filesystem[R](root: Path, agents: Chunk[Agent[R]]): IO[YallmoError, Orchestrator[R]] =
    StorageFS.open(root).map(storage => new OrchestratorLive(storage, agents.map(agent => agent.id -> agent).toMap))
}
