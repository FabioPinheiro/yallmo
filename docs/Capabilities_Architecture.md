# Capabilities architecture

This document describes the implemented configuration and enforcement flow, not a future permission system.

The central rule is: **configuration restricts what trusted application code makes available; the model may propose actions, but does not authorize or execute them.** Prompt instructions alone are not an authorization boundary.

## 1. Three separate concerns

| Concern | Examples | Owner |
| --- | --- | --- |
| Agent settings | Model, system prompt, thinking level, parallel tool calls | `AgentConfig.config`, decoded by `ConfiguredAgentSettings`; used by the application/program to assemble requests |
| Capability authorization | Which tools, retrievers, skills and context extensions an agent may use | `AgentCapabilities`, configured adapters, native programs and `AgentRunner` |
| Adapter/model support | Whether a request can encode audio or use a particular provider protocol | Concrete agent/provider adapter; actual endpoint/model acceptance may only be known at invocation |

Core settings decoding does not supply an assistant persona. `ConfiguredAgentSettings.decode(definition)` requires a configured prompt; callers may instead pass `defaultSystemPrompt = Some(applicationPrompt)` for an absent prompt. An explicitly blank prompt remains invalid, even when a fallback is supplied. The REPL owns its terminal fallback in `ReplStartup.DefaultSystemPrompt`; its transcription path supplies no fallback and therefore requires an explicit configured prompt.

An audio-capable agent is not automatically a transcription agent. An agent using a tool-capable model is not automatically authorized to execute tools. `parallelToolCalls = true` does not grant tools or make runner action execution concurrent.

## 2. Configuration structure

Capabilities belong to an agent definition, alongside—not inside—its model/prompt `config` object. For example, this **agent-definition fragment** permits two tool IDs and disables the other three capability categories:

```json
{
  "agentType": "OAIAgent",
  "providerIds": ["local"],
  "config": {
    "model": "configured-model",
    "systemPrompt": "Inspect the workspace and explain your findings.",
    "parallelToolCalls": false
  },
  "capabilities": {
    "tools": ["readFile", "listFiles"],
    "retrievers": [],
    "skills": [],
    "extensions": []
  }
}
```

A complete configuration also needs the agent's map key and a matching provider definition. Tool IDs must exist in the application's available catalog; the example does not install tools or configure an endpoint.

`AgentCapabilities` represents each field as `Option[Set[String]]`:

| JSON form | Representation | Meaning |
| --- | --- | --- |
| Missing `capabilities`, or `"capabilities": {}` | All fields `None` | Inherit the application's existing setup for each category |
| Field omitted from a partial object | That field is `None` | Inherit that category only |
| `"tools": []` | `Some(Set.empty)` | Disable tools; do not inherit them |
| `"tools": ["readFile"]` | `Some(Set("readFile"))` | Permit only that ID from the available tools |

These semantics apply independently to tools, retrievers, skills and extensions. In particular, `{"tools": []}` does **not** disable inherited retrievers, skills or extensions.

IDs match catalog `.key` values exactly. Native tool definitions use the same key as their function `name`. Allowlists are sets, not execution-order declarations: filtering preserves the application's catalog order. An allowlist cannot create a missing capability or provide its implementation.

### Available definitions versus executable implementations

`StaticContext[R]` contains model-facing tool/retriever definitions, skill instructions and extension instances. The runner separately holds maps of executable `AgentTool[R]` and `KnowledgeRetriever[R]` implementations.

A tool or retriever must be both authorized in the effective context and backed by a registered implementation before it can run. Merely including it in configuration or a model response is insufficient.

## 3. Ownership and source map

All source paths below are relative to the repository root.

