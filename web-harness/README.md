# Web harness

Scala.js / Laminar session inspector and trusted-browser chat. See [the server guide](../server/README.md) for development,
production bundling, embedding, API documentation and security boundaries.
For keyboard shortcuts, modes and dictation keys, use the [client controls quick reference](../docs/CLIENT_CONTROLS.md).

The interface uses a dark theme by default, including native form controls and dialogs.
Shared color tokens live in `vite/style.css`.

## Layout

- `src/` — Scala.js sources and tests.
- `vite/` — JavaScript, CSS, HTML, JavaScript tests, Vite config and npm manifests.
- `vite/node_modules/` — npm-managed dependencies (ignored by Git, retained by `sbt clean`).
- `target/scalajs-dev/` and `target/scalajs-prod/` — sbt linker output.
- `vite/dist/` — production bundle embedded by the server.
- `target/vite/cache/` — Vite's generated cache.

Run npm from `vite/`, or use `npm --prefix web-harness/vite` from the repository root.

```sh
# Repository root:
npm --prefix web-harness/vite ci
sbt 'webHarness/fastLinkJS'
npm --prefix web-harness/vite run dev
```

The backend must be running on loopback port 8080 (`sbt 'server/run --storage ./zzz'`). Vite uses `host: true` to listen
on all interfaces, including Docker, and proxies `/rpc` with `ws: true` to `ws://127.0.0.1:8080`, preserving the real
browser Origin. Keep the dev server/proxy private: publish Docker's Vite port on host loopback only (for example
`127.0.0.1:5173:5173`), or use a trusted network. Backend loopback binding alone does not protect the Vite proxy.

Static assets still use HTTP; application requests and attachment bytes use JSON-RPC exclusively. No Scala.js/Vite sbt
bridge is needed: Vite imports the output of the separately-running linker. Seven hash-linked navigation links provide chat and show sessions,
all messages, a small CSS conversation tree,
registry agent IDs, stored blob IDs and the Scalamode playground. Reconnect refreshes the active view; requests from an unmounted tab cannot update
the current view. Function-call JSON is pretty-printed. Tool-result objects show compact inline scalar fields
(e.g. exitCode, timedOut, truncated) and labeled output with decoded line breaks; unknown fields are retained and
invalid JSON falls back to plain text. Long text starts collapsed in native details elements. Graph nodes expand to
the same message cards, including tool results and attachment links.

One application-root WebSocket is shared across tabs, initialized on mount and closed on unmount/unload. Reconnect
explicitly reconnects after a disconnect; failed queries are not replayed automatically. Attachment downloads validate
and assemble bounded base64 chunks into a local browser Blob (64 MiB maximum), with local object-URL cleanup and
visible partial-transfer errors. There is no Fetch client or HTTP blob fallback.

The browser depends on `protocolJS → modelJS`, using the shared JVM/JS JSON-RPC/application protocol and domain types.
There is no direct source inclusion, JVM core dependency or duplicate browser DTO layer. The cross-projects use
`shared/src`, `jvm/src` and `js/src`; System One/macros are included and tested on both platforms.

## Scalamode

Open **Scalamode** (`#/scalamode`, NORMAL shortcut `7`) to run a Scala string without asking an LLM to generate or execute
a conversation turn. Choose an agent using the **same toolbar picker as Chat**, edit the code, and use
**Run scalamode** for restricted Scala execution. Both views use the current conversation's selected agent;
Scalamode has no separate agent selection. The shared agent picker and run button are disabled while a request is pending.
The page sends an ordinary `agents.invoke` message containing
`ToolCall(name = scalamode, argumentsJson = {"code":"..."})` and displays its correlated `ToolOutput`:
stdout/result, compiler/runtime diagnostics, exit code, timeout and truncation metadata.
Editor text is treated only as text; no Markdown/HTML is executed by the browser.

A configured backend, initialized Operator and advertised `agents.invoke` method are required. Tool availability and
the selected agent's capability allowlist are checked by the server, not inferred from the agent selector. Scala CLI
must exist on the backend; restricted execution also needs the pinned offline compiler cache. `ApplicationTools`
registers the restricted tool.
Unavailable or denied tools return server errors. Restricted execution still accepts
only `{"code":"..."}`, with the same compiler restrictions, injected `tool.api` helper and execution limits.

