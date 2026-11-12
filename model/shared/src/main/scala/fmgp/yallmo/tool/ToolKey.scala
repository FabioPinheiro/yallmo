package fmgp.yallmo.tool

import zio.json.{JsonCodec, JsonFieldDecoder, JsonFieldEncoder}

/** Stable identifier for a tool: a capability exposing one or more callable functions.
  *
  * A tool key is not a function name. Membership is declared explicitly by each tool's set of function definitions,
  * never inferred from naming conventions. Custom tools may use any non-blank key.
  */
opaque type ToolKey = String

object ToolKey {
  val Filesystem: ToolKey = "filesystem"
  val Bash: ToolKey = "bash"
  val RepositoryChanges: ToolKey = "repositoryChanges"
  val Scalamode: ToolKey = "scalamode"
  val Sdlc: ToolKey = "sdlc"
  val Coach: ToolKey = "coach"

  def from(value: String): Either[String, ToolKey] =
    Either.cond(value.trim.nonEmpty, value, "tool key must not be blank")

  /** For trusted identifiers; untrusted input must go through [[from]] or the JSON codecs. */
  def unsafe(value: String): ToolKey = value

  extension (key: ToolKey) def value: String = key

  given JsonCodec[ToolKey] = JsonCodec.string.transformOrFail(ToolKey.from, _.value)
  given JsonFieldEncoder[ToolKey] = JsonFieldEncoder.string.contramap(_.value)
  given JsonFieldDecoder[ToolKey] = JsonFieldDecoder.string.mapOrFail(ToolKey.from)
}