| Component | Responsibility |
| --- | --- |
| [`AgentCapabilities.scala`](../core/src/main/scala/fmgp/yallmo/orchestration/config/AgentCapabilities.scala) | Decode optional allowlists; validate IDs and filter context (`resolve`); intersect restrictions (`intersect`); filter assembled tool requests (`filterRequest`) |
| [`ConfigFile.scala`](../core/src/main/scala/fmgp/yallmo/orchestration/config/ConfigFile.scala) | Validate configuration references and, when catalogs are supplied, capability IDs for all configured agents |
| [`RuntimeInstances.scala`](../core/src/main/scala/fmgp/yallmo/orchestration/RuntimeInstances.scala) | Lazily resolve/cache ordinary agents and providers; attach configured policy; provide generic checked audio-agent lookup |
| [`ConfiguredAgent.scala`](../core/src/main/scala/fmgp/yallmo/orchestration/ConfiguredAgent.scala) | Apply tool restrictions even to fully assembled direct requests; reject prohibited tool-call responses |
| [`OAIAgent.scala`](../core/src/main/scala/fmgp/yallmo/provider/oai/OAIAgent.scala), [`ChatGPTAgent.scala`](../core/src/main/scala/fmgp/yallmo/provider/openai/ChatGPTAgent.scala) | Own agent identity, `turn`, canonical request/response mapping through protocol mappers, and turn diagnostics |
| [`OAILlmProvider.scala`](../core/src/main/scala/fmgp/yallmo/provider/oai/OAILlmProvider.scala), [`OpenAiCodexClient.scala`](../core/src/main/scala/fmgp/yallmo/provider/openai/OpenAiCodexClient.scala) | Own provider inference/HTTP completion and provider-specific credentials; neither is an `Agent` or exposes `turn` |
| [`AgentInstance.scala`](../core/src/main/scala/fmgp/yallmo/orchestration/AgentInstance.scala) | Define the program-side policy and `configuredContext` hook used by the runner |
| [`NativeFunctionAgent.scala`](../core/src/main/scala/fmgp/yallmo/orchestration/NativeFunctionAgent.scala) | Combine program/adapter policies; assemble native requests from filtered context; decode responses into proposed actions |
| [`AgentRunner.scala`](../core/src/main/scala/fmgp/yallmo/orchestration/AgentRunner.scala) | Resolve context before extensions; authorize actions and validate tool arguments before executing implementations |
| [`ToolApproval.scala`](../core/src/main/scala/fmgp/yallmo/orchestration/ToolApproval.scala) | Optional additional approval guard around a tool implementation |
| [`ReplStartup.scala`](../repl/src/main/scala/fmgp/yallmo/repl/ReplStartup.scala) | Supply REPL catalogs, validate policies, bind selected agents, and enforce REPL-specific voice requirements |

`OpenAiCodexClient` retains its existing name but is a provider connection, not a second agent. `ChatGPTAgent` validates/maps native requests before asking that connection to complete a wire request; OAuth credentials, refresh coordination, HTTP headers, SSE decoding, and transport timeouts remain in the connection. Named agents sharing one connection therefore share account state, not agent identity. Native request-body mapping lives in `OpenAiCodexTurnMapper`, not in the provider connection.

`HasAgentCapabilities` carries the runtime adapter's policy into a bound or environment-resolved native program. The configured wrapper preserves the `AudioAgent` capability marker. When all policy fields are omitted, `ConfiguredAgent.apply` returns the original adapter unchanged.

## 4. Startup and resolution flow

```text
Private configuration file
  -> ConfigFile.loadOrInitialize / validate
       structural fields and agent/provider references
  -> Application constructs complete available capability catalogs
  -> ConfigFile.validateCapabilities(config, availableContext)
       validate all configured agents, including unselected ones
  -> RuntimeInstances.getAgent(agentId), when needed
       initialize/cache adapter and provider locally
       attach configured restrictions
  -> Bind adapter to the selected native program
```

Loading/lookup does not perform inference or authenticate against an endpoint. Settings decoding and capability validation are also local operations. A registry uses an immutable configuration snapshot; editing the file does not change already-created adapters or policies automatically.

Capability validation needs the application's **complete, unfiltered available context**. `ConfigFile.validate` alone cannot check tool/skill/etc. IDs because the configuration file does not define those catalogs. The REPL supplies them in `ReplStartup.application` after tool availability evaluation.

`resolve` rejects unknown IDs with `InvalidConfig`, naming the agent, capability field and unknown IDs. Do not prefilter the catalog by an agent's policy before validating it: that can confuse a denied capability with a nonexistent one. A capability installed in code but unavailable in the current application's catalog is not usable by naming it in an allowlist.

The current REPL supplies available tools; its base retriever, skill and extension catalogs are empty. The generic core policy supports all four categories for applications that supply them. `/tools` reports application-level availability, not necessarily a selected agent's effective permissions.

## 5. Combining program and configured restrictions

`NativeFunctionAgent` can have its own `capabilities` in addition to the runtime adapter's configured policy. Neither can widen the other:

