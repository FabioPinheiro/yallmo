package fmgp.yallmo.orchestration.config

import fmgp.yallmo.model.AgentId
import zio.json.JsonCodec

/** Immutable configuration data. Decoding, copying and serialization never initialize runtime resources.
  *
  * RuntimeInstances owns agent/provider construction, caches and concurrency for a chosen configuration snapshot.
  * Catalogs use IDs as JSON object keys; provider definitions do not repeat those IDs. JSON serialization includes
  * configured credentials; never log the serialized configuration.
  *
  * @param defaultAgentId
  *   optional startup preference, not the currently selected agent
  * @param browserAudioPort
  *   browser microphone/TTS HTTP port (1–65535); missing fields in older files default to 5180
  */
final case class Config(
    userName: String,
    defaultAgentId: Option[AgentId] = None,
    agents: Map[AgentId, AgentConfig] = Map.empty,
    providers: Map[ProviderId, ProviderConfig] = Map.empty,
    browserAudioPort: Int = 5180,
) derives JsonCodec
