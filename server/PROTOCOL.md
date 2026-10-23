# Yallmo wire protocol v1

**Status: read-only WebSocket slice implemented; mutation/execution remain contract definitions only.** DTOs are in
[`YallmoProtocol.scala`](../protocol/shared/src/main/scala/fmgp/yallmo/server/protocol/YallmoProtocol.scala); envelopes are in
[`JsonRpc.scala`](../protocol/shared/src/main/scala/fmgp/yallmo/server/jsonrpc/JsonRpc.scala). Both use ordinary `derives JsonCodec`.
`OrchestratorWSServer(master)` serves `/rpc` and SPA assets over the supplied runtime. The website has migrated to a
browser-native WebSocket client; the former REST viewer/Fetch API has been removed. HTTP is static/upgrade infrastructure
only. `OrchestratorWSClient.connect(endpoint)` returns a scoped JVM `OrchestratorClient`. No Unix adapter is implemented yet.
Enabled methods: `initialize`, `sessions.list`, `sessions.get`, `messages.list`, `agents.list`, `blobs.list`, `blobs.read`.
Other methods below return Method not found and are not advertised. No progress notifications are emitted yet.
The browser compiles the same domain/protocol sources as the server; there is no separate viewer wire contract.

## Exactly what crosses each connection

The **JSON documents are identical** on WebSocket and Unix-domain sockets:

- **WebSocket:** one complete UTF-8 JSON-RPC document per text message. No extra Content-Length header inside it.
- **Unix socket:** `Content-Length: <UTF-8 byte count>\r\n\r\n`, then exactly those UTF-8 JSON bytes. A read can contain
  part of a document or several documents; reads are not message boundaries.
- Requests: `{"jsonrpc":"2.0","id":"r1","method":"sessions.list","params":{}}`.
- Successful replies: `{"jsonrpc":"2.0","id":"r1","result":[]}`.
- Error replies: `{"jsonrpc":"2.0","id":"r1","error":{"code":-32601,"message":"Method not found"}}`.
- Notifications have `method` and `params`, but **no id**. They never receive replies.

JSON property order is irrelevant. Yallmo-generated request IDs are non-null strings. JSON-RPC numeric/null IDs are
representable in the envelope DTOs too; explicit null is not a notification. Every listed operation uses a named
parameter object, including `{}` for no parameters. Optional fields are omitted when absent; explicit null on an
optional application field decodes as absent. Required null results/IDs are retained with `@jsonExplicitNull`.

These are intentionally simple DTO codecs, not a JSON-RPC framework. The shared read-only handler validates complete
input, `jsonrpc == "2.0"`, IDs, named params, required members and mixed envelope fields. The client validates replies
and result/error exclusivity. Envelope codecs alone do not enforce those protocol rules. Decode concrete envelope
classes, not a discriminator-tagged Scala envelope ADT. Batches are ordinary arrays: validate members individually,
return one array containing request/error replies, and send nothing for a notification-only batch. Empty batches are
invalid. Current limits are 64 batch members, 1 MiB per assembled incoming/outgoing JSON message and 128 pending client
requests. Oversized responses close the connection with 1009; there is no pagination fallback yet. See README for
fragmentation limitations of the pinned ZIO HTTP API. The WebSocket tests exercise framing/protocol validation.

## Connection setup

The current read-only listener uses loopback binding and Host/Origin checks for both WebSocket upgrades and SPA assets. It has
no operator authentication or remote-hosting support. Its `local-viewer`/`viewer` identity is a local marker, not proof
of an OS identity. Before enabling writes or remote hosting, authenticate through the separately agreed transport
mechanism. Send `initialize` first; this is version/capability negotiation, **not authentication**. Other methods are unavailable
until initialization succeeds. JSON-RPC's fixed `"2.0"` and Yallmo's integer `protocolVersion: 1` are different versions.