Source, pending state and the last result live at application root and survive tab navigation, not
page reload. Editing while running does not change the submitted code. Double clicks cannot
submit twice; only a matching author/parent/call ID/tool name response is accepted. Reconnect is blocked while a run
is pending. Disconnect invalidates stale callbacks and never replays the request: admitted backend work may finish.
Inspect All messages before a manual repeat after an uncertain outcome. The input and response are saved there,
without creating a chat session. Leaving the page does not cancel execution.

## Read messages aloud

Each message card (including tool messages and pending chat input) has a small **Read** button. It reads the full
message content verbatim, including Markdown/code and collapsed text, using the browser's default voice. It does not
read card metadata or download attachments. Click **Stop** to cancel; reading another message replaces playback.
Leaving the card's view, page exit and application unmount stop playback. Nothing is read automatically.

Reading uses native `speechSynthesis` and does **not** require a configured `Transcriber`, a microphone, a backend
request or a new session. Unsupported browsers and empty messages have disabled buttons. Browser errors/stalls show
a notification without retrying. Browser voices may use network services; local synthesis and audible output cannot
be guaranteed. Text preparation is kept separate from playback for a future speech-ready summarization flow; no
summarization or transcription is implemented in the read-aloud flow.

## Dictation

In Chat, **Dictation** appears beside **Send** only for an initialized Operator whose configured agent catalog contains the exact ID
`Transcriber` and whose server advertises both `blobs.upload` and `agents.invoke`. No chat agent needs to be selected.
This catalog check does not prove endpoint/model audio compatibility; normal server configuration/provider checks
still apply. Configure an audio-capable `Transcriber` with transcription instructions; use explicit empty capability
allowlists for tools/retrievers/skills/extensions if you want transcription only. There is no web-specific voice RPC.

Microphone capture requires **HTTPS or localhost**, microphone permission, Web Audio and AudioWorklet support.
Unsupported browsers show a disabled control with an explanation. Nothing requests microphone access until a click.
The first click requests access and starts recording; **Stop** finishes capture and transcribes it. **Discard**
stops/releases the microphone without uploading. Permission/setup and worklet completion have deadlines;
late permission grants after cancellation are immediately released. Tracks close after capture, not after inference.

The elapsed timer highlights the last five seconds. At **30 seconds**, recording stops automatically, a visible
notification announces the limit, and transcription starts once. There is no beep or automatic restart. Repeat with a
new explicit click. Starting capture stops browser speech; Read buttons are disabled only while microphone capture is
active to avoid recording synthesized speech.
Send/session creation/reconnect are blocked during dictation, while the draft stays editable.
Leaving Chat, switching conversations, page exit/freeze or disconnect discards capture and prevents stale results from changing any draft.

The resampler/WAV encoder (`vite/audio-core.js`), browser adapters (`vite/browser-audio.js`) and AudioWorklet
(`vite/capture-worklet.js`) live in this project and are also imported by the REPL's `browser-STT-TTS/` page. One bounded mono 16 kHz PCM16 WAV (at most 960044 bytes) is uploaded through ordinary
`blobs.upload` commands, all keyed by the recording's `BlobId` (Begin echoes it; no separate upload ID);
a normal `agents.invoke` message to `Transcriber` is submitted while chunks are arriving.
Chunks are sequential and acknowledgements are checked; invocation has no ordinary read deadline. Transfers never
replay across reconnect and never create/submit a chat session. The server persists audio, input and final response
through the generic APIs; those messages appear in All messages, not as a new conversation.

The transcript replaces the selection/inserts at the cursor remembered before clicking Dictation. If the draft was edited
in the meantime or no usable cursor exists, it appends to the latest originating draft instead. Nothing is sent to a
chat agent automatically. **Discard** during upload/transcription ignores the result and best-effort aborts an
unfinished upload; it **does not cancel agent execution or delete already stored recordings/messages**. Work may still
finish after discard/disconnect. Errors retain draft text and never retry; inspect saved messages before a manual retry
when the outcome is uncertain. Browser buffers live in memory only, with no storage APIs or inline audio in logs.

