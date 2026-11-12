package fmgp.yallmo.orchestration

import fmgp.yallmo.tool.ToolKey

import fmgp.yallmo.model.{YallmoError, RagDocument}
import fmgp.yallmo.tool.{ToolDefinition, FunctionToolDefinition}

import zio.*
import fmgp.yallmo.orchestration.{ExternalFunctionCall, ExternalFunctionResult, RagQuery, RetrieverDefinition}

/** A trusted external capability that an agent may request through [[LlmAction.CallExternalFunction]].
  *
  * The [[AgentRunner]] checks that the calling agent advertises this definition before invoking it. Implementations
  * must validate model-produced `argumentsJson` at their own boundary.
  */
trait AgentFunction[-R] {
  def definition: FunctionToolDefinition

  def execute(call: ExternalFunctionCall): ZIO[R, YallmoError, ExternalFunctionResult]
}

/** A tool binds implementations for its declared function keys. Each function retains its own execution boundary. */
final case class AgentTool[-R](definition: ToolDefinition, functions: Chunk[AgentFunction[R]]) {
  require(
    functions.forall(function => definition.functions.contains(function.definition)),
    "Function is not declared by this tool"
  )
  def name: fmgp.yallmo.tool.ToolKey = definition.name
}

object AgentTool {

  /** Bind the available implementations to memberships explicitly declared by tools. */
  def group[R](definitions: Chunk[ToolDefinition], functions: Chunk[AgentFunction[R]]): Chunk[AgentTool[R]] =
    ToolDefinition.available(definitions, functions.map(_.definition)).map { definition =>
      AgentTool(definition, functions.filter(function => definition.functions.contains(function.definition)))
    }
}

/** Agent-owned, environment-bound functions. Applications must bind these only to the selected agent's runner/context,
  * never publish them in the shared catalog: two instances may use identical tool names for different private roots.
  *
  * TODO FIXME REMOVE why do we need this?
  */
trait HasLocalAgentFunctions {
  def localFunctions: Chunk[AgentFunction[Any]]
  def localTools: Chunk[ToolDefinition] = Chunk.empty
}

/** A typed RAG boundary.
  *
  * Documents remain source-attributed [[RagDocument]] values until the receiving agent renders them for its model.
  */
trait KnowledgeRetriever[R] {
  def definition: RetrieverDefinition

  def retrieve(query: RagQuery): ZIO[R, YallmoError, Chunk[RagDocument]]
}