`initialize.result.methods` and `.notifications` list only operations/events actually enabled and authorized on that
connection. Defining a DTO below does not advertise a working handler. The current server advertises only the seven read-only
methods listed above and an empty notifications list. Browser viewers cannot acquire execution
permission merely by submitting an operator method. Viewer permissions cover read queries/blob downloads; session
mutation, execution/control and diagnostics require operator permission. Authentication/bootstrap is still a
transport implementation decision; credentials are not supplied as arbitrary identities in these application params.

## Requests and results

Names below are exact. Scala operation objects contain the matching `method`, `Params` and `Result` types.
Domain IDs reuse `AgentId`, `SessionId`, `MessageId` and `BlobId` and their validating codecs; they still encode as
JSON strings. Session names must be safe path segments, message IDs must be `sha256:` plus 64 lowercase hex digits,
and blob IDs must be canonical lowercase UUIDs. Offsets/sizes are integers; timestamps use `Instant`/ISO-8601.
Request/command/run IDs remain protocol strings, not domain IDs.

| Method / Scala object | `params` | `result` |
| --- | --- | --- |
| `initialize` / `Initialize` | `{protocolVersion: Int, clientName: String}` | `{protocolVersion: Int, serverInstanceId: String, identity: Identity, methods: [String], notifications: [String], maxBlobChunkBytes: Int}` |
| `sessions.list` / `SessionsList` | `{}` | `[SessionSummary]` |
| `sessions.get` / `SessionsGet` | `{sessionId: SessionId}` | `SessionSnapshot` |
| `sessions.create` / `SessionsCreate` | `{name?: SessionId}` | `SessionSnapshot` |
| `sessions.fork` / `SessionsFork` | `{sourceSessionId: SessionId, name?: SessionId}` | `SessionSnapshot` |
| `messages.list` / `MessagesList` | `{}` | `[MessageSnapshot]` |
| `agents.list` / `AgentsList` | `{}` | `[AgentId]` |
| `tools.list` / `ToolsList` | `{agentId: AgentId}` | `[AgentToolDefinition]` |
| `blobs.list` / `BlobsList` | `{}` | `[BlobId]` |
| `blobs.read` / `BlobsRead` | `{blobId: BlobId, offset: Long, maxBytes: Int = 65536}` | `{blobId: BlobId, offset: Long, dataBase64: String, totalBytes: Long, eof: Boolean}` |
| `turn.submit` / `TurnSubmit` | `{commandId: String, sessionId: SessionId, agentId: AgentId, task: AgentTask, expectedHead?: MessageId}` | `TurnOutcome` (one final response) |
| `runs.get` / `RunsGet` | `{runId: String}` | `{runId: String, commandId: String, sessionId: SessionId, agentId: AgentId, status: RunStatus, outcome?: TurnOutcome}` |
| `runs.cancel` / `RunsCancel` | `{runId: String}` | `{runId: String, requested: Boolean}` |
| `diagnostics.message` / `DiagnosticsMessage` | `{sessionId: SessionId, messageId: MessageId}` | `{message: MessageSnapshot, providerRequest?: Object}` |

Domain values are reused instead of redefining viewer DTOs:

- `Identity = {id: AgentId, role: "viewer" | "operator"}`. The server determines it.
- Core/model `SessionSummary = {id: SessionId, messageCount: Long, agentIds: [AgentId]}`.
- Core/model `AgentTask = {prompt: String}` and `AgentResult = {agentId: AgentId, text: String}`.
- Core/model `AgentToolDefinition = {key: String, description: String, inputSchema: JsonSchema.ObjectSchema}`.
- `SessionSnapshot = {id: SessionId, agentIds: [AgentId], messages: [MessageSnapshot]}`.
- `MessageSnapshot = {id: MessageId, agentId: AgentId, parentId?: MessageId, timestamp: String, role: String,
  content: String, blobIds: [BlobId], toolName?: String}`.
- `ApplicationError = {code: String, message: String}`. Codes are machine-readable reasons; messages are safe display text.
- `RunStatus = "queued" | "running" | "succeeded" | "failed" | "cancelled"`.

