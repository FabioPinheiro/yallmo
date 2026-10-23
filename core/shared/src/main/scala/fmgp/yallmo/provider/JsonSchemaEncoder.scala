package fmgp.yallmo.provider

import fmgp.yallmo.model.llm.JsonSchema

/** Encodes tool schemas into fresh JSON values for the provider wire dialects. */
private[provider] object JsonSchemaEncoder:
  def encode(value: JsonSchema): ujson.Obj =
    value match
      case JsonSchema.StringSchema(description, allowed) =>
        decorate(
          ujson.Obj("type" -> "string"),
          description,
          Option.when(allowed.nonEmpty)("enum" -> ujson.Arr.from(allowed))
        )
      case JsonSchema.NumberSchema(description)       => decorate(ujson.Obj("type" -> "number"), description)
      case JsonSchema.IntegerSchema(description)      => decorate(ujson.Obj("type" -> "integer"), description)
      case JsonSchema.BooleanSchema(description)      => decorate(ujson.Obj("type" -> "boolean"), description)
      case JsonSchema.ArraySchema(items, description) =>
        decorate(ujson.Obj("type" -> "array", "items" -> encode(items)), description)
      case JsonSchema.ObjectSchema(properties, required, additional, description) =>
        val result = decorate(
          ujson.Obj(
            "type" -> "object",
            "properties" -> ujson.Obj.from(properties.view.mapValues(encode).toMap),
            "additionalProperties" -> additional
          ),
          description
        )
        if required.nonEmpty then result("required") = ujson.Arr.from(required.toSeq.sorted)
        result

  private def decorate(
      value: ujson.Obj,
      description: Option[String],
      enumOption: Option[(String, ujson.Value)] = None
  ): ujson.Obj =
    description.foreach(value("description") = _)
    enumOption.foreach { case (key, option) => value(key) = option }
    value
