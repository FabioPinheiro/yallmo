# Conversation DAG visualization plan

## 1. Goal

Provide a local, read-only web application for exploring a Yallmo conversation:

- show user, assistant, reasoning, RAG, function-call, function-result, and legacy tool-result messages;
- navigate one session's history or the shared history of forked sessions;
- select a node to inspect safe, complete details;
- make large histories usable through previews, filtering, and expand/collapse.

The initial product is a **historical conversation explorer**. It visualizes completed, persisted sessions. It does not send prompts, execute tools, alter sessions, or expose provider credentials.

## 2. Current Yallmo state

`StorageFS` already persists the essential structural data:

- `MessageData` contains `agentId`, `parentId`, `timestamp`, and a typed `MessagePayload`.
- A message has a stable SHA-256 `MessageId`.
- A session ref points to a head artifact.
- `Storage.fork(source, target)` makes two session heads share prior message artifacts.

This means the object store has a parent-linked history and shared fork ancestry. The present public read API is not sufficient for a visualizer: `Storage.loadSession` returns only a flattened `Chunk[MessagePayload]`, losing message IDs, timestamps, and parent links. There is no HTTP server yet.

The visualizer must query Yallmo through a new read model. The browser must never parse `objects.jsonl` or access the Storage directory directly.

## 3. Terminology and graph semantics

A **session** is Yallmo's durable named conversation ref. A **lineage** is the ancestor path from one session head to its root. A **family** contains selected session heads with shared ancestry.

The first graph has two edge categories:

1. `parent`: a persisted `MessageData.parentId` relationship. It means “this message was appended after / builds on this prior message”.
2. `tool-result-for`: a `FunctionCallOutput.callId` matched to a prior `FunctionCallRequest.call.id`. It means “this is the output of that call”.

`parent` is reliable historical structure. It is not a claim that every parent supplied the entire causal input context. Do not invent edges that Yallmo did not persist.

RAG and legacy `ToolResult` nodes initially have only parent edges. Accurate retrieval queries, approval decisions, tool timing, retries, and exact prompt/context consumption require future run events.

## 4. Scope and non-goals

### MVP scope

- local-only HTTP API and browser UI;
- session picker;
- selected session lineage view;
- optional family view for forked sessions;
- node type colours/icons, preview text, pan/zoom, minimap and fit-to-view;
- click node for a side-panel detail view;
- node-type filters and a “show ancestors / descendants” focus action;
- static graph snapshot loading; refresh button.

### Explicit non-goals

- changing or deleting Yallmo data;
- sending a prompt or executing a tool from the web UI;
- a multi-user/authenticated deployment;
- live token streaming, timings, retry visualization, or prompt replay;
- arbitrary graph database adoption;
- treating model reasoning or provider continuation data as displayable content.

## 5. Architecture

```text
StorageFS object store
        |
        v
ConversationGraphReader (typed, read-only projection)
        |
        v
ZIO HTTP routes (/api/v1/...)
        |
        v
React + React Flow browser application
```

Add a small public projection API near `fmgp.yallmo.orchestration.universe`; it owns traversal and redaction. Keep `StorageStore`, object-file layout, and ref-file layout private implementation details.

The backend is the authority for graph shape, access scope, previews, and redaction. React Flow only renders the returned projection.

## 6. Backend read model

Create public serializable models in `core`, for example in
`fmgp.yallmo.orchestration.universe.visualization`:

```scala
enum GraphScope:
  case Lineage
  case Family

final case class GraphNode(
    id: MessageId,
    parentIds: Chunk[MessageId],
    timestamp: Instant,
    agentId: AgentId,
    kind: GraphNodeKind,
    preview: String,
    toolName: Option[String],
    callId: Option[FunctionCallId],
    documentCount: Option[Int]
)

enum GraphEdgeKind:
  case Parent
  case ToolResultFor

final case class GraphEdge(
    id: String,
    source: MessageId,
    target: MessageId,
    kind: GraphEdgeKind
)

final case class ConversationGraph(
    selectedSession: SessionId,
    scope: GraphScope,
    nodes: Chunk[GraphNode],
    edges: Chunk[GraphEdge],
    heads: Chunk[SessionHead],
    truncated: Boolean
)
```

