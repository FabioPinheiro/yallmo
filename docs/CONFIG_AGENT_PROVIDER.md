# Agent-first configuration and selection

**Current status: config models, safe file initialization/loading and lazy runtime instances live in core. The REPL requires --config and supports an optional startup --agent. AGENTS supports process-local selection among loaded agents; switching preserves the current session and conversation. Live config reload remains future work.**

## Current implementation boundary

- `core/.../orchestration/config/Config.scala` is data only: `userName`, `defaultAgentId`, ID-keyed **maps** of agents and providers, optional `transcriberAgent`, and `browserAudioPort` (default 5180, valid 1–65535). Newly initialized config files serialize the port; older files missing it retain the default without being rewritten. JSON catalogs are objects, not arrays.
- `AgentConfig.agentType` currently supports `OAIAgent` and `ChatGPTAgent`. Each requires exactly one matching provider. REPL startup decodes `config.model` (required), optional `systemPrompt`, `thinkingLevel` and `parallelToolCalls`, then binds its native runner to the resolved adapter. The same settings drive requests and footer feedback. The terminal default system prompt lives in `ReplStartup`, not core. Generic `ConfiguredAgentSettings.decode` requires a configured prompt unless its caller supplies `defaultSystemPrompt = Some(...)`; the REPL supplies its fallback for chat, but not for transcription.
- `RuntimeInstances.make(config)` captures a shared HTTP client, but initializes connections and agents only on lookup. Caches and the initialization semaphore belong to this runtime registry, not to `Config`.
- `RuntimeInstances.make(config, agents)` also accepts already-created agents such as human input channels, retaining their environment requirements. Duplicate supplied IDs and collisions with configured IDs are rejected.
- `RuntimeInstances.fromAgents(agents)` needs no HTTP client and supports existing startup/test code.
- `OrchestratorLive` delegates resolution to the registry. Its only factories are `inMemory(instances)` and `filesystem(root, instances)`; callers must construct `RuntimeInstances` explicitly. Already-created agents go through `RuntimeInstances.fromAgents(agents)` first.
- Lookups do not contact providers or authenticate. ChatGPT profiles use independent literal credentials; refresh is deferred until inference, shared by agents using the same connection, and retained **only in memory**.
- A registry uses a fixed configuration snapshot. Creating another registry from the same Config creates independent caches. No automatic reload/rebuild/save policy is implied.
- REPL startup requires `--config <file>`. Missing files initialize privately with the JVM user name and empty catalogs. `--agent` overrides `defaultAgentId`; absent both, the REPL starts unselected and rejects prompts without side effects. The old `--provider`/`--model` flags are removed. AGENTS supports in-session selection from the startup snapshot without inference/authentication. New selections keep the current session and conversation while changing the chat runtime and prompt, not the independent voice agent. Sessions can be resumed independently of the selected agent; new message authors are enrolled on persistence without rewriting prior authors. Pre-switch opaque provider continuations are excluded from the new binding's request view, not removed from stored history. Live config reload is not implemented.
- Profile login persists credentials only to the selected private configuration profile; there is no global `/login` command. Voice uses only the explicit `transcriberAgent` and its configured model/prompt, independently of chat selection, with no fallback. Missing references disable voice; invalid explicit settings fail local startup. All four effective capability sets must be empty (use explicit `[]` allowlists). See [setup](HOW_TO_RUN.md).

For current capability configuration and enforcement, see [Capabilities architecture](Capabilities_Architecture.md).

The sections below retain the earlier design and future milestones. Earlier `AppConfig`/`program` names, array examples and claimed settings-decoding helper are historical sketches, not the current API.

## 1. Agreed direction

- The REPL selects an **agent**, not a provider. Provider selection must not be required before startup.
- An agent has an identity and configured/programmed behavior. It can use **zero, one or multiple providers**.
- Multiple agents can share one provider configuration. Multiple provider instances can use the same adapter with different endpoints/accounts.
- The **agent's program** decides whether, when and how to use its providers. The REPL does not choose a provider for each agent action.
- Sessions should be largely independent of agents. Defer the difficult switching/history-sharing policy for as long as possible; implement incrementally.
- Use literal values in a private configuration file.
- Do not add a general routing language, credential-store framework or universal “bindings” abstraction just to begin loading configuration.

