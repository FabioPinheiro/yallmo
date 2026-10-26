# View web

Scala.js / Laminar session inspector and trusted-browser chat. See [the server guide](../server/README.md) for development,
production bundling, embedding, API documentation and security boundaries.

```sh
# Repository root:
npm --prefix view-web ci
sbt 'viewWeb/fastLinkJS'
npm --prefix view-web run dev
```

The backend must be running on loopback port 8080 (`sbt 'server/run --storage ./zzz'`). Vite uses `host: true` to listen
on all interfaces, including Docker, and proxies `/rpc` with `ws: true` to `ws://127.0.0.1:8080`, preserving the real
browser Origin. Keep the dev server/proxy private: publish Docker's Vite port on host loopback only (for example
`127.0.0.1:5173:5173`), or use a trusted network. Backend loopback binding alone does not protect the Vite proxy.

Static assets still use HTTP; application requests and attachment bytes use JSON-RPC exclusively. No Scala.js/Vite sbt
bridge is needed: Vite imports the output of the separately-running linker. Six hash-linked navigation links provide chat and show sessions,
all messages, a small CSS conversation tree,
registry agent IDs and stored blob IDs. Reconnect refreshes the active view; requests from an unmounted tab cannot update
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

## Chat (trusted browser)

Open **Chat** (`#/chat`) from the full-height left sidebar. Each page load defaults to `fabio` and connects once
as `user-fabio`. Edit the compact toolbar's **Username** and press **Reconnect** to apply it; typing never disconnects.
With an unchanged name, Reconnect reuses the connected socket and refreshes state. Empty names disable Reconnect;
creation, pending turns and connection setup also block it. Claimed authorship is **not authentication**: every reachable
client can claim a name and execute configured agents/tools. Keep the backend and Vite proxy on trusted networks.

Choose an agent in the shared toolbar from the configured IDs advertised by `initialize` (not the mixed runtime
registry in Agents). **Enter sends; Shift+Enter inserts a newline.** IME composition and Ctrl/Cmd+Enter do not send.
A blocked Enter attempt shows a small temporary notification (also logged to the browser console); with text but no
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
progress, cancellation, upload or automatic retry.
Turn submission has no read deadline; ordinary reads, creation and diagnostics remain bounded. A pending turn and
per-conversation drafts live at application root and survive tab/session navigation, but not page reloads.
New messages, including pending cards, follow the scroll position only when near the bottom.

Busy/conflict, failed outcomes and uncertain transport results preserve your next draft (or restore the submitted text
if empty) and require a successful history
reload before manual resend. A disconnected turn **may still be running**: reconnect, inspect history, and avoid
sending a duplicate prompt. Reconnect never replays requests. The client also exposes `diagnostics.request` through
the shared protocol DTOs; a diagnostics UI is intentionally deferred.

Validation: `sbt ';viewWeb/testOnly *;viewWeb/compile;viewWeb/fastLinkJS'`; production: `sbt 'viewWeb/fullLinkJS'` then
`npm --prefix view-web run build`. Browser-adapter tests use the existing fake WebSocket seam; chat transition tests
cover create-once/send-once, configured agents, stored heads, draft retention, continuation reset, stale responses,
failed outcomes and uncertain transport. Manual smoke check: verify the default identity, edit a name and Reconnect, send to a configured agent, switch views
while waiting, resume a REPL session, change agents, and verify narrow-screen sidebar and scroll behavior.
