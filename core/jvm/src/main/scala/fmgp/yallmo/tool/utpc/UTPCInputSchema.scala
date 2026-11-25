package fmgp.yallmo.tool.utpc

import zio.json.ast.Json
import scala.util.Try

/** Deliberately small input-schema subset. Unknown keywords fail closed, including in unused properties. Output schemas
  * are inert manual data, not interpreted here. Trusted JVM patterns use search semantics; patterned inputs are limited
  * to 1,024 characters and regex stack exhaustion is reported as a validation error.
  */
private[utpc] object UTPCInputSchema {
  private val keywords = Set(
    "type",
    "properties",
    "required",
    "additionalProperties",
    "minimum",
    "maximum",
    "pattern",
    "description",
    "title"
  )
  private val types = Set("object", "string", "number", "integer", "boolean", "null")
  private val MaxPatternInputChars = 1024

  private def validPattern(pattern: String): Boolean =
    try pattern.length <= 2048 && Try(pattern.r).isSuccess
    catch { case _: StackOverflowError => false }

  private def validatePattern(pattern: String, text: String): Either[String, Unit] =
    if text.length > MaxPatternInputChars then Left("Patterned string exceeds the 1,024-character limit")
    else
      try {
        Try(pattern.r.findFirstIn(text).nonEmpty).toEither.left
          .map(_ => "Cannot evaluate input string pattern")
          .flatMap(matches => Either.cond(matches, (), "Arguments violate string pattern"))
      } catch { case _: StackOverflowError => Left("Input string pattern exceeded the regex stack limit") }

  def check(schema: Json, depth: Int = 0): Either[String, Unit] = {
    def invalid = Left("Unsupported or malformed UTCP input schema")
    if depth > 16 then invalid
    else
      schema match {
        case Json.Bool(_)      => Right(())
        case Json.Obj(entries) =>
          val fields = entries.toMap
          if fields.size != entries.size || !fields.keySet.subsetOf(keywords) then invalid
          else
            fields.foldLeft[Either[String, Unit]](Right(())) { case (result, (key, value)) =>
              result.flatMap { _ =>
                key match {
                  case "type" =>
                    value match {
                      case Json.Str(kind) if types.contains(kind) => Right(())
                      case _                                      => invalid
                    }
                  case "properties" =>
                    value match {
                      case Json.Obj(properties) if properties.map(_._1).distinct.size == properties.size =>
                        properties.foldLeft[Either[String, Unit]](Right(())) { case (r, (_, child)) =>
                          r.flatMap(_ => check(child, depth + 1))
                        }
                      case _ => invalid
                    }
                  case "required" =>
                    value match {
                      case Json.Arr(values)
                          if values.forall(_.isInstanceOf[Json.Str]) && values.distinct.size == values.size =>
                        Right(())
                      case _ => invalid
                    }
                  case "additionalProperties" => check(value, depth + 1)
                  case "minimum" | "maximum"  =>
                    value match {
                      case Json.Num(_) => Right(())
                      case _           => invalid
                    }
                  case "pattern" =>
                    value match {
                      case Json.Str(pattern) if validPattern(pattern) => Right(())
                      case _                                          => invalid
                    }
                  case "description" | "title" =>
                    value match {
                      case Json.Str(_) => Right(())
                      case _           => invalid
                    }
                  case _ => invalid
                }
              }
            }
        case _ => invalid
      }
  }

  def validate(schema: Json, value: Json): Either[String, Unit] =
    check(schema).flatMap(_ => validateAt(schema, value, 0, "agrs"))

  private def valueType(value: Json): String = value match {
    case _: Json.Obj  => "object"
    case _: Json.Arr  => "array"
    case _: Json.Str  => "string"
    case _: Json.Num  => "number"
    case _: Json.Bool => "boolean"
    case Json.Null    => "null"
  }

  private def validateAt(schema: Json, value: Json, depth: Int, path: String): Either[String, Unit] = {
    def invalid = Left(s"$path does not satisfy the manual input schema")
    if depth > 32 then invalid
    else
      schema match {
        case Json.Bool(true)   => Right(())
        case Json.Bool(false)  => invalid
        case Json.Obj(entries) =>
          val fields = entries.toMap
          val correctType = fields.get("type").forall {
            case Json.Str("object")  => value.isInstanceOf[Json.Obj]
            case Json.Str("string")  => value.isInstanceOf[Json.Str]
            case Json.Str("number")  => value.isInstanceOf[Json.Num]
            case Json.Str("integer") =>
              value match {
                case Json.Num(n) => n.stripTrailingZeros.scale <= 0
                case _           => false
              }
            case Json.Str("boolean") => value.isInstanceOf[Json.Bool]
            case Json.Str("null")    => value == Json.Null
            case _                   => false
          }
          if !correctType then {
            val expected = fields.get("type").collect { case Json.Str(kind) => kind }.getOrElse("a supported type")
            Left(s"$path: expected $expected, received ${valueType(value)}")
          } else
            value match {
              case Json.Obj(values) =>
                val properties = fields.get("properties").collect { case Json.Obj(p) => p.toMap }.getOrElse(Map.empty)
                val required = fields
                  .get("required")
                  .collect { case Json.Arr(r) => r.collect { case Json.Str(s) => s }.toSet }
                  .getOrElse(Set.empty)
                if values.map(_._1).distinct.size != values.size || !required.subsetOf(values.map(_._1).toSet) then
                  invalid
                else
                  values.foldLeft[Either[String, Unit]](Right(())) { case (result, (key, item)) =>
                    result.flatMap(_ =>
                      validateAt(
                        properties.getOrElse(key, fields.getOrElse("additionalProperties", Json.Bool(true))),
                        item,
                        depth + 1,
                        if properties.contains(key) && key.matches("[A-Za-z_][A-Za-z0-9_]*") then s"$path.$key"
                        else s"$path.[additional property]"
                      )
                    )
                  }
              case Json.Num(n) =>
                val min = fields.get("minimum").collect { case Json.Num(v) => v }
                val max = fields.get("maximum").collect { case Json.Num(v) => v }
                Either.cond(
                  min.forall(n.compareTo(_) >= 0) && max.forall(n.compareTo(_) <= 0),
                  (),
                  s"$path violates numeric bounds"
                )
              case Json.Str(text) =>
                fields.get("pattern") match {
                  case Some(Json.Str(pattern)) => validatePattern(pattern, text).left.map(reason => s"$path: $reason")
                  case _                       => Right(())
                }
              case _ => Right(())
            }
        case _ => invalid
      }
  }
}
