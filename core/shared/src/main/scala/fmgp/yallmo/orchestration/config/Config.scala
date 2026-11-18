package fmgp.yallmo.orchestration.config

import fmgp.yallmo.model.{AgentId, User, UserId}
import zio.json.*

/** Immutable configuration data. Decoding, copying and serialization never initialize runtime resources.
  *
  * AgentCatalog owns agent/provider construction, caches and concurrency for a chosen configuration snapshot. Catalogs
  * use IDs as JSON object keys; provider definitions do not repeat those IDs. JSON serialization includes configured
  * credentials; never log the serialized configuration.
  *
  * @param browserAudioPort
  *   browser microphone/TTS HTTP port (1–65535); missing fields in older files default to 5180
  */
final case class Config(
    agents: Map[AgentId, AgentConfig] = Map.empty,
    providers: Map[ProviderId, ProviderConfig] = Map.empty,
    browserAudioPort: Int = 5180,
    users: Map[UserId, User] = Map.empty,
)

object Config {
  @jsonNoExtraFields
  private final case class Credential(secretHash: String) derives JsonCodec

  given JsonCodec[Config] = {
    // Only config users omit id: the mapping key is the login identity, not a duplicate credential field.
    val usersCodec = summon[JsonCodec[Map[UserId, Credential]]].transform[Map[UserId, User]](
      _.map { case (id, credential) => id -> User(id, credential.secretHash) },
      _.map { case (name, user) => name -> Credential(user.secretHash) }
    )
    given JsonDecoder[Map[UserId, User]] = usersCodec.decoder
    given JsonEncoder[Map[UserId, User]] = usersCodec.encoder
    DeriveJsonCodec.gen[Config]
  }
}