Existing capability, approval and budget boundaries remain enforced by trusted runtime code. The agent owns its behavior, not unlimited access to tools or credentials.

## 2. Why “bindings” was suggested, and why we do not need it now

The earlier proposal used *binding* to mean a named provider role inside an agent, such as `draft` or `review`, each with its own model/settings.

That was an additional abstraction, **not a requirement from the user**. It is unnecessary for the first configuration format.

Use a simple `providerIds` array instead:

```json
{ "id": "local-helper", "providerIds": [] }
```

```json
{ "id": "bob", "providerIds": ["OAI"] }
```

```json
{ "id": "reviewer", "providerIds": ["OAI", "chatgpt-1"] }
```

This list declares the provider configurations available to the agent. It does **not** imply round-robin routing, fallback, ordering, delegation or a call to every provider. Those decisions belong to the agent's program.

If a particular program later needs roles such as draft/review, it can define those in its own settings. Do not force every agent to use the same role abstraction.

## 3. Minimal concepts

| Concept | Meaning |
| --- | --- |
| `userName` | The operator's configured name, replacing the hardcoded `fabio`. Keep display-name versus durable actor-ID semantics separable. |
| Agent `id` | Stable identity such as `bob`, independent of providers. |
| Agent `program` | Registered implementation of behavior, e.g. a native-function agent, local handler or multi-provider workflow. |
| Agent `providerIds` | Zero or more provider instances the program can use. |
| Agent `config` | Program-specific settings; a simple LLM program can define model, thinking level and system prompt here. |
| Provider `id` | Named instance such as `OAI`, `chatgpt-1` or `chatgpt-2`. |
| Provider `providerType` | Adapter/protocol implementation, initially `OAI` and `ChatGPT`. |
| Session | Conversation/messages and their provenance; not owned by one provider or hardwired agent implementation. |

Model and thinking settings are agent/program configuration, not necessarily properties of a shared endpoint. Bob and Eve can use the same provider with different models/settings. A multi-provider program defines its own model-selection behavior rather than inheriting an invented universal rule.

## 4. Proposed configuration example

Illustrative valid JSON: the structural models/codecs now exist, but loading/validation/runtime integration and the proposed program implementations do not. Placeholder token strings below are not real credentials.

```json
{
  "version": 1,
  "userName": "fabio",
  "defaultAgentId": "bob",
  "agents": [
    {
      "id": "bob",
      "program": "native-function",
      "providerIds": ["OAI"],
      "config": {
        "model": "ai/gemma4:E4B",
        "systemPrompt": "You are Bob, a concise assistant."
      }
    },
    {
      "id": "eve",
      "program": "native-function",
      "providerIds": ["OAI"],
      "config": {
        "model": "another-local-model",
        "systemPrompt": "You are Eve, a careful reviewer."
      }
    },
    {
      "id": "charlie",
      "program": "native-function",
      "providerIds": ["chatgpt-1"],
      "config": {
        "model": "gpt-6.1-sol",
        "thinkingLevel": "high"
      }
    },
    {
      "id": "agatha",
      "program": "native-function",
      "providerIds": ["chatgpt-2"],
      "config": {
        "model": "gpt-6.1-sol",
        "thinkingLevel": "medium"
      }
    },
    {
      "id": "local-helper",
      "program": "local-handler",
      "providerIds": [],
      "config": {}
    },
    {
      "id": "reviewer",
      "program": "draft-then-review",
      "providerIds": ["OAI", "chatgpt-1"],
      "config": {}
    }
  ],
  "providers": [
    {
      "id": "OAI",
      "providerType": "OAI",
      "endpoint": "http://localhost:12434/engines/llama.cpp/v1"
    },
    {
      "id": "chatgpt-1",
      "providerType": "ChatGPT",
      "credentials": {
        "accessToken": "<private-access-token-1>",
        "refreshToken": "<private-refresh-token-1>",
        "expiresAtMillis": 0
      }
    },
    {
      "id": "chatgpt-2",
      "providerType": "ChatGPT",
      "credentials": {
        "accessToken": "<private-access-token-2>",
        "refreshToken": "<private-refresh-token-2>",
        "expiresAtMillis": 0
      }
    }
  ]
}
```