`GraphNodeKind` maps each `MessagePayload` variant to a stable API name: `user`, `assistant`, `reasoning`, `rag_result`, `function_call`, `function_result`, `tool_result`, and `context`. Unknown future payloads must render as `unknown`, rather than failing the whole graph.

`MessageDetail` is a separate type returned only for a selected message. It contains structured, redacted content appropriate for its kind; it must not simply serialize internal `MessagePayload` blindly.

## 7. Required Storage query API

Add a read-only interface implemented by `StorageFS`. It may be added to `Storage` or supplied as a companion reader service, but must not leak `StorageStore`.

```scala
trait ConversationGraphReader:
  def graph(
      sessionId: SessionId,
      scope: GraphScope,
      limits: GraphLimits
  ): IO[StorageError, ConversationGraph]
  def messageDetail(
      sessionId: SessionId,
      messageId: MessageId
  ): IO[StorageError, MessageDetail]
```

`GraphLimits` bounds traversal and response size, for example `maxNodes = 500` and `maxPreviewChars = 280`. If a result is limited, `truncated` is true and the response contains a continuation/focus strategy later; the first MVP may instead return a clear limit error.

Implementation rules:

- `Lineage` walks the chosen head through `parentId` and emits one parent edge per message.
- `Family` first finds session heads in the same connected ancestor component, then emits the union of their reachable ancestors and all relevant heads.
- Empty sessions have no message head and yield an empty graph.
- `messageDetail` must confirm that the requested message is reachable from the requested session/family scope before returning it.
- Match tool-call/result nodes by `callId` only when both messages are inside the returned graph.
- Index artifacts and refs in memory when `StorageFS.open` loads them; do not repeatedly parse the JSONL file for each HTTP request.

## 8. HTTP API contract

All endpoints are read-only and return `application/json`. Use the `/api/v1` prefix. Error bodies use:

```json
{"error":{"code":"session_not_found","message":"Session 'x' does not exist"}}
```

### List sessions

```http
GET /api/v1/sessions
```

Response:

```json
{
  "sessions": [
    {
      "id": "main-20260301-120000",
      "messageCount": 18,
      "headId": "sha256:...",
      "agentId": "assistant"
    }
  ]
}
```

`headId` is `null` for an empty session. Extend the session summary projection as needed; do not force the UI to load every graph just to build its session list.

### Load graph

```http
GET /api/v1/sessions/{sessionId}/graph?scope=lineage
```

`scope` accepts `lineage` (default) or `family`. Invalid values return `400`.

```json
{
  "selectedSession": "main-20260301-120000",
  "scope": "lineage",
  "nodes": [
    {
      "id": "sha256:...",
      "parentIds": ["sha256:..."],
      "timestamp": "2026-03-01T12:00:00Z",
      "agentId": "assistant",
      "kind": "function_call",
      "preview": "readFile { path: guide.md }",
      "toolName": "readFile",
      "callId": "call_123",
      "documentCount": null
    }
  ],
  "edges": [
    {
      "id": "parent:child:parent",
      "source": "sha256:parent",
      "target": "sha256:child",
      "kind": "parent"
    }
  ],
  "heads": [
    {"sessionId":"main-20260301-120000","nodeId":"sha256:..."}
  ],
  "truncated": false
}
```

### Load selected node detail

```http
GET /api/v1/sessions/{sessionId}/messages/{messageId}
```

This returns the node's full **redacted** content and metadata. A missing or unreachable message returns `404`; malformed identifiers return `400`.

### Status codes

- `200`: successful read;
- `400`: invalid path ID, query parameter, or limit;
- `404`: session or reachable message not found;
- `413` or `422`: graph request exceeds configured bounds;
- `500`: unexpected server failure, without an internal exception/stack trace in the response.

No write endpoints are part of this plan. CORS is disabled by default; the local frontend is served by the same process or explicitly allowlisted in development.

## 9. Redaction policy

The graph API defaults to previews. It must never expose:

