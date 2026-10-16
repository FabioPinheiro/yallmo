package fmgp.yallmo.provider.oai

import fmgp.yallmo.model.FunctionToolDefinition
import fmgp.yallmo.provider.JsonSchemaCharacterizationSuite

final class OAIJsonSchemaSuite extends JsonSchemaCharacterizationSuite:
  protected def tool(definition: FunctionToolDefinition): ujson.Obj = OAIChatCompletionsMapper.turnTool(definition)
  protected def parameters(value: ujson.Value): ujson.Value = value("function")("parameters")
  protected def expectedTool(parameters: ujson.Value): ujson.Obj =
    ujson.Obj(
      "type" -> "function",
      "function" -> ujson.Obj("name" -> "inspect", "description" -> "Inspect inputs", "parameters" -> parameters)
    )
