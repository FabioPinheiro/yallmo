package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.tool.*
import fmgp.yallmo.orchestration.{AgentFunction, ExternalFunctionCall, ExternalFunctionResult}
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
) extends AgentFunction[ScalaCodeExecutor] {
  require(timeout > Duration.Zero, "timeout must be positive")
  require(maxOutputBytes > 0, "maxOutputBytes must be positive")

  override val definition: FunctionToolDefinition = ScalaTool.definition

  override def execute(call: ExternalFunctionCall): ZIO[ScalaCodeExecutor, YallmoError, ExternalFunctionResult] =
    ScalaTool
      .executeWith(call, definition.name) { code =>
        ZIO.serviceWithZIO[ScalaCodeExecutor](_.execute(code, timeout, maxOutputBytes, dependencies))
      }
}

object ScalaTool {

  val DefaultDependencies: Seq[String] = Seq("dev.zio::zio:2.1.27")

  /** Code-only binding: the service is a host descriptor, not an environment requirement or a model argument. */
  def withServices(
      service: ScalamodeService,
      timeout: Duration = 45.seconds,
      maxOutputBytes: Int = 64 * 1024
  ): AgentFunction[ScalaCodeExecutor] = {
    require(timeout > Duration.Zero, "timeout must be positive")
    require(maxOutputBytes > 0, "maxOutputBytes must be positive")
    new AgentFunction[ScalaCodeExecutor] {
      override val definition = ScalamodeToolDefinition.withServices(service.inspections)
      override def execute(call: ExternalFunctionCall) = executeWith(call, definition.name) { code =>
        ZIO.serviceWithZIO[ScalaCodeExecutor](_.executeWithServices(code, service, timeout, maxOutputBytes))
      }
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
