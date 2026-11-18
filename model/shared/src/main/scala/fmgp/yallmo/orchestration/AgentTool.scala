package fmgp.yallmo.orchestration

import fmgp.yallmo.tool.scalamode.ScalamodeFunction
import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.tool.{ToolDefinition, FunctionToolDefinition}
import zio.*

/** A trusted external capability that an agent may request.
  *
  * The runner checks the advertised definition before invocation. Implementations must validate model-produced
  * `argumentsJson` at their own boundary.
  */
trait AgentFunction[-R] {
  def definition: FunctionToolDefinition

  def execute(call: ExternalFunctionCall): ZIO[R, YallmoError, ExternalFunctionResult]
}

/** A tool binds implementations for its declared function keys. Each function retains its own execution boundary.
  * Resolution validates exact declaration/implementation agreement before exposing the tool to a wiring stage.
  */
final case class AgentTool[-R](definition: ToolDefinition, functions: Chunk[AgentFunction[R]]) {
  def name: fmgp.yallmo.tool.ToolKey = definition.name
}

/** A resolution-time transformation of the permitted tool catalog, composable inside Agent.toolWirings. */
type ToolWiring = Seq[AgentTool[Any]] => IO[YallmoError, Seq[AgentTool[Any]]]

object AgentTool {

  /** Bind the available implementations to memberships explicitly declared by tools. */
  def group[R](definitions: Chunk[ToolDefinition], functions: Chunk[AgentFunction[R]]): Chunk[AgentTool[R]] =
    ToolDefinition.available(definitions, functions.map(_.definition)).map { definition =>
      AgentTool(definition, functions.filter(function => definition.functions.contains(function.definition)))
    }
}

object AgentFunction {
  def provideEnvironment[R](tool: AgentFunction[R], environment: ZEnvironment[R]): AgentFunction[Any] =
    ScalamodeFunction.from(tool) match {
      case Some(integrated) => ScalamodeFunction.provideEnvironment(integrated, environment)
      case None             =>
        new AgentFunction[Any] {
          val definition = tool.definition
          def execute(call: ExternalFunctionCall) = tool.execute(call).provideEnvironment(environment)
        }
    }
}
