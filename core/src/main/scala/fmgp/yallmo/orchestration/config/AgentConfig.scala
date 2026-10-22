package fmgp.yallmo.orchestration.config

import zio.json.JsonCodec
import zio.json.ast.Json

/** Definition of one agent: OAIAgent, ChatGPTAgent or FolderStructureAgent.
  *
  * Native adapters require exactly one matching provider; FolderStructureAgent requires filesystem.root and uses no
  * provider. Model, prompt and generation options remain part of the native caller's request; ConfiguredAgentSettings
  * decodes reusable native settings. Runtime adapters also enforce the tool allowlist on fully assembled requests,
  * independently of context construction.
  *
  * @param providerIds
  *   providers available to the program; empty is structurally valid, but current native adapters require exactly one
  * @param config
  *   native model/systemPrompt/thinkingLevel/parallelToolCalls settings used during request assembly
  * @param capabilities
  *   omitted fields inherit the application's available context; explicit empty allowlists disable capabilities
  * @param filesystem
  *   optional trusted root configuration; decoding does not open or grant a filesystem capability
  */
final case class AgentConfig(
    agentType: String,
    providerIds: Seq[ProviderId] = Seq.empty,
    config: Json.Obj = Json.Obj(),
    capabilities: AgentCapabilities = AgentCapabilities(),
    filesystem: Option[FileSystemConfig] = None
) derives JsonCodec

/** Trusted root for an agent's filesystem. Relative roots use the process working directory, not the config file's
  * directory. Loading this data neither checks that the directory exists nor creates it. Runtime setup resolves root
  * symlinks to an existing absolute path before opening the filesystem; links within that workspace remain forbidden.
  */
final case class FileSystemConfig(root: String) derives JsonCodec
