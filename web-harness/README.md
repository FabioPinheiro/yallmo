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
browser Origin. It also proxies `/api/sdlc` over HTTP to `http://127.0.0.1:8080`. Keep the dev server/proxy private: publish Docker's Vite port on host loopback only (for example
`127.0.0.1:5173:5173`), or use a trusted network. Backend loopback binding alone does not protect the Vite proxy.

Static assets and SDLC inspection use HTTP; other application requests and attachment bytes use JSON-RPC. No Scala.js/Vite sbt
bridge is needed: Vite imports the output of the separately-running linker. Eight hash-linked navigation links provide chat,
all messages, an interactive timestamp-based conversation DAG,
registry agent IDs, stored blob IDs, the Scalamode playground, SDLC inspection and Providers. Sessions are available
from the conversation sidebar; empty, unknown and former Sessions hashes open Chat. Reconnect refreshes the active view; requests from an unmounted tab cannot update
the current view. Function-call JSON is pretty-printed. Tool-result objects show compact inline scalar fields
(e.g. exitCode, timedOut, truncated) and labeled output with decoded line breaks; unknown fields are retained and
invalid JSON falls back to literal text. Tool calls/results and long messages start collapsed to their header line;
the header toggle expands the body and attachments without a separate text disclosure row. Short messages start open.
Message identity is available beside the UTC timestamp, formatted as `YYYY-MM-DD HH:mm:ss`.
Chat and Graph share the full-height conversation layout, header spacing and bottom composer styles. The graph canvas
fills the remaining space between its controls and composer; typing stays visible without scrolling the whole page.
Graph reuses columns after short forks finish, reserving node/label space and ancestry-edge intervals. Existing message
coordinates never move on append, refresh or filtering; session markers remain distinct below their heads. A bounded
elbow router allows crossings/dotted fallback edges on pathological dense fixed layouts, rather than moving old nodes.
Mouse hover opens a passive safe inspector without changing selection/composer/focus. It stays over node/card or while
focused inside; leaving both dismisses it. Click/tap makes the single draggable inspector persistent; unrelated hover
cannot replace it. Outside click or Escape dismisses it. Touch uses tap, not hover.
The quiet toolbar has search, **Branches** (All / Current / Current + siblings) and a default-on **Live** checkbox.
Current shows focused ancestry and every descendant of the selected node; Current + siblings also adds the subtree of
its nearest ancestral split (including self), excluding remote cousins/unrelated roots. Before selection everything is visible. Search always finds global
inventory and reveals hidden results; All restores all nodes at their original coordinates. Filters never change drafts,
submit parents or storage. There is no permanent node list or extra refresh/fit/zoom/pin/close toolbar.
Graph uses `events.subscribe` / `events.update` on the shared WebSocket while Live is on, mounted, connected and visible,
including read-only viewers. There is no polling timer or recurring inventory read. Callback registration precedes subscribe;
early events and pending observations are bounded to 128 events/operations and 2 MiB UTF-8 JSON each. Canonical session
revisions prevent stale pointers; deltas preserve other sessions. Pending messages are inspectable, not proof of persistence
or selectable composer parents. Uncertainty/gaps/overflow trigger at most one read-only resubscription, never authoring replay;
optimistic ghosts are removed explicitly without discarding confirmed branches or surviving geometry.
Live off invalidates callbacks/unsubscribes and freezes sidebar summaries too; reconnect/keyboard refresh cannot bypass pause.
Live on/visibility return catches up with a fresh baseline. Own branch responses merge while paused or before bootstrap.
Graph reconnect suppresses Chat-owned list/history reads; returning to Chat reloads stale saved history before Send without
losing its draft or resetting continuation. Reconnect remains explicit. Pushed updates preserve viewport, filter, selection,
search and persistent inspector; sidebar upserts never reload Chat transcripts. External-process filesystem writes are not watched.
The bottom **Reply from** composer sends an unnamed branch from the exact selected stored message using the shared
configured agent. It runs the full model/tool loop on persisted ancestry, creates no session and moves no pointers.
Session/missing-node selection clears the parent. Drafts/pending work are independent of Chat; edits and new selection
cannot retarget an in-flight request. Failures may leave saved objects/tool effects: inspect the DAG and explicitly allow
a new submission before repeating uncertain work. There is no automatic retry. See the linked graph guide for API/safety details.
See [Conversation DAG inspector](../docs/CONVERSATION_GRAPH.md) for navigation, push/lifecycle, layout details and
best-effort branch persistence limitations. Persisted prompt/tool/reply artifacts can appear during a pending branch; automatic
reads never retry a branch or clear its explicit recovery gate.