The zero expiry in this structural example means expired/not ready; it is not a working authenticated profile. Credentials may also be absent when logged out. Missing credentials should not prevent startup, agent listing or provider-free operation.

`config: {}` on a hypothetical workflow does not fully specify its behavior. Its registered program supplies and validates that behavior; this document does not invent routing rules yet. Program names are allowlisted implementation identifiers, not executable Scala/shell text.

For OAI, `endpoint` is the base URL rather than the final `/chat/completions` route. ChatGPT defaults to its subscription endpoint, so it need not repeat it in every profile. Do not silently send subscription credentials to a platform API or arbitrary host.

## 5. Credentials: private configuration profiles

- Store literal credential fields in the private runtime configuration; no separate credential-store subsystem or reference resolver is needed.
- The configuration can contain real tokens, so keep it **private, gitignored and owner-readable only where supported**. Commit only sanitized examples and never print the full configuration in diagnostics.
- Login updates only the selected private profile. Login and inference must use the same profile so `chatgpt-1` cannot overwrite `chatgpt-2` credentials.
- Agents sharing a profile share refresh coordination. Refreshed credentials currently remain in memory; durable refresh write-back and cross-process coordination are deferred.
- Keep raw secrets and provider errors containing them out of history, session files, footer and logs.

## 6. Who decides how providers are used?

**Recommendation: the agent's program does.**

A simple agent might always call its one provider. A local agent might call none. A workflow might draft using one provider and review with another, choose based on input, or make parallel calls.

The configuration declares identities, available provider references and program settings. The REPL selects the agent. The agent implementation decides its behavior. Trusted runtime services resolve configured provider IDs and enforce permissions, tool approvals and budgets.

Start with registered Scala implementations, not a generic routing DSL. JSON can supply settings to those implementations as needed. Add declarative routing only if real use cases make it worthwhile.

The program must not gain arbitrary credentials simply by naming a provider outside its configured `providerIds`. Cross-account or separately billed fallback must be explicit behavior, not an automatic reaction to an authentication/rate-limit error.

## 7. Sessions: deliberately defer switching policy

The user prefers an explicit choice for history transfer, but wants to delay this complex decision and implement it in steps.

For now:

- Treat a session as conversation data/provenance that is largely independent of the chosen agent.
- Keep existing message author IDs accurate. Switching agents must not relabel old messages.
- Do not add a permanent `session.agentId` owner as a shortcut for configuration loading.
- Do not decide now whether `/agent use` keeps the current context, restores another context, starts a new conversation or prompts for a transfer decision.
- Reading/validating the config does not require changing session storage or agent-switching behavior.
- When switching is implemented, history sharing across agents/accounts must be deliberate rather than a silent transfer.

Provider-specific state still needs isolation. In particular, encrypted Codex continuations cannot be shared between two accounts just because they both use the `ChatGPT` adapter. Define that boundary before implementing actual cross-profile routing/history transfer, not in the initial config parser.

## 8. Future REPL and footer direction

Proposed commands, not implemented in this plan:

- Start without a required provider choice. An optional configured `defaultAgentId` or future `--agent` can select an initial agent; otherwise start unselected.
- `/agent` lists configured agents and the current selection.
- `/agent use <id>` selects an agent; its conversation effects remain deferred as above.
- `/login` resolves the selected agent's login-capable providers. If several exist, ask which one; do not guess. Explicit login to a provider profile must also be possible without changing agents.
- Preserve existing private-input/cancellation protections when extending profile-scoped browser login.

Footer feedback must distinguish **selected agent** from **current/last observed provider call**. Show configured provider IDs (`chatgpt-1`, `chatgpt-2`), not only the adapter type. Zero-provider agents should not have invented model/thinking fields; multi-provider idle/parallel states must not pretend there is always one current provider.

That status work should follow actual runtime integration. The config-file milestone should not rewrite the footer first.

## 9. REPL initialization and live configuration

**Agreed direction:** configuration is an immutable Scala case class, held in a shared `Ref[AppConfig]` so the application can replace it atomically. `AppConfig` is an illustrative name to avoid confusion with ZIO's own `Config` type.

