package fmgp.yallmo.model

import zio.json.{JsonCodec, JsonFieldDecoder, JsonFieldEncoder}

/** Stable tool identifier, shared by definitions, invocations and capability policies.
  *
  * Built-in keys are centralized here, but custom tools may use any non-blank key. Registration and authorization are
  * checked separately by the runner. The string value is preserved verbatim in configuration and persisted messages.
  */
opaque type ToolKey = String

object ToolKey {
  val ReadFile: ToolKey = "readFile"
  val ListFiles: ToolKey = "listFiles"
  val Bash: ToolKey = "bash"
  val RepositoryChanges: ToolKey = "repositoryChanges"
  val Scalamode: ToolKey = "scalamode"
  val ScalaUnsafe: ToolKey = "scalaunsafe"
  val CoachListApplications: ToolKey = "coach_list_applications"
  val CoachGetApplication: ToolKey = "coach_get_application"
  val CoachSaveApplication: ToolKey = "coach_save_application"

  def from(value: String): Either[String, ToolKey] =
    Either.cond(value.trim.nonEmpty, value, "tool key must not be blank")

  /** For trusted identifiers; untrusted input must go through [[from]] or the JSON codecs. */
  def unsafe(value: String): ToolKey = value

  extension (key: ToolKey) def value: String = key

  given JsonCodec[ToolKey] = JsonCodec.string.transformOrFail(ToolKey.from, _.value)
  given JsonFieldEncoder[ToolKey] = JsonFieldEncoder.string.contramap(_.value)
  given JsonFieldDecoder[ToolKey] = JsonFieldDecoder.string.mapOrFail(ToolKey.from)
}