## Shared Markdown and code blocks

SDLC descriptions/steps and all message cards (Chat, pending prompts, All messages and Graph) share the
sanitized Markdown renderer. Fenced `scala` blocks use highlight.js with only the Scala grammar bundled, in the shared
dark palette. Language tags are case-insensitive; unknown/unlabeled languages and Scala blocks over 50,000 characters
remain readable plain code. Inline code stays inline. Fenced `mermaid` blocks retain diagram rendering and source access
on these routes too.

Tool call JSON stays pretty-printed and literal. Decoded tool-result blocks and Scalamode output/logs preserve whitespace
and literal Markdown outside top-level fenced blocks, while supporting Scala highlighting and Mermaid inside fences.
Raw HTML is never executed, Markdown image URLs never load, and code text remains selectable. Metadata, Read speech,
attachment controls, stored content and SDLC Copy Markdown/Raw JSON exports are unchanged.

One application-root WebSocket is shared across tabs, initialized on mount and closed on unmount/unload. Reconnect
explicitly reconnects after a disconnect; failed queries are not replayed automatically. Attachment downloads validate
and assemble bounded base64 chunks into a local browser Blob (64 MiB maximum), with local object-URL cleanup and
visible partial-transfer errors. There is no HTTP blob fallback; Fetch is used only for SDLC inspection.

The browser depends on `protocolJS → modelJS`, using the shared JVM/JS JSON-RPC/application protocol and domain types.
There is no direct source inclusion or JVM core dependency. SDLC uses a small browser projection backed by the full JSON AST,
not duplicated core PlanTask/PlanIntent codecs. The cross-projects use
`shared/src`, `jvm/src` and `js/src`; System One/macros are included and tested on both platforms.

## Providers and ChatGPT login

Open **Providers** (`#/providers`, NORMAL shortcut `p`) on a configured server as a trusted Operator. This lists existing
provider IDs/types and credential presence only; it does not test whether credentials are valid. For a ChatGPT profile,
click **Log in** / **Reconnect login**, open the OpenAI authorization link in a new tab, and approve access. Paste the
full final `http://localhost:1455/auth/callback` URL (including code and state) into the private field, even if that
localhost page fails to load. Click **Complete login**. **Login saved and active** means new requests use the tokens
immediately, without restarting the Yallmo server. Already-running requests keep their existing connection. If the
server can only save (for example, a profile added after startup), it explicitly reports that a restart is required.

All provider-management traffic uses the existing WebSocket (`providers.list`, `providers.login.start/complete/cancel`).
OpenAI login itself opens separately over HTTPS. Tokens and PKCE verifier remain server-side; the callback field is
transient, cleared on submit and tab exit, and never enters chat/history/browser storage. Pending attempts survive tab
navigation but expire after 15 minutes and are dropped on disconnect. A pending start/save disables toolbar reconnect;
network loss makes the outcome uncertain. Reload providers before starting another login; nothing is replayed.

Cancel discards only a still-pending attempt, not an exchange already underway. New provider creation, endpoint editing,
logout and general configuration reload are intentionally excluded. Successful login updates only the selected
provider's live credentials and lazily rebinds its dependent agents, preserving their existing filesystem capabilities. Automatic token refresh is not the Reconnect button: it
already occurs during inference and remains memory-only. The server's claimed Operator identity is not authentication;
restrict this administrative interface to trusted users and use HTTPS/WSS or a secure tunnel for remote access.

