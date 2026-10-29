package fmgp.yallmo.tool

import fmgp.yallmo.model.ToolKey
import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.llm.JsonSchema
import fmgp.yallmo.orchestration.{AgentTool, AgentToolDefinition, ExternalToolCall, ExternalToolResult}
import zio.*

/** One-shot restricted Scala evaluation through an external Scala CLI process.
  *
  * @param dependencies
  *   Trusted host-supplied Scala CLI dependency coordinates. Replaces the defaults; use an empty sequence for none.
  */
final class ScalaTool(
    timeout: Duration = 45.seconds,
    maxOutputBytes: Int = 64 * 1024,
    dependencies: Seq[String] = ScalaTool.DefaultDependencies
) extends AgentTool[ScalaCodeExecutor] {
  require(timeout > Duration.Zero, "timeout must be positive")
  require(maxOutputBytes > 0, "maxOutputBytes must be positive")

  override val definition: AgentToolDefinition = ScalaTool.definition

  override def execute(call: ExternalToolCall): ZIO[ScalaCodeExecutor, YallmoError, ExternalToolResult] =
    ScalaTool
      .executeWith(call, definition.key) { code =>
        ZIO.serviceWithZIO[ScalaCodeExecutor](_.execute(code, timeout, maxOutputBytes, dependencies))
      }
      .map(result => ExternalToolResult(ScalaTool.render(result)))
}

object ScalaTool {

  val DefaultDependencies: Seq[String] = Seq("dev.zio::zio:2.1.27")

  /** Shared argument/error boundary for the two Scala tools, independent of their execution backend. */
  private[tool] def executeWith[R](call: ExternalToolCall, toolKey: ToolKey)(
      execute: String => RIO[R, CommandOutput]
  ): ZIO[R, YallmoError, CommandOutput] =
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
        .mapError(YallmoError.InvalidToolArguments(call.toolKey, _))
      result <- execute(code)
        .mapError(error => YallmoError.ToolExecutionFailed(toolKey, error.getMessage, error))
    } yield result

  private[tool] def render(result: CommandOutput): String = {
    val json = ujson.read(BashTool.render(result))
    if result.timedOut then
      json("hint") = "Scala CLI exceeded its compilation/execution deadline. " +
        "The default compiler is pinned and offline; check the captured output for compiler or runtime diagnostics."
    ujson.write(json)
  }

  val definition: AgentToolDefinition = AgentToolDefinition(
    key = ToolKey.Scalamode,
    description = "Evaluate a Scala block with a pinned Scala 3 nightly, offline. The last expression is returned. " +
      "Trusted API, automatically imported: def api: Unit — prints Tool API available. Also available as tool.api. " +
      "No direct file, network, process, environment access or println. " +
      "No user-supplied dependencies, directives or annotations. " +
      "Returns exitCode, output (value, trusted API output or compiler/runtime errors), timedOut and truncated.",
    inputSchema = JsonSchema.ObjectSchema(
      Map("code" -> JsonSchema.StringSchema(Some("Scala block body; its last expression is the result"))),
      required = Set("code"),
      additionalProperties = false
    )
  )

  // Deliberately conservative, including inside literals/comments. These checks protect compiler configuration.
  // Restricted execution relies on compiler safe mode, not an API blacklist; dynamic execution is explicitly trusted.
  private[tool] def validate(code: String): Either[String, Unit] =
    if code.trim.isEmpty then Left("code must not be empty")
    else if code.length > 64 * 1024 then Left("code exceeds 65,536 characters")
    else if code.contains("//>") || code.contains("\\u") || code.contains('@') || code.contains('`') ||
      "\\b(language|unsafe|assumeSafe)\\b|import\\s+\\$".r.findFirstIn(code).nonEmpty
    then Left("Directives, annotations, Unicode escapes, backticks and compiler safety overrides are not supported")
    else Right(())
}
