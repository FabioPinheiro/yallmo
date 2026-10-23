package fmgp.yallmo.tool

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.llm.JsonSchema
import fmgp.yallmo.orchestration.{AgentTool, AgentToolDefinition, ExternalToolCall, ExternalToolResult}
import zio.*

/** One-shot, compiler-restricted Scala evaluation. This is not an OS/JVM sandbox. */
final class ScalaTool(
    timeout: Duration = 45.seconds,
    maxOutputBytes: Int = 64 * 1024
) extends AgentTool[ScalaCodeExecutor] {
  require(timeout > Duration.Zero, "timeout must be positive")
  require(maxOutputBytes > 0, "maxOutputBytes must be positive")

  override val definition: AgentToolDefinition = ScalaTool.definition

  override def execute(call: ExternalToolCall): ZIO[ScalaCodeExecutor, YallmoError, ExternalToolResult] =
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
        .flatMap(value => ZIO.fromEither(ScalaTool.validate(value)).as(value))
        .mapError(YallmoError.InvalidToolArguments(call.toolKey, _))
      result <- ZIO
        .serviceWithZIO[ScalaCodeExecutor](_.execute(code, timeout, maxOutputBytes))
        .mapError(error => YallmoError.ToolExecutionFailed(definition.key, error.getMessage, error))
    } yield ExternalToolResult(ScalaTool.render(result))
}

object ScalaTool {
  private[tool] def render(result: CommandOutput): String = {
    val json = ujson.read(BashTool.render(result))
    if result.timedOut then
      json("hint") = "Scala CLI exceeded its compilation/execution deadline. " +
        "The default compiler is pinned and offline; check the captured output for compiler or runtime diagnostics."
    ujson.write(json)
  }

  val definition: AgentToolDefinition = AgentToolDefinition(
    key = "scala",
    description =
      "Evaluate a pure Scala block with a pinned Scala 3 nightly, offline. The last expression is returned. " +
        "No file, network, process, environment access or println. No dependencies, directives or annotations. " +
        "Returns exitCode, output (value or compiler/runtime errors), timedOut and truncated.",
    inputSchema = JsonSchema.ObjectSchema(
      Map("code" -> JsonSchema.StringSchema(Some("Scala block body; its last expression is the result"))),
      required = Set("code"),
      additionalProperties = false
    )
  )

  // Deliberately conservative, including inside literals/comments. These checks protect compiler configuration;
  // safe mode, NOT a lexical API blacklist, is responsible for restricting the available APIs.
  private[tool] def validate(code: String): Either[String, Unit] =
    if code.trim.isEmpty then Left("code must not be empty")
    else if code.length > 64 * 1024 then Left("code exceeds 65,536 characters")
    else if code.contains("//>") || code.contains("\\u") || code.contains('@') || code.contains('`') ||
      "\\b(language|unsafe|assumeSafe)\\b|import\\s+\\$".r.findFirstIn(code).nonEmpty
    then Left("Directives, annotations, Unicode escapes, backticks and compiler safety overrides are not supported")
    else Right(())
}
