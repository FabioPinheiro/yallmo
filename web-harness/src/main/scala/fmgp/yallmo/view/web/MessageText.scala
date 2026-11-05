package fmgp.yallmo.view.web

import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import zio.json.*
import zio.json.ast.Json

/** Presentation only: preserve invalid JSON and ordinary transcript text exactly as received. */
object MessageText {
  private val timestampFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

  def formatTimestamp(timestamp: Instant): String = timestampFormat.format(timestamp)

  def startsCollapsed(role: String, text: String): Boolean =
    role == "tool_call" || role == "tool_result" || isLong(text)

  def format(role: String, text: String): String =
    if role == "tool_call" || role == "tool_result" then text.fromJson[Json].map(_.toJsonPretty).getOrElse(text)
    else text

  /** Speech text is deliberately verbatim and independent of visual JSON formatting. Keep text preparation separate
    * from playback so a future speech-ready summary can use the same player.
    */
  def forSpeech(text: String): String = text

  def isLong(text: String): Boolean =
    text.length > 1200 || text.linesIterator.take(17).size > 16
}
