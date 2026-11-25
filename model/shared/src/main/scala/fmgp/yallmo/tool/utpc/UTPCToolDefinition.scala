package fmgp.yallmo.tool.utpc

import fmgp.yallmo.model.llm.JsonSchema
import fmgp.yallmo.tool.{FunctionKey, FunctionToolDefinition, ToolDefinition, ToolKey}

/** One host-configured gateway; remote tool names are deliberately not a model-selected capability. */
object UTPCToolDefinition extends ToolDefinition {
  val Execute: FunctionKey = FunctionKey.unsafe("utpc")
  val Definition: FunctionToolDefinition = FunctionToolDefinition(
    Execute,
    "Omit manual to list all manuals with their descriptions. Supply only manual (without agrs) to inspect a specific manual's full JSON, input JSON schema and expected input/output structures. Only after reading that manual, supply manual and agrs to call its API. Match the documented argument types; do not guess them.",
    JsonSchema.ObjectSchema(
      Map(
        "manual" -> JsonSchema.StringSchema(Some("The name of the manual.")),
        "agrs" -> JsonSchema.ObjectSchema(
          Map.empty,
          additionalProperties = true,
          description = Some(
            "Arguments matching the selected manual's input schema. Omit to read the manual; supply an object (including {} for a no-argument API) to execute. Values are not automatically coerced between arrays and strings."
          )
        )
      ),
      required = Set.empty,
      additionalProperties = false
    )
  )
  override val name: ToolKey = ToolKey.unsafe("utpc")
  override val functions: Set[FunctionToolDefinition] = Set(Definition)
}