## SDLC inspection

Open **SDLC** (`#/sdlc`, NORMAL shortcut `8`) to inspect stored **Tasks** and **Intents**. The page reads
`GET /api/sdlc/tasks` and `GET /api/sdlc/intents` independently over same-origin HTTP, bypassing browser caches.
Each endpoint must return a **bare JSON array** of full stored objects. Tasks have a nonempty string `id`, an explicit
plain-text `title`, and a Markdown `description` (default empty). Intents retain `text`; legacy text-only records are
also readable. All fields are retained, including timestamps, relationships, task state, archives and unknown fields.
There is no core dependency, schema simplification or editing API.

Cards use a compact inline header: **ID · state · completed/total steps · title** (with archive badges where relevant).
The step count is text-only, without a progress bar; long headers wrap naturally on small screens. Task titles are explicit
(inferred from Markdown for intents/legacy records). Expand a card
with its native button (mouse, Enter, or Space) to read the Markdown narrative, ordered read-only steps, assumptions,
and relationships. Relationship buttons reveal, expand, and focus the target task/intent, clearing filters and showing
archives when necessary. **Copy Markdown** and **Raw JSON** sit together in the header, available even when collapsed.
Raw JSON sits near the +/− control and toggles the complete stored object independently
of the description. Missing/unloaded targets are shown as disabled buttons. Step completion remains independent
of task state; this page never writes planning data.

Search matches IDs, narrative, step titles/descriptions, assumptions, and relationship targets. The task-state filter
applies only to tasks. Archived records are hidden by default and can be included with the archive checkbox. Counts
show visible versus loaded records. **Copy Markdown** exports the task title as a heading, description, and checkbox
steps (or the intent narrative). Clipboard denial/unavailability produces a visible error. There is no separate
Markdown-source disclosure. Raw JSON retains all metadata and unknown fields.

Markdown is parsed with Marked and sanitized with DOMPurify through a mount-owned Vite/Scala.js bridge. Raw HTML is
shown literally, Markdown image URLs never load, and only inert formatting/table/link elements are allowed. Link URLs are
restricted to HTTP(S)/mailto (including relative HTTP(S) URLs), with `noopener noreferrer` in a new tab. A missing/failed
bridge falls back to plain text, never unsanitized HTML. The same bridge is used by message cards throughout the app.

Fenced `scala` blocks in task/intent narratives and step descriptions receive syntax highlighting.
Fenced `mermaid` blocks render as diagrams, as they do in shared message cards. Mermaid is bundled locally
and lazy-loaded only when needed, with strict security and HTML labels disabled. Generated SVG is sanitized and shown
as an inert data-URL image, never inserted as live SVG; callbacks and clickable diagram links are not enabled. Each
diagram uses the shared dark palette and has a **Diagram source** disclosure; Copy Markdown/Raw JSON preserve the
original editable source. SVG viewBox dimensions define the intrinsic size, so small/narrow diagrams never stretch to
fill the card. Previews fit the available width and at most `min(24rem, 55vh)` in height, preserving aspect ratio.
**Expand diagram** removes the preview height cap; **Fit diagram** restores it. These controls apply to message cards too.
Invalid/unsupported diagrams or loading failures show a notice and the original code. Application-controlled rendering
rejects Mermaid configuration directives/frontmatter, raw HTML, image/icon nodes, custom CSS (`classDef`, `style`,
`linkStyle`), backslash escapes, and sources over 50,000 characters; the renderer also limits edges to 500. These
resource-bearing features remain source-only so even temporary rendering cannot fetch user-specified images/fonts.
Raw HTML/SVG fences and other code languages remain escaped code, not executable previews. Unmount cancels late
updates and removes temporary rendering containers when pending work finishes.

