package fmgp.yallmo.tool

import zio.json.{JsonCodec, JsonFieldDecoder, JsonFieldEncoder}

/** Stable callable-function identifier, shared by definitions, invocations and capability policies.
  *
  * Tools declare their own function keys and membership sets; this companion only constructs and encodes identifiers.
  * Custom functions may use any non-blank key. Registration and authorization are checked separately by the runner. The
  * string value is preserved verbatim in configuration and persisted messages.
  */
opaque type FunctionKey = String

object FunctionKey {
  def from(value: String): Either[String, FunctionKey] =
    Either.cond(value.trim.nonEmpty, value, "function key must not be blank")

  /** For trusted identifiers; untrusted input must go through [[from]] or the JSON codecs. */
  def unsafe(value: String): FunctionKey = value

  extension (key: FunctionKey) def value: String = key

  given JsonCodec[FunctionKey] = JsonCodec.string.transformOrFail(FunctionKey.from, _.value)
  given JsonFieldEncoder[FunctionKey] = JsonFieldEncoder.string.contramap(_.value)
  given JsonFieldDecoder[FunctionKey] = JsonFieldDecoder.string.mapOrFail(FunctionKey.from)
}
