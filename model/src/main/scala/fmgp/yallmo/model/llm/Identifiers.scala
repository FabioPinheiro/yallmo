package fmgp.yallmo.model.llm

import scala.annotation.targetName
import zio.json.JsonCodec

/** Identifies the provider adapter that owns provider-specific opaque state. */
opaque type ProviderId = String

object ProviderId {
  def from(value: String): Either[String, ProviderId] =
    Either.cond(value.trim.nonEmpty, value, "provider id must not be blank")

  def unsafe(value: String): ProviderId = value

  def value(id: ProviderId): String = id

  given JsonCodec[ProviderId] = JsonCodec.string.transformOrFail(ProviderId.from, ProviderId.value)
}

extension (id: ProviderId) {
  @targetName("providerIdValue")
  def value: String = id
}