Each section has independent loading, empty, filtered-empty, and error states. **Refresh** (or NORMAL `r`) reloads both
sections once; there is no polling or automatic retry. Late responses from older refreshes or an unmounted page are
ignored. Requests already in flight are not aborted.

SDLC reads do **not** use `OrchestratorWSClient`, RPC initialization, a selected agent or a connected WebSocket.
The existing application shell still attempts its ordinary WebSocket connection for other views; a disconnected status
or connection notification does not block SDLC HTTP reads. Reconnect can also refresh the active page.
Standalone server startup creates the shared SDLC service and serves both HTTP endpoints, even without an agent
configuration. Data comes from `<--storage>/sdlc/tasks.jsonl` and `intents.jsonl`. HTTP inspection follows the trusted-host
policy, with no WebSocket identity or agent allowlist required; keep this sensitive read surface private. Vite's
HTTP development proxy forwards these same-origin requests to the backend.

Focused checks: `sbt 'webHarness/testOnly *Sdlc*Suite'` and `npm --prefix web-harness/vite test`.
Model/decoder tests cover independent loading/errors, stale responses, unmount/remount, structured fields, search and
archive/state filters, Markdown export, and full-object retention. JavaScript tests exercise formatting and sanitizer
behavior in jsdom. Headless browser smoke checks with mocked HTTP verified keyboard expansion, sanitized Markdown,
read-only checklist progress, copy/export, raw JSON, filters, navigation to archived tasks and related intents, refresh
recovery after an independent endpoint failure, and a nonoverflowing 375px viewport. HTTP route tests also cover full
metadata, readonly behavior, empty stores and sanitized failures.

## Scalamode

Open **Scalamode** (`#/scalamode`, NORMAL shortcut `7`) to run a Scala string without asking an LLM to generate or execute
a conversation turn. Choose an agent using the **same toolbar picker as Chat**, edit the code, and use
**Run scalamode** for restricted Scala execution. Both views use the current conversation's selected agent;
Scalamode has no separate agent selection. The shared agent picker and run button are disabled while a request is pending.
The page sends an ordinary `agents.invoke` message containing
`ToolCall(name = scalamode, argumentsJson = {"code":"...","mode":"filesystem"})` and displays its correlated `ToolOutput`:
stdout/result, compiler/runtime diagnostics, typed timeout outcomes, exit code and truncation metadata.
The result panel shows `compilationTime`, `executionTime` and `totalTime` in milliseconds (`ms`), including failures
and timeouts. Execution time includes Scala CLI/JVM startup, not only snippet evaluation; total time also includes
executor setup and cleanup. Skipped or unmeasured timings display `0 ms`.
The default example lists entries in the selected agent’s configured root with `fs.list(".").map(_.mkString("\n"))`.
The browser always requests filesystem-capable execution, which requires a filesystem-enabled backend.
Editor text is treated only as text; no Markdown/HTML is executed by the browser.

A configured backend, initialized Operator and advertised `agents.invoke` method are required. Tool availability and
the selected agent's capability allowlist are checked by the server, not inferred from the agent selector. Scala CLI
must exist on the backend; restricted execution also needs the pinned offline compiler cache. `ApplicationTools`
registers the restricted tool.
Unavailable or denied tools return server errors. The backend API still accepts
`{"code":"..."}` in pure mode, or `{"code":"...","mode":"filesystem"}` when supported, with the same compiler
restrictions, injected `tool.api` helper and execution limits.

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
Send/new conversation/reconnect are blocked during dictation, while the draft stays editable.
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
as `UserId("fabio")`. Chat explicitly acts as `user-fabio`; voice and scalamode act as `web-user-fabio`,
invoking configured recipients over that same connection without creating implicit sessions or reconnecting. Edit the compact toolbar's **Username** and press **Reconnect** to apply it; typing never disconnects.
With an unchanged name, Reconnect reuses the connected socket and refreshes state. Empty names disable Reconnect;
pending turns and connection setup also block it. Claimed authorship is **not authentication**: every reachable
client can claim a name and execute configured agents/tools. Keep the backend and Vite proxy on trusted networks.

