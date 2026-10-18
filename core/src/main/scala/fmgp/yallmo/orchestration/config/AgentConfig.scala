package fmgp.yallmo.orchestration.config

import zio.json.JsonCodec
import zio.json.ast.Json

/** Definition of one agent. Supported adapter types are `OAIAgent` and `ChatGPTAgent`.
  *
  * Both currently require exactly one matching provider. They forward native turn requests unchanged; model, prompt and
  * generation options remain part of the caller's request. The application interprets `config` to construct that
  * request: REPL startup reads model, systemPrompt, thinkingLevel and parallelToolCalls for its native runner.
  *
  * @param providerIds
  *   providers available to the program; empty is structurally valid, but current native adapters require exactly one
  * @param config
  *   program-specific settings interpreted by the application; native adapters still receive explicit turn requests
  */
final case class AgentConfig(
    agentType: String,
    providerIds: Seq[ProviderId] = Seq.empty,
    config: Json.Obj = Json.Obj()
) derives JsonCodec
