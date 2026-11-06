package fmgp.yallmo.orchestration

import fmgp.yallmo.model.{AgentId, Message, MessageId, MessagePayload, YallmoError}
import zio.{Chunk, ZIO}

/** A configured recipient of one message, not an agent loop. Payload support belongs to the recipient, not storage.
  * Implementations execute through their authorized services; [[Orchestrator.invoke]] owns input/output persistence.
  */
trait AgentInvocation[-R] {
  def agentId: AgentId
  private[orchestration] def invoke(input: MessagePayload): ZIO[R, YallmoError, MessagePayload]
}

/** An opaque configured agent loop, executable only by orchestration through [[Orchestrator.submit]] or
  * [[Orchestrator.submitBranch]].
  *
  * AgentRunner.execution binds the agent, capabilities and budget without performing effects. Application code can pass
  * this handle to either submission operation, but cannot construct its own implementation or invoke the loop directly.
  * The model module defines this boundary because it cannot depend on the concrete runner in core.
  *
  * {{{
  * val execution = runner.execution(agent, staticContext, budget)
  * orchestrator.submit(sessionId, authorId, AgentTask("Explain Scala"), execution)
  * }}}
  */
abstract class AgentExecution[-R] private[orchestration] () {

  /** Registered agent identity used to validate execution and attribute its generated messages. */
  def agentId: AgentId

  /** Internal loop entry point. Only orchestration supplies managed history, including the accepted prompt exactly
    * once. Generated payloads append directly; the returned snapshot is compatibility output, never reapplied to
    * history.
    */
  private[orchestration] def run(
      task: AgentTask,
      history: ManagedHistory
  ): ZIO[R, YallmoError, AgentTurnResult]
}

/** A completed submission and its last acknowledged session publication. Best-effort object failures may leave
  * newMessages unsaved; a session-head conflict instead leaves independently stored messages on an inspectable branch.
  * newMessages is the accepted prompt once plus actual execution appends, even if rebased out of active context.
  * Immutable rebase copies are not new execution and never appear in this audit as additional messages.
  */
final case class SessionTurn(session: Session, result: AgentResult, newMessages: Chunk[MessagePayload])

/** A failed submission, with the latest saved session when the user prompt was successfully saved. */
final case class SessionSubmitFailure(error: YallmoError, acceptedSession: Option[Session])

/** No session or pointer is allocated. newMessages contains verified stored artifacts, including the accepted prompt.
  */
final case class BranchTurn(
    parentId: MessageId,
    promptId: MessageId,
    head: MessageId,
    result: AgentResult,
    newMessages: Chunk[Message]
)

enum BranchAcceptance { case NotAccepted, Accepted, Unknown }

/** head is the last confirmed stored active head, not a promise that no later objects exist after an unknown failure.
  */
final case class BranchSubmitFailure(
    error: YallmoError,
    acceptance: BranchAcceptance,
    promptId: Option[MessageId] = None,
    head: Option[MessageId] = None,
    newMessages: Chunk[Message] = Chunk.empty
)