```text
Read chosen config file
  -> decode and validate AppConfig
  -> create ONE Ref[AppConfig] for this REPL process
  -> give the application access to that shared configuration
  -> start the REPL without eagerly connecting/authenticating every provider
```

Illustrative ZIO shape only, not implemented APIs:

```scala
for {
  initialConfig <- loadAndValidateConfig(path)
  configRef     <- Ref.make(initialConfig)
  _             <- runRepl(configRef)
} yield ()
```

The case class itself is never mutated. A change replaces its value with a new immutable copy:

```scala
configRef.update(current => current.copy(userName = "fabio"))
```

### Atomic updates and validation

- Start with ordinary `Ref`, not a more complex state framework. Updates are pure transformations; use `update` or `modify` rather than `get` followed by `set`, which can lose concurrent changes.
- Validate candidate configuration before it becomes visible. A rejected edit leaves the old value intact; read/modify/validate/replace must have atomic semantics rather than validating a stale snapshot and overwriting intervening updates.
- Keep network calls, file writes and resource construction outside pure Ref callbacks. Those callbacks may be retried by the implementation and must not perform hidden side effects. Add `Ref.Synchronized` or a serialization boundary only if later requirements genuinely need effectful update sequencing.
- Share the same Ref rather than creating one copy per command, agent or provider. A small config service can encapsulate access/validation later; it does not require a generic framework now.

### What changes when the Ref changes?

- A future lookup/submission reads the current configuration. A running turn should use one consistent resolved snapshot, not reread changing credentials/model/agent settings halfway through its behavior. Auth refresh coordination remains a separate explicit responsibility.
- Updating the Ref does not automatically rebuild already-created ZLayers, providers or agent instances. Later runtime integration must resolve/rebuild affected instances deliberately; unrelated providers should remain lazy.
- A Ref is not a notification stream. For initial command-driven changes, explicitly update the relevant UI after success. If background config changes later require subscriptions, evaluate `SubscriptionRef` then.
- Do not log or expose full snapshots once they contain credential values.

### Live state versus saved configuration

Updating `Ref[AppConfig]` changes **memory**, not the JSON file. Saving must be an explicit application operation with atomic file replacement and failure reporting. Whether saving is automatic or user-triggered remains deferred; do not assume every edit is immediately persisted.

If saves become concurrent, serialize/revision-order them so an older snapshot cannot overwrite a newer one. A save failure must not falsely report a durable change.

Keep transient state—current session, active calls, drafts and ideally current agent selection—separate from persisted configuration. In particular, a configured `defaultAgentId` is a startup preference, not necessarily the agent currently selected. This does not decide the deferred session/history-switching policy.

## 10. Existing code to revisit incrementally

- `ReplMain` hardcodes the human `fabio` and constructs an agent from a provider-first choice.
- `ReplProvider` combines selected provider, model and provider-derived agent identity; it is not yet the agent catalog proposed here.
- `NativeFunctionAgent` is an existing agent-program candidate, but currently reads one global `Agent[Any]` service. Multiple agents/providers eventually need explicit resolution rather than swapping a global service underneath a turn.
- `OrchestratorLive` already has an agent registry; reject duplicate IDs before constructing maps rather than allowing overwrite.
- `ReplSession` holds one fixed agent/context. `ReplOrchestratorLive` holds a fixed assistant and attributes every non-user message to it. These assumptions need careful later work, not a premature session rewrite in the config step.
- OAuth services currently use global credential keys/default storage. Parameterize selected profiles when implementing credential integration.
- `ReplActiveAgent` currently describes one immutable native configuration. Runtime status for zero/multiple providers is later work.

Keep provider protocols and existing schema encoding in their current adapters. Loading configuration does not justify renaming every existing agent/provider type or introducing a new framework.

## 11. First implemented slice and next initialization boundary

The following immutable models now exist in `core/src/main/scala/fmgp/yallmo/orchestration/config/`:

