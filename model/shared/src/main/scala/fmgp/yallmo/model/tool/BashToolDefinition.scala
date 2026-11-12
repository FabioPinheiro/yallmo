package fmgp.yallmo.model.tool

import fmgp.yallmo.tool.{FunctionKey, FunctionToolDefinition, ToolDefinition, ToolKey}
import fmgp.yallmo.model.llm.JsonSchema

/** Stable callable identifiers and membership declared by this tool, independent of its runtime bindings. */
object BashToolDefinition extends ToolDefinition {
  val Execute: FunctionKey = FunctionKey.unsafe("bash")

  val ExecuteDefinition: FunctionToolDefinition = FunctionToolDefinition(
    name = Execute,
    description = "Run a Bash command in the workspace and return its exit code, combined output, and timeout status.",
    inputSchema = JsonSchema.ObjectSchema(
      Map("command" -> JsonSchema.StringSchema(Some("Bash command to execute in the workspace"))),
      required = Set("command"),
      additionalProperties = false
    )
  )

  override val name: ToolKey = ToolKey.Bash
  override val functions: Set[FunctionToolDefinition] = Set(ExecuteDefinition)
}
