package fmgp.yallmo.model.tool

import fmgp.yallmo.tool.{FunctionKey, FunctionToolDefinition, ToolDefinition, ToolKey}
import fmgp.yallmo.model.llm.JsonSchema
import zio.Chunk

/** Stable callable identifiers and membership declared by this tool, independent of its runtime bindings. */
object RepositoryChangesToolDefinition extends ToolDefinition {
  val Inspect: FunctionKey = FunctionKey.unsafe("repositoryChanges")

  val InspectDefinition: FunctionToolDefinition = FunctionToolDefinition(
    name = Inspect,
    description =
      "Inspect Git repository status, unstaged changes, or staged changes without modifying the repository.",
    inputSchema = JsonSchema.ObjectSchema(
      Map(
        "view" -> JsonSchema.StringSchema(
          Some("Repository view: status, unstaged (working tree), or staged (index ready to commit)"),
          Chunk("status", "unstaged", "staged")
        )
      ),
      required = Set("view"),
      additionalProperties = false
    )
  )

  override val name: ToolKey = ToolKey.RepositoryChanges
  override val functions: Set[FunctionToolDefinition] = Set(InspectDefinition)
}
