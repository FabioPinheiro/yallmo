package fmgp.yallmo.orchestration.config

import zio.json.JsonCodec
import zio.json.ast.Json

/** Definition of one agent. Supported adapter types are `OAIAgent` and `ChatGPTAgent`.
  *
  * Both currently require exactly one matching provider. Model, prompt and generation options remain part of the
  * caller's request; ConfiguredAgentSettings decodes reusable native settings. Runtime adapters also enforce the tool
  * allowlist on fully assembled requests, independently of context construction.
  *
  * @param providerIds
  *   providers available to the program; empty is structurally valid, but current native adapters require exactly one
  * @param config
  *   native model/systemPrompt/thinkingLevel/parallelToolCalls settings used during request assembly
  * @param capabilities
  *   omitted fields inherit the application's available context; explicit empty allowlists disable capabilities
  */
final case class AgentConfig(
    agentType: String,
    providerIds: Seq[ProviderId] = Seq.empty,
    config: Json.Obj = Json.Obj(),
    capabilities: AgentCapabilities = AgentCapabilities()
) derives JsonCodec
