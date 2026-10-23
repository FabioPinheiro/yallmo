package fmgp.yallmo.view.web

import zio.json.*
import zio.json.ast.Json

/** Presentation only: preserve invalid JSON and ordinary transcript text exactly as received. */
object MessageText {
  def format(role: String, text: String): String =
    if role == "tool_call" || role == "tool_result" then text.fromJson[Json].map(_.toJsonPretty).getOrElse(text)
    else text

  def isLong(text: String): Boolean =
    text.length > 1200 || text.linesIterator.take(17).size > 16
}
