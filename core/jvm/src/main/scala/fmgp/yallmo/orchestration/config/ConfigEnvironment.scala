package fmgp.yallmo.orchestration.config

import fmgp.yallmo.model.YallmoError
import zio.Chunk
import zio.json.ast.Json

/** Single-pass environment substitution on parsed string values, never keys or serialized JSON/YAML.
  *
  * Environment values stay strings and cannot inject configuration structure. `$${NAME}` escapes a reference;
  * replacement values are never expanded recursively. Callers retain the original tree for persistence.
  */
private[config] object ConfigEnvironment {
  private val references = "\\$\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}|\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}".r

  def resolve(value: Json, environment: Map[String, String]): Either[YallmoError, Json] = value match {
    case Json.Str(text) => expand(text, environment).map(Json.Str(_))
    case obj: Json.Obj  =>
      obj.fields
        .foldLeft[Either[YallmoError, Chunk[(String, Json)]]](Right(Chunk.empty)) { case (result, (key, item)) =>
          for {
            fields <- result
            resolved <- resolve(item, environment)
          } yield fields :+ (key -> resolved)
        }
        .map(Json.Obj(_))
    case arr: Json.Arr =>
      arr.elements
        .foldLeft[Either[YallmoError, Chunk[Json]]](Right(Chunk.empty)) { (result, item) =>
          for {
            elements <- result
            resolved <- resolve(item, environment)
          } yield elements :+ resolved
        }
        .map(Json.Arr(_))
    case other => Right(other)
  }

  private def expand(text: String, environment: Map[String, String]): Either[YallmoError, String] = {
    val output = new StringBuilder
    val end = references.findAllMatchIn(text).foldLeft[Either[YallmoError, Int]](Right(0)) { (result, reference) =>
      for {
        previous <- result
        replacement <- Option(reference.group(1)) match {
          case Some(escaped) => Right("${" + escaped + "}")
          case None          =>
            val name = reference.group(2)
            environment
              .get(name)
              .toRight(
                YallmoError.InvalidConfig(s"Missing environment variable $name referenced by configuration")
              )
        }
      } yield {
        output.append(text.substring(previous, reference.start))
        output.append(replacement)
        reference.end
      }
    }
    end.map(previous => output.append(text.substring(previous)).result())
  }
}
