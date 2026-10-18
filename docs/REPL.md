# Interactive REPL

> The source package is `fmgp.yallmo.repl`. This file is named `REPL.md` to retain the project’s historical spelling.

The interactive client preserves one in-memory `RunContext` across submitted prompts while keeping its
`StaticContext` fixed for the session. It is a composition layer over `ReplApplication`, `ReplSession`, and
`AgentRunner`; it does not create a second agent execution path.

## Start the terminal client

`--config <file>` is required. Missing files are initialized privately with the JVM user name, empty catalogs and no
selected agent. See [setup](HOW_TO_RUN.md) and the sanitized [example](examples/repl-config.json).

```bash
# Keep sbt attached to this terminal so JLine can use its TTY.
sbt --server -no-server "repl/run --config ./yallmo.json"
sbt --server -no-server "repl/run --config ./yallmo.json --agent bob --storage ./zzz"
```

`--agent` overrides `defaultAgentId`; absent both, startup stays unselected and prompts are rejected without an agent
or tool call. Commands remain available. Press `Esc`, `a` to choose an agent from the loaded configuration.
The old `--provider`/`--model` flags are removed. Models, prompts and
optional thinking settings belong in each agent's `config` object. `userName` supplies the human message-author ID.

The REPL always uses the JLine client, with editable input, history, completion, menus, status and voice support.
An interactive terminal is required; dumb terminals and redirected streams fail without fallback. There is no
`--ui` option or alternate terminal implementation.

## ChatGPT subscription generation

Configure a `ChatGPTAgent` referencing a named `ChatGPT` provider profile and select its agent ID. A logged-out profile
does not prevent startup; inference requires its literal credentials. Separate profiles never fall back to
a different profile or a paid platform API. In the line UI, inspect a ChatGPT profile in PROVIDER to
log in and save credentials privately to that profile's config. Restart is required to use them; login does not
switch agents or update the running provider snapshot. Token refresh still stays only in memory.
See [setup, privacy and limitations](OPENAI_SUBSCRIPTION.md).

## Conversation labels

The input prompt uses the configured human `userName`, e.g. `yallmo(fabio)>`. Replies use the actual responding agent
ID, e.g. `assistant(bob)>`. Selecting Eve changes subsequent replies to `assistant(eve)>` without changing the session.
`/history` loads the current session's stored messages and labels user messages and assistant replies with their
original authors, including after an agent switch or session resume. Output starts with `### Session history '<session_name>'`
and ends with `### END OF SESSION`. Before a session exists, history is empty and the header uses `no session`.
Names are sanitized for terminal safety. These labels are presentation only: message payloads, provider requests and
stored author IDs are not rewritten.

## Active-agent footer

