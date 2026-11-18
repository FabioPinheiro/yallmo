package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.orchestration.{AgentFunction, ExternalFunctionCall, ExternalFunctionResult}
import zio.*

/** Explicit integration hooks for Scalamode implementations. Ordinary agent functions remain opaque to wiring and
  * alternate playground execution, even when they advertise the same name or schema.
  */
trait ScalamodeFunction[-R] extends AgentFunction[R] {

  /** Trusted opt-in to pure playground execution with a request-local executor. */
  def manualScalamode: Option[ManualScalamode[R]] = None

  /** Rebind an integrated default while retaining its execution boundary. Already-bound functions return None. */
  def bindDefaultScalamode(replacement: ScalamodeFunction[Any]): Option[ScalamodeFunction[R]] = None
}

/** Pure playground execution; the supplied executor must not receive the selected agent's services. */
trait ManualScalamode[-R] {
  def execute(call: ExternalFunctionCall, executor: ScalaCodeExecutor): ZIO[R, YallmoError, ExternalFunctionResult]
}

object ScalamodeFunction {

  /** Bind both execution paths without losing explicit integration through the environment wrapper. */
  def provideEnvironment[R](
      tool: ScalamodeFunction[R],
      environment: ZEnvironment[R]
  ): ScalamodeFunction[Any] = new ScalamodeFunction[Any] {
    val definition = tool.definition
    def execute(call: ExternalFunctionCall) = tool.execute(call).provideEnvironment(environment)
    override def manualScalamode = tool.manualScalamode.map(operation =>
      new ManualScalamode[Any] {
        def execute(call: ExternalFunctionCall, executor: ScalaCodeExecutor) =
          operation.execute(call, executor).provideEnvironment(environment)
      }
    )
    override def bindDefaultScalamode(replacement: ScalamodeFunction[Any]) =
      tool.bindDefaultScalamode(replacement).map(provideEnvironment(_, environment))
  }

  /** Discover explicit integration, never infer it from tool metadata. */
  def from[R](function: AgentFunction[R]): Option[ScalamodeFunction[R]] = function match {
    // R is erased, but ScalamodeFunction inherits AgentFunction with the same environment requirement.
    case integrated: ScalamodeFunction[R] @unchecked => Some(integrated)
    case _                                           => None
  }
}
