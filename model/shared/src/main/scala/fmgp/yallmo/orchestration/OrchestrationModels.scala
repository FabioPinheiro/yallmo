package fmgp.yallmo.orchestration

import fmgp.yallmo.model.YallmoError
import zio.*
import fmgp.yallmo.model.{FunctionCallId, MessagePayload}
import fmgp.yallmo.model.llm.JsonSchema
import fmgp.yallmo.model.*

// /** Re-export the model-layer identifier used by agents throughout orchestration. */
// type AgentId = fmgp.yallmo.model.AgentId

// object AgentId {
//   def from(value: String): Either[String, AgentId] = fmgp.yallmo.model.AgentId.from(value)

//   def unsafe(value: String): AgentId = fmgp.yallmo.model.AgentId.unsafe(value)

//   def value(id: AgentId): String = fmgp.yallmo.model.AgentId.value(id)
// }

// extension (id: AgentId) {
//   def value: String = AgentId.value(id)
// }

/** The explicit request handled by an [[AgentInstance]]. */
final case class AgentTask(prompt: String)

/** The terminal answer produced by an agent run. */
final case class AgentResult(
    agentId: AgentId,
    text: String
)

/** One completed agent turn: an optional textual assistant reply and its next decision.
  *
  * A provider may request function calls without producing text. In that case `assistant` is `None`; the calls are
  * retained as [[MessagePayload.FunctionCallRequest]] messages rather than fabricating an empty assistant message.
  */
final case class AgentTurn(
    assistant: Option[MessagePayload.Assistant],
    decision: AgentDecision,
    additionalMessages: Chunk[MessagePayload] = Chunk.empty
)

/** The terminal answer and complete in-memory transcript produced by one agent run. */
final case class AgentTurnResult(
    result: AgentResult,
    runContext: RunContext
)

/** A source-attributed document supplied to an agent by a RAG retrieval action. */
/** A query issued against a named RAG retriever. */
final case class RagQuery(text: String)

/** A model-facing description of an external capability available to one agent. */
final case class AgentToolDefinition(
    key: String,
    description: String,
    inputSchema: JsonSchema.ObjectSchema = JsonSchema.ObjectSchema(Map.empty, additionalProperties = true)
)

/** A provider-neutral external-tool invocation proposed by an agent. */
final case class ExternalToolCall(
    toolKey: String,
    argumentsJson: String,
    callId: Option[FunctionCallId] = None
)

/** The normalized outcome of an external-tool invocation. */
final case class ExternalToolResult(text: String)

/** Model guidance made available to an agent for this run. */
final case class AgentSkill(
    key: String,
    description: String,
    instructions: String
)

/** Static configuration explicitly available to one agent for an entire run.
  *
  * The runner never replaces this value after a run starts. Tools and retrievers must be advertised here before the
  * runner can resolve a model-requested capability.
  */
final case class StaticContext[R](
    systemPrompt: String,
    tools: Chunk[AgentToolDefinition] = Chunk.empty,
    retrievers: Chunk[RetrieverDefinition] = Chunk.empty,
    skills: Chunk[AgentSkill] = Chunk.empty,
    extensions: Chunk[ContextExtension[R]] = Chunk.empty[ContextExtension[R]]
)

/** A retriever advertised to an agent context. */
final case class RetrieverDefinition(
    key: String,
    description: String
)

/** A model-proposed action. Its execution remains owned by the trusted [[AgentRunner]]. */
enum LlmAction {
  case RetrieveRag(retrieverKey: String, query: RagQuery)
  case CallExternalTool(call: ExternalToolCall)
}

/** One outcome of an [[AgentInstance]] turn.
  *
  * A batch permits independent retrievals or reads in one model turn. The runner executes the batch in declaration
  * order today; concurrent execution must be introduced only for explicitly safe capabilities.
  */
enum AgentDecision {
  case FinalAnswer(text: String)
  case Actions(actions: Chunk[LlmAction])
}

/** Global guardrails for one orchestration run. */
final case class AgentBudget(
    maxTurns: Int,
    maxActions: Int,
    timeout: Duration
) {
  def validate: Either[YallmoError.InvalidAgentBudget, Unit] =
    if maxTurns <= 0 then Left(YallmoError.InvalidAgentBudget("maxTurns must be positive"))
    else if maxActions <= 0 then Left(YallmoError.InvalidAgentBudget("maxActions must be positive"))
    else if timeout <= Duration.Zero then Left(YallmoError.InvalidAgentBudget("timeout must be positive"))
    else Right(())
}

object AgentBudget {
  val default: AgentBudget = AgentBudget(maxTurns = 12, maxActions = 24, timeout = 2.minutes)
}