Capable terminals show one bottom status line: local INSERT/NORMAL/PROVIDER/AGENTS/voice hints on the left and
`<agent-id> (<provider> - <model> • <thinking-level>)` on the right. Provider labels are configured instance IDs,
such as `local` or `chatgpt-2`, not just adapter types. All values come from the selected native agent's configuration;
unselected startup has no invented agent/model footer. Unspecified thinking
is shown as `default` (not `off`, and not a claim about the server's effective default); explicit levels are lowercase.
Agent selection in AGENTS updates this footer together with the actual runtime, system prompt and voice provider/model.
Login and session commands do not change the selected agent.

The footer reflows on terminal resize using display-column widths. On narrow terminals metadata takes priority over
local hints and is truncated with an ellipsis as needed. Configured labels have ANSI/control characters removed.
Terminals without status-bar support omit the footer.

## Line-editor modes

The REPL starts in `[NORMAL]`. Press `i` to enter `[INSERT]` and type a draft; `Esc` returns to NORMAL.
After ordinary submissions, subsequent prompts retain the INSERT default. Menu handoffs restore their own mode and draft.
NORMAL is an explicit command/navigation map rather than the full Vi editor.

| Mode | Keys |
| --- | --- |
| `[INSERT]` | Type a draft, `Enter` to submit, `Esc` for NORMAL. `p` and `a` are ordinary text. |
| `[NORMAL]` | `p`: PROVIDER, `a`: AGENTS, `i`: edit, `v`: voice input, `q`: exit. Only `i` enters INSERT. `Esc` and unrecognized keys do nothing. Non-editing Vi movement and arrow keys remain available. |
| `[PROVIDER]` | `Up`/`Down` (or `j`/`k`): select; `Enter`: inspect/New/confirm; `r`: refresh list; ChatGPT details only: `Enter` or `l` for browser login; `Esc`: back, then NORMAL; `Ctrl+C`: cancel form. |
| `[AGENTS]` | Lists configured agents, types, provider IDs and models; marks the active agent. `Up`/`Down` or `j`/`k`: highlight, `Enter`: activate, `Esc`/`Ctrl+C`: cancel to NORMAL, `i`: edit. |

The footer displays controls for the active mode; AGENTS does not repeat that hint in its list. Navigation does not submit or change the chat draft or
cursor; `Enter` in menus/forms never submits a chat prompt. Catalog actions erase their transient prompt and redraw in place,
without adding `yallmo>` lines to scrollback. The chat cursor is hidden while either picker is open and restored on return,
login handoff, interruption or exit. From PROVIDER, press `Esc` back to its list, then `Esc` to
NORMAL and `i` to edit the original draft. AGENTS also supports `i` directly. Profile login bypasses command parsing,
chat buffers and history. The managed menu is cleared during private auth input and restored to the **same profile's
details** after success, cancellation, timeout or failure, with credential presence reloaded from disk. The exact chat
draft and cursor remain unchanged; no global credentials are read or modified.

Agent selection is **independent of sessions**: selecting a different agent keeps the current session ID, complete
conversation, editable draft, log level and last provider-request capture. Subsequent replies use the newly selected
runtime and are saved with that agent's identity; previous message authors are unchanged. Any session can be resumed
with `/session resume <id>` regardless of the selected agent, without changing that selection. Agent selection itself
never creates or resumes a session; the usual first-turn session creation and explicit session commands are unchanged.
Opaque provider continuation data from before a switch or resume is retained in the transcript but excluded from
requests to the new binding; ordinary conversation messages are preserved. Selecting the current agent is a no-op. Resolution failures keep the previous agent and conversation and show an error
inside the picker. Selection is process-local; it does not rewrite `defaultAgentId` or reload the config. Empty catalogs
show setup guidance. Agent creation/deletion is not implemented.

`/login` is not a REPL command: it is treated like any unknown command and excluded from help and Tab completion.
Login is available only inside the selected ChatGPT provider’s details; there is no global login action.

## Provider catalog

`Esc p` opens every configured provider in the explicit `--config` file, even with no selected agent. Arrow keys and
`Enter` open configuration details: ID, type, sanitized endpoint and credential presence. Presence is **not** a validity
or successful-authentication check. Listing, inspection and creation do not initialize providers, perform OAuth or
contact endpoints. `r` rereads the file. AGENTS selects the active agent; PROVIDER does not select or assign a provider.

Select **New provider**, choose a type with arrows and `Enter`, and enter an exact, case-sensitive ID:

- **OAI**: also enter an absolute HTTP(S) base URL with a host, for example ID `local-extra` and URL
  `http://localhost:12434/v1`. The URL is hidden while typing; review omits userinfo, query and fragment.
- **ChatGPT**: for example ID `subscription-extra`; no endpoint or secret is requested. The trusted adapter default
  endpoint is shown, and the saved profile is logged out. Inspect it to log in, even without a selected agent.

Only **ChatGPT details** offer selectable **Login (browser)** (or **Re-login** when credentials
are present). Use `Enter`, or `l` scoped to those details. OAI details have no login actions; the provider
list has no global login entries or `l` actions. Bracketed pastes cannot trigger menu actions. No browser is opened
automatically. Paste a browser callback only at the private hidden redirect prompt, never in chat. Empty input,
`cancel`, Ctrl+C or EOF cancels browser input; Enter/Ctrl+C/EOF cancels browser exchange. Authentication is timeout bounded.
Saved feedback means only that the selected profile's credentials were persisted to the private config, with an
explicit restart notice. It does not mean the active agent/runtime is now logged in. Cancellation/failure does not
write credentials. Other profiles are never a fallback.

Forms support typing and Backspace (append/erase, not a general cursor editor), with a visible 4096-unit UTF-16 field
limit. `j`/`k`/`l`/`d` are ordinary form text. `Enter` advances to review; only the explicit **Save provider** confirmation
writes. `Esc` goes back; leaving type selection or `Ctrl+C` abandons the draft. **Cancel** also abandons it. Validation
or save failure retains the form for correction/retry. Once publication starts, cancellation is not a rollback.
Form values and menu actions never enter chat history, agent transcripts or command parsing. IDs are displayed with
terminal-control/format characters sanitized; malformed configured endpoints show `<invalid endpoint>`. Credentials,
URL userinfo/query/fragment and filesystem exception contents are not rendered. Store the config in a trusted private
directory: saved OAI URLs can themselves contain sensitive values even though summaries hide them.

A successful save refreshes the menu and atomically replaces the same config file using a same-directory private
`rw-------` temporary file. Existing provider credentials, agents, defaults, user settings and unknown JSON values are
preserved semantically; whitespace/object layout may change. Duplicate IDs/JSON keys and invalid configurations fail
without clobbering the file. Missing, symlink and nonregular targets are refused by the menu (startup separately
initializes missing configs). POSIX permissions and atomic replacement are required; unsupported filesystems fail
closed, with no non-atomic fallback. This is atomic publication, not an fsync/power-loss durability guarantee.

The running registry retains an immutable **startup configuration snapshot**. AGENTS can switch between agents in
that snapshot. To use a newly saved provider, configure an agent referencing its ID and restart to load those definitions.
Provider edit/delete and live config reload are not implemented. Saves are serialized only within this catalog instance; coordinate external writers because
another process racing between read and replace can lose updates. Do not edit concurrently.

## INSERT commands

| Command | Meaning |
| --- | --- |
| `/help` | Show commands. |
| `/history` | Render the current agent transcript. |
| `/tools` | Show the startup availability snapshot for agent tools. |
| `/debug print` | Print the current transcript as Pi-style JSONL. |
| `/debug export` | Replace `~/.yallmo/yallmo-debug.log` with the current snapshot. |
| `/debug request` | Print the latest raw request payload sent by the current session to the LLM provider. |
| `/log <trace\|debug\|info\|warning\|error\|fatal\|off>` | Set the minimum application log level for subsequent REPL turns. The default is `error`; use `off` to suppress them. |
| `/new [name]` | Start a fresh session. With `--storage`, persist it immediately; provide `name` to choose its ID. |
| `/session list` | List sessions held by the configured storage; the active session is marked with `*`. |
| `/session resume <id>` | Restore a stored session by ID. Use `--storage <directory>` to retain it across REPL processes. |
| `/exit` | Exit the client. |

A REPL starts with no selected session. Without `--storage`, sessions use process-local in-memory storage. `/new`
creates an empty session named `main-YYYYMMDD-HHMMSS` (UTC), or `/new <name>` creates the requested safe name. If a
prompt is submitted before `/new`, the REPL creates the same timestamped session automatically. Session IDs are shown
by `/session list`; use `/session resume <id>` to restore one.

Press Ctrl+C twice consecutively to exit. Prefix a prompt with `//` to send a literal prompt beginning
with `/`. Provider request captures are written under `debug/` relative to the REPL's startup directory and can contain
prompt and tool-result content; `/debug request` displays only the most recent capture from the current session. Captures
are not automatically cleaned up. Use `/debug request` only in an appropriate local terminal. Provider continuations are
redacted before capture.

## Voice input lifecycle

Voice input is a local input feature, not an agent tool. It is not advertised in `StaticContext`, cannot be requested by
the model, and does not appear in `/tools`.

It uses only the selected audio-capable connection and that agent's configured model. In the current adapters this
means OAI; ChatGPT-selected and unselected REPLs report voice as unavailable. There is no fallback
to a different provider/account. The chosen OAI endpoint/model must actually support the audio dialect below.

### Editable voice insertion

In the terminal client, INSERT uses JLine's Vi insert map and NORMAL uses explicit command/navigation bindings. While editing a draft, press `Esc`, then
`v` to enter local recording mode. Speak, press `Space` to stop, or `Esc` to cancel. The client transcribes the audio
and inserts the text at the original cursor position; it does **not** submit the draft. Press `Enter` only after making
any desired edits (`i` enters INSERT). A 30-second capture limit remains in effect. On capable terminals, a persistent bottom status bar
shows `[INSERT]`, `[NORMAL]`, `[PROVIDER]`, `[AGENTS]`, `[RECORDING]`, or `[TRANSCRIBING]` together with the relevant keys; it is hidden when
the terminal cannot support a JLine status bar.

`Esc` first switches from INSERT to NORMAL; pressing it again in NORMAL does nothing. `v` is a terminal-client keybinding, not a
REPL command, so no `ReplApplication` state or agent transcript changes until the draft is later submitted. In NORMAL,
`q` submits the typed `/exit` command and closes the REPL. Voice completion, cancellation or failure returns to NORMAL; press `i` to edit.

```mermaid
sequenceDiagram
    actor User
    participant Line as Local line client
    participant Recorder as AudioRecorder
    participant Transcriber
    participant Model as Selected OAI endpoint/model
    participant Agent as normal REPL / AgentRunner

    User->>Line: Esc, v
    Line->>Line: preserve editable draft and cursor
    Line->>Recorder: record until Space or Esc (maximum 30 seconds)
    Recorder->>Recorder: microphone → in-memory 16 kHz mono WAV
    Recorder-->>Line: AudioInput(WAV bytes)
    Line->>Transcriber: transcribe(audio)
    Transcriber->>Model: validate audio size, then send prompt and base64 WAV
    Model-->>Transcriber: text result
    Transcriber-->>Line: Transcript(text)
    Line->>Line: insert transcript at the saved cursor
    Note over User,Line: User reviews and edits the draft, then presses Enter.
    User->>Agent: submitted draft
    Agent-->>Line: assistant response
    Line->>User: assistant> response
```

### Initialization

At REPL startup, `ReplMain` builds these components:

1. `ConfigFile` loads/initializes the required config; `RuntimeInstances` owns lazy connections and named agents.
   Only the explicit/default selected agent is resolved; unselected startup creates no provider instance.
2. An audio path using that same selected connection/model if it supports `LlmProviderAudio`. Otherwise voice fails
   locally on invocation rather than choosing an unrelated provider.
3. `AudioRecorder`, a local Java Sound implementation. It does **not** open the microphone at startup.
4. `Transcriber`, which owns the transcription-only system prompt and makes one audio-only request per call.
5. `TerminalRepl`, which invokes `AudioRecorder` and `Transcriber` for the editable `Esc v` keybinding.

The microphone is opened only by `AudioRecorder.record` or `AudioRecorder.recordUntil`. The recorder prefers 16 kHz,
16-bit mono PCM, but can capture common 24/44.1/48 kHz mono or stereo device formats and converts them to 16 kHz mono
before encoding the WAV entirely in memory. `Transcriber` submits that audio in a single tool-free, history-free model
request. The resulting transcript is written into the existing local JLine buffer.

### Preconditions

A voice request proceeds only when all of the following hold:

| Boundary | Required condition | Failure behavior |
| --- | --- | --- |
| Client | It runs in the local interactive terminal client. | No recording or agent turn. |
| Invocation | Editable voice is started with `Esc v`. | The draft is unchanged. |
| Local audio | The JVM has microphone permission and Java Sound supports a convertible 16/24/44.1/48 kHz mono or stereo `TargetDataLine`. | `Voice input failed`; state or draft remains unchanged. |
| Payload | The recorded WAV is non-empty and at most 1 MiB. A maximum 30-second capture is approximately 960 KiB plus header. | `OAIAgent` rejects it before HTTP. |
| Model Runner | The selected OAI endpoint is reachable, its agent model is audio-capable, and it accepts the `input_audio`/WAV chat-completions dialect used by `gdict`. | A bounded HTTP/protocol error is displayed; no text is submitted. |
| Response | `OAIAgent` receives non-blank `choices[0].message.content`. | A protocol error is displayed; no text is submitted. |

`OPENAI_API_KEY`, `ffmpeg`, and a recording file are not required by Yallmo’s implementation. The independent
`gdict` script uses `ffmpeg` for its macOS capture path; Yallmo uses Java Sound instead.

### Privacy and SSH boundary

The raw WAV and base64 representation exist only in memory for the audio transcription request. They are not written to disk, placed
in REPL history, or emitted through the provider payload debug facility. The returned transcript is normal untrusted
user input. Editable voice text remains only in the local draft until the user explicitly submits it.

Microphone access belongs to the machine running the REPL process. If `yallmo` is launched on a remote SSH host,
`Esc v` attempts to use that remote host’s microphone—not the local SSH client’s microphone. Local capture with a
remote Model Runner needs a future client/server transport boundary.

## Agent tools

At startup, the REPL selects from `readFile`, `listFiles`, `bash`, and `repositoryChanges` in `fmgp.yallmo.tool`.
A selected tool is placed in both the runner and the agent's static context only when its requirement passes:

- filesystem tools require an accessible workspace directory;
- `bash` requires Bash on `PATH`;
- `repositoryChanges` additionally requires Git on `PATH` and a Git work tree.

`repositoryChanges` is read-only and supports `status`, `unstaged` (`git diff`), and `staged` (`git diff --staged`).
File paths are relative to the REPL startup directory; `../` traversal is intentionally supported. `bash` has the
process’s permissions, a 30-second default timeout, and a 64 KiB returned-output limit; run it only in an appropriate
workspace.

## Troubleshooting

If sbt reports `FileSystemException` for a `target/.../zinc/inc_compile_3.zip` file, remove the stale generated cache
and retry:

```bash
rm -rf target
sbt "repl/run --config ./yallmo.json"
```
