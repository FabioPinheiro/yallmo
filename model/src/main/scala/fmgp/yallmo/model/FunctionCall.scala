package fmgp.yallmo.model

import zio.json.JsonCodec

/** Stable link between one model-requested function call and its trusted result. */
opaque type FunctionCallId = String

object FunctionCallId {
  def from(value: String): Either[String, FunctionCallId] =
    Either.cond(value.trim.nonEmpty, value, "function call id must not be blank")

  def unsafe(value: String): FunctionCallId = value

  def value(id: FunctionCallId): String = id

  given JsonCodec[FunctionCallId] = JsonCodec.string.transformOrFail(FunctionCallId.from, FunctionCallId.value)
}

/** A function invocation proposed by the model. It is data until the trusted runner validates and executes it. */
final case class FunctionCall(
    id: FunctionCallId,
    name: String,
    argumentsJson: String,
    providerItemId: Option[String] = None
) derives JsonCodec {
  def validate: Either[String, FunctionCall] =
    Either.cond(name.trim.nonEmpty, this, "function call name must not be blank")
}

/** The trusted host's result for one [[FunctionCall]]. */
final case class FunctionCallOutput(
    callId: FunctionCallId,
    name: String,
    output: String
) derives JsonCodec {
  def validate: Either[String, FunctionCallOutput] =
    Either.cond(name.trim.nonEmpty, this, "function call output name must not be blank")
}
