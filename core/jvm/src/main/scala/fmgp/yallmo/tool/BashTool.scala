package fmgp.yallmo.tool

import fmgp.yallmo.model.YallmoError

import fmgp.yallmo.model.tool.BashToolDefinition

import fmgp.yallmo.orchestration.*
import zio.*

import java.nio.file.Path
import fmgp.yallmo.orchestration.{ExternalFunctionCall, ExternalFunctionResult}

/** Executes a Bash command in one configured workspace directory.
  *
  * This is an intentionally powerful capability: callers should advertise it only to agents allowed to execute local
  * commands. Standard error is merged with standard output, and returned output is bounded before it is sent back to
  * the model.
  */
final class BashTool(
    workspace: Path = Path.of("").toAbsolutePath.normalize,
    timeout: Duration = 30.seconds,
    maxOutputBytes: Int = BashTool.DefaultMaxOutputBytes
) extends AgentFunction[BashExecutor] {
  require(timeout > Duration.Zero, "timeout must be positive")
  require(maxOutputBytes > 0, "maxOutputBytes must be positive")

  override val definition: FunctionToolDefinition = BashTool.definition

  override def execute(call: ExternalFunctionCall): ZIO[BashExecutor, YallmoError, ExternalFunctionResult] =
    command(call).flatMap { value =>
      BashExecutor
        .execute(value, workspace, timeout, maxOutputBytes)
        .mapError(error => YallmoError.ToolExecutionFailed(definition.name, error.getMessage, error))
        .map(BashTool.render)
        .map(text => ExternalFunctionResult(text))
    }

  private def command(call: ExternalFunctionCall): IO[YallmoError, String] =
    ZIO
      .fromEither(
        scala.util.Try(ujson.read(call.argumentsJson)("command").str).toEither.left.map(_.getMessage)
      )
      .mapError(YallmoError.InvalidToolArguments(call.functionKey, _))
}

object BashTool {
  val DefaultMaxOutputBytes: Int = 64 * 1024

  val definition: FunctionToolDefinition = BashToolDefinition.ExecuteDefinition

  private[tool] def render(result: CommandOutput): String =
    ujson.write(
      ujson.Obj(
        "exitCode" -> ujson.Num(result.exitCode),
        "timedOut" -> ujson.Bool(result.timedOut),
        "output" -> ujson.Str(result.output),
        "truncated" -> ujson.Bool(result.truncated)
      )
    )
}