Checks: `npm --prefix web-harness/vite test`, `sbt ';webHarness/testOnly *;webHarness/fastLinkJS'`, and production linking/bundling.
Recorder tests mock permission, deadlines and device lifecycle; Scala.js tests cover transfer ordering/bounds,
acknowledgements, configured-agent gating, stale responses and cursor-safe draft updates. Browser smoke checks used
the production AudioWorklet under the server CSP with a synthetic MediaStream and mocked RPC responses, including
limit/discard/navigation behavior. These are **not real microphone, OS permission or remote provider evidence**.
Manual acceptance still requires an actual audio-capable Transcriber and microphone: grant/deny permission, speak,
stop/discard, reach the limit, edit the draft while waiting, navigate/disconnect, and verify actual transcript quality.

## Chat (trusted browser)

Open **Chat** (`#/chat`) from the full-height left sidebar. Each page load defaults to `fabio` and connects once
as `user-fabio`. Edit the compact toolbar's **Username** and press **Reconnect** to apply it; typing never disconnects.
With an unchanged name, Reconnect reuses the connected socket and refreshes state. Empty names disable Reconnect;
creation, pending turns and connection setup also block it. Claimed authorship is **not authentication**: every reachable
client can claim a name and execute configured agents/tools. Keep the backend and Vite proxy on trusted networks.

Choose an agent in the shared toolbar from the configured IDs advertised by `initialize` (not the mixed runtime
registry in Agents). Send behavior and shortcuts are defined in the [client controls guide](../docs/CLIENT_CONTROLS.md#website-keys).
A blocked send attempt shows a small temporary notification (also logged to the browser console); with text but no
agent, a warning appears beside the disabled Send button. Failures also notify, while important recovery instructions
remain in the conversation. Notifications expire after five seconds and never retry requests.

The first Send creates a conversation only after an agent is selected, retains its returned ID and submits once.
Later turns reuse that session and its known head. **New conversation** also creates an empty session; expand
**Name (optional)** to supply a safe name. Choose an existing session, including a REPL session, in the sidebar to
resume it in Chat from any view. On narrow screens, **Menu** expands navigation and conversations. Agent changes
and resumes reset the next provider continuation without clearing history or drafts.

The transcript uses authoritative persisted snapshots and the inspector's message/tool cards and downloads.
Send immediately adds a temporary user card labeled **Pending — not yet confirmed saved**, even while the first
conversation is being created. It captures the submitted text and connected identity, follows its conversation across
tabs, and is replaced by the returned snapshot on completion, never persisted by the browser. Sending clears the
composer immediately and leaves it editable for your next draft; Send stays blocked while waiting, with the waiting
status beside it. Completion never erases that next draft. On failure or disconnect, the temporary card clears and
recovery instructions remain; the submitted text is restored only if the composer is still empty. There is no streaming,
progress or execution cancellation for chat turns, and no automatic retry. Dictation uses the independent upload/invoke path above.
Turn submission has no read deadline; ordinary reads, creation and diagnostics remain bounded. A pending turn and
per-conversation drafts live at application root and survive tab/session navigation, but not page reloads.
New messages, including pending cards, follow the scroll position only when near the bottom.

Busy/conflict, failed outcomes and uncertain transport results preserve your next draft (or restore the submitted text
if empty) and require a successful history
reload before manual resend. A disconnected turn **may still be running**: reconnect, inspect history, and avoid
sending a duplicate prompt. Reconnect never replays requests. The client also exposes `diagnostics.request` through
the shared protocol DTOs; a diagnostics UI is intentionally deferred.

Validation: `sbt ';webHarness/testOnly *;webHarness/compile;webHarness/fastLinkJS'`; production: `sbt 'webHarness/fullLinkJS'` then
`npm --prefix web-harness/vite run build`. Browser-adapter tests use the existing fake WebSocket seam; chat transition tests
cover create-once/send-once, configured agents, stored heads, draft retention, continuation reset, stale responses,
failed outcomes and uncertain transport. Manual smoke check: verify the default identity, edit a name and Reconnect, send to a configured agent, switch views
while waiting, resume a REPL session, change agents, and verify narrow-screen sidebar and scroll behavior.

Upload staging is storage-owned: the wire still uses the caller-created BlobId for Begin/Append/Complete/Abort.
Only the connection that began an upload can control it; disconnect aborts unfinished uploads. Accepted chunks refresh
10-second idle expiry but never the 60-second absolute lifetime. Keep using a fresh BlobId when retrying after failure.
Invocation waits only for known active dependencies with a bounded server read timeout; completed reads remain immediate.