- `AppConfig`: version, user name, agent/provider catalogs and optional startup default.
- `AgentConfig`: stable `AgentId`, registered program name, zero/multiple `providerIds` and immutable `zio.json.ast.Json.Obj` program settings.
- `ProviderConfig`: `providerType`-discriminated `OAI` and `ChatGPT` definitions. ChatGPT credentials may be absent while logged out.
- `ProviderConfigId`: configured instance/account ID, deliberately distinct from the existing adapter-level `model.llm.ProviderId`.
- `OAuthCredentialsConfig`: literal token values and Unix-millisecond expiry; its diagnostic `toString` is redacted. JSON serialization still includes secrets for persistence.

Each model has a ZIO JSON codec. There is no file loading, provider connection, registry, shared Ref or initialization side effect. Cross-reference, version, endpoint and registered-program validation are left to the forthcoming loader/initializer. No tests were added or run for these models, as requested; compilation succeeded.

### How an agent should initialize from its own configuration

Recommended next boundary:

```text
Application reads one AppConfig snapshot
  -> finds selected AgentConfig by agent ID
  -> selects the registered initializer by AgentConfig.program
  -> that initializer decodes AgentConfig.config into its OWN settings type
  -> validates its settings and required provider cardinality/capabilities
  -> receives access to only its declared providerIds
  -> constructs its agent runtime/resources
```

`AgentConfig.decodeConfig[A]` already provides the pure settings-decoding step. For example, an initializer could define:

```scala
final case class NativeSettings(model: String) derives JsonCodec

val settings: Either[String, NativeSettings] =
  agentDefinition.decodeConfig[NativeSettings]
```

The settings type and its semantic validation belong to that program, not to a universal `AgentConfig`. Decode failures return a sanitized message because arbitrary settings can contain secrets.

Do not have every agent load the file or inspect the global Ref. The application resolves its configuration snapshot and passes the relevant definition to the initializer. Configuration models and the future initializer/runtime contract belong in `core`, under `orchestration.config`, alongside orchestration services. The `model` module remains independent of these application configuration types. The actual factory API and existing `NativeFunctionAgent` adaptation are deliberately not implemented in this slice.

## 12. Small milestones — incremental

- [ ] C0 Agree the minimal file structure, its location and how program-specific config is represented.
- [x] C1a Define immutable config models/codecs and a sanitized structural example: `userName`, agents, `providerIds`, providers and literal values. Compile only; no model tests, per user request.
- [ ] C1b Add loader/initializer validation for version, duplicate IDs/references, known programs and field semantics; **no session changes**.
- [ ] C2 Load/validate a selected config file, create one shared `Ref[AppConfig]`, and expose the current agent catalog without provider requests or eager credential requirements. Add atomic validated in-memory update tests; keep saving and runtime resource reconfiguration explicit. Keep existing runtime behavior during this step.
- [ ] C3 Connect simple configured agents/providers and independent credential profiles using literal values. Adapt login/refresh persistence safely; first demonstrate two agents sharing one provider and two separate ChatGPT profiles.
- [ ] C5 Implement agent selection with explicitly agreed, incremental session/history semantics. Do not force that decision into C1.
- [ ] C6 Add one real zero-provider program and one bounded multi-provider program with program-owned behavior; protect provider/account-specific continuations.
- [ ] C7 Update observed footer state, migration guidance and offline regression coverage for the integrated behavior.

Each milestone should be useful and reviewable alone. Do not combine config, routing, auth redesign, session transfer and UI switching into one implementation.

## 13. Deferred decisions

- Default config filename/location and whether there is an explicit `--config` option. Prefer one source initially, not layered precedence.
- Automatic versus explicit saving of live configuration, and which commands may edit it. A shared Ref does not itself decide persistence.
- The exact registered program names and fields each program needs. Validate settings per program instead of imposing one routing schema.
- Agent-switching/history-transfer UI and persistence semantics: intentionally postponed.
- Cross-process credential ownership and durable refresh write-back: decide when extending auth integration.
- Legacy `--provider` / `--model` migration: do not let them silently override a named agent's behavior.
- Human display name versus stable message-author ID; keep that distinction possible.

**Current scope: immutable models/codecs, safe config-file initialization/loading, RuntimeInstances caching/concurrency, OrchestratorLive integration and configured/unselected REPL startup. Live Ref updates, credential persistence, in-session selection and history switching remain deferred. See the current implementation boundary at the top.**