Choose an agent in the shared toolbar from the configured IDs advertised by `initialize` (not the mixed runtime
registry in Agents). Send behavior and shortcuts are defined in the [client controls guide](../docs/CLIENT_CONTROLS.md#website-keys).
A blocked send attempt shows a small temporary notification (also logged to the browser console); with text but no
agent, a warning appears beside the disabled Send button. Failures also notify, while important recovery instructions
remain in the conversation. Notifications expire after five seconds and never retry requests.

The first Send issues one `turn.submit` with an explicit New target, only after an agent is selected.
The response supplies the session ID; later turns use Existing with that ID and its known head.
**New conversation** prepares a local draft only; expand **Name (optional)** to choose the name used on first submit.
No empty remote session or separate create request is sent. Choose an existing session, including a REPL session, in the sidebar to
resume it in Chat, except on **Conversation graph**: there it focuses/highlights the published session path and opens
its inspector without changing Chat state or route. Graph selection is shared with canvas/search session selection and
starts unselected on each mount. New conversation and Unsent draft still open Chat. On narrow screens, **Menu**
expands navigation and conversations; selecting a graph session closes it. Agent changes
and resumes reset the next provider continuation without clearing history or drafts.

The transcript uses authoritative persisted snapshots and the inspector's message/tool cards and downloads.
Send immediately adds a temporary user card labeled **Pending — not yet confirmed saved**, including the first submit for a new conversation. It captures the submitted text and connected identity, follows its conversation across
tabs, and is replaced by the returned snapshot on completion, never persisted by the browser. Sending clears the
composer immediately and leaves it editable for your next draft; Send stays blocked while waiting, with the waiting
status beside it. Completion never erases that next draft. On failure or disconnect, the temporary card clears and
recovery instructions remain; the submitted text is restored only if the composer is still empty. There is no streaming,
progress or execution cancellation for chat turns, and no automatic retry. Dictation uses the independent upload/invoke path above.
Turn submission has no read deadline; ordinary reads and diagnostics remain bounded. A pending turn and
per-conversation drafts live at application root and survive tab/session navigation, but not page reloads.
New messages, including pending cards, follow the scroll position only when near the bottom.

Failures preserve your next draft (or restore the submitted text if empty). Existing conversations require history
reload before manual resend. A rejected first submission retains a local draft without inventing a remote session.
Unknown first-submit results, including InternalError, block another send: inspect the session list and explicitly resume
the saved conversation before manually resending. A disconnected turn **may still be running**: reconnect, inspect history, and avoid
sending a duplicate prompt. Reconnect never replays requests. The client also exposes `diagnostics.request` through
the shared protocol DTOs; a diagnostics UI is intentionally deferred.

Validation: `sbt ';webHarness/testOnly *;webHarness/compile;webHarness/fastLinkJS'`; production: `sbt 'webHarness/fullLinkJS'` then
`npm --prefix web-harness/vite run build`. Browser-adapter tests use the existing fake WebSocket seam; chat transition tests
cover single-submit New/Existing targets, local-only New drafts, lost-first-reply recovery, configured agents, stored heads, draft retention, continuation reset, stale responses,
failed outcomes and uncertain transport. Manual smoke check: verify the default identity, edit a name and Reconnect, send to a configured agent, switch views
while waiting, resume a REPL session, change agents, and verify narrow-screen sidebar and scroll behavior.

Upload staging is storage-owned: the wire still uses the caller-created BlobId for Begin/Append/Complete/Abort.
Only the connection that began an upload can control it; disconnect aborts unfinished uploads. Accepted chunks refresh
10-second idle expiry but never the 60-second absolute lifetime. Keep using a fresh BlobId when retrying after failure.
Invocation waits only for known active dependencies with a bounded server read timeout; completed reads remain immediate.