| Program field | Adapter field | Effective field |
| --- | --- | --- |
| Omitted | Omitted | Inherit the application's available category |
| Explicit set | Omitted | Program set |
| Omitted | Explicit set | Adapter set |
| Explicit set A | Explicit set B | Intersection of A and B |

An explicit empty set therefore wins over any broader set. For example, if the adapter permits `readFile` and `listFiles`, but the program permits only `readFile`, the effective context contains only `readFile`.

Both original policies are validated against the available catalog **before** applying their intersection. A typo in the adapter configuration is not hidden just because the program's empty allowlist would remove it.

This works for both `program.bind(adapter)` and native programs resolving `Agent[Any]` from the ZIO environment.

## 6. Conversational turn and action flow

```text
AgentRunner.runWithContext
  -> agent.configuredContext(availableContext)
       validate and filter policy; compute effective context
  -> each turn:
       apply only effectiveContext.extensions, in order
       -> agent.next(task, effectiveContext, runContext)
       -> native program assembles request
            filtered tools become function definitions
            filtered skills contribute instructions
       -> configured adapter filters direct tool definitions/choice
       -> concrete Agent.turn performs one provider request
       -> configured adapter checks returned tool calls
       -> native program proposes an AgentDecision
       -> runner handles the decision:
            final answer -> return
            actions -> authorize, validate, execute, record results -> next turn
```

### Before inference

- Denied tools/retrievers are removed from the context advertised to the program.
- Denied skills do not contribute their instructions during prompt assembly.
- Denied extensions are removed before any extension is applied. The runner reapplies the remaining ordered extensions on subsequent turns.
- `NativeFunctionAgent` filters the assembled tool request using its combined policy as well as constructing it from the filtered context.

### At execution

For `CallExternalTool`, the runner checks the effective tool definition, validates model-produced arguments against its input schema, and looks up the implementation before calling `execute`.

For `RetrieveRag`, it checks effective retriever membership and looks up the implementation before calling `retrieve`.

These checks still apply if a program proposes an action that was never advertised. Denial must not execute the implementation. Tool failures/denials become structured tool-result messages so the loop can respond; retrieval failures remain typed run failures. Turn/action budgets and the run timeout apply independently of capability permissions.

`ToolApproval.guard` can add an approval decision before an already-authorized tool implementation runs. Allowlists do not automatically install this guard, and approval is not a replacement for membership checks or argument validation. Implementations remain responsible for their own domain/resource safety.

## 7. Direct assembled requests cannot bypass tool policy

A caller can bypass native prompt assembly and call a resolved configured adapter directly, or call `Orchestrator.turn`. These paths still pass through `ConfiguredAgent` for configured policies.

When the `tools` allowlist is explicit, `filterRequest`:

1. Removes function definitions whose names are not allowed.
2. Changes a forced function choice to `ToolChoice.None` if that function is no longer present.
3. Sets `ToolChoice.None` and disables parallel tool calls when no tool definitions remain.
4. Otherwise preserves the requested tool choice and allows parallel calls only if tools remain.

For an explicit tools policy, the adapter also rejects a returned function call if its name is forbidden, it was not advertised in the filtered request, or the filtered choice was `ToolChoice.None`. This is a typed `InvalidConfig` failure before any execution.

If `tools` is omitted, request filtering and this extra response guard preserve existing behavior. The runner's effective-context checks still authorize execution on the runner path. An adapter turn itself does not execute tools or enter an orchestration loop.

## 8. REPL transcription is application policy

The configured `transcriberAgent` is an ordinary agent from the same catalog and remains selectable for normal use. There is no special core `ConfiguredTranscriber` type or transcription resolver in `RuntimeInstances`. The REPL's `Transcriber` service invokes that agent and handles transcript validation/retention; it is an application service, not a new agent type.

`ReplStartup.voice` owns the voice-specific setup:

1. Missing `transcriberAgent` returns no voice service; there is no chat-agent/provider/model fallback.
2. An explicit reference must resolve to a configured agent with an explicit nonblank model and system prompt.
3. Resolve its policy against the application's available context and require all four **effective** capability sets to be empty. Conflicting policy fails startup instead of silently enabling capabilities.
4. Resolve the ordinary agent through `getAudioAgent` and pass it plus generic settings directly to [`Transcriber`](../repl/src/main/scala/fmgp/yallmo/repl/voice/Transcriber.scala).

