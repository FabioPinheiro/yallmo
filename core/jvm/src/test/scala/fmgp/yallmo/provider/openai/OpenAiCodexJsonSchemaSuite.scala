package fmgp.yallmo.provider.openai

import fmgp.yallmo.model.FunctionToolDefinition
import fmgp.yallmo.provider.JsonSchemaCharacterizationSuite

final class OpenAiCodexJsonSchemaSuite extends JsonSchemaCharacterizationSuite:
  protected def tool(definition: FunctionToolDefinition): ujson.Obj = OpenAiCodexTurnMapper.tool(definition)
  protected def parameters(value: ujson.Value): ujson.Value = value("parameters")
  protected def expectedTool(parameters: ujson.Value): ujson.Obj =
    ujson.Obj(
      "type" -> "function",
      "name" -> "inspect",
      "description" -> "Inspect inputs",
      "parameters" -> parameters,
      "strict" -> false
    )
