package fmgp.yallmo.tool

import fmgp.yallmo.model.llm.JsonSchema
import zio.json.*
import zio.json.ast.Json

/** A callable function exposed to a model; tool membership stays in the host-side tool catalog. */
final case class FunctionToolDefinition(
    name: FunctionKey,
    description: String,
    inputSchema: JsonSchema.ObjectSchema = JsonSchema.ObjectSchema(Map.empty, additionalProperties = true)
) {
  def validate: Either[String, FunctionToolDefinition] =
    for {
      _ <- Either.cond(name.value.trim.nonEmpty, (), "function tool name must not be blank")
      _ <- Either.cond(description.trim.nonEmpty, (), s"function tool '$name' description must not be blank")
      _ <- JsonSchema.validate(inputSchema)
    } yield this
}

/** Validates untrusted provider JSON arguments against the schema advertised to that provider. */
private[yallmo] object FunctionToolDefinition {
  def validateArgument(
      argumentsJson: String,
      schema: JsonSchema.ObjectSchema
  ): Either[String, Unit] =
    argumentsJson.fromJson[Json].flatMap(value => validateAt(value, schema, "$"))

  private def validateAt(
      value: Json,
      schema: JsonSchema,
      path: String
  ): Either[String, Unit] =
    (schema, value) match
      case (JsonSchema.StringSchema(_, allowedValues), Json.Str(string)) =>
        Either.cond(
          allowedValues.isEmpty || allowedValues.contains(string),
          (),
          s"$path must be one of ${allowedValues.mkString(", ")}"
        )
      case (JsonSchema.NumberSchema(_), Json.Num(_))       => Right(())
      case (JsonSchema.IntegerSchema(_), Json.Num(number)) =>
        Either.cond(number.stripTrailingZeros.scale <= 0, (), s"$path must be an integer")
      case (JsonSchema.BooleanSchema(_), Json.Bool(_))          => Right(())
      case (JsonSchema.ArraySchema(items, _), Json.Arr(values)) =>
        values.zipWithIndex.foldLeft[Either[String, Unit]](Right(())) { case (result, (item, index)) =>
          result.flatMap(_ => validateAt(item, items, s"$path[$index]"))
        }
      case (JsonSchema.ObjectSchema(properties, required, additionalProperties, _), Json.Obj(entries)) =>
        val fields = entries.toMap
        val missing = required.diff(fields.keySet)
        val unexpected = fields.keySet.diff(properties.keySet)
        if missing.nonEmpty then Left(s"$path is missing required properties: ${missing.toSeq.sorted.mkString(", ")}")
        else if !additionalProperties && unexpected.nonEmpty then
          Left(s"$path contains unsupported properties: ${unexpected.toSeq.sorted.mkString(", ")}")
        else
          properties.foldLeft[Either[String, Unit]](Right(())) { case (result, (name, propertySchema)) =>
            result.flatMap(_ => fields.get(name).fold(Right(()))(validateAt(_, propertySchema, s"$path.$name")))
          }
      case (JsonSchema.StringSchema(_, _), _)       => Left(s"$path must be a string")
      case (JsonSchema.NumberSchema(_), _)          => Left(s"$path must be a number")
      case (JsonSchema.IntegerSchema(_), _)         => Left(s"$path must be an integer")
      case (JsonSchema.BooleanSchema(_), _)         => Left(s"$path must be a boolean")
      case (JsonSchema.ArraySchema(_, _), _)        => Left(s"$path must be an array")
      case (JsonSchema.ObjectSchema(_, _, _, _), _) => Left(s"$path must be an object")
}