Collections reuse core's `Chunk`, which encodes as ordinary JSON arrays. Missing codecs for reused domain values are
locally derived in `YallmoProtocol` (import `YallmoProtocol.given` when encoding these domain values directly).
The Full-layout extraction preserves persisted formats and wire schemas. The website depends on `protocolJS → modelJS`,
not directly included sources, JVM core or duplicate DTOs. All model sources, including System One/macros, compile and
test on both platforms. Missing codecs remain protocol-local.

Snapshots are the necessary exception to direct reuse: raw `Session`/`Message` include `MessagePayload.Reasoning`
with a private `ProviderContinuation`. `MessageSnapshot.from`/`SessionSnapshot.from` expose the existing domain IDs,
public text and attachment references, never that opaque continuation. They do not modify stored messages. Other
payload text, including tool output, remains sensitive and requires authorization.

Tool inputs reuse the existing `JsonSchema` model with default derived encoding, **not** a new provider JSON Schema
encoder. `inputSchema` is the object subtype, so its fields are directly `properties`, `required`,
`additionalProperties`, and optional `description`. Nested property/item schemas use the enum's default tags:
`StringSchema`, `NumberSchema`, `IntegerSchema`, `BooleanSchema`, `ArraySchema`, `ObjectSchema`. For example:

```json
{"key":"inspect","description":"Read","inputSchema":{"properties":{"path":{"StringSchema":{"allowedValues":[]}}},"required":["path"],"additionalProperties":false}}
```

String schemas add `allowedValues`; array schemas contain `items`; object schemas contain `properties`, `required`
and `additionalProperties`. Every schema can have optional `description`. This follows the shared model exactly.

### Operation rules

- Session names are validated IDs, **not paths**. Omitted create/fork names are generated server-side. Create uses the
  configured human identity; an assistant joins when used. Fork copies the source's **current head**, not an arbitrary
  historical parent. List ordering follows the existing viewer: sessions/agents/blob IDs by ID, messages by timestamp
  then ID. Session messages are in transcript order. Tools are ordered by their core `key`.
- `turn.submit` selects an agent explicitly. The server resolves trusted execution and human authorship. Omitted
  `expectedHead` disables the precondition; a supplied head must match under write admission or submission is rejected.
  `commandId` is an execution/deduplication identity, not the JSON-RPC request ID. Reuse with different params conflicts.
  Deduplication/retention is process-local; no restart guarantee or command-lookup operation is promised in this step.
- `runs.get.outcome` is absent while queued/running and present when terminal. `runs.cancel.requested = true` means a
  cancellation request was recorded, not that the run stopped. False means it was already terminal. Unknown run IDs
  produce NotFound. Control requests must not wait behind the active turn. Disconnect is not cancellation.
- `blobs.read` reads completed blobs only. `offset >= 0`, `1 <= maxBytes <= 65536`; violations are Invalid params.
  An offset beyond the total size is invalid; an offset at EOF returns empty data and `eof: true`. Decode each base64
  chunk separately and advance by its decoded byte count. Stop at `eof`; `totalBytes` must remain consistent. No uploads,
  transfer sessions, binary frames or HTTP blob fallback are defined here. Example: bytes `00 01 ff` encode as `AAH/`.
- `diagnostics.message.providerRequest` is authorized and redacted, never a server path or reusable provider secret.
  It is absent when no capture is available. Transcript text/tool results can themselves be sensitive.

## Turn notifications and final response

Notifications go to the submitting connection, identified by run ID; they are transient and not replayed. Their names
and params are exactly:

| Notification / Scala object | `params` |
| --- | --- |
| `turn.started` / `TurnStarted` | `{requestId: String \| Number \| null, commandId: String, runId: String, sessionId: SessionId}` |
| `turn.progress` / `TurnProgress` | `{runId: String, text: String}` |