Use the following explicit policy for the voice agent so future application catalogs cannot accidentally enable inherited features:

```json
{
  "capabilities": {
    "tools": [],
    "retrievers": [],
    "skills": [],
    "extensions": []
  }
}
```

Omitted settings can pass the effective-empty check when the inherited catalogs are already empty; they do not mean that the category is permanently disabled.

Each voice invocation sends one fresh request containing only current audio and configured instructions, no tools or continuation, `ToolChoice.None`, and `parallelToolCalls = false`. It bypasses the conversational runner, so no extensions, skills, RAG or follow-up tool loop are applied. Unexpected calls or unsupported/blank responses fail without execution. Audio is first appended as a `MessagePayload.AudioAttachment` reference with separately stored bytes in a generated session.
`Orchestrator.turn` supplies `AgentTurnRequest.readBlob`; inference failure leaves that audio intact. A valid assistant
transcript is then appended before text is offered to the editable draft. Recordings survive draft discard when using
filesystem storage. See [Attachments and blob storage](orchestration/ATTACHMENTS_AND_BLOB_STORAGE_PLAN.md).

The generic audio marker/lookup does not prove the selected remote model accepts audio. Shared audio agents are not globally forced into these single-turn/no-tools restrictions: those are properties of the REPL voice invocation.

## 9. Trust boundaries and limitations

- **Not a sandbox:** trusted Scala code can construct raw adapters or invoke tools directly. Registry wrappers protect configured-agent paths; application code must preserve those paths.
- **Raw provider APIs:** `inferenceWithTextAndTools` is deprecated at the text-provider interface; `generateWithTools` is deprecated on the common provider API and explicitly on the OAI/Codex implementations. Their internal `toolLoop`/`executeTool` helpers are also deprecated. These APIs remain for compatibility but do not pass through the configured agent wrapper/runner. Use `Agent` with `NativeFunctionAgent`/`AgentRunner` for new tool-executing code; deprecation does not retrofit policy enforcement into the legacy path.
- **Caller-supplied agents:** `RuntimeInstances.fromAgents`/registered instances are returned as supplied, without inventing configuration policy for them. Program-side policy and runner checks still apply when used through the runner.
- **Not transcript sanitization:** allowlists prevent fresh capability application/execution. They do not remove old tool results, previously inserted skill text, or arbitrary caller-provided context from history. Direct-request filtering does not identify the origin of text inside a payload.
- **Blob access is not a new authorization policy:** `readBlob` is a request-local function, not serialized content.
  `Orchestrator.turn` binds its own Storage reader; `NativeFunctionAgent.bind(adapter, orchestrator.readBlob)` binds the
  reader for runner calls. Direct adapters default to `Blob.unavailable` and need an explicit reader for audio. The four
  capability allowlists do not scope blob reads to particular sessions or agents; trusted callers own access control.
- **Not credentials/provider routing policy:** these four allowlists do not grant accounts, endpoints or providers. Model/prompt settings are also not grants.
- **No remote probes:** successful local validation proves references, catalogs and adapter compatibility, not authentication, remote availability or model capabilities.
- **Secret-safe diagnostics:** never log the full configuration, credentials, raw recordings, or hidden skill/extension contents to explain a policy error.

## 10. Tests and related documentation

- [`AgentCapabilitiesSuite`](../core/src/test/scala/fmgp/yallmo/orchestration/AgentCapabilitiesSuite.scala): omitted/empty/partial settings, unknown IDs, intersections, direct-request bypass attempts, filtered skills/extensions and denied actions.
- [`RuntimeInstancesSuite`](../core/src/test/scala/fmgp/yallmo/orchestration/RuntimeInstancesSuite.scala): generic resolution/cache/audio checks and configured tool-request boundary.
- [`ReplStartupSuite`](../repl/src/test/scala/fmgp/yallmo/repl/ReplStartupSuite.scala): application-specific voice validation, inherited policies, ordinary selection and isolated retention.
- [`TranscriberSuite`](../repl/src/test/scala/fmgp/yallmo/repl/voice/TranscriberSuite.scala): fresh tool-free turns, unexpected response rejection and retention failures.
- [Agent/provider configuration](CONFIG_AGENT_PROVIDER.md) — its opening section describes the current implementation; later sections include historical designs.
- [REPL usage](REPL.md) and [secret-free configuration example](examples/repl-config.json).
