# Yallmo wire protocol v1

**Implemented: viewer reads and trusted-local configured execution. Authentication is explicitly deferred for this local path.**
This is not an authenticated identity or public-hosting service. Keep loopback binding and Host/Origin safeguards;
do not expose it through a public proxy. Claimed user names are impersonable.

DTOs live in [`YallmoProtocol.scala`](../protocol/shared/src/main/scala/fmgp/yallmo/server/protocol/YallmoProtocol.scala)
and envelopes in [`JsonRpc.scala`](../protocol/shared/src/main/scala/fmgp/yallmo/server/jsonrpc/JsonRpc.scala).
The browser shares protocol/model sources but remains a read-only viewer. HTTP serves static assets and the WebSocket
upgrade only; no conversation/blob REST endpoint, upload, login, voice, diagnostics or tool-control API was enabled.

## Connection and capabilities

Send `initialize` first. Protocol `1` is independent of JSON-RPC `"2.0"`. Initialization is negotiation, **not authentication**.
`OrchestratorWSClient.connect` initializes once before returning; `capabilities` reads that cached result without another request.

Viewer methods: `initialize`, `sessions.list`, `sessions.get`, `messages.list`, `agents.list`, `blobs.list`, `blobs.read`.
Operator additionally advertises **only** `sessions.create` and `turn.submit`. Notifications are empty.
A server constructed with just a supplied master, or standalone without `--config`, remains viewer-only.

Operator requires all of: configured backend, actual loopback transport peer, **absent Origin**, and a nonempty initialized
claimed `humanId`. Anonymous connections and Origin-bearing browsers remain Viewer. `clientName` is never authority;
a browser cannot gain permission by naming itself a REPL or calling a mutation method. Host/Origin validation and the
loopback listener remain in force. Authenticated public deployment requires separate authentication and security review.

Initialize result `configuredAgentIds`, when present, is the safe executable-assistant selection catalog. `agents.list`
also contains human/presence IDs and is **not** an executable catalog. Provider/model configuration is not exposed.
The server independently resolves configured assistants; it never accepts executable code, author IDs or provider state
from a turn request. Identity rebinding is rejected. Configured/caller-supplied agent ID collisions are rejected;
known dynamically registered human authors can reconnect.

Presence is connection-owned and cleaned on disconnect; duplicate same-name viewers retain presence while connected.
A human registered on create/submit remains in the runtime registry for the process lifetime, independently of active
viewers. Neither retention nor a stable label is authentication or durable identity.

## Framing and bounds

WebSocket `/rpc` carries one complete UTF-8 JSON-RPC document per text message, with no Content-Length header.
Unix framing is not implemented. Requests use named params, including `{}` for no params:

```json
{"jsonrpc":"2.0","id":"r1","method":"sessions.list","params":{}}
```

Responses use exactly one of `result` or `error`. Notifications have no ID and receive no response. Explicit null IDs
are not notifications. Generated IDs are strings; envelope codecs also represent numeric/null IDs. Property order
is irrelevant. Optional application fields are omitted when absent; null decodes as absent. Required null envelope
results/IDs are retained. The handler validates envelopes/params beyond what derived codecs alone enforce.

- 1 MiB assembled incoming/outgoing JSON; oversized responses close with **1009**, no pagination fallback.
- At most **64** batch members. Batches process sequentially within one admitted connection worker and return one
  aggregate response; other admitted messages can proceed. Empty batches are invalid; notification-only batches send nothing.
- **8** nonwaiting connection workers. Saturation closes with **1013**; no unbounded task/semaphore waiter queue.
  Outgoing text/control sends are serialized. Reads on the same socket remain responsive during a blocked turn.
- JVM client: at most **128** pending requests; correlation by ID, late replies ignored after interruption/deadline.
  Read/create deadlines default to **15 seconds**; `turn.submit` deliberately has **no read deadline**.
- Both clients fail pending waits on close/error and never reconnect/replay automatically. A deadline or disconnect
  is a lost local wait, not evidence that server work stopped.
