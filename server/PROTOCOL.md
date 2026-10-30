# Yallmo wire protocol v1

**Implemented: viewer reads and configured execution for trusted browser and REPL clients.**
Access is assumed already authenticated outside this server; it performs no authentication or Host/Origin checks.
Keep all direct/proxied access inside a trusted environment, never public. Claimed user names are impersonable.
The listener always binds to `0.0.0.0` for trusted Docker/multi-machine access.

DTOs live in [`YallmoProtocol.scala`](../protocol/shared/src/main/scala/fmgp/yallmo/server/protocol/YallmoProtocol.scala)
and envelopes in [`JsonRpc.scala`](../protocol/shared/src/main/scala/fmgp/yallmo/server/jsonrpc/JsonRpc.scala).
The browser shares protocol/model sources and uses the same initialization and methods as the REPL. HTTP serves static assets and the WebSocket
upgrade only; no conversation/blob REST endpoint, login, voice or tool-control API is enabled. Generic binary uploads use JSON-RPC only. Operator-only
`diagnostics.request` reads existing provider-request captures without executing a turn.

## Connection and capabilities

Send `initialize` first. Protocol `1` is independent of JSON-RPC `"2.0"`. Initialization is negotiation, **not authentication**.
`OrchestratorWSClient.connect` initializes once before returning; `capabilities` reads that cached result without another request.

Viewer methods: `initialize`, `sessions.list`, `sessions.get`, `messages.list`, `agents.list`, `blobs.list`, `blobs.read`.
Operator additionally advertises `sessions.create`, `turn.submit`, `agents.invoke`, `blobs.upload` and the read-only `diagnostics.request`. Notifications are empty.
A server constructed with just a supplied master, or standalone without `--config`, remains viewer-only.

Operator requires a configured backend and a nonempty initialized claimed `userId`, irrespective of Origin or peer
address. Anonymous connections remain Viewer. `clientName` is never authority; a browser uses its real Origin and
claims a human ID in the existing `initialize` params. No permission opt-in or Origin allowlist is required. Anyone
with network access can claim an author and execute configured agents/tools, so public deployment is unsupported.

Initialize result `configuredAgentIds`, when present, is the safe executable-assistant selection catalog. `agents.list`
also contains human/presence IDs and is **not** an executable catalog. Provider/model configuration is not exposed by this catalog; explicit operator diagnostics may show the request model.
The server independently resolves configured assistants; it never accepts executable code or provider state from a
turn request. `agents.invoke` accepts an authored Message only when its author matches the initialized identity. Identity rebinding is rejected. Configured/caller-supplied agent ID collisions are rejected;
known dynamically registered human authors can reconnect.

