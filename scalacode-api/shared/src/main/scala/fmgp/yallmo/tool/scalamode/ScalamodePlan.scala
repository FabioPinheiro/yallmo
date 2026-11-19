package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.orchestration.ExternalFunctionCall

/** Immutable trusted operation. Backend substitution never reconstructs or widens a captured service descriptor. */
private[scalamode] final case class ScalamodePlan(
    settings: ScalamodeFunction.Settings,
    service: Option[ScalamodeService] = None
) {
  val definition = service.fold(ScalaTool.definition)(value => ScalamodeToolDefinition.withServices(value.inspections))

  def function(executor: ScalaCodeExecutor): ScalamodeFunction[Any] = new ScalamodeFunction[Any] {
    val definition = ScalamodePlan.this.definition
    override def withExecutor(replacement: ScalaCodeExecutor) = Some(function(replacement))
    def execute(call: ExternalFunctionCall) = ScalaTool.executeWith(call, definition.name) { code =>
      service match {
        case None        => executor.execute(code, settings.timeout, settings.maxOutputBytes, settings.dependencies)
        case Some(value) =>
          executor.executeWithServices(code, value, settings.timeout, settings.maxOutputBytes, settings.dependencies)
      }
    }
  }
}
