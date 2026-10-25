# HOW TO RUN


## Connect to a configured standalone server

```bash
# Private configuration defines bob and its provider; server owns config, tools and filesystem.
mkdir -p ./server-storage ./server-odin-workspace
sbt 'server/run --storage ./server-storage --config ./yallmo.json --workspace ./server-odin-workspace'
# Separate interactive terminal (both --server forms accepted):
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
`/tools`, `/debug`, provider catalog/login, voice/TTS and uploads remain unavailable remotely. `--transcriber-agent`
with `--server` is rejected before networking. `--config`/`--storage` remain server-only; credentials/query/fragment in
`--server` are rejected. Connection failure exits without local fallback or autodiscovery. Without `--server`, local
mode is unchanged. See [connected REPL semantics](REPL.md#explicit-connected-mode).

## Initialize a configuration and start the local REPL

`--user-name <id>` is required and supplies local persisted authorship.
The embedded server independently loads `yallmo.json` in the working directory; the REPL has no `--config` option or config-file access.
A missing server file is initialized with empty agent/provider catalogs and no selection.
On POSIX filesystems the new file is owner-readable/writable only.
Publication is atomic and never overwrites another process's file; the filesystem must support hard links. Existing files are loaded, not rewritten.

```bash
sbt --server -no-server 'repl/run --user-name operator'
```

An unselected REPL accepts commands but rejects prompts without sending anything to a provider. Press `Esc`, `a` to
open AGENTS, highlight a configured agent with Up/Down and press Enter to activate it. Switching keeps the current
session, conversation and typed draft, and updates the chat footer. The independent `--transcriber-agent` selection do not change. Session commands are independent of agent selection.
Selection does not edit configuration. Edit the file and restart to load new definitions; live config reload
is not implemented.

The browser microphone/TTS server port is the top-level `"browserAudioPort": 5180` setting. New config files include 5180;
older files without the setting also use 5180. Change the file and restart to apply a new port.

The repository-root `yallmo.json` is gitignored. Keep other config paths private and excluded from version control too.
The file may contain OAuth secrets: do not print or share it. Credential values are literal; `${NAME}` references are not resolved.

Without `--server`, the supplied human is registered in the local server runtime before assembly and used as the
persisted author, without authentication. Explicit WebSocket mode above supports configured trusted-local prompts;
provider-login/voice/human-interaction transport remains pending. This is not full remote parity or a public-auth boundary.

## Configure agents

Use the sanitized [example yallmo.json](examples/yallmo.json) as a structural starting point. Copy/edit it into your **private**
server-owned `yallmo.json`, not into a committed file containing credentials. The supplied `--user-name` must
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


sbt --server -no-server 'repl/run --user-name fabio --server ws://127.0.0.1:8080/rpc --agent Gemma --transcriber-agent Transcriber'
```

`--agent` is optional. Without it, startup stays unselected—there is no first-agent/default fallback.
Voice is independently opt-in with `--transcriber-agent Transcriber`; absent means no transcription.
Unknown CLI options are named in the error, followed by valid usage; missing values and duplicate options report their specific cause.

The REPL always uses the JLine client and requires an interactive terminal.
See the [REPL guide](REPL.md) for commands, storage and voice behavior.

For server-owned filesystem stores, protect raw recordings and backups. The current audio schema does not decode/migrate
old inline/redacted audio records. Filesystem message/session writes require one owner and serialization across the root.
See [Attachments and blob storage](orchestration/ATTACHMENTS_AND_BLOB_STORAGE_PLAN.md) for the full contract.

## ChatGPT subscription profiles

A configured `ChatGPTAgent` can start logged out, but inference then fails locally. Profiles use literal private
credentials in the selected configuration profile. In the line UI, press `Esc`, `p`, select the ChatGPT profile, and choose login
inside its details. Restart after saving credentials. There is no global `/login` command.
Refreshed credentials currently remain **only in the cached connection's memory**;
restarting from stale rotating credentials may require reauthentication. See [REPL profile behavior](REPL.md#chatgpt-subscription-generation).

## Other demos

- [Local RAG demo](../RAG.md).
- [System One / Jev guide](system_one/System_One_Overview.md).
