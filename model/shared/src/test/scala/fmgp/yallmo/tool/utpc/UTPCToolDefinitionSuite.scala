package fmgp.yallmo.tool.utpc

import fmgp.yallmo.model.llm.JsonSchema
import munit.FunSuite

final class UTPCToolDefinitionSuite extends FunSuite {
  test("utpc exposes exactly the optional manual/agrs schema without a remote selector") {
    val definition = UTPCToolDefinition.Definition
    assertEquals(definition.name.value, "utpc")
    assert(definition.description.contains("Omit manual to list all manuals with their descriptions"))
    assert(definition.description.contains("full JSON, input JSON schema and expected input/output structures"))
    assert(definition.description.contains("Only after reading that manual"))
    assert(definition.description.contains("Match the documented argument types"))
    assertEquals(
      definition.inputSchema,
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
    assertEquals(UTPCToolDefinition.functions, Set(definition))
    assertEquals(UTPCToolDefinition.name.value, "utpc")
    assert(definition.validate.isRight)
  }
}
