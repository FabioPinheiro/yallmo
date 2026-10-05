package fmgp.yallmo.model.llm

import zio.Chunk

/** Provider-neutral JSON Schema subset used to describe function-tool inputs. */
enum JsonSchema {
  case StringSchema(description: Option[String] = None, allowedValues: Chunk[String] = Chunk.empty)
  case NumberSchema(description: Option[String] = None)
  case IntegerSchema(description: Option[String] = None)
  case BooleanSchema(description: Option[String] = None)
  case ArraySchema(items: JsonSchema, description: Option[String] = None)
  case ObjectSchema(
      properties: Map[String, JsonSchema],
      required: Set[String] = Set.empty,
      additionalProperties: Boolean = false,
      description: Option[String] = None
  )
}

object JsonSchema {
  def validate(schema: JsonSchema): Either[String, JsonSchema] =
    schema match
      case JsonSchema.StringSchema(_, values) =>
        Either.cond(values.forall(_.nonEmpty), schema, "string enum values must not be empty")
      case JsonSchema.ArraySchema(items, _)                    => validate(items).map(_ => schema)
      case JsonSchema.ObjectSchema(properties, required, _, _) =>
        val invalidRequired = required.diff(properties.keySet)
        if properties.keys.exists(_.trim.isEmpty) then Left("object schema property names must not be blank")
        else if invalidRequired.nonEmpty then
          Left(s"required properties are not declared: ${invalidRequired.mkString(", ")}")
        else
          properties.values
            .foldLeft[Either[String, Unit]](Right(()))((result, value) =>
              result.flatMap(_ => validate(value).map(_ => ()))
            )
            .map(_ => schema)
      case _ => Right(schema)
}