Presence is connection-owned and cleaned on disconnect; duplicate same-name viewers retain presence while connected.
A human registered on create/submit/invoke remains in the runtime registry for the process lifetime, independently of active
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
| `initialize` | `{protocolVersion: Int, clientName: String, userId?: AgentId}` | `{protocolVersion: Int, serverInstanceId: String, identity: Identity, methods: [String], notifications: [String], maxBlobChunkBytes: Int, configuredAgentIds?: [AgentId]}` |
| `sessions.list` | `{}` | `[SessionSummary]` |
| `sessions.get` | `{sessionId: SessionId}` | `SessionSnapshot` |
| `sessions.create` | `{name?: SessionId}` | `SessionSnapshot` |
| `messages.list` | `{}` | `[MessageSnapshot]` |
| `agents.list` | `{}` | `[AgentId]` |
| `agents.invoke` | `{agentId: AgentId, input: Message}`; no session controls | `Message` (recipient's response payload) |
| `blobs.list` | `{}` | `[BlobId]` |
| `blobs.read` | `{blobId: BlobId, offset: Long, maxBytes: Int = 65536}` | `{blobId: BlobId, offset: Long, dataBase64: String, totalBytes: Long, eof: Boolean}` |
| `blobs.upload` | Discriminated command (below), no session ID | Discriminated result (below) |
| `diagnostics.request` | `{sessionId: SessionId}` | `{providerRequest?: JsonObject}` |
| `turn.submit` | `{sessionId: SessionId, agentId: AgentId, task: AgentTask, expectedHead?: MessageId, resetContinuation: Boolean = false}` | `TurnOutcome` (one terminal response) |

Existing domain types/codecs are reused. IDs encode as strings: safe path-segment `SessionId`, `sha256:` plus
64 lowercase hex characters for `MessageId`, canonical lowercase UUID for `BlobId`. Timestamps use ISO-8601 `Instant`.
Collections encode as arrays. Protocol-local codecs do not alter storage formats.

- `Identity = {id: AgentId, role: "viewer" | "operator"}`.
- `SessionSummary = {id: SessionId, messageCount: Long, agentIds: [AgentId]}`.
- `AgentTask = {prompt: String}`; `AgentResult = {agentId: AgentId, text: String}`.
- `Message = {id: MessageId, data: {agentId: AgentId, parentId?: MessageId, timestamp: String, payload: MessagePayload}}`.
  The shared codec verifies `id` against the canonical encoded `data`; payload variants retain their existing tagged JSON.
- `SessionSnapshot = {id: SessionId, agentIds: [AgentId], messages: [MessageSnapshot]}`.
- `MessageSnapshot = {id: MessageId, agentId: AgentId, parentId?: MessageId, timestamp: String, role: String,
  content: String, blobIds: [BlobId], toolName?: String}`.
- `ApplicationError = {code: String, message: String}` with safe display text, never provider errors, credentials,
  filesystem paths or stacks. Transcript/tool text may itself contain sensitive material.

Snapshots expose public text/attachment references, not opaque `ProviderContinuation`. They never fabricate stored
messages or change persisted history. Listing order remains sessions/agents/blobs by ID, all messages by timestamp
then ID; session messages are in transcript order.

`blobs.read`: offset must be nonnegative and no greater than size; maxBytes is 1..65536 decoded bytes. At EOF, data
is empty and eof true. Decode each base64 chunk independently and advance by decoded bytes. Binary frames remain unsupported.
Normal reads/listing never wait for in-flight uploads; staged bytes are invisible.

## Generic binary upload

`blobs.upload` uses explicit `command` and `status` discriminators. Only initialized configured operators may use it;
viewers/unconfigured connections get Forbidden and uninitialized connections get Unauthorized. An active upload is keyed by
its `BlobId` and owned by the exact connection, **not** its claimed user ID. Another connection cannot append, complete or abort it.

Example params/results (enclose each in the usual JSON-RPC request/response):

```json
{"command":"begin","blobId":"00000000-0000-0000-0000-000000000001","totalBytes":3}
{"status":"begun","blobId":"00000000-0000-0000-0000-000000000001"}
{"command":"append","blobId":"00000000-0000-0000-0000-000000000001","offset":0,"dataBase64":"AQID"}
{"status":"appended","blobId":"00000000-0000-0000-0000-000000000001","nextOffset":3}
{"command":"complete","blobId":"00000000-0000-0000-0000-000000000001"}
{"status":"completed","blobId":"00000000-0000-0000-0000-000000000001"}
```

`{"command":"abort","blobId":"..."}` returns `{"status":"aborted","blobId":"..."}`.
Every command and acknowledgement uses the same canonical lowercase UUID `BlobId`, which is also the stored blob identity.
Begin echoes the supplied ID; it does not allocate a second identity. It reserves the ID and declared length;
active/stored duplicate IDs yield Conflict. Empty blobs use Begin(0)
then Complete without Append. Append requires a nonempty decoded chunk of at most **65536 bytes**, the exact next byte
offset, and no overrun of the declared total. It accepts only canonical padded standard base64 (padding only when
required), bounded to **87384 characters before decoding**, then validates decoded size and canonical re-encoding.
Invalid base64/encoding bounds yield InvalidParams; invalid offset/chunk/total/incomplete completion yield Rejected.
Rejected commands retain the prior upload state but do not refresh its inactivity deadline.

Fixed staging limits, defined as constants in `BlobUploadLimits`:
**16** active uploads, **4 MiB** per upload, **16 MiB** aggregate declared-byte reservations, **10 seconds** inactivity
since Begin/last accepted Append, **60 seconds** absolute lifetime from Begin (never refreshed), and **2 minutes**
for invocation dependency reads. A 960044-byte file fits in multiple chunks.
These are staging-content/reservation limits, not a total JVM heap cap: bounded transport frames/decoding and copying
have additional overhead; completed Storage content follows existing storage/read semantics. Capacity exhaustion is Busy.

Complete requires all declared bytes and succeeds only after Storage publication. The filesystem backend forces the
full temporary file, publishes with an exclusive hard link (never replacing an existing ID), and forces its directory.
It requires filesystem hard-link/directory-force support; unsupported operations fail safely, with no fallback to direct
partial writes. Reads/restarts ignore temporary filenames. File cleanup after publication is best effort; a crash can
leave ignored temporary files. Existing root ownership/semaphore rules still apply. This is not a multi-file/message
transaction or a new recovery/garbage-collection service, and it cannot detect partial files from older implementations.
Publication is uninterruptible and holds the registry/storage permits. The idle and absolute deadlines cannot preempt
it: blocked filesystem publication can delay expiry, disconnect cleanup and terminal capability removal beyond those
deadlines. They are not hard filesystem I/O deadlines.

Abort, expiry and disconnect discard unfinished bytes and fail existing dependency waiters with a typed reason;
storage failure also fails waiters, never with partial bytes. Commands for inactive or foreign uploads return NotFound.
Terminal uploads have no retained replay ledger. Complete/Abort/disconnect races serialize: successful publication
cannot be undone by later Abort. Errors/logs contain no uploaded bytes; transport TRACE redacts `dataBase64` in both
directions (including batches) and omits malformed JSON payloads.

Storage owns staging and implements shared `BlobOps`, forwarded unchanged by Orchestrator. Its
`readBlob(blobId, awaitTimeout = Duration.Zero)` returns completed blobs immediately; zero timeout returns missing
for active/missing content. A positive finite timeout waits only for a known active upload, outside storage/registry
permits; unknown IDs fail immediately. Negative/infinite timeouts are invalid. Timeout abandons just that waiter,
not the upload. `beginBlobUpload(blobId, totalBytes)` returns a `BlobUploader` capability with `blobId`, `append`,
`complete` and `abort`; there is no owner argument or second upload identity. The server holds a connection-local
`Map[BlobId, BlobUploader]`: Append/Complete/Abort resolve only there, never by global ID lookup. Disconnect is
serialized with Begin and aborts that connection's unfinished capabilities. Terminal capabilities are removed
on completion/abort and by bounded dependency cleanup even with no further traffic. Storage releases staging
buffers, reservations, waiters and timers on every terminal outcome, including publication failure and automatic expiry.
`agents.invoke` uses an explicit bounded read timeout; there is no separate RPC await endpoint. Uploads never create sessions or messages,
mutate Message/AudioAttachment, or run an agent/provider. Stored blobs can be orphaned: later message/invocation failure
cannot undo a completed upload. Abort cleans upload resources only, not agent execution.

**No automatic retry, replay, resume or reconnect promises.** A new Begin may reuse an unstored BlobId after cleanup,
but stale commands from the same connection cannot be distinguished from commands for that new upload. Use a fresh
BlobId if old commands may still arrive; internal timers and captured dependency waiters never retarget a new upload.
Await each Append response and use `nextOffset` for the next command; do not pipeline unordered chunks. Lost Complete responses may leave a fully stored blob; query ordinary
reads/listing rather than assuming a safe retry. Browser dictation uses these commands as described in
[web-harness](../web-harness/README.md#dictation).

## Independent agent invocation

`agents.invoke` runs a configured agent without requiring or creating a session. It is available only on configured
servers to initialized Operators; anonymous clients get Forbidden and unconfigured servers return MethodNotFound.
Params have exactly `agentId` (recipient) and `input` (full Message). Extra params, including `sessionId`,
`expectedHead` and `resetContinuation`, are InvalidParams. Message hash/field validation happens during decoding.
The input author must match the initialized claimed identity (Forbidden otherwise); this is still not authentication.

Input and output payloads are not restricted by the transport or Orchestrator: the configured recipient determines what
it supports and returns. An optional parent must already exist, but does not implicitly load parent history.
Unsupported agent audio capability and invalid configured recipients are rejected before saving input.
The input timestamp/content/id are preserved exactly, not replaced with a server-generated user message.

The core Orchestrator saves the input independently in message storage, awaits any referenced blobs with an explicit bounded `readBlob` timeout,
then supplies only its payload to an `AgentInvocation`. Successful execution saves the recipient's exact response payload
in a full `Message`, with the selected agent as author, a server timestamp and `parentId = input.id`.
No session history, prior provider continuation or session continuation policy is inherited.

The normal text-oriented `AgentExecution` adapter still runs its configured conversation loop and returns an `Assistant`
answer. Its internal tool/reasoning exchanges remain transient. The configured runner rejects incoming opaque
`Reasoning` continuations as server-owned; callers can submit a `Context` summary instead. Submitted `Instructions` do not
replace the native agent's immutable configured system instructions. History APIs continue using redacted snapshots.

A directly submitted `ToolCall` takes the runner's explicit tool-invocation branch, not a model turn. It resolves the
selected agent's effective capabilities, validates arguments and invokes the same registered/approval-wrapped tool,
with the configured budget deadline. The response is `ToolOutput` with the original call ID and tool name.
Unavailable tools, invalid arguments and approval denials become correlated tool-error output without executing the
implementation. Merely seeing a `ToolCall` in history, or submitting a `ToolOutput`, does not execute it.
No provider inference or session is needed for direct tools.

The restricted Scala tool key is `scalamode`, accepting `{"code":"..."}`. The browser's
[Scalamode page](../web-harness/README.md#scalamode) invokes it through this generic flow using **Run scalamode**.
The server still checks tool availability and authorization.

Both input and final output are visible through `messages.list` and survive filesystem reopen. Neither creates a
session/sidebar conversation or advances an existing session. Message puts validate content addresses and existing
parent references; identical puts are idempotent, **agent execution is not**. Repeating the same invocation can call
providers/tools again and store another response. There is no request deduplication, automatic retry or replay ledger.

Send the invocation and subsequent upload commands as separate WebSocket documents: an invocation can wait for its
blob while later Append/Complete requests proceed on the same socket. Do not place the waiting invocation before its
own upload completion in a sequential JSON-RPC batch. Existing frame/worker bounds still apply; reserve capacity for
upload controls rather than saturating the connection with waiting invocations.

Failures use ordinary sanitized JSON-RPC errors, not `TurnOutcome`. Missing blobs are NotFound; aborted/expired/failed
dependencies reject execution; provider/defect/storage failures return safe errors. A failed input write prevents
execution; dependency/execution/output-write failures do not roll back an accepted input. Errors do not assert that
nothing was saved. The application owns admitted work beyond caller disconnect; disconnect may abort its unfinished
connection-owned uploads, but never provides general agent-execution cancellation. Shutdown stops owned work before
storage/tool ownership is released. Lost replies may hide completed work: inspect `messages.list` by the known input
ID and response parent before considering a **manual** repeat. There is no standalone status/cancel/diagnostics API.
The browser dictation adapter uses this method without the ordinary read deadline; the REPL does not expose an
independent invocation command. See [dictation](../web-harness/README.md#dictation) for UI gating, cursor insertion and discard semantics.

## Provider-request diagnostics

`diagnostics.request` requires initialization and the same trusted-environment Operator policy as configured execution;
it is not advertised to viewers or unconfigured servers. It accepts only a session ID, never a filesystem path.
The result's optional document includes existing capture metadata (`turnId`, `capturedAt`, provider/model) and the
actual diagnostic request payload. It never returns a server file path, HTTP headers or transport credentials.

Capture follows application-owned execution, including typed failures and defects, and survives caller disconnect.
For a multi-request turn it is the last captured provider request. A new execution clears its session's older capture;
while running, or if no capture was produced, the result is absent. A rejected admission/head/selection check does not
replace the earlier completed capture. Unknown sessions return NotFound.

Only the latest paths for 128 captured sessions are indexed in memory (oldest capture association evicted first).
Reconnect can resume/read the capture; server restart loses the index even if existing debug files remain. Deleted
files are absent; symlinks and corrupt/unreadable captures are rejected with sanitized errors. Reads are bounded to
256 KiB before JSON parsing; oversized input or encoded documents return Rejected without closing the socket.
There is no pagination, persistence index or arbitrary-file read API.

Existing capture redaction protects encrypted provider continuations and refuses inline audio. **This is not a general
secret scrubber:** conversation/tool text and model details remain sensitive. Never expose this unauthenticated
trusted-environment listener publicly. The REPL displays documents only on explicit `/debug request`.

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
| -32003 | Forbidden / operator operation unavailable |
| -32004 | Not found |
| -32005 | Conflict / head mismatch, explicit name or identity collision |
| -32006 | Busy / same-session turn already active or upload capacity exhausted |
| -32007 | Rejected / invalid input, selection, upload/dependency or unavailable/oversized diagnostic capture |

For `turn.submit`, pre-execution rejections are JSON-RPC errors and execution failures use `TurnOutcome`.
`agents.invoke` uses ordinary JSON-RPC errors at every failure phase. Provider causes/stacks are not sent.
Unconfigured mutation endpoints return MethodNotFound. Malformed unidentifiable requests use null ID; notifications
receive no reply. Connection overload is a close, not a run-capacity/ledger API.

Existing `SessionsFork`, `ToolsList`, `RunsGet`, `RunsCancel`, `CommandsGet`, `DiagnosticsMessage` and
`TurnStarted`/`TurnProgress` codec definitions remain **disabled drafts**, including their reserved fields.
They are not advertised, prerequisites for active submission, implemented controls or a promised roadmap.
Provider/login/voice/human-interaction transport and public authentication remain separate pending work.

## Validation

`YallmoProtocolSuite` freezes enabled shapes, optional fields/defaults and shared JVM/JS decoding.
`OrchestratorInvokeSuite` covers session-independent persistence on memory/filesystem storage, blob dependency waits,
invalid inputs/executions, persistence failures and interruption. `AgentsInvokeSuite` covers authorization, ordinary
tool execution, continuation privacy, owned lifetime/shutdown, and real same-socket upload/invocation correlation.
The independent-invocation backend change was validated with **570 tests**, with **2 existing Scala-CLI integration skips**: core JVM 302,
core JS 15, protocol JVM/JS 26 each, server 157, web-harness 44; REPL test compilation also passed. Focused post-format
revalidation passed 127 checks and REPL test compilation. Subsequent browser dictation integration passed 59 Scala.js
checks and 51 Node checks (including the unchanged browser audio tests); see the frontend guide for synthetic-browser
smoke evidence and remaining real microphone/provider acceptance checks.
`OrchestratorWebSocketSuite` retains initialization/correlation/pending/deadline/disconnect/framing gates;
`ServerSubmissionSuite` covers actual durable execution, concurrent sessions, Busy/head checks, viewer denial,
disconnect, shutdown/lease ordering, closed scope, overload, continuation/restart policy and session-isolated
provider-request diagnostics (failure/reconnect/redaction/permissions/limits). Connected REPL suites
exercise negotiated capabilities, state retention/no replay and actual terminal submission to temporary configured
server storage with fake providers and no client-local bootstrap. See component handoffs for exact run counts/logs.
