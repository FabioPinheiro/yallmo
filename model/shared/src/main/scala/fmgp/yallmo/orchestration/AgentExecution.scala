package fmgp.yallmo.orchestration

import fmgp.yallmo.model.{AgentId, MessagePayload, YallmoError}
import zio.{Chunk, UIO, ZIO}

/** A configured recipient of one message, not an agent loop. Payload support belongs to the recipient, not storage.
  * Implementations execute through their authorized services; [[Orchestrator.invoke]] owns input/output persistence.
  */
trait AgentInvocation[-R] {
  def agentId: AgentId
  private[orchestration] def invoke(input: MessagePayload): ZIO[R, YallmoError, MessagePayload]
}

/** An opaque configured agent loop, executable only by orchestration through [[Orchestrator.submit]].
  *
  * AgentRunner.execution binds the agent, capabilities and budget without performing effects. Application code can pass
  * this handle to submit, but cannot construct its own implementation or invoke the loop directly. The model module
  * defines this boundary because it cannot depend on the concrete runner in core.
  *
  * {{{
  * val execution = runner.execution(agent, staticContext, budget)
  * orchestrator.submit(sessionId, authorId, AgentTask("Explain Scala"), execution)
  * }}}
  */
abstract class AgentExecution[-R] private[orchestration] () {

  /** Registered agent identity used to validate execution and attribute its generated messages. */
  def agentId: AgentId

  /** Internal loop entry point. Only orchestration can supply the accepted history and persistence operation.
    *
    * The transcript includes the accepted prompt exactly once and must remain append-only. The required callback
    * schedules ordered, best-effort saves before tool execution and after each result; it is not a durability barrier.
    * There is deliberately no callback-free overload or default. The returned transcript includes the entire run.
    */
  private[orchestration] def run(
      task: AgentTask,
      acceptedContext: RunContext,
      saveTranscriptLater: RunContext => UIO[Unit]
  ): ZIO[R, YallmoError, AgentTurnResult]
}

/** A completed submission and its durable session. Best-effort checkpoint failures may leave newMessages unsaved. */
final case class SessionTurn(session: Session, result: AgentResult, newMessages: Chunk[MessagePayload])

/** A failed submission, with the latest saved session when the user prompt was successfully saved. */
final case class SessionSubmitFailure(error: YallmoError, acceptedSession: Option[Session])