- provider credentials, HTTP headers, or debug-capture files;
- `Reasoning.continuation.encryptedContent`;
- system prompts or agent artifact configuration;
- unrestricted raw tool arguments/results if a redaction policy marks them sensitive.

For MVP, serve the UI only on loopback and treat tool text as potentially sensitive. Add a central `VisualizationRedactor` before rendering previews or details. Reasoning nodes expose only their optional summary and a boolean that continuation exists. Detail responses should return document and tool content only after redaction and size limits.

## 10. Frontend prototype

Create a separate `visualizer/` Vite application using TypeScript:

```text
visualizer/
  src/api.ts          HTTP client and wire types
  src/graph.ts        API graph -> React Flow nodes and edges
  src/nodes.tsx       custom compact node renderers
  src/App.tsx         picker, canvas, filters, detail panel
  src/mock-data.ts    contract fixture used before backend exists
```

Dependencies:

- `react`, `typescript`, Vite;
- `@xyflow/react` (React Flow);
- `elkjs` for layered left-to-right layout.

The UI uses a static fixture that exactly follows the HTTP graph response before the backend exists. This permits quick feedback on the interaction model without changing persisted data.

Node rendering:

- user: blue;
- assistant: violet;
- function call: amber;
- function result / tool result: green or red when error-shaped;
- RAG: teal;
- reasoning/context: neutral and collapsed by default.

The canvas loads previews only. Selecting a node requests its detail and shows it in a right-side panel. A node can be focused to show only its ancestors and descendants. The default layout is left-to-right causal flow; `ELK.js` recomputes layout after filtering or expanding a collapsed group.

## 11. Delivery milestones

### Milestone 1 — UI-only proof of concept

Build the Vite app with fixture data representing a user message, assistant plan, two tool calls/results, a final answer, and one fork. Implement layout, filters, selection, detail panel, and focus mode. No Scala changes.

### Milestone 2 — typed historical graph projection

Add `ConversationGraphReader`, graph/domain codecs, traversal tests, and redaction tests. Preserve the existing `Storage` behavior and object-store format; this is a projection, not a storage migration.

### Milestone 3 — local HTTP server

Add ZIO HTTP routes, JSON encoding, input validation, structured errors, and a local server entry point configured with a Storage root. Connect the UI to these routes. Keep all routes read-only.

### Milestone 4 — fork-family and scale behaviour

Implement `scope=family`, session-head markers, maximum-node limits, preview/detail size bounds, empty-session handling, and clear “graph truncated” UI.

### Milestone 5 — runtime event model (future)

Only after historical browsing is useful, introduce append-only run events from `AgentRunner`: turn lifecycle, retrieval query, approval, tool timing/result, and retry. Then add snapshot refresh or SSE for live execution.

## 12. Tests and acceptance criteria

Backend tests cover:

- lineage graph preserves every persisted message ID, timestamp, and parent edge;
- a fork shares historical node IDs and has two distinct session heads;
- function result creates a `tool-result-for` edge for its matching request;
- empty session, unknown session, malformed ID, unreachable node, and bounded graph cases;
- previews/details apply redaction and never return provider continuation ciphertext;
- HTTP routes return specified response/error shapes.

Frontend tests cover:

- fixture graph maps node kinds and semantic edges correctly;
- type filters, focus mode, selection, and reset work;
- clicking a node loads and displays its detail;
- an API failure is visible without clearing the currently rendered graph.

The MVP is complete when a user can open a persisted REPL session in a browser, navigate its message lineage, inspect a safe detail panel, and switch to a family view that visibly shows shared ancestry and branch heads.

## 13. Open decisions

1. Should the local server be launched from the REPL process or as a separate `runMain` application? Start with a separate process to keep the REPL simple.
2. Is a fork-family graph always desired, or should the session picker explicitly choose participating heads? Start with all connected session refs and add selection later.
3. Which tool keys/results require stronger redaction than simple character limits? Define this before serving anything beyond loopback.
4. What event retention and privacy policy is acceptable before implementing live streaming? Do not add SSE until this is answered.