`turn.started.requestId` copies the originating JSON-RPC ID without changing its type.
`turn.started` means registered/admitted, **not prompt committed**. `turn.progress` is sanitized informational text,
not a token-delta schema or a durable transcript. Producing it requires application instrumentation; adapters must not
fabricate progress. A server only advertises notifications it can actually emit. Notifications are followed by one
terminal response for the original request ID, not a second reply for registration.

`TurnOutcome` is a discriminated application result (not a tagged JSON-RPC envelope):

- `{status: "succeeded", runId: String, sessionId: SessionId, headMessageId: MessageId, result: AgentResult, newMessages: [MessageSnapshot]}`.
- `{status: "failed", runId: String, sessionId: SessionId, accepted: Boolean, headMessageId?: MessageId, error: ApplicationError}`.
- `{status: "cancelled", runId: String, sessionId: SessionId, accepted: Boolean, headMessageId?: MessageId}`.

`accepted` means the prompt was saved; it cannot be inferred from `turn.started`. A registered run may fail before
acceptance, so failed/cancelled outcomes can say false. A successful run includes the prompt and new persisted output
in `newMessages`. `headMessageId` refers to durable state, not rollback of external side effects. Validation/policy
rejection before run registration uses a JSON-RPC error instead.

Example exchange, identical on both transports (only framing omitted):

```json
{"jsonrpc":"2.0","id":"r3","method":"turn.submit","params":{"commandId":"c1","sessionId":"s1","agentId":"agent1","task":{"prompt":"Hello"}}}
```
```json
{"jsonrpc":"2.0","method":"turn.started","params":{"requestId":"r3","commandId":"c1","runId":"run1","sessionId":"s1"}}
```
```json
{"jsonrpc":"2.0","method":"turn.progress","params":{"runId":"run1","text":"Working"}}
```
```json
{"jsonrpc":"2.0","id":"r3","result":{"status":"failed","runId":"run1","sessionId":"s1","accepted":true,"headMessageId":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","error":{"code":"provider_failed","message":"Provider unavailable"}}}
```

The server owns the run beyond connection lifetime. On reconnect, a known run ID can be queried. Without a received
run ID, this MVP does **not** promise automatic uncertain-submission recovery. Never automatically resubmit a turn
because its response was lost. Replay, subscriptions, historical branching, voice, human interaction, credential
mutation and PTY messages are not defined here; they require explicit contract extensions. Pagination remains deferred.

## Error codes

Standard JSON-RPC codes: `-32700` Parse error, `-32600` Invalid Request, `-32601` Method not found,
`-32602` Invalid params, `-32603` Internal error.

Application errors use these envelope codes, with optional `error.data: ApplicationError` for a safe reason:

| Code | Meaning |
| --- | --- |
| `-32001` | Unsupported Yallmo protocol version |
| `-32002` | Unauthorized |
| `-32003` | Forbidden |
| `-32004` | Not found |
| `-32005` | Conflict (head mismatch, duplicate session, conflicting command ID) |
| `-32006` | Busy (write/run admission unavailable) |
| `-32007` | Rejected (other application validation/policy rejection) |

Protocol errors never contain stack traces. Run failures after registration use `TurnOutcome` instead of collapsing
execution errors into Invalid Request. Malformed/unidentifiable requests use `id: null`; notifications receive no reply.

Wire examples and derived-codec round trips are tested in
[`YallmoProtocolSuite.scala`](../protocol/shared/src/test/scala/fmgp/yallmo/server/jsonrpc/YallmoProtocolSuite.scala). Actual WebSocket
queries, errors, correlation, timeouts and scope/disconnect cleanup are tested in `OrchestratorWebSocketSuite`;
`WebSocketSupportSuite` covers fragment limits and continuation UTF-8 handling. `OrchestratorWebHostSuite` covers
static assets, security headers, removed REST routes and the new standalone entry point. Browser client tests cover
initialization, reconnect without replay, timeouts, response correlation and bounded chunk reconstruction; production
and Vite browser smoke checks exercise the real website/download path. Write authorization, execution, Unix framing,
persistence semantics and notification delivery remain future work.