- JVM transport uses public JDK WebSocket, which sends no Origin by default. It retains strict Unicode/UTF-8 byte
  bounds across fragments, one-message callback backpressure and scoped bounded executor resources. Server-side
  pinned ZIO HTTP framing restrictions remain documented in [README](README.md#security-and-transport-limits).

## Enabled requests and results

| Method | `params` | `result` |
| --- | --- | --- |
| `initialize` | `{protocolVersion: Int, clientName: String, humanId?: AgentId}` | `{protocolVersion: Int, serverInstanceId: String, identity: Identity, methods: [String], notifications: [String], maxBlobChunkBytes: Int, configuredAgentIds?: [AgentId]}` |
| `sessions.list` | `{}` | `[SessionSummary]` |
| `sessions.get` | `{sessionId: SessionId}` | `SessionSnapshot` |
| `sessions.create` | `{name?: SessionId}` | `SessionSnapshot` |
| `messages.list` | `{}` | `[MessageSnapshot]` |
| `agents.list` | `{}` | `[AgentId]` |
| `blobs.list` | `{}` | `[BlobId]` |
| `blobs.read` | `{blobId: BlobId, offset: Long, maxBytes: Int = 65536}` | `{blobId: BlobId, offset: Long, dataBase64: String, totalBytes: Long, eof: Boolean}` |
| `turn.submit` | `{sessionId: SessionId, agentId: AgentId, task: AgentTask, expectedHead?: MessageId, resetContinuation: Boolean = false}` | `TurnOutcome` (one terminal response) |

Existing domain types/codecs are reused. IDs encode as strings: safe path-segment `SessionId`, `sha256:` plus
64 lowercase hex characters for `MessageId`, canonical lowercase UUID for `BlobId`. Timestamps use ISO-8601 `Instant`.
Collections encode as arrays. Protocol-local codecs do not alter storage formats.

- `Identity = {id: AgentId, role: "viewer" | "operator"}`.
- `SessionSummary = {id: SessionId, messageCount: Long, agentIds: [AgentId]}`.
- `AgentTask = {prompt: String}`; `AgentResult = {agentId: AgentId, text: String}`.
- `SessionSnapshot = {id: SessionId, agentIds: [AgentId], messages: [MessageSnapshot]}`.
- `MessageSnapshot = {id: MessageId, agentId: AgentId, parentId?: MessageId, timestamp: String, role: String,
  content: String, blobIds: [BlobId], toolName?: String}`.
- `ApplicationError = {code: String, message: String}` with safe display text, never provider errors, credentials,
  filesystem paths or stacks. Transcript/tool text may itself contain sensitive material.

Snapshots expose public text/attachment references, not opaque `ProviderContinuation`. They never fabricate stored
messages or change persisted history. Listing order remains sessions/agents/blobs by ID, all messages by timestamp
then ID; session messages are in transcript order.

`blobs.read`: offset must be nonnegative and no greater than size; maxBytes is 1..65536 decoded bytes. At EOF, data
is empty and eof true. Decode each base64 chunk independently and advance by decoded bytes. No upload or binary frames.
Core full-blob reads and partial-failure storage limitations remain; this is not a blob lifecycle/status service.

## Submission, continuation and failure semantics

Create returns an empty session with initialized human authorship. Names are IDs, never paths. Only generated-name
create collisions retry server-side; an explicit duplicate conflicts. A session must already exist for `turn.submit`.
The REPL selects a configured agent **before** any prompt-induced create, retains create's returned ID even if the
subsequent submit fails, then submits exactly once. Later prompts reuse the ID. `/new [name]` explicitly creates once.

The application owns work, not the socket. A short root-local storage mutex coordinates filesystem mutation/publication
and snapshot capture; **no provider/tool work is held under it**. Nonwaiting same-session turn exclusion rejects Busy
before a second prompt. Independent sessions/agents execute concurrently, not under a global agent/run gate.
Exactly one storage service owns a root; the cooperative standalone lease does not protect against legacy/uncooperative
same-root owners. Do not run those writers together. No crash transaction or external side-effect rollback is promised.

Under session admission, a supplied `expectedHead` must match the stored last message. Omitted/null skips the condition;
there is no invented “must be empty” condition. Busy/head/configuration rejection precedes prompt writes. The core
persist–run–persist path alone saves the prompt once and generated suffix after success; RPC never preappends it.

`resetContinuation: true` discards old opaque provider continuation from future requests, not visible transcript.
The server also resets on execution-binding change or unknown policy after restart. Resume asks for reset on the next
turn; the client sends no history offset or provider continuation. Later same-binding turns reuse the server boundary.
Remote UI runContext stays empty; public snapshots/deltas render original authors/content safely.

Terminal `TurnOutcome` is a discriminated **application result**, not a JSON-RPC envelope:

- `{status: "succeeded", session: SessionSnapshot, result: AgentResult, newMessages: [MessageSnapshot]}`.
- `{status: "failed", acceptance: "notAccepted" | "accepted" | "unknown", session?: SessionSnapshot, error: ApplicationError}`.

Success includes the actual durable suffix (prompt plus generated output) with real persisted IDs. No runId, commandId,
ledger, status, cancel, tombstone, deduplication, replay or exactly-once guarantee is active. An accepted typed failure
retains the prompt and returns its safe snapshot. Missing accepted-session information is **Unknown**, never inferred
NotAccepted. Defect/shutdown returns terminal Unknown with the best safely loadable snapshot; absence is not rollback.
NotAccepted means known not persisted, Accepted means known persisted. Snapshot heads are last confirmed knowledge,
not a guarantee that an uncertain write cannot advance them.

Application-scope work survives disconnect; only the connection wait is interrupted. Terminal delivery uses an
application-owned Promise, including typed errors, defects, shutdown and already-closed-scope/startup races. Work and
its finalizers stop **before** storage/tool ownership and root lease release. Uncooperative finalizers can delay shutdown.
No terminal registry is retained. Ctrl+C abandons local waiting only; it never issues remote cancellation.

After unknown transport outcome, refresh `/history` or reconnect, resume the known session and read history before
**manual** resend. A lost create response may leave an unknown empty session: use `/session list`; never automatically
create/submit again. A lost turn response may still yield durable completion. Busy/stale-head retains current client
session/head; refresh before another manual attempt. There is no automatic reconciliation or safe replay claim.

Example request (one terminal result when the connection remains usable):

```json
{"jsonrpc":"2.0","id":"r3","method":"turn.submit","params":{"sessionId":"s1","agentId":"bob","task":{"prompt":"Hello"},"resetContinuation":false}}
```

## Errors and disabled definitions

Standard JSON-RPC codes: -32700 Parse error, -32600 Invalid Request, -32601 Method not found,
-32602 Invalid params, -32603 sanitized Internal error.

| Code | Meaning |
| --- | --- |
| -32001 | Unsupported protocol version |
| -32002 | Unauthorized / initialization required |
| -32003 | Forbidden / configured viewer mutation |
| -32004 | Not found |
| -32005 | Conflict / head mismatch, explicit name or identity collision |
| -32006 | Busy / same-session turn already active |
| -32007 | Rejected / invalid author or configured assistant selection |

Pre-execution rejections are JSON-RPC errors; execution failures use `TurnOutcome`. Provider causes/stacks are not sent.
Unconfigured mutation endpoints return MethodNotFound. Malformed unidentifiable requests use null ID; notifications
receive no reply. Connection overload is a close, not a run-capacity/ledger API.

Existing `SessionsFork`, `ToolsList`, `RunsGet`, `RunsCancel`, `CommandsGet`, diagnostics and
`TurnStarted`/`TurnProgress` codec definitions remain **disabled drafts**, including their reserved fields.
They are not advertised, prerequisites for active submission, implemented controls or a promised roadmap.
Provider/login/voice/human-interaction transport and public authentication remain separate pending work.

## Validation

`YallmoProtocolSuite` freezes enabled shapes, optional fields/defaults and shared JVM/JS decoding.
`OrchestratorWebSocketSuite` retains initialization/correlation/pending/deadline/disconnect/framing gates;
`ServerSubmissionSuite` covers actual durable execution, concurrent sessions, Busy/head checks, viewer denial,
disconnect, shutdown/lease ordering, closed scope, overload and continuation/restart policy. Connected REPL suites
exercise negotiated capabilities, state retention/no replay and actual terminal submission to temporary configured
server storage with fake providers and no client-local bootstrap. See component handoffs for exact run counts/logs.
