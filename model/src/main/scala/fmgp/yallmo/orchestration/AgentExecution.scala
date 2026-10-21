package fmgp.yallmo.orchestration

import fmgp.yallmo.model.{AgentId, MessagePayload, YallmoError}
import zio.{Chunk, ZIO}

/** The identity and execution operation of an already-configured agent loop, independent of session storage.
  *
  * This is a small dependency boundary, not another agent implementation or execution engine. The model module defines
  * [[Orchestrator]] but cannot depend on the concrete AgentRunner and AgentInstance types in core. Bundling agentId and
  * run here lets the orchestrator invoke a configured loop without knowing how that loop is assembled.
  *
  * AgentRunner.execution supplies the usual implementation: it binds an agent program, static context and execution
  * budget to the existing runner. Constructing that adapter performs no model/tool calls. Calling run describes a full
  * bounded agent loop, potentially containing several model requests and tool/retrieval actions, not one provider turn.
  *
  * Responsibilities remain separate:
  *   - The orchestrator accepts and saves the prompt, supplies stored history, invokes this operation and saves
  *     results.
  *   - The configured runner executes model/tool work and enforces its permissions and budgets.
  *   - This interface neither owns storage nor duplicates the runner's execution logic.
  *
  * The supplied transcript already includes the accepted user prompt. Implementations must preserve that prefix and
  * return the complete transcript without appending the prompt again. They must not persist messages themselves; the
  * orchestrator saves only the generated suffix after successful execution.
  *
  * {{{
  * val execution = runner.execution(agent, staticContext, budget)
  * orchestrator.submit(sessionId, authorId, AgentTask("Explain Scala"), execution)
  * }}}
  *
  * @tparam R
  *   services required by the configured loop, such as model and tool services
  */
trait AgentExecution[-R] {

  /** Registered agent identity used to validate execution and attribute its generated session messages. */
  def agentId: AgentId

  /** Describes execution of one accepted task; the orchestrator controls when this effect runs.
    *
    * @param task
    *   task to execute; its prompt is already present in acceptedContext
    * @param acceptedContext
    *   stored conversation history including the accepted prompt exactly once
    * @return
    *   the final result attributed to agentId and the full append-only transcript, not just the generated suffix;
    *   execution failures are left to the orchestrator to report together with the accepted session
    */
  def run(task: AgentTask, acceptedContext: RunContext): ZIO[R, YallmoError, AgentTurnResult]
}

/** A completed submission and the durable session after its generated messages have been saved. */
final case class SessionTurn(session: Session, result: AgentResult, newMessages: Chunk[MessagePayload])

/** A failed submission, with the accepted session when the user prompt was successfully saved. */
final case class SessionSubmitFailure(error: YallmoError, acceptedSession: Option[Session])
