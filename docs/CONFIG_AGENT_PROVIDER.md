# Agent-first configuration and selection

The server owns credential-bearing configuration. The embedded/local server loads `yallmo.json` in the process
working directory; standalone server `--config <path>` chooses that standalone instance's file. The REPL neither
reads the file nor accepts `--config`.

```sh
sbt --server -no-server 'repl/run --user-name operator --agent bob --transcriber-agent Transcriber'
```

`--user-name` is required and validated as an `AgentId`. `--agent` and `--transcriber-agent` are independent optional
selections: absent means no chat selection and no transcription, respectively. `--log-level` remains a local flag.
`--storage` belongs only to the standalone server and is rejected by the REPL. Normal embedded sessions/blobs are
in memory; no durable REPL lifecycle logger is installed. Explicit debug exports/captures remain local UI features.
Explicit `--server` consumes standalone sessions without client-local config/provider/audio bootstrap. There is no OS-name, first-agent or default-selection fallback.

**Migration:** top-level `userName`, `defaultAgentId` and `transcriberAgent` no longer apply. Loading legacy fields
emits a sanitized warning; use the CLI flags instead. Raw catalog edits preserve arbitrary unknown file fields,
including old preferences, without making them active server settings.

Without `--server`, the local `HumanAgent` is registered before assembly. With explicit `--server`, configured
trusted-local prompt execution is enabled: the server alone owns providers, tools, config and durable storage.
Authentication is **explicitly deferred** for actual loopback peers with absent Origin and a claimed human. Usernames
are impersonable authorship, not authentication; retain loopback/Host/Origin checks and never publicly expose/proxy.
Anonymous and Origin-bearing browser connections remain viewers. Only `sessions.create` and `turn.submit` supplement
the seven viewer methods; provider-login/voice/human-interaction transport and diagnostics remain pending remotely.

```sh
mkdir -p ./server-storage
sbt 'server/run --storage ./server-storage --config ./private/server.json --workspace .'
sbt --server -no-server 'repl/run --user-name alice --server ws://127.0.0.1:8080/rpc --agent bob'
```

Choose among initialize's `configuredAgentIds` when supplied, not human/presence `agents.list`. Remote model/provider
metadata stays unknown. No selected agent means no prompt-induced create/write; the server independently validates
selection. First prompt creates once and submits once; subsequent prompts reuse that session. Lost responses require
history/refresh before manual resend, never automatic replay. See [REPL](REPL.md#explicit-connected-mode).

## Current implementation boundary

- `core/.../orchestration/config/Config.scala` is data only: ID-keyed **maps** of agents and providers, and `browserAudioPort` (default 5180, valid 1–65535). Newly initialized config files serialize the port; older files missing it retain the default without being rewritten. JSON catalogs are objects, not arrays.
- The native `AgentConfig.agentType` values `OAIAgent` and `ChatGPTAgent` each require exactly one matching provider. Server runtime selection decodes `config.model` (required), optional `systemPrompt`, `thinkingLevel` and `parallelToolCalls`, then binds its native runner to the resolved adapter. The same settings drive requests and footer feedback. The chat default system prompt lives in server `ApplicationRuntime`, not core. Generic `ConfiguredAgentSettings.decode` requires a configured prompt unless its caller supplies `defaultSystemPrompt = Some(...)`; the server supplies its fallback for chat, but not for transcription.
- `FolderStructureAgent` is a provider-free configured agent requiring `filesystem.root`; its first lookup validates that existing directory and binds the filesystem once. Its turns need no extra environment. REPL startup and in-session selection use its direct execution binding, without decoding native model/prompt/generation settings or requiring a provider. The agent menu and footer show `n/a` for its model.
- `RuntimeInstances.make(config)` captures a shared HTTP client, but initializes connections and agents only on lookup. Caches and the initialization semaphore belong to this runtime registry, not to `Config`.
- `RuntimeInstances.make(config, agents)` also accepts already-created agents such as human input channels, retaining their environment requirements. Duplicate supplied IDs and collisions with configured IDs are rejected.
- `RuntimeInstances.fromAgents(agents)` needs no HTTP client and supports existing startup/test code.
- `OrchestratorLive` delegates resolution to the registry. Its only factories are `inMemory(instances)` and `filesystem(root, instances)`; callers must construct `RuntimeInstances` explicitly. Already-created agents go through `RuntimeInstances.fromAgents(agents)` first.
- Lookups do not contact providers or authenticate. ChatGPT profiles use independent literal credentials; refresh is deferred until inference, shared by agents using the same connection, and retained **only in memory**.
- A registry uses a fixed configuration snapshot. Creating another registry from the same Config creates independent caches. No automatic reload/rebuild/save policy is implied.
- `ReplStartup.make(options)` constructs the local human and calls `ApplicationRuntime.load(Chunk(human))` with the
  server's default path. It consumes only runtime, `ProviderCatalog`, safe agent summaries and the browser-audio port.
  `openWorkspace` retains scoped tool services and `Assembly[Any]`; immutable agent switches share assembly/storage/context.
  `SessionSubmission` retains core persist–run–persist. AGENTS changes process-local selection, preserving session and
  conversation. New authors are enrolled on persistence; old authors remain unchanged. Old opaque provider continuation
  is excluded from the new binding's requests, not removed from stored history. No live config reload is implemented.
- Profile login persists credentials only to the selected private configuration profile; there is no global `/login` command. Voice uses only the explicit `--transcriber-agent` selection and its configured model/prompt, independently of chat selection, with no fallback. An absent flag disables voice; missing IDs or invalid explicit settings fail local startup. All four effective capability sets must be empty (use explicit `[]` allowlists). See [setup](HOW_TO_RUN.md).

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

`ProviderCatalog` lives in `server.application`, including private locked atomic raw-tree edits. The terminal still
renders provider menus and runs `ProviderLoginService` in-process against this catalog. Profile creation/login does
not change the runtime snapshot; restart after saving. There is no global `/login` command. Listing does not contact
providers or test credentials. Cancellation and failures before save leave credentials untouched.

Credentials are literal private values; no environment substitution or global credential store exists. Keep the
server file gitignored and owner-readable/writable, never share its contents. Login edits only the selected ChatGPT
profile and preserves unrelated/unknown fields. Refreshed rotating tokens remain only in cached connection memory;
durable refresh write-back and cross-process coordination remain deferred.

See [setup](HOW_TO_RUN.md), [terminal behavior](REPL.md), and the secret-free [example](examples/yallmo.json).
