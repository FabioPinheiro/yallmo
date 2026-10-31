# Agent-first configuration and selection

The server owns credential-bearing configuration and loads its file via `--config <path>`. The REPL is client-only:
it neither reads configuration nor starts an embedded runtime. Start the server separately, then connect:

```sh
mkdir -p ./server-storage
sbt 'server/run --storage ./server-storage --config ./yallmo.json --workspace .'
sbt --server -no-server 'repl/run --user-name operator --agent bob'
```

`--user-name` is required and validated as an `AgentId`; the client sends claimed human ID `user-<id>`.
`--agent` is optional; absent means no selection. `--log-level` remains a local display flag. The default server
is `ws://127.0.0.1:8080/rpc`; `--server` overrides it. Connection failure exits without local fallback.
`--config`/`--storage` belong only to the server. `--transcriber-agent` is rejected; remote voice/login transport is pending.
There is no OS-name, first-agent or default-selection fallback, nor client-local config/provider/audio bootstrap.

**Migration:** top-level `userName`, `defaultAgentId` and `transcriberAgent` no longer apply. Loading legacy fields
emits a sanitized warning; use `--user-name` and `--agent` for client preferences. There is no current CLI transcription
selection. Raw catalog edits preserve arbitrary unknown file fields without making them active server settings.

Configured trusted-local prompt execution is enabled: the server alone owns providers, tools, config and durable storage.
Authentication is **explicitly deferred** for actual loopback peers with absent Origin and a claimed human. Usernames
are impersonable authorship, not authentication; retain loopback/Host/Origin checks and never publicly expose/proxy.
Anonymous and Origin-bearing browser connections remain viewers. Operators additionally receive `sessions.create`,
`turn.submit` and the read-only `diagnostics.request` used by `/debug request`. Provider-login/voice/human-interaction
transport and other diagnostics remain pending remotely.

```sh
mkdir -p ./server-storage
sbt 'server/run --storage ./server-storage --config ./private/server.json --workspace .'
sbt --server -no-server 'repl/run --user-name alice --server ws://127.0.0.1:8080/rpc --agent bob'
```

