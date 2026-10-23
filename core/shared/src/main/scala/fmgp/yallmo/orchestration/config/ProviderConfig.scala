package fmgp.yallmo.orchestration.config

import zio.json.{JsonCodec, JsonFieldDecoder, JsonFieldEncoder, jsonDiscriminator, jsonHint}

opaque type ProviderId = String

object ProviderId {
  def from(value: String): Either[String, ProviderId] =
    Either.cond(value.trim.nonEmpty, value, "provider configuration id must not be blank")

  def unsafe(value: String): ProviderId = value

  def value(id: ProviderId): String = id

  given JsonCodec[ProviderId] = JsonCodec.string.transformOrFail(from, value)
  given JsonFieldEncoder[ProviderId] = JsonFieldEncoder.string.contramap(value)
  given JsonFieldDecoder[ProviderId] = JsonFieldDecoder.string.mapOrFail(from)
}

/** A named connection/account configuration, shared independently of agent identity.
  *
  * JSON uses a `providerType` discriminator. Decoding is inert; RuntimeInstances initializes the connection on first
  * lookup.
  */
@jsonDiscriminator("providerType")
sealed trait ProviderConfig derives JsonCodec

/** Literal OAuth credentials stored in private configuration; no environment lookup is performed.
  *
  * @param expiresAtMillis
  *   expiry as Unix milliseconds; expired values do not imply a usable authenticated profile
  *
  * JSON serialization intentionally includes secrets for persistence. Do not log serialized configuration.
  */
final case class OAuthCredentialsConfig(
    accessToken: String,
    refreshToken: String,
    expiresAtMillis: Long
) derives JsonCodec {
  override def toString: String = "OAuthCredentialsConfig(<redacted>)"
}

object ProviderConfig {

  /** Compatible Chat Completions connection; endpoint is the base URL, not the final completion route. */
  @jsonHint("OAI")
  final case class OAI(
      endpoint: String
  ) extends ProviderConfig derives JsonCodec

  /** ChatGPT subscription account. Missing credentials means logged out, not an invalid catalog.
    *
    * @param endpoint
    *   optional explicit subscription URL; only the adapter's trusted default URL is accepted, preventing credential
    *   forwarding to an unintended host or paid platform API
    */
  @jsonHint("ChatGPT")
  final case class ChatGPT(
      credentials: Option[OAuthCredentialsConfig] = None,
      endpoint: Option[String] = None
  ) extends ProviderConfig derives JsonCodec
}
