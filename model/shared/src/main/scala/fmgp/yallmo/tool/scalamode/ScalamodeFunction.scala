package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.orchestration.{AgentFunction, ExternalFunctionCall}
import zio.*

/** Explicit integration, never inferred from a tool's name or schema. Opaque custom functions remain opaque. */
trait ScalamodeFunction[-R] extends AgentFunction[R] {

  /** Replace only the execution engine, retaining this operation's definition, capabilities and guards. */
  def withExecutor(executor: ScalaCodeExecutor): Option[ScalamodeFunction[R]] = None

  /** Opt-in for unbound defaults. The transformation receives the original trusted configuration. */
  def bindDefaultScalamode(binding: ScalamodeFunction.DefaultBinding): Option[ScalamodeFunction[R]] = None
}

object ScalamodeFunction {
  final case class Settings(timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String])
  trait DefaultBinding {
    def bind(settings: Settings): ScalamodeFunction[Any]
  }

  /** Forward integration structurally; never reconstruct a function from its inspection metadata. */
  def provideEnvironment[R](tool: ScalamodeFunction[R], environment: ZEnvironment[R]): ScalamodeFunction[Any] =
    new ScalamodeFunction[Any] {
      val definition = tool.definition
      def execute(call: ExternalFunctionCall) = tool.execute(call).provideEnvironment(environment)
      override def withExecutor(executor: ScalaCodeExecutor) =
        tool.withExecutor(executor).map(provideEnvironment(_, environment))
      override def bindDefaultScalamode(binding: DefaultBinding) =
        tool.bindDefaultScalamode(binding).map(provideEnvironment(_, environment))
    }

  def from[R](function: AgentFunction[R]): Option[ScalamodeFunction[R]] = function match {
    // R is erased, but both interfaces have the same environment requirement.
    case integrated: ScalamodeFunction[R] @unchecked => Some(integrated)
    case _                                           => None
  }
}
