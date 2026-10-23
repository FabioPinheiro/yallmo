package fmgp.yallmo.tool

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.model.llm.JsonSchema
import zio.*

import java.nio.file.Path
import fmgp.yallmo.orchestration.{AgentToolDefinition, ExternalToolCall, ExternalToolResult}

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
) extends AgentTool[BashExecutor] {
  require(timeout > Duration.Zero, "timeout must be positive")
  require(maxOutputBytes > 0, "maxOutputBytes must be positive")

  override val definition: AgentToolDefinition = BashTool.definition

  override def execute(call: ExternalToolCall): ZIO[BashExecutor, YallmoError, ExternalToolResult] =
    command(call).flatMap { value =>
      BashExecutor
        .execute(value, workspace, timeout, maxOutputBytes)
        .mapError(error => YallmoError.ToolExecutionFailed(definition.key, error.getMessage, error))
        .map(BashTool.render)
        .map(ExternalToolResult.apply)
    }

  private def command(call: ExternalToolCall): IO[YallmoError, String] =
    ZIO
      .fromEither(
        scala.util.Try(ujson.read(call.argumentsJson)("command").str).toEither.left.map(_.getMessage)
      )
      .mapError(YallmoError.InvalidToolArguments(call.toolKey, _))
}

object BashTool {
  val DefaultMaxOutputBytes: Int = 64 * 1024

  val definition: AgentToolDefinition = AgentToolDefinition(
    key = "bash",
    description = "Run a Bash command in the workspace and return its exit code, combined output, and timeout status.",
    inputSchema = JsonSchema.ObjectSchema(
      Map("command" -> JsonSchema.StringSchema(Some("Bash command to execute in the workspace"))),
      required = Set("command"),
      additionalProperties = false
    )
  )

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
