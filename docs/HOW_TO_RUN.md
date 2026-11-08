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
No agent selected means no prompt write or session creation: choose `--agent` or use the agent picker from the [client controls guide](CLIENT_CONTROLS.md#connected-repl-keys). `/new [name]` creates an empty
remote session explicitly. `/session list` and `/session resume <id>` enable restart/reconnect history reads.
Assistant choices use advertised configured IDs, not mixed human/presence IDs; remote model/provider metadata is unknown.


Detached work survives disconnect; Ctrl+C never remotely cancels. A lost reply is uncertain, not permission to retry:
refresh `/history` (or reconnect/resume the known session) before manual resend. Lost create reply: inspect `/session list`.
Busy/head conflict preserves state and requires refresh. No automatic retry/replay, status/cancel or exactly-once claim.

No client-local config/storage/runtime/tools/audio/browser/OAuth is initialized, even when prompts are enabled.
`/debug request` reads the selected session's latest provider capture when the server advertises diagnostic permission.
Negotiated Operators can use the provider picker for server-owned ChatGPT login. `/tools`, `/debug print|export`,
provider creation, voice/TTS and uploads remain unavailable in the terminal client. `--transcriber-agent`
is rejected before networking, even without `--server`. `--config`/`--storage` remain server-only; credentials/query/fragment in
`--server` are rejected. Connection failure exits without local fallback or autodiscovery. Without `--server`, the client connects to
`ws://127.0.0.1:8080/rpc`; it never starts an embedded application. See [REPL semantics](REPL.md#server-connection).

## Trusted Docker or multi-machine access FIXME

Access is assumed already authenticated externally: the server has no authentication or Host/Origin restrictions.
Anyone who can reach it can claim a user and execute configured agents/tools. Never expose it publicly; trust every
client, browser origin and proxy with access. Preserve the real browser Origin; no rewriting or permission opt-in is needed.

## Initialize server configuration

Only the server loads configuration. Use `--config ./yallmo.yaml` (or `.yml`) for commented YAML, or keep
`--config ./yallmo.json` as above. Both use the same typed validation; a missing file initializes
with empty agent/provider catalogs. On POSIX filesystems it is owner-readable/writable only. Publication is atomic
and never overwrites another process's file; the filesystem must support hard links. Existing files are loaded,
not rewritten. Configure agents/providers and restart the server before selecting an agent in the client.

The repository-root `yallmo.json`, `yallmo.yaml` and `yallmo.yml` are gitignored. Keep other config paths private and excluded from version control too.
The file may contain OAuth secrets: do not print or share it. String values may reference the server process environment
with `${NAME}`; missing variables fail loading. `$${NAME}` is literal. Loading and unrelated catalog edits retain the
references on disk, not their values. See [environment-reference rules](CONFIG_AGENT_PROVIDER.md#environment-references).

`--user-name <id>` is required by the client and sent as claimed human ID `user-<id>`, not authentication.
The server alone owns tools, providers and durable storage. Provider listing/login is available in the web harness
and negotiated connected REPL; terminal provider creation, voice and human-interaction integration remain pending. There is no legacy in-process REPL fallback.

For the [YAML example](examples/yallmo.yaml), format rules, comment-preserving updates and the explicit
[JSON → YAML migration command](CONFIG_AGENT_PROVIDER.md#explicit-json--yaml-migration), see the configuration guide.
Loading never migrates or overwrites existing files automatically.

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

## ChatGPT subscription profiles

A configured `ChatGPTAgent` can start logged out, but inference then fails server-side. Profiles use literal private
credentials in the selected server configuration profile. Open the web harness **Providers** tab (`#/providers`),
choose **Log in** / **Reconnect login** on an existing ChatGPT profile, approve the OpenAI browser link, and paste the
full final localhost callback URL into its private field. After **Login saved and active**, new requests use the
new credentials immediately; **no server restart is needed**. Existing in-flight bindings finish with their previous
connection. A saved-only fallback explicitly asks for a restart (for example, a profile added outside the running
configuration snapshot). The connected REPL offers the same server-side login through its provider picker, with
hidden callback input: see [connected REPL controls](CLIENT_CONTROLS.md#connected-repl-keys). See also [browser login](../web-harness/README.md#providers-and-chatgpt-login).
Refreshed credentials currently remain **only in the cached server connection's memory**; restarting from stale
rotating credentials may require reauthentication.

## Other demos

- [Local RAG demo](../RAG.md).
- [System One / Jev guide](system_one/System_One_Overview.md).
