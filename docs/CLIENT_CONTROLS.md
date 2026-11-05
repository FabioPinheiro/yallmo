# Client controls — quick reference

**One guide for keyboard shortcuts, modes and ClientHarness.** Other guides cover setup, configuration and feature
internals; this is the source of truth for how to operate the clients.

- [Modes at a glance](#modes-at-a-glance)
- [Website keys](#website-keys)
- [Connected REPL keys](#connected-repl-keys)
- [Dictation](#dictation)
- [Retained terminal audio and provider controls](#retained-terminal-audio-and-provider-controls)
- [For developers: ClientHarness](#for-developers-clientharness)

> **Availability matters:** the website supports chat and dictation when the server/browser advertise the required
> capabilities. The connected REPL supports provider listing and private ChatGPT login when the server advertises all
> four provider methods for an Operator. Provider creation, voice and speech are **not enabled there**; their retained
> embedded implementations are documented separately below.

## Modes at a glance

| Mode / focus | What you can do | How to leave |
| --- | --- | --- |
| **NORMAL** | Use navigation/action shortcuts without typing into your draft. | `i` edits the message; `a` chooses an agent. |
| **INSERT** — a text field/composer | Type normally. Letters such as `a`, `i`, `m` and `r` are text, not shortcuts. | `Esc` returns to NORMAL without changing the draft. |
| **Website command menu** | Search for an action; `Up`/`Down` or `Tab`/`Shift+Tab` changes focus; `Enter` activates. | `Esc` closes it and restores the previous focus. |
| **Website agent selector** | Use the native selector's arrows and `Enter`; page shortcuts do not intercept selection. | `Esc` leaves the selector. |
| **REPL AGENTS picker** | `Up`/`Down` or `j`/`k` highlights; `Enter` chooses the agent. | `Esc`/`Ctrl+C` cancels to NORMAL; `i` edits the existing draft. |
| **REPL PROVIDERS picker** | Inspect server profiles and privately log in to ChatGPT when negotiated. | `Esc` backs out to NORMAL, preserving the draft. |
| **DICTATION — recording** | `Space` stops and transcribes; `Esc` discards. | Stopping proceeds to transcription; cancelling keeps the draft unchanged. |
| **DICTATION — setup / processing** | `Esc` cancels setup or discards the pending result. Space only stops an already-started browser recording. | Completion or cancellation exits dictation. |

The website follows focus: a text field is INSERT; outside editable controls it is NORMAL. The toolbar shows the
current mode or dictation hints. The REPL starts in NORMAL; ordinary subsequent prompts default to INSERT, while
menu handoffs restore their own mode and draft. NORMAL is a small action/navigation map, not a full Vi editor.

## Website keys

Letter, number and `?` action shortcuts apply only outside inputs, selects and editable content.
The global command shortcut and native navigation/activation controls also work while a field has focus.

| Key | Action | Quick note |
| --- | --- | --- |
| `Esc` | Return to NORMAL / close mobile navigation | During dictation, cancels/discards instead. In the command menu, closes the menu. |
| `i` | Edit message | Opens Chat and focuses the composer; keeps your draft. |
| `a` | Choose configured agent | Focuses the toolbar's native selector. |
| `?` | Open website commands | Searchable list of actions. |
| `Ctrl+K` / `Cmd+K` — **global** | Open website commands | Works while typing, too. |
| `1` | Chat | |
| `2` | Sessions | |
| `3` | All messages | |
| `4` | Conversation graph | |
| `5` | Agents registry | This is not the configured-agent selector. |
| `6` | Blobs | |
| `j` / `k` | Focus next / previous control | Skips hidden/disabled controls; entering a text field switches to INSERT. |
| `s` | Choose conversation | Focuses the conversation list and expands mobile navigation when needed. |
| `n` | New conversation | Uses the same optional name and capability/busy checks as its button. |
| `r` | Reload current view | In Chat, reloads history; never resends a prompt. |
| `u` | Change username | Focuses the name field; Reconnect must explicitly apply it. |
| `m` | Focus Dictation / Stop | Does **not** start recording by itself; activate the focused button with `Enter` or `Space`. |
| `Tab` / `Shift+Tab` | Next / previous control | Native navigation works throughout the website. |
| `Enter` / `Space` on a button | Activate that control | While recording, Space stops dictation rather than activating another button. |

**While composing a message:** plain `Enter` sends, `Shift+Enter` inserts a newline. IME composition and
Ctrl/Cmd/Alt-modified Enter do not send. Capability, selected-agent, history and pending-work checks still apply.

The command menu also offers **Reload conversations**, **Reconnect**, **Stop reading aloud** and **Focus page
content**, without dedicated shortcuts. Its buttons remain mouse-accessible, including the toolbar's mode/commands
button. `Up`/`Down` moves between menu controls; typing `j`/`k` in its search field searches instead of navigating.

Normal shortcuts do not consume text, IME composition or other Ctrl/Cmd/Alt combinations. Held keys do not retrigger
NORMAL actions or dictation effects. Native text editing, scrolling, details/summary and button controls remain usable.
Disabled or unavailable actions do not bypass guards or retry work.

## Connected REPL keys

These are the controls available through today's client-only REPL entry point. See [REPL setup and commands](REPL.md)
for startup options, negotiated capabilities and slash commands.

| Mode | Key | Action |
| --- | --- | --- |
| NORMAL | `i` | Enter INSERT to type a prompt or slash command. |
| INSERT | `Esc` | Return to NORMAL; keep the draft. |
| NORMAL | `a` | Open AGENTS for this client's configured-agent choice/preference. |
| NORMAL | `p` | Open server PROVIDERS when provider administration is negotiated. |
| PROVIDERS | `Up`/`Down` or `j`/`k`; `Enter` | Highlight and inspect an existing provider; no creation form. |
| PROVIDERS | `r` | Reload safe provider summaries from the server. |
| ChatGPT details | `Enter` / `l` | Start private browser login/re-login for this exact provider. |
| PROVIDERS | `Esc` | Back to the list, then NORMAL, preserving the draft/cursor. |
| AGENTS | `Up`/`Down` or `j`/`k` | Highlight an agent. |
| AGENTS | `Enter` | Choose the highlighted agent; does not submit a chat prompt. |
| AGENTS | `Esc` / `Ctrl+C` | Cancel to NORMAL without changing the draft or selection. |
| AGENTS | `i` | Return directly to editing the draft. |
| INSERT | `Tab` | Complete supported slash commands and arguments. |
| INSERT / NORMAL | `Enter` | Submit the current input; prompts require a permitted connection and a selected agent. |
| NORMAL | Arrows / non-editing Vi movement | Navigate the draft/history; unrecognized keys do nothing. |
| NORMAL | `q` | Run the existing `/exit` command. |
| Terminal input / local wait | `Ctrl+C` | Interrupt locally; a second consecutive interrupt exits. **Does not cancel accepted server work.** |

Repeated `Esc` in NORMAL is a no-op. Only `i` enters INSERT from NORMAL; other Vi editing operators are not enabled.
Choosing an agent does not create/resume a session or clear its draft/history. Model/provider details may be unknown
on a connected client. Read-only connections can choose a preference, but cannot submit prompts.

`?` and Ctrl/Cmd+K are website commands, not implemented REPL shortcuts. Likewise, website `n`, `r`, `s`, `u` and
numbered view shortcuts do not become terminal bindings simply because both clients use ClientHarness.

**REPL login recipe:** `Esc` → `p` → choose a ChatGPT provider → `Enter` → `Enter` or `l`. Open the displayed
OpenAI link, approve access, then paste the **full** final localhost callback URL into the hidden prompt. The localhost
page may fail to load; copy its address anyway. Successful login normally activates tokens for new server requests
immediately, with no restart or agent reselection. A saved-only fallback explicitly asks for restart.

Private callback input bypasses parsing, chat buffers, Tab completion and persistent
history; login leaves the selected agent/session and exact draft/cursor unchanged. Empty input, `cancel`, Ctrl+C or EOF
cancels before exchange. Once completion may have been sent, Enter/Ctrl+C/EOF only abandons the local wait: the server
may still save. Uncertain outcomes are not retried; reload providers before starting again. The pending attempt is
best-effort discarded on every exit; a start whose reply was lost may remain until replacement, disconnect or expiry.
Viewers/older servers do not advertise/install these controls. All OAuth, tokens, config writes and activation stay on
the server via the existing WebSocket; no local provider config or browser/audio server is initialized.

## Dictation

**Website recipe:** `Esc` → `m` → `Space` to start on the focused Dictation button → speak → `Space` again to stop
and transcribe. Use `Esc` instead of that second Space to discard.

| When | Key | Result |
| --- | --- | --- |
| Dictation button focused, not recording | `Enter` or `Space` | Explicitly request microphone access and start capture. |
| Recording, regardless of focused control | Plain `Space` | Stop once and transcribe; neither scrolls nor clicks another button. |
| Any active dictation phase | `Esc` | Cancel setup/capture or discard the pending transcript result. |
| After stopping with Space | Space release / held-key repeats | Consumed; cannot restart recording or activate another control. |
| No active dictation | `Space` | Normal typing or native control activation. |

Dictation keys take priority over the composer and page/menu controls. The toolbar shows **Space: stop · Esc: cancel**
while recording, and **Esc: cancel** while preparing/processing. The 30-second limit stops and transcribes automatically;
nothing restarts automatically. Transcripts enter the draft, never send it to a chat agent.

The website requires the configured `Transcriber` agent, upload/invoke capabilities, a supported browser, a secure
context and microphone permission. See [dictation setup, privacy and lifecycle](../web-harness/README.md#dictation).
Send, session creation and reconnect remain blocked while dictation is busy.

Cancellation before upload discards the capture. Discarding during upload/transcription ignores the result and
best-effort aborts an unfinished upload; it **does not cancel submitted server execution or delete saved audio/messages**.
Review durable results before manually retrying uncertain work. Completion never erases a newer draft.

## Retained terminal audio and provider controls

**Embedded reference only:** these implementations/tests remain in the repository. Connected provider listing/login
is described above; provider creation forms, voice/TTS and the embedded browser-audio bridge remain unavailable in the
connected CLI. There is no local-mode flag or fallback. Do not infer remote support for those retained features.

| Retained mode | Key | Action |
| --- | --- | --- |
| NORMAL | `p` | Open the provider catalog, independent of chat-agent selection. |
| PROVIDER list | `Up`/`Down` or `j`/`k`; `Enter` | Highlight; inspect/New/confirm. |
| PROVIDER list | `r` | Refresh the catalog from its configuration file. |
| ChatGPT provider details only | `Enter` or `l` | Start browser login/re-login for the selected profile. |
| PROVIDER | `Esc` | Back to the list, then NORMAL; `i` edits again from NORMAL. |
| Provider forms | Text / `Backspace`; `Enter` | Append/erase, then advance to review; only **Save provider** writes. Letters such as `j`/`k`/`l`/`d` are form text. |
| Provider forms | `Esc`; `Ctrl+C` / **Cancel** | Go back; abandon the form draft. Publication already started is not rolled back. |
| NORMAL | `v` | Start JVM-microphone voice insertion. |
| NORMAL | `m` | Start browser-microphone voice insertion; lazily acquire the bridge. |
| NORMAL | `t` | Speak the latest nonblank assistant text in the current conversation; a new request replaces speech. |
| Waiting for capture / recording | `Space`; `Esc` | Stop/transcribe; cancel without transcription. Terminal Stop can be requested during microphone setup. |

Voice completion, cancellation or failure returns to NORMAL; `i` edits again. Empty/failed/cancelled captures preserve
the exact draft and NORMAL cursor. Capture completion/error/deadline releases the wait without another key. Browser
bridge status distinguishes waiting for actual microphone acknowledgement from recording; transcription is a separate phase.

Pickers preserve the draft/cursor, never submit a chat prompt, and redraw transient UI without adding chat history
or extra chat prompt lines to scrollback.
The chat cursor is hidden during picker interaction and restored on return, login handoff, interruption or exit.
Private provider login bypasses chat parsing/history and restores the same profile's details afterward. In the private
callback prompt, empty input, `cancel`, Ctrl+C or EOF cancels; during browser exchange, Enter/Ctrl+C/EOF cancels.
See [provider configuration and credential handling](REPL.md#provider-catalog).

Setup and storage/privacy details stay in [retained terminal voice documentation](REPL.md#editable-voice-insertion) and
[browser-audio setup](../browser-STT-TTS/README.md); they do not redefine the bindings here.

## For developers: ClientHarness

ClientHarness (`client-harness/`) is the JVM/Scala.js module for shared interaction policy. It depends on neither client
nor server runtime, protocol, core or model. The [module diagram](../README.md#module-dependencies) shows the build graph.

| Source | Responsibility |
| --- | --- |
| [ControlAction / KeyboardControls](../client-harness/shared/src/main/scala/fmgp/yallmo/clientharness/KeyboardControls.scala) | Stable command `id`, separate `webLabel` / `terminalLabel`, optional `shortcut`; normalized `KeyInput`, modes, modifier/IME/repeat guards, submission, menu direction and website command search. |
| [DictationKeyboard](../client-harness/shared/src/main/scala/fmgp/yallmo/clientharness/DictationKeyboard.scala) | Shared stop/discard decisions, held-key suppression and matching Space-release consumption; effects are callbacks. |
| [WebKeyboard](../web-harness/src/main/scala/fmgp/yallmo/view/web/WebKeyboard.scala) | DOM event/focus adapter, menu rendering and browser effects. App registers guarded actions; ViewRoute maps routes; VoiceModel translates capture phases to keyboard modes. |
| [TerminalControlBindings](../repl/src/main/scala/fmgp/yallmo/repl/terminal/TerminalControlBindings.scala) | JLine adapter; widget names derive from keymap + action ID, never display labels or additional name arguments. |

IDs are explicit machine names, independent of enum names and display wording. Footer action hints use `terminalLabel`;
website commands/search use `webLabel`. Keymap-scoped IDs let NORMAL and menu handlers coexist without overwriting each
other. Commands with no shortcut use `None`, not an empty binding. Registration never grants a capability.

DOM/JLine rendering, native editor/paste widgets, terminal EOF/interrupts, audio resources, RPC, storage and execution
remain platform-owned. VoiceInsert normalizes terminal controls and uses the same dictation controller; adapters preserve
their existing startup/cancellation behavior. Do not move these effects into the shared policy module.

**When adding a control:** define its stable ID, both labels and optional shortcut in ControlAction; register effects only
in supported clients; add shared policy tests and client lifecycle tests; update the relevant table **in this file only**.
Keep IDs unchanged when editing wording. Feature/setup guides should link here, not copy shortcut tables.

```sh
sbt ';clientHarnessJVM/testOnly *;clientHarnessJS/testOnly *'
```
