package fmgp.yallmo.view.web

import zio.json.*
import zio.json.ast.Json

/** Presentation of JSON tool results: scalar metadata stays compact; strings are decoded once, preserving newlines
  * without interpreting literal backslash sequences in paths or command output. Unknown fields are retained.
  */
object ToolResultContent {
  final case class Field(name: String, text: String)
  final case class Result(metadata: List[Field], blocks: List[Field])

  def parse(text: String): Option[Result] =
    text.fromJson[Json].toOption.flatMap {
      case Json.Obj(fields) if fields.nonEmpty =>
        val (metadata, blocks) = fields.toList.partition {
          case (_, Json.Num(_) | Json.Bool(_) | Json.Null) => true
          case _                                           => false
        }
        Some(Result(metadata.map(field), blocks.map(field)))
      case Json.Str(output) => Some(Result(Nil, List(Field("output", output))))
      case _                => None
    }

  private def field(entry: (String, Json)): Field = {
    val (name, value) = entry
    Field(
      name,
      value match {
        case Json.Str(text) => text
        case other          => other.toJsonPretty
      }
    )
  }
}
