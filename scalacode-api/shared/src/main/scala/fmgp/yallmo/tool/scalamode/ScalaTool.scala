package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.tool.*
import fmgp.yallmo.orchestration.*
import zio.*

/** One-shot restricted Scala evaluation through isolated Scala CLI compilation and a ZIOApp runner.
  *
  * @param dependencies
  *   Trusted host-supplied Scala CLI dependency coordinates. Replaces the defaults; an empty sequence keeps only the
  *   mandatory ZIO/ZIO JSON runner dependencies.
  */
final class ScalaTool(
    timeout: Duration = 45.seconds,
    maxOutputBytes: Int = 64 * 1024,
    dependencies: Seq[String] = ScalaTool.DefaultDependencies
) extends ScalamodeFunction[ScalaCodeExecutor] {
  require(timeout > Duration.Zero, "timeout must be positive")
  require(maxOutputBytes > 0, "maxOutputBytes must be positive")

  override val definition: FunctionToolDefinition = ScalaTool.definition

  private val plan = ScalamodePlan(ScalamodeFunction.Settings(timeout, maxOutputBytes, dependencies))

  override def bindDefaultScalamode(binding: ScalamodeFunction.DefaultBinding) = Some(binding.bind(plan.settings))

  override def withExecutor(executor: ScalaCodeExecutor) = Some(plan.function(executor))

  override def execute(call: ExternalFunctionCall): ZIO[ScalaCodeExecutor, YallmoError, ExternalFunctionResult] =
    ZIO.serviceWithZIO[ScalaCodeExecutor](plan.function(_).execute(call))
}

object ScalaTool {

  val DefaultDependencies: Seq[String] = ScalaCodeExecutor.DefaultDependencies

  /** Code-only binding: the service is a host descriptor, not an environment requirement or a model argument. */
  def withServices(
      service: ScalamodeService,
      timeout: Duration = 45.seconds,
      maxOutputBytes: Int = 64 * 1024,
      dependencies: Seq[String] = DefaultDependencies
  ): ScalamodeFunction[ScalaCodeExecutor] = {
    require(timeout > Duration.Zero, "timeout must be positive")
    require(maxOutputBytes > 0, "maxOutputBytes must be positive")
    val plan = ScalamodePlan(ScalamodeFunction.Settings(timeout, maxOutputBytes, dependencies), Some(service))
    new ScalamodeFunction[ScalaCodeExecutor] {
      override val definition = plan.definition
      override def withExecutor(executor: ScalaCodeExecutor) = Some(plan.function(executor))
      override def execute(call: ExternalFunctionCall) =
        ZIO.serviceWithZIO[ScalaCodeExecutor](plan.function(_).execute(call))
    }
  }

  /** Shared argument/error boundary for default and service-bound Scala tools, independent of their execution backend.
    */
  private[tool] def executeWith[R, A](call: ExternalFunctionCall, functionKey: FunctionKey)(
      execute: String => RIO[R, A]
  ): ZIO[R, YallmoError, A] =
    for {
      code <- ZIO
        .fromEither(
          scala.util
            .Try {
              val arguments = ujson.read(call.argumentsJson).obj
              require(arguments.keySet == Set("code"), "Expected only a code string")
              arguments("code").str
            }
            .toEither
            .left
            .map(_.getMessage)
        )
        .flatMap(value => ZIO.fromEither(validate(value)).as(value))
        .mapError(YallmoError.InvalidToolArguments(call.functionKey, _))
      result <- execute(code)
        .mapError(error => YallmoError.ToolExecutionFailed(functionKey, error.getMessage, error))
    } yield result

  val definition: FunctionToolDefinition = ScalamodeToolDefinition.Default

  // Deliberately conservative, including inside literals/comments. These checks protect compiler configuration.
  // Restricted execution relies on compiler safe mode, not an API blacklist.
  private[tool] def validate(code: String): Either[String, Unit] =
    if code.trim.isEmpty then Left("code must not be empty")
    else if code.length > 64 * 1024 then Left("code exceeds 65,536 characters")
    else if code.contains("//>") || code.contains("\\u") || code.contains('@') || code.contains('`') ||
      "\\b(language|unsafe|assumeSafe)\\b|import\\s+\\$".r.findFirstIn(code).nonEmpty
    then Left("Directives, annotations, Unicode escapes, backticks and compiler safety overrides are not supported")
    else Right(())
}