Choose among initialize's `configuredAgentIds` when supplied, not human/presence `agents.list`. Remote model/provider
metadata stays unknown. No selected agent means no prompt-induced create/write; the server independently validates
selection. First prompt creates once and submits once; subsequent prompts reuse that session. Lost responses require
history/refresh before manual resend, never automatic replay. See [REPL](REPL.md#server-connection).

## JSON and YAML configuration

Select either format explicitly with server `--config ./yallmo.yaml` (or `.yml`) or the existing
`--config ./yallmo.json`. Both decode into the same typed `Config` and use the same reference/capability validation;
there is no schema or runtime-composition change. The server does not discover or migrate files automatically.
Existing embedded callers' `ApplicationRuntime.DefaultConfigPath` remains `yallmo.json` for compatibility.

See the commented [YAML example](examples/yallmo.yaml) and existing [JSON example](examples/yallmo.json).
YAML supports comments, nested mappings, flow lists and literal/folded multiline prompts under `agents.<id>.config.systemPrompt`.
Use JSON-compatible scalars: `true`/`false`, `null`, finite JSON numbers and strings. Words such as `on`, `off`, `yes`,
`no` and dates remain strings; quote numeric or boolean-looking IDs. Duplicate/non-string keys, aliases, merge keys,
custom tags and multiple YAML documents are rejected. Unknown fields are retained by application catalog edits.

Loading never rewrites an existing file. Application provider additions and credential updates keep the selected
format, unknown fields, comments on surviving YAML nodes, key order and scalar styles (including multiline prompts).
Whitespace/indentation may be normalized on updates; byte-for-byte formatting preservation is not promised. The
existing private atomic-write policy and per-catalog locking still apply; external writers must coordinate.

### Explicit JSON → YAML migration

Stop other config writers, then run this offline command from the repository root:

```sh
sbt 'core/runMain fmgp.yallmo.orchestration.config.ConfigMigrationMain ./yallmo.json ./yallmo.yaml'
```

Migration requires an existing, valid JSON source and creates a private YAML destination with the complete raw tree,
including unknown/legacy fields. It never initializes a missing source, modifies the source or replaces an existing
destination (even under a concurrent migration). Hard-link publication support is required. Inspect/edit the YAML
privately, then explicitly switch the server to `--config ./yallmo.yaml`; JSON remains supported and unchanged.
The output still contains any literal credentials from the source: do not log, share or commit it. Root `yallmo.json`,
`yallmo.yaml` and `yallmo.yml` are gitignored; exclude other private paths yourself.

## Current implementation boundary

- `core/.../orchestration/config/Config.scala` is data only: ID-keyed **maps** of agents and providers, and `browserAudioPort` (default 5180, valid 1–65535). Newly initialized config files serialize the port; older files missing it retain the default without being rewritten. JSON catalogs are objects, not arrays.
- The native `AgentConfig.agentType` values `OAIAgent` and `ChatGPTAgent` each require exactly one matching provider. Server runtime selection decodes `config.model` (required), optional `systemPrompt`, `thinkingLevel` and `parallelToolCalls`, then binds its native runner to the resolved adapter. The same settings drive requests and footer feedback. The chat default system prompt lives in server `ApplicationRuntime`, not core. Generic `ConfiguredAgentSettings.decode` requires a configured prompt unless its caller supplies `defaultSystemPrompt = Some(...)`; the server supplies its fallback for chat, but not for transcription.
- `FolderStructureAgent` is a provider-free configured agent requiring `filesystem.root`; its first lookup validates that existing directory and binds the filesystem once. Its turns need no extra environment. Server-side selection uses its direct execution binding, without decoding native model/prompt/generation settings or requiring a provider. The connected client displays no provider/model metadata.
- `RuntimeInstances.make(config)` captures a shared HTTP client, but initializes connections and agents only on lookup. Caches and the initialization semaphore belong to this runtime registry, not to `Config`.
- `RuntimeInstances.make(config, agents)` also accepts already-created agents such as human input channels, retaining their environment requirements. Duplicate supplied IDs and collisions with configured IDs are rejected.
- `RuntimeInstances.fromAgents(agents)` needs no HTTP client and supports existing startup/test code.
- `OrchestratorLive` delegates resolution to the registry. Its only factories are `inMemory(instances)` and `filesystem(root, instances)`; callers must construct `RuntimeInstances` explicitly. Already-created agents go through `RuntimeInstances.fromAgents(agents)` first.
- Lookups do not contact providers or authenticate. ChatGPT profiles use independent literal credentials; refresh is deferred until inference, shared by agents using the same connection, and retained **only in memory**.
- A registry uses a fixed configuration snapshot. Creating another registry from the same Config creates independent caches. No automatic reload/rebuild/save policy is implied.
- `ReplMain` always connects to the selected/default endpoint and builds `ConnectedReplApplication`. It never calls
  `ApplicationRuntime.load` or opens an embedded workspace. Existing embedded adapters remain for feature integration
  and tests, but are not startup paths. `ReplStartup.fromRuntime` requires an explicitly supplied runtime.
- The server retains core persist–run–persist and session storage. Client agent selection preserves session/history,
  with continuation reset intent sent on the next turn. No live config reload is implemented.
- Provider administration/login, voice and debug transport remain pending. Their existing local implementations and
  tests are preserved for follow-up integration; absence from the connected client is not a reason to delete them.

- Audio payloads contain a `BlobId`, not bytes. `Orchestrator.turn` and REPL native bindings supply the blob reader;
  direct audio adapter calls must supply it themselves. Voice persists audio before inference, independently of active chat.
  This schema does not decode/migrate old `AudioInput` records. See [Attachments and blob storage](orchestration/ATTACHMENTS_AND_BLOB_STORAGE_PLAN.md).

For current capability configuration and enforcement, see [Capabilities architecture](Capabilities_Architecture.md).

### Per-agent filesystem configuration

`AgentConfig` accepts an optional typed `filesystem` field. Existing configurations omit it and retain their behavior.
`FolderStructureAgent` requires it, but needs no provider or model:

```json
{
  "agents": {
    "folder-reviewer": {
      "agentType": "FolderStructureAgent",
      "filesystem": { "root": "/trusted/agents/folder-reviewer" }
    }
  }
}
```

Roots must be non-blank. Absolute paths are recommended; relative paths use the process working directory, matching
`FileSystem.open`, not the configuration file's directory. Loading/validating configuration does not open a filesystem,
check directory existence, or create directories. Runtime setup must validate an existing root before granting the
instance to an agent. `RuntimeInstances.initializeAgent` resolves the trusted root with `Path.toRealPath()` on first
lookup, following links in the root itself or its ancestors, then opens the resulting absolute directory and caches
`FolderStructureAgent.make(id, fs)`. Missing or dangling roots fail without creating directories. Retargeting a configured
link does not redirect an already-initialized agent. Links inside the workspace remain forbidden; `FileSystem.open`
itself still requires a symlink-free root. State filenames and review behavior belong to the agent, not this config.

With the application's existing `Client` layer and a turn `request`:

```scala
for {
  config <- ConfigFile.loadOrInitialize(configPath)
  instances <- RuntimeInstances.make(config)
  agent <- instances.getAgent(AgentId("folder-reviewer"))
  response <- agent.turn(request)
} yield response
```

The registry API still captures a shared HTTP client, but this agent never uses it or makes provider requests.

### Folder-review example (direct use)

The implementation remains `Agent[FileSystem]`; its factory receives an initialized filesystem and binds that
instance once, returning `Agent[Any]`. The configured cache uses `Agent[Any]` because these agents need no remaining
per-turn environment, not `Agent[?]`, which would hide unknown requirements. Already-created agents registered by
callers continue to retain their own `Agent[R]` requirements.

Given a decoded `rootConfig: FileSystemConfig` and an `agentId`, direct construction is also possible:

```scala
for {
  fs <- FileSystem.open(rootConfig.root)
  agent = FolderStructureAgent.make(agentId, fs)
  response <- agent.turn(request)
} yield response
```

Reuse that bound agent for subsequent turns. This provider-free example ignores request content, recursively
lists the current folder/file names, and generates descriptions such as `Directory src.` and `File Main.scala.`.
It reads no workspace file contents and does not use an LLM. Its `Folder`, `File` and `State` case classes/codecs all
live inside `FolderStructureAgent`'s companion object.

- `fs-structure.json`: replaced with the freshly reviewed tree as pretty JSON, using `name`, `description`, `folders`
  and `files`. Existing hand-written descriptions are not preserved. The same JSON is returned as assistant text for
  use in a subsequent prompt.
- `state.jsonl`: appends one `{"reviewCount":1}`-shaped snapshot per completed write sequence, with the default JSONL
  sync policy. Counts resume from the last record on a later turn or newly constructed agent. Only a missing file
  starts at zero; empty/corrupt state and negative/exhausted counts fail explicitly.

Only the two root-level output names are excluded from the reviewed tree; matching names inside subfolders remain
ordinary entries. Assume sequential turns and exclusive ownership of the agent's root; there is no agent-level locking.
The structure write precedes the counter append; the two writes are not transactional, so failure/interruption may
leave an updated structure without an updated counter. Filesystem failures are surfaced as typed `AgentExecutionFailed`
errors, without silently resetting state. Configured registry construction and provider-free REPL selection are wired.

## Provider profiles and persistence

The following describes the retained embedded provider UI, not a connected-client capability.

`ProviderCatalog` lives in `server.application`, including private locked atomic raw-tree edits. The terminal still
renders provider menus and runs `ProviderLoginService` in-process against this catalog. Profile creation/login does
not change the runtime snapshot; restart after saving. There is no global `/login` command. Listing does not contact
providers or test credentials. Cancellation and failures before save leave credentials untouched.

Credentials are literal private values; no environment substitution or global credential store exists. Keep the
server file gitignored and owner-readable/writable, never share its contents. Login edits only the selected ChatGPT
profile and preserves unrelated/unknown fields. Refreshed rotating tokens remain only in cached connection memory;
durable refresh write-back and cross-process coordination remain deferred.

See [setup](HOW_TO_RUN.md), [terminal behavior](REPL.md), and the secret-free [example](examples/yallmo.json).
