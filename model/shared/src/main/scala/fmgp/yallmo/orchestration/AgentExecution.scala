package fmgp.yallmo.orchestration

import fmgp.yallmo.model.{AgentId, MessagePayload, YallmoError}
import zio.{Chunk, ZIO}

/** A configured recipient of one message. Payload support belongs to the recipient, not the transport or storage.
  * Implementations execute through their authorized services and do not persist messages themselves.
  */
trait AgentInvocation[-R] {
  def agentId: AgentId
  def invoke(input: MessagePayload): ZIO[R, YallmoError, MessagePayload]
}

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
  *   - The orchestrator accepts and saves input, supplies either session history or just an independent input, invokes
  *     this operation and saves results.
  *   - The configured runner executes model/tool work and enforces its permissions and budgets.
  *   - This interface neither owns storage nor duplicates the runner's execution logic.
  *
  * The supplied transcript already includes the accepted user prompt. Implementations must preserve that prefix and
  * return the complete transcript without appending the prompt again. They must not persist messages themselves;
  * orchestration saves the generated suffix for session submissions or only the final answer for independent calls.
  *
  * {{{
  * val execution = runner.execution(agent, staticContext, budget)
  * orchestrator.submit(sessionId, authorId, AgentTask("Explain Scala"), execution)
  * }}}
  *
  * @tparam R
  *   services required by the configured loop, such as model and tool services
  */
trait AgentExecution[-R] extends AgentInvocation[R] {

  /** Registered agent identity used to validate execution and attribute its generated messages. */
  def agentId: AgentId

  /** Describes execution of one accepted task; the orchestrator controls when this effect runs.
    *
    * @param task
    *   task to execute; its prompt is already present in acceptedContext
    * @param acceptedContext
    *   accepted input exactly once, preceded by stored history only for session-based execution
    * @return
    *   the final result attributed to agentId and the full append-only transcript, not just the generated suffix;
    *   execution failures are left to the orchestrator; already accepted input is not rolled back
    */
  def run(task: AgentTask, acceptedContext: RunContext): ZIO[R, YallmoError, AgentTurnResult]

  /** Compatibility adapter for text-oriented loops. Other recipients may override this to return any payload. */
  override def invoke(input: MessagePayload): ZIO[R, YallmoError, MessagePayload] = {
    val context = RunContext(Chunk(input))
    run(AgentTask(input.contentStr), context).flatMap { turn =>
      if turn.result.agentId != agentId ||
        turn.runContext.messages.take(context.messages.size) != context.messages || turn.result.text.trim.isEmpty
      then
        ZIO.fail(
          YallmoError.InvalidAgentDecision(
            "Agent execution must preserve the accepted input and agent ID and return a non-blank answer"
          )
        )
      else ZIO.succeed(MessagePayload.Assistant(turn.result.text))
    }
  }
}

/** A completed submission and the durable session after its generated messages have been saved. */
final case class SessionTurn(session: Session, result: AgentResult, newMessages: Chunk[MessagePayload])

/** A failed submission, with the accepted session when the user prompt was successfully saved. */
final case class SessionSubmitFailure(error: YallmoError, acceptedSession: Option[Session])
