# Conversation DAG inspector

## Live graph: pushed updates

1. `ConversationGraphModel.scala` subscribes to `events.update` over the existing initialized `/rpc` WebSocket.
   `events.subscribe` supplies public messages, canonical session metadata/heads/revisions and in-flight observations.
   Session names are stable `SessionId` strings. Empty histories remain visible; failed session reads are explicitly
   unknown/stale, never interpreted as empty histories. No recurring message/session inventory reads or timer remain.
2. `conversation-graph-model.js` reconciles immutable artifacts by message ID and mutable pointers by session ID.
   All inventory branches are retained, including originals, disconnected roots and messages outside any session.
   Shared heads/ancestors are single message nodes. Referenced but absent parents/heads become ID-bearing placeholders.
3. A custom preset layout uses compressed timestamp distance and ancestry constraints. Lanes are reusable:
   prefer the parent's column, then the nearest safe column, reserving node/label clearance and each ancestry
   edge's actual occupied interval. Finished short forks release columns for later branches; simultaneous branches
   remain separate. Equal/copy timestamps use ancestry and stable ID ordering, not fabricated clock precision.
   Long idle periods use logarithmic spacing, **not** a proportional timeline. Message and placeholder
   coordinates are allocated once per mounted graph and retained across automatic/manual refreshes and placeholder replacement.
   Late older roots may appear above the initial origin. Late ancestors are placed above existing descendants.
   Timestamp reversals are red edges; corrupt cycles or newly discovered constraints incompatible with fixed positions
   also remain visible as red edges rather than moving old messages or hiding ancestry. Inspect timestamps for exact time.
   Ancestry edges use deterministic elbows (normally below the parent's bounded label). The bounded router tries
   below/level/above departure rows and columns through the current outer lanes. If no route clears fixed obstacles,
   it allocates a fresh outer node column and keeps a dotted fallback edge. Nodes remain distinct and old messages
   never move; edge crossings, and edge/node overlap in pathological dense/fixed layouts, are possible. This is not
   a general obstacle router or globally minimal-width layout. The simple scans can be cubic on dense inventories;
   updates with no new messages reuse all allocated positions/routes.
4. `conversation-graph.js` incrementally adds/patches/removes Cytoscape elements without resetting pan, zoom,
   selection or the inspector. Session pointers use dashed directed session → head edges; message edges are parent → child.
   Session nodes occupy compact collision-checked slots below their heads, including shared heads; empty sessions
   receive visible slots too. Session positions may move on refresh, but message positions never move for them.
   A highlighted path says **published ancestry**,
   not that everything outside it was discarded. There is no inferred copy provenance or branch-recovery operation.
5. Mouse hover opens one passive safe inspector without selecting a node, changing focus/parent, highlighting a
   session or moving the viewport. It stays while the pointer is over node/card or keyboard focus is inside the card;
   leaving both dismisses after a short grace period. Pan/zoom or hiding the target dismisses passive previews.
   Click/tap, search and sidebar navigation open a persistent inspector; unrelated hover cannot replace it. It has
   an unlabeled drag strip and follows its node on pan/zoom. Outside click or Escape dismisses it. Dragging or using
   buttons/links inside does not dismiss it. View-only Branches/Live controls retain it unless its target is hidden.
   Each replaced/dismissed message root is explicitly unmounted. Message bodies reuse safe Laminar `App.messageCard`, including identity,
   author/role/time, tool output, public reasoning text/summaries, attachments and speech controls. Opaque provider
   continuation data is not part of `MessageSnapshot`. Session inspectors show name and current/last-known head.
   The toolbar contains search, a labelled **Branches** select and a **Live** checkbox. A nonempty query searches
   global full text, IDs, roles, authors and session names, including hidden branches; up to 30 keyboard-selectable
   result buttons appear in a dropdown. Selecting a result updates focus, reveals/opens it and clears the query.
   There is no permanent node list/disclosure or Refresh button.

   Branch modes are **All**, **Current**, and **Current + siblings** (default). Before any explicit selection, all
   inventory is visible. Current shows the focused message, all its descendants, and its ancestry including shared
   ancestors/placeholders. Current + siblings also walks from the focused node (including itself) toward the root to the
   **nearest split**, adding that fork's whole descendant subtree. Thus a leaf shows its nearest sibling continuations,
   not remote cousins or unrelated roots; selecting an earlier node always exposes all of its children and deeper
   descendants. With no split, the focused ancestry and descendant subtree are shown. Session focus uses its latest published head; an empty/missing session remains navigable.
   Session markers with visible heads remain visible (including shared heads), plus the focused empty session.
   Edges require both endpoints visible. Hidden inventory remains mounted and reserves its global positions. All
   restores it exactly, and the chosen mode survives pushed updates until unmount. Outside clicks/inspector dismissal retain
   focus. Filters/hover do not change composer parent/draft/pending work, fetch scope, or storage. An explicitly
   adopted branch response updates focus once; ordinary snapshots never re-adopt it or override a later selection.
6. Bootstrap loading/failure keeps the previous graph. Confirmed immutable branches accumulate; pending observations
   live in a separate bounded overlay. Uncertainty removes only that overlay and obtains a fresh baseline, never deleting
   confirmed branches. Explicit renderer inventory replacement removes ghosts and stale inspector artifacts while retaining
   surviving node coordinates/routes. Session baselines replace the aggregate; deltas upsert only newer revisions,
   including A → B → A. Unmount unsubscribes, invalidates callbacks and removes the visibility listener, Cytoscape,
   observers, card roots and global pointer/key listeners. Positions/inspector do not persist across route unmount/reload.

Pan/zoom use the canvas gestures; initial load fits automatically. Search and sidebar session navigation are explicit
navigation actions; Branches and Live are local view controls. Explicit search centering/session fitting recovers the
inspector inside the viewport after navigation; ordinary pan/live updates keep following the node without clamping.
No title or fit/zoom/highlight-reset/close-all toolbar buttons are shown.
While this route is active, sidebar session clicks stay on Graph, focus/highlight published ancestry and open/reuse
the session inspector; they do not resume Chat or change its drafts, continuation or pending turn. Canvas/search session
selection updates the same sidebar selection. Graph starts unselected on each mount. Before its
first snapshot, only the latest requested session is queued; repeated clicks re-focus, ordinary refreshes do not. Empty
and unreadable sessions remain navigable as session nodes. New conversation and Unsent draft still explicitly open
Chat; existing-session clicks on other routes still resume Chat. Mobile graph selection closes the sidebar.
The empty state includes empty session nodes; a truly empty store displays “No stored messages or sessions.”
Request failures and unreadable session snapshots are visible without deleting previously inspected messages.
Reading/navigating the DAG does not change history. The composer uses the additive sessionless operation below;
existing session submission semantics are unchanged.

## Unnamed branch composer

Graph reuses Chat’s full-height conversation layout and composer styles. Its controls stay at the top, the canvas fills
the flexible middle area, and the composer stays at the bottom. Resizing the composer resizes the canvas rather than
pushing the input below the page. Chat’s transcript remains independently scrollable.

The bottom composer says **Reply from** the selected message (hover for its exact ID). Only confirmed stored message nodes/search
results can select a parent (pending observations remain inspectable); selecting a session, missing node, empty canvas or leaving Graph clears it. Choose a configured
agent in the shared toolbar, type text and use Send or plain Enter (Shift+Enter inserts a newline; IME does not send).
This saves a new user child of that **exact** parent and runs the configured model/tool loop on its complete ancestry.
It creates no named or temporary session and never changes participants or published pointers, even when branching from
an old message whose original descendants remain stored. `agents.invoke` cannot implement this: invocation does not load
ancestry or run the full tool loop.

Draft, captured parent/agent and pending work live at application root, separately from Chat. Selecting another parent or
editing the next draft cannot change an in-flight submission. Send/reconnect/agent changes and competing Chat/Scalamode
submissions are blocked while pending. Leaving the route does not cancel backend work. Returned public message IDs merge
immediately into the existing DAG without fitting/resetting its viewport or moving old messages. Success adopts the new
head as the next parent only if selection has not changed. Ordinary pushed updates never adopt a previously returned head or
change the parent/draft/pending state; persisted prompt/tool/reply messages can appear before the terminal response.

Failures can leave accepted prompts, tool effects and partial saved branches. Partial IDs are merged when known; failure
also requests a read-only refresh while Graph is mounted. Uncertain outcomes require inspection and an explicit “I reviewed
saved messages” acknowledgement before another Send; this never replays the previous request. Neither refresh nor reconnect
retries work. A new Send is a new execution and can duplicate tool effects. Draft restoration never overwrites later edits.

### `branches.submit`

Configured, initialized trusted Operators only; the server binds `actingAgentId` to the connected user's owned actors and
resolves `agentId` exclusively from configured assistants. Strict params (including strict nested task fields):

```json
{"parentId":"sha256:<64 lowercase hex>","agentId":"assistant","task":{"prompt":"Reply here"},"actingAgentId":"web-user-fabio"}
```

No session ID, arbitrary history, executable or client-created message/timestamp is accepted. Complete hash-valid ancestry,
a valid registered author/assistant, a nonblank prompt and complete tool exchanges are checked **before** prompt writes or
execution. The server allocates the prompt ID/time once, saves it with the exact selected parent, then supplies ancestry plus
that prompt once to the managed runner. The initial opaque provider continuation scope is always fresh: inherited reasoning
continuation payloads are excluded only from provider request replay, not rewritten/deleted in storage; newly generated
continuations remain usable within the run.

Terminal outcomes are distinct from session outcomes:
- `status: "succeeded"`: `parentId`, `promptId`, confirmed stored `head`, `result: {agentId,text}`, `newMessages` public snapshots.
- `status: "failed"`: `parentId`, `acceptance: "accepted" | "unknown"` (the codec also supports `"notAccepted"`), optional
  attempted `promptId`/last confirmed `head`, verified public `newMessages`, and a sanitized `error: {code,message}`.
- Definite preacceptance authorization/validation/configuration rejections use JSON-RPC errors and write no prompt.
  A failed prompt-write acknowledgement is **unknown**, not proof that nothing was saved. Unexpected scope/transport loss
  can return unknown without IDs; refresh the global inventory to inspect it. No opaque continuation or provider cause is a wire field.

The application owns execution; the socket/client owns only its wait. Both clients omit the short read deadline for this
method, but the configured agent budget still applies. Work is not rolled back or automatically retried after disconnect.
Managed writes drain and terminal results verify inventory before success: a missing generated artifact cannot produce a
fake successful head. Persistence is still not a transaction, fsync/crash-durability guarantee, idempotency token or exactly-once
tool execution. Partial/original/rebase artifacts remain inspectable. No arbitrary-head session publication/recovery UI is added.

## Automatic updates and connection lifecycle

The architecture and acceptance contract are documented in [Push-update plan](CONVERSATION_GRAPH_PUSH_PLAN.md).

**Live uses server push**, including for read-only viewers. Mounted, visible, connected Graph with Live enabled registers
its notification callback before sending `events.subscribe`. Notifications arriving before the result are bounded to
**128 events / 2 MiB UTF-8 JSON**. The result establishes subscription/stream identity and the starting sequence;
matching buffered events apply from `startSequence + 1`, with ID deduplication and session revision checks. Snapshot
session revisions dominate older buffered pointers; no false atomic end watermark discards concurrent message events.

Storage and live delivery run in parallel on the backend. Pending observations are not proof of persistence and cannot
select a composer parent until confirmed. Confirmation promotes public messages and canonical session outcomes. Pending
operations are bounded to **128 / 2 MiB** in the browser too; there is no retained operation journal. Uncertainty,
sequence gaps, stream changes or overflow invalidate the feed and allow **one** automatic read-only resubscription per
explicit refresh/resume. Repeated failure stops with an error and retains confirmed data; explicit Refresh can retry reads.
The server independently bounds egress and closes a slow/oversized socket with 1013; reconnect remains explicit.

Live off, hidden document, disconnect and route exit invalidate callbacks before unsubscribe, freezing graph/sidebar
background changes. Late bootstrap responses are unsubscribed, not applied. Resume obtains a new baseline. Live toggles
never reconnect or replay authoring requests. Own terminal branch results still merge while paused or before bootstrap,
including after remount; explicit head adoption is consumed once, never repeated by a background snapshot.

Graph reconnect continues suppressing Chat-owned list/history reads, including while paused. Retained saved Chat histories
are marked stale without losing drafts/continuation; returning to Chat reloads before Send. Sidebar metadata updates are
inventory-only and do not reload transcripts. Pushed updates preserve viewport, filter/search, inspector and composer state.

No authoring operation/tool execution is replayed, and events never clear an unknown-outcome review gate. These are
process-local observations and best-effort persistence, not a transaction, durable cursor or crash-durability guarantee.
Direct external-process filesystem writes are not watched; a fresh baseline is needed to discover them.

## Validation

- `cd web-harness/vite && npm test`: reusable two-lane staggered forks, node/edge clearance on representative layouts,
  reorder/append/placeholder stability, nearest-fork visibility, headless renderer identity and jsdom hover/inspector lifecycle.
  A deterministic seed-1 stress test covers 200 dense 40-message layouts (including the formerly nonterminating second
  fixture), bounded routing fallbacks, node nonoverlap and fixed old coordinates; this is not a universal edge-clearance proof.
- `sbt 'webHarness/testOnly *GraphBranchModelSuite *ConversationGraphModelSuite *SidebarSessionNavigationSuite *ViewWebSuite'`: notification-before-response, bounded bootstrap/UTF-8 overflow,
  session revisions, optimistic reconciliation, parent/draft capture, stale replies, Live-off in-flight rejection and resume,
  hidden/disconnected pause, reconnect/unmount,
  inventory-only sidebar updates and empty snapshots.
- `sbt 'core/testOnly *OrchestratorBranchSuite *OrchestratorSubmitSuite *ManagedHistory*Suite *OrchestratorRebase*Suite'`: memory/FS/reopen, no session calls, strict ancestry, tool loops and persistence failures.
- `sbt 'server/testOnly *ServerSubmissionSuite'` and `sbt 'protocol/testOnly *YallmoProtocolSuite'`: auth, configured ownership, fresh continuations and strict wire codecs.
- `sbt 'webHarness/fastLinkJS'` for development; `sbt 'webHarness/fullLinkJS'` then `npm run build` in the Vite directory
  for the production alias. These are separate commands when using the local thin sbt client.

Historical pre-push mocked production-browser checks covered desktop pointer hover/card transition/click persistence, mobile touch, branch modes,
global search/sidebar navigation, moved session heads, immutable coordinates, Live pause during a read/reconnect, sidebar
freeze, resume, and direct branch adoption while paused. No external model/provider is used. Large real inventories and
visual/accessibility review remain release gates; synthetic tests do not prove universal routing quality.

Push migration validation: 87 JavaScript tests and 151 web Scala tests passed, plus fast/full Scala.js linking and the
Vite production build (existing large-chunk warning). Push-specific synthetic desktop/mobile production-browser checks
also passed: notification-before-response, optimistic ghost reconciliation, no recurring inventory reads, stable coordinates,
filters/search/sidebar/head movement, passive/persistent inspectors, paused late events/reconnect/sidebar, explicit branch
adoption and independent drafts, and deferred Chat history reload. The temporary browser fixture was adapted to the push
protocol; historical polling results are not counted as push evidence. No real provider calls were made.

A corrupt filesystem inventory may still prevent bootstrap before individual session reads can report unknown heads.
Snapshots remain subject to the existing 1 MiB wire limit; there is no pagination fallback. See the implementation receipt
in [the push plan](CONVERSATION_GRAPH_PUSH_PLAN.md) for changed paths, exact automated checks and remaining limitations.
