# HOW TO RUN


## Connect to a configured standalone server

```bash
# Private configuration defines bob and its provider; server owns config, tools and filesystem.
mkdir -p ./server-storage ./server-odin-workspace
sbt 'server/run --storage ./server-storage --config ./yallmo.json --workspace ./server-odin-workspace'
# Separate interactive terminal (--server is optional at ws://127.0.0.1:8080/rpc):
sbt --server -no-server 'repl/run --user-name fabio --server ws://127.0.0.1:8080/rpc --agent bob --log-level warning'
```

Type a prompt, then `/history`. First prompt creates once, retains its ID and submits once; later prompts reuse it.
No agent selected means no prompt write or session creation: choose `--agent` or `Esc a`. `/new [name]` creates an empty
remote session explicitly. `/session list` and `/session resume <id>` enable restart/reconnect history reads.
Assistant choices use advertised configured IDs, not mixed human/presence IDs; remote model/provider metadata is unknown.


Detached work survives disconnect; Ctrl+C never remotely cancels. A lost reply is uncertain, not permission to retry:
refresh `/history` (or reconnect/resume the known session) before manual resend. Lost create reply: inspect `/session list`.
Busy/head conflict preserves state and requires refresh. No automatic retry/replay, status/cancel or exactly-once claim.

No client-local config/storage/runtime/tools/audio/browser/login is initialized, even when prompts are enabled.
`/debug request` reads the selected session's latest provider capture when the server advertises diagnostic permission.
`/tools`, `/debug print|export`, provider catalog/login, voice/TTS and uploads remain unavailable remotely. `--transcriber-agent`
is rejected before networking, even without `--server`. `--config`/`--storage` remain server-only; credentials/query/fragment in
`--server` are rejected. Connection failure exits without local fallback or autodiscovery. Without `--server`, the client connects to
`ws://127.0.0.1:8080/rpc`; it never starts an embedded application. See [REPL semantics](REPL.md#server-connection).

## Initialize server configuration

Only the server loads configuration. Start it with `--config ./yallmo.json` as above; a missing file initializes
with empty agent/provider catalogs. On POSIX filesystems it is owner-readable/writable only. Publication is atomic
and never overwrites another process's file; the filesystem must support hard links. Existing files are loaded,
not rewritten. Configure agents/providers and restart the server before selecting an agent in the client.

The repository-root `yallmo.json` is gitignored. Keep other config paths private and excluded from version control too.
The file may contain OAuth secrets: do not print or share it. Credential values are literal; `${NAME}` references are not resolved.

`--user-name <id>` is required by the client and sent as claimed human ID `user-<id>`, not authentication.
The server alone owns tools, providers and durable storage. Provider-login/voice/human-interaction transport remains
pending; there is no legacy in-process REPL fallback.

## Configure agents

Use the sanitized [example yallmo.json](examples/yallmo.json) as a structural starting point. Copy/edit it into your **private**
server-owned `yallmo.json`, not into a committed file containing credentials. The claimed human ID `user-<user-name>` must
not collide with a configured agent ID. Agent/provider catalogs are JSON objects keyed by ID.

Settings requirements depend on the agent type. Native LLM agents (`OAIAgent` and `ChatGPTAgent`) need exactly one matching provider. In their `config`:

- `model` is required and must not be blank;
- `systemPrompt` is optional for both agent types; absent means the standard Yallmo assistant prompt. `OAIAgent` sends
  it as a `system` message; `ChatGPTAgent` sends it as the Responses API `instructions` field;
- `thinkingLevel` is optional: `minimal`, `low`, `medium`, `high` (adapters may ignore unsupported options);
- `parallelToolCalls` is optional and defaults to `false`.

`FolderStructureAgent` instead requires only `filesystem.root`, pointing to an existing directory. It needs no provider,
model, system prompt or generation options, and can be selected at startup or through AGENTS. Each submitted prompt
reviews that directory and writes `fs-structure.json` and `state.jsonl`; selection alone does not execute a review.
See [per-agent filesystem configuration](CONFIG_AGENT_PROVIDER.md#per-agent-filesystem-configuration).

For OAI, set the provider `endpoint` to the API base URL, not its final `/chat/completions` route. No model-wide default
is inherited from the endpoint or environment.

## REPL

```bash
sbt --server -no-server 'repl/run --user-name fabio --server ws://127.0.0.1:8080/rpc'


sbt --server -no-server 'repl/run --user-name fabio --server ws://127.0.0.1:8080/rpc --agent Gemma'
```

`--agent` is optional. Without it, startup stays unselected—there is no first-agent/default fallback.
Voice/TTS is unavailable; `--transcriber-agent` is rejected.
Unknown CLI options are named in the error, followed by valid usage; missing values and duplicate options report their specific cause.

The REPL always uses the JLine client and requires an interactive terminal.
See the [REPL guide](REPL.md) for commands, server storage and unavailable features.

For server-owned filesystem stores, protect raw recordings and backups. The current audio schema does not decode/migrate
old inline/redacted audio records. Filesystem message/session writes require one owner and serialization across the root.
See [Attachments and blob storage](orchestration/ATTACHMENTS_AND_BLOB_STORAGE_PLAN.md) for the full contract.

## ChatGPT subscription profiles

A configured `ChatGPTAgent` can start logged out, but inference then fails server-side. Profiles use literal private
credentials in the selected server configuration profile. The client has no provider-login menu or `/login` command;
provider administration/login transport remains pending. Refreshed credentials currently remain **only in the cached
server connection's memory**; restarting from stale rotating credentials may require reauthentication.

## Other demos

- [Local RAG demo](../RAG.md).
- [System One / Jev guide](system_one/System_One_Overview.md).
