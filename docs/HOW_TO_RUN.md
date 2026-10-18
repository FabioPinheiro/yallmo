# HOW TO RUN

Run commands from the repository root with a JDK compatible with Scala 3.9 and [sbt](https://www.scala-sbt.org/).

## Verify the checkout offline

```bash
sbt ';model/testOnly *;core/testOnly *;repl/testOnly *'
```

## Initialize a configuration and start the REPL

`--config` is required. A missing file is initialized with the JVM user's name, empty agent/provider catalogs and no
selected agent. On POSIX filesystems the new file is owner-readable/writable only. Publication is atomic and never
overwrites another process's file; the filesystem must support hard links. Existing files are loaded, not rewritten.

```bash
sbt --server -no-server 'repl/run --config ./yallmo.json'
```

An unselected REPL accepts commands but rejects prompts without sending anything to a provider. Press `Esc`, `a` to
open AGENTS, highlight a configured agent with Up/Down and press Enter to activate it. Switching keeps the current
session, conversation and typed draft, and updates the footer and voice model. Session commands are independent of agent selection.
Selection does not change the configured default. Edit the file and restart to load new definitions; live config reload
is not implemented.

The browser microphone/TTS server port is the top-level `"browserAudioPort": 5180` setting. New config files include 5180;
older files without the setting also use 5180. Valid values are 1–65535. Change the file and restart to apply a new port. The server
still binds `0.0.0.0`; for Docker, publish the configured port only on host loopback and keep both sides of the mapping identical.

The repository-root `yallmo.json` is gitignored. Keep other config paths private and excluded from version control too.
The file may contain OAuth secrets: do not print or share it. Credential values are literal; `${NAME}` references are not resolved.

## Configure agents

Use the sanitized [example](examples/repl-config.json) as a structural starting point. Copy/edit it into your **private**
configuration, not into a committed file containing credentials. Set `userName` to the desired human identity; it must
not collide with a configured agent ID. Agent/provider catalogs are JSON objects keyed by ID.

Each supported agent (`OAIAgent` or `ChatGPTAgent`) needs exactly one matching provider. In its `config`:

- `model` is required and must not be blank;
- `systemPrompt` is optional for both agent types; absent means the standard Yallmo assistant prompt. `OAIAgent` sends
  it as a `system` message; `ChatGPTAgent` sends it as the Responses API `instructions` field;
- `thinkingLevel` is optional: `minimal`, `low`, `medium`, `high` (adapters may ignore unsupported options);
- `parallelToolCalls` is optional and defaults to `false`.

For OAI, set the provider `endpoint` to the API base URL, not its final `/chat/completions` route. No model-wide default
is inherited from the endpoint or environment.

## Select an initial agent

```bash
sbt --server -no-server 'repl/run --config ./yallmo.json --agent bob --storage ./zzz'
```

`--agent` overrides `defaultAgentId`. Without either, startup stays unselected—there is no first-agent fallback.
Unknown selections fail with an error. The old `--provider` and `--model` flags have been removed.
Unknown CLI options are named in the error, followed by valid usage; missing values and duplicate options report their specific cause.

The REPL always uses the JLine client and requires an interactive terminal. Streams, pipes and dumb terminals are
not supported; there is no fallback client or UI selection flag. See the [REPL guide](REPL.md) for commands, storage
and voice behavior.

## ChatGPT subscription profiles

A configured `ChatGPTAgent` can start logged out, but inference then fails locally. Profiles use literal private
credentials in the selected configuration profile. In the line UI, press `Esc`, `p`, select the ChatGPT profile, and choose login
inside its details. Restart after saving credentials. There is no global `/login` command.
Refreshed credentials currently remain **only in the cached connection's memory**;
restarting from stale rotating credentials may require reauthentication. See [the provider guide](OPENAI_SUBSCRIPTION.md).

## Other demos

- [Local RAG demo](../RAG.md).
- [System One / Jev guide](system_one/System_One_Overview.md).
