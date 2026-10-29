package fmgp.yallmo.tool

import fmgp.yallmo.model.ToolKey
import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.llm.JsonSchema
import fmgp.yallmo.orchestration.{AgentTool, AgentToolDefinition, ExternalToolCall, ExternalToolResult}
import zio.*

/** Trusted in-process Scala/ZIO evaluation using the selected subset `R` of the calling agent's environment.
  *
  * Construction captures configuration only. Both the executor and script services are resolved on each invocation. `R`
  * must contain the services advertised in `config.apiDescription`; this is not a sandbox.
  */
@deprecated("UNSAFE with no sandbox", "0.2.0")
final class ScalaDynamicTool[R: EnvironmentTag](
    config: ScalaCodeDynamicExecutor.Config,
    timeout: Duration = 45.seconds,
    maxOutputBytes: Int = 64 * 1024
) extends AgentTool[ScalaCodeDynamicExecutor & R] {
  require(timeout > Duration.Zero, "timeout must be positive")
  require(maxOutputBytes > 0, "maxOutputBytes must be positive")

  override val definition: AgentToolDefinition = ScalaDynamicTool.definition(config)

  override def execute(call: ExternalToolCall): ZIO[ScalaCodeDynamicExecutor & R, YallmoError, ExternalToolResult] =
    ScalaTool
      .executeWith(call, definition.key) { code =>
        ZIO.serviceWithZIO[ScalaCodeDynamicExecutor](_.execute[R](code, timeout, maxOutputBytes))
      }
      .map(result => ExternalToolResult(ScalaDynamicTool.render(result)))
}

@deprecated("UNSAFE with no sandbox", "0.2.0")
object ScalaDynamicTool {
  def definition(config: ScalaCodeDynamicExecutor.Config): AgentToolDefinition = AgentToolDefinition(
    key = ToolKey.ScalaUnsafe,
    description = "Compile a trusted Scala block and execute its returned ZIO effect inside the server JVM. " +
      "Return a ZIO effect with Throwable errors using only the advertised services. zio.* is already imported. " +
      "Use ZIO.serviceWithZIO to access existing services; do not construct services or run a Runtime. " +
      "Only the returned value and compiler diagnostics are captured, not println or ZIO logs. " +
      "No user-supplied dependencies, directives or annotations. " +
      "This is not a sandbox: code has server privileges and cancellation is cooperative. " +
      "Returns exitCode, output, timedOut and truncated. Available service API:\n" + config.apiDescription,
    inputSchema = JsonSchema.ObjectSchema(
      Map(
        "code" -> JsonSchema.StringSchema(Some("Scala block body returning a ZIO effect using the advertised services"))
      ),
      required = Set("code"),
      additionalProperties = false
    )
  )

  private[tool] def render(result: CommandOutput): String = {
    val json = ujson.read(BashTool.render(result))
    if result.timedOut then
      json("hint") = "Scala compilation or ZIO execution exceeded its deadline. " +
        "In-process cancellation is cooperative; check the output for diagnostics."
    ujson.write(json)
  }
}
