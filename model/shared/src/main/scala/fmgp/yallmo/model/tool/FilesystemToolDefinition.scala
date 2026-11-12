package fmgp.yallmo.model.tool

import fmgp.yallmo.tool.{FunctionKey, FunctionToolDefinition, ToolDefinition, ToolKey}
import fmgp.yallmo.model.llm.JsonSchema

/** Stable callable identifiers and membership declared by this tool, independent of its runtime bindings. */
object FilesystemToolDefinition extends ToolDefinition {
  val ReadFile: FunctionKey = FunctionKey.unsafe("readFile")
  val ListFiles: FunctionKey = FunctionKey.unsafe("listFiles")

  val pathSchema: JsonSchema.ObjectSchema = JsonSchema.ObjectSchema(
    Map(
      "path" -> JsonSchema.StringSchema(
        Some("Workspace-relative path; use . for the root. Absolute paths, .. and symlinks are forbidden")
      )
    ),
    required = Set("path"),
    additionalProperties = false
  )

  val ReadFileDefinition: FunctionToolDefinition = FunctionToolDefinition(
    name = ReadFile,
    description =
      "Read a UTF-8 file relative to the workspace; absolute paths, parent traversal and symlinks are forbidden.",
    inputSchema = pathSchema
  )

  val ListFilesDefinition: FunctionToolDefinition = FunctionToolDefinition(
    name = ListFiles,
    description =
      "List all sorted direct entries relative to the workspace; absolute paths, parent traversal and symlinks are forbidden.",
    inputSchema = pathSchema
  )

  override val name: ToolKey = ToolKey.Filesystem
  override val functions: Set[FunctionToolDefinition] = Set(ReadFileDefinition, ListFilesDefinition)
}
