package fmgp.yallmo.model

import fmgp.yallmo.model.llm.JsonSchema

/** A callable function exposed to a model. */
final case class FunctionToolDefinition(
    name: String,
    description: String,
    inputSchema: JsonSchema.ObjectSchema
) {
  def validate: Either[String, FunctionToolDefinition] =
    for {
      _ <- Either.cond(name.trim.nonEmpty, (), "function tool name must not be blank")
      _ <- Either.cond(description.trim.nonEmpty, (), s"function tool '$name' description must not be blank")
      _ <- JsonSchema.validate(inputSchema)
    } yield this
}

/** Tells a provider whether and how the model may select a function tool. */
enum ToolChoice {
  case Auto
  case None
  case Required
  case Function(name: String)
}
