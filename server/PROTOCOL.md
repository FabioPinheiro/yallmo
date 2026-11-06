# Yallmo wire protocol

**Implemented: viewer reads and configured execution for trusted browser and REPL clients.**
Access is assumed already authenticated outside this server; it performs no authentication or Host/Origin checks.
Keep all direct/proxied access inside a trusted environment, never public. Claimed user names are impersonable.
The listener always binds to `0.0.0.0` for trusted Docker/multi-machine access.

DTOs live in [`YallmoProtocol.scala`](../protocol/shared/src/main/scala/fmgp/yallmo/server/protocol/YallmoProtocol.scala)
and envelopes in [`JsonRpc.scala`](../protocol/shared/src/main/scala/fmgp/yallmo/server/jsonrpc/JsonRpc.scala).
The browser shares protocol/model sources and uses the same initialization and methods as the REPL. HTTP serves static assets and the WebSocket
upgrade only; no conversation/blob REST endpoint or dedicated voice/tool-control API is enabled.
Provider administration and browser OAuth use JSON-RPC on the same WebSocket. Generic binary uploads use JSON-RPC only. Operator-only
`diagnostics.request` reads existing provider-request captures without executing a turn.

## Connection and capabilities

Send `initialize` first. Initialization binds the user and advertises capabilities; it is **not authentication**.
Client and server share one current contract. There is no application protocol version field, version negotiation or
backward-compatibility layer. JSON-RPC envelopes still require `"jsonrpc": "2.0"`.
`OrchestratorWSClient.connect` initializes once before returning; `capabilities` reads that cached result without another request.

Viewer methods: `initialize`, `sessions.list`, `sessions.get`, `messages.list`, `agents.list`, `blobs.list`, `blobs.read`, `events.subscribe`, `events.unsubscribe`.
Operator additionally advertises `turn.submit`, `branches.submit`, `agents.invoke`, `blobs.upload` and the read-only `diagnostics.request`, plus `providers.list`, `providers.login.start`, `providers.login.complete` and
`providers.login.cancel`. The implemented notification is `events.update` (only after subscribing).
A server constructed with just a supplied master, or standalone without `--config`, remains viewer-only.

Operator requires a configured backend and a nonempty initialized claimed `userId`, irrespective of Origin or peer
address. Anonymous connections remain Viewer. `clientName` is never authority; a browser uses its real Origin and
claims a human ID in the existing `initialize` params. No permission opt-in or Origin allowlist is required. Anyone
with network access can claim an author and execute configured agents/tools, so public deployment is unsupported.

Initialize result `configuredAgentIds`, when present, is the safe executable-assistant selection catalog. `agents.list`
also contains human/presence IDs and is **not** an executable catalog. Provider/model configuration is not exposed by this catalog; explicit operator diagnostics may show the request model.
The server independently resolves configured assistants; it never accepts executable code or provider state from a
turn request. Initialization binds a `UserId`, not a message author. `Identity = {id: UserId, role: Role,
agentIds: [AgentId]}` advertises server-owned actors, separate from `configuredAgentIds` recipients. The trusted-local
policy provisions `user-<UserId>` for chat and `web-user-<UserId>` for website/harness operations. Ownership mappings
last for the handler's process lifetime and are reconstructed deterministically after restart; there is no ownership
management or authentication API. Anonymous viewers have no owned actors.

Every authoring request (`turn.submit`, `branches.submit`, `agents.invoke`) requires `actingAgentId`. The server
checks it against the connected user's ownership before mutation/execution; `agents.invoke` additionally requires
`input.data.agentId == actingAgentId`. Missing actors are InvalidParams; unowned actors or mismatched authors are
Forbidden. Recipient `agentId` remains separate and all configured capability checks still apply. Chat and website
actors share one connection; switching actors needs neither reinitialization nor a new session. Read-only and
connection-owned administrative/blob requests do not author messages and remain user/connection-scoped.
User rebinding is rejected. Owned actors colliding with executable runtime IDs are rejected; registered human
actors can reconnect. There is no implicit-author fallback.

Presence is connection-owned and cleaned on disconnect; duplicate same-name viewers retain presence while connected.
A human registered on submit/invoke remains in the runtime registry for the process lifetime, independently of active
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
  Read deadlines default to **15 seconds**; `turn.submit`, `branches.submit` and `providers.login.complete` deliberately have **no read deadline**.
- Both clients fail pending waits on close/error and never reconnect/replay automatically. A deadline or disconnect
  is a lost local wait, not evidence that server work stopped.
- JVM transport uses public JDK WebSocket, which sends no Origin by default. It retains strict Unicode/UTF-8 byte
  bounds across fragments, one-message callback backpressure and scoped bounded executor resources. Server-side
  pinned ZIO HTTP framing restrictions remain documented in [README](README.md#security-and-transport-limits).

## Orchestration event subscriptions

Initialized Viewers and Operators may subscribe using the same trusted-host policy as inventory reads. This grants
no authoring rights. A connection owns at most one subscription; subscribing replaces its previous one. Unsubscribing
an obsolete/unknown ID is harmless and never closes a newer subscription or the shared socket. Legacy/unsubscribed
clients receive no event notifications. The JVM client exposes `subscribeEvents(nonce)`, `nextEventUpdate`, and
`unsubscribeEvents(subscriptionId)`; it never reconnects or replays authoring requests.

`EventsSubscribe.Result` contains `{subscriptionId, streamId, nonce, startSequence, messages, sessions, pending}`:

- `messages`: canonical public `MessageSnapshot` inventory, without opaque provider continuations.
- `sessions`: `EventSession = {id, agentIds, messageCount, head?, revision, error?}`. After a successful inventory
  listing, an individual subsequent history-read failure retains summary fields and a sanitized `error`; its absent
  head is **unknown**, not a confirmed empty pointer. Inventory-listing failures still fail bootstrap: filesystem
  listing itself traverses histories, so a missing referenced message can prevent that initial listing.
- `pending`: `[{operationId, messages: [MessageSnapshot]}]` for retained in-flight observations at registration.

Notifications have exactly the JSON-RPC notification envelope, with no request ID/result/error:

```json
{"jsonrpc":"2.0","method":"events.update","params":{"subscriptionId":"s","streamId":"process-stream","nonce":"client-nonce","sequence":1,"change":{"kind":"pending","operationId":"op","messages":[]}}}
```

`change.kind` is `pending` (`operationId`, public `messages`), `confirmed` (`operationId`, public `messages`, canonical
`sessions`), or `uncertain` (`operationId`). Pending is an optimistic observation, **not persistence**. Maintain it
separately from confirmed inventory; confirmation promotes it, and uncertainty invalidates the feed and requires a
new subscription/baseline. Never infer rollback, delete stored branches, or replay authoring operations. Failed storage
acknowledgements preserve the original operation error; bounded read-only recovery may still confirm exactly observed
stored messages/canonical session state. Cancellation emits uncertainty without waiting for recovery reads.

Storage and live enqueue run in parallel through one storage decoration covering immutable puts, batches, session
creation/forks, participants and CAS. Pending enqueue completes before its terminal event, even if storage finishes
first. Session intents never publish pointers. A short ordered session-mutation boundary assigns monotonically increasing
process-local revisions to canonical outcomes, including A → B → A and recovered acknowledgements. Immutable object
writers remain independent and preserve their existing parent-before-child/drain behavior.

The subscriber registers **before** baseline reads. Notifications may precede the subscribe response: register a local
nonce handler before sending, buffer them, and apply matching events from `startSequence + 1` in order after the baseline.
Snapshot session revisions dominate earlier buffered pointer events; do not discard all buffered events through an end
watermark. Independent immutable writes may still be absent from the snapshot: retained pending observations plus their
buffered terminal events cover that race. Deduplicate immutable message IDs; ignore stale subscription generations and
mutable revisions. Sequence gaps, overflow, uncertainty, or a new stream identity require a fresh baseline, not replay.

Per-master bounds: **128** subscriptions; retained pending observations **128 events / 2 MiB**. If pending retention
capacity is exceeded, new subscriptions fail Busy until untracked in-flight writes settle; storage is never dropped or
rejected by this policy. Per-subscription buffers allow **128 events / 2 MiB**, conservatively counting all public message
fields (including tool names and JSON escaping), with explicit overflow failure rather than sliding/dropping loss.
One scoped sender per connection shares serialized frame writes, independently of the 8 request workers. Overflow,
oversized notifications (also subject to the **1 MiB** frame limit), or a **5-second** send deadline close only that socket
with **1013** and a sanitized reconnect/resubscribe reason. Unsubscribe, failed bootstrap, disconnect and socket scope
shutdown release registry entries and sender waiters. These bounds measure server egress, not browser rendering speed.

The wire methods are `events.subscribe`, `events.unsubscribe`, and `events.update`; the previous graph-prefixed
method names have no compatibility aliases. This namespace rename does not add session filtering or Chat subscriptions.

The stream is ephemeral and application/master-local: no durable cursor, historical replay, cross-process filesystem
watch, or persistence-format migration. Direct external-process writes are outside this in-process dispatcher.

## Enabled requests and results

| Method | `params` | `result` |
| --- | --- | --- |
| `initialize` | `{clientName: String, userId?: UserId}` | `{serverInstanceId: String, identity: Identity, methods: [String], notifications: [String], maxBlobChunkBytes: Int, configuredAgentIds?: [AgentId]}` |
| `sessions.list` | `{}` | `[SessionSummary]` |
| `sessions.get` | `{sessionId: SessionId}` | `SessionSnapshot` |
| `messages.list` | `{}` | `[MessageSnapshot]` |
| `events.subscribe` | `{nonce: String}` (unique printable ASCII, 1–128 characters) | `EventsSubscribe.Result` (below) |
| `events.unsubscribe` | `{subscriptionId: String}` | `{}` (idempotent, connection-owned) |
| `agents.list` | `{}` | `[AgentId]` |
| `agents.invoke` | `{agentId: AgentId, input: Message, actingAgentId: AgentId}`; no session controls | `Message` (recipient's response payload) |
| `blobs.list` | `{}` | `[BlobId]` |
| `blobs.read` | `{blobId: BlobId, offset: Long, maxBytes: Int = 65536}` | `{blobId: BlobId, offset: Long, dataBase64: String, totalBytes: Long, eof: Boolean}` |
| `blobs.upload` | Discriminated command (below), no session ID | Discriminated result (below) |
| `providers.list` | `{}` | `[{id: String, providerType: "OAI" | "ChatGPT", credentials: "not-applicable" | "logged-out" | "configured"}]` |
| `providers.login.start` | `{providerId: String}` | `{attemptId: String, authorizationUrl: String, expiresAtMillis: Long}` |
| `providers.login.complete` | `{attemptId: String, redirectUrl: String}` | `{restartRequired: Boolean}` |
| `providers.login.cancel` | `{attemptId: String}` | `{}` |
| `diagnostics.request` | `{sessionId: SessionId}` | `{providerRequest?: JsonObject}` |
| `branches.submit` | `{parentId: MessageId, agentId: AgentId, task: {prompt: String}, actingAgentId: AgentId}` (strict fields) | `BranchOutcome` (one terminal response, no session) |
| `turn.submit` | `{target: SubmitTarget, agentId: AgentId, task: AgentTask, actingAgentId: AgentId, expectedHead?: MessageId, resetContinuation: Boolean = false}` | `TurnOutcome` (one terminal response) |

Existing domain types/codecs are reused. IDs encode as strings: safe path-segment `SessionId`, `sha256:` plus
64 lowercase hex characters for `MessageId`, canonical lowercase UUID for `BlobId`. Timestamps use ISO-8601 `Instant`.
Collections encode as arrays. Protocol-local codecs do not alter storage formats.

- `Identity = {id: UserId, role: "viewer" | "operator", agentIds: [AgentId]}`; owned acting agents are separate from configured recipients.
- `SessionSummary = {id: SessionId, messageCount: Long, agentIds: [AgentId]}`.
- `SubmitTarget = {kind: "new", name?: SessionId} | {kind: "existing", sessionId: SessionId}`. The target is mandatory; legacy top-level `sessionId` is rejected. `expectedHead` is valid only for existing targets.
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

## Provider administration and browser login

All four `providers.*` operations require an initialized configured Operator, including listing. This uses the same
trusted-environment policy as execution, **not an authenticated admin role**. Protect direct and proxied network access;
anyone able to claim a user can replace provider credentials. Both the browser and connected terminal expose safe
provider listing and profile-scoped login over the existing socket when these methods are negotiated.

Listing reads the server's current explicit config and returns presence-only summaries; it never tests credentials,
returns tokens/endpoints, or selects/executes an agent. This first version manages login for existing ChatGPT profiles
only. Edit the private configuration separately to create profiles. No credentials are read from Pi or Codex files.

Start validates the profile and returns the OpenAI authorization link, an opaque attempt ID and Unix-millisecond
expiry. PKCE verifier and expected state stay on the server. Attempts belong to the **exact connection**, not the
claimed name: another same-name connection cannot complete or cancel them. A new Start replaces that connection's
previous pending attempt. There are at most **64** pending attempts per configured application, expiring after
**15 minutes**, with scoped periodic cleanup and disconnect cleanup. Capacity exhaustion is Busy.

The user opens OpenAI in their browser and pastes the full final
`http://localhost:1455/auth/callback?code=...&state=...` URL into the web form or hidden terminal callback prompt. The localhost page may fail to load;
copy its address anyway. Do not paste it into chat, logs or a shared document. Complete consumes an owned attempt
before exchanging the code, checks expiry and delegates strict callback/state validation to the existing OAuth flow.
Failed authorization also consumes the attempt; no mutation is automatically replayed. Callback text is limited to
**16,384 characters**, attempt IDs to **64**, and Start provider IDs to **1,024**. Unknown, expired, consumed or foreign
attempts return NotFound; authorization failures return a fixed Rejected error without OAuth error contents.

Cancel is idempotent cleanup of a still-pending owned attempt; it does not cancel an exchange already admitted.
Authentication is interruptible. After successful authentication, latest-target revalidation and private atomic
credential persistence and runtime activation finish without interruption. A removed/converted/untrusted profile is never recreated or given
credentials. Lost replies are uncertain: reload providers before manually starting again. Presence alone does not
prove that an uncertain reconnect succeeded. Browser and JVM completion waits are not subject to the short read timeout;
OpenAI exchange itself has the existing 30-second deadline.

Success changes only the selected profile's credentials and preserves other configuration (including YAML comments).
The standalone configured application then atomically publishes a fresh connection for that runtime profile,
invalidates its dependent configured agent bindings, and returns `restartRequired: false`. Subsequent lookups share
the new connection and isolated refresh state; in-flight callers keep their previous connection. Unrelated providers,
registered users, storage, prompts, model settings and already-rooted filesystem capabilities are unchanged. No
browser reconnect or agent reselection is required. Disk save and activation share a commit lock so concurrent
logins publish in the same order. A delayed refresh on a previous connection cannot overwrite the new generation.

A save-only application, or a profile absent/incompatible in the running snapshot (e.g. externally added since
startup), returns `restartRequired: true`: credentials are durable but the server must restart before using them.
Clients must not claim live activation in this case. No general config watcher/reload, logout or token export is
added. Automatic token refresh during inference is separate, remains in memory only, and may rotate the refresh
token; never assume the disk credentials are still usable after a later server restart.

TRACE redacts login request params and OAuth response fields, including batches and escaped field names. Tokens never
appear in protocol results. The web UI holds callback text only in transient memory, clears it on submit/tab exit,
and never writes it to browser storage. Use HTTPS/WSS or a secure tunnel for access beyond a trusted local machine.

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

`agents.invoke` handles one configured agent turn or an explicit tool call, not an agent loop, without requiring or creating a session. It is available only on configured
servers to initialized Operators; anonymous clients get Forbidden and unconfigured servers return MethodNotFound.
Params have exactly `agentId` (recipient), `input` (full Message) and `actingAgentId` (owned actor). Extra params, including `sessionId`,
`expectedHead` and `resetContinuation`, are InvalidParams. Message hash/field validation happens during decoding.
The input author must match the explicitly selected owned actor (Forbidden otherwise); this is still not authentication.

Input and output payloads are not restricted by the transport or Orchestrator: the configured recipient determines what
it supports and returns. An optional parent must already exist, but does not implicitly load parent history.
Unsupported agent audio capability and invalid configured recipients are rejected before saving input.
The input timestamp/content/id are preserved exactly, not replaced with a server-generated user message.

The core Orchestrator saves the input independently in message storage, awaits any referenced blobs with an explicit bounded `readBlob` timeout,
then supplies only its payload to an `AgentInvocation`. Successful execution saves the recipient's exact response payload
in a full `Message`, with the selected agent as author, a server timestamp and `parentId = input.id`.
No session history, prior provider continuation or session continuation policy is inherited.

The text-oriented `AgentRunner.invocation` adapter requests at most one agent turn and returns a non-blank `Assistant`
answer. Model-requested actions are rejected with guidance to use `turn.submit`; there is no transient tool-loop fallback.
`AgentExecution` is an opaque session-loop handle and cannot be invoked directly. The configured runner rejects incoming opaque
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

## Sessionless branch submission

`branches.submit` requires an initialized configured Operator, an owned acting actor and a configured recipient.
Parent ancestry must be complete and hash-valid, with complete tool exchanges. Nonblank text only; the client cannot
supply history, executable code/handles, timestamps, session controls or prompt messages. The server saves the user prompt
once under the exact parent before running the same configured managed model/tool loop used by session submission.
No session is created (not even temporarily), joined, forked or published. Existing descendants and reasoning objects remain
unchanged. Provider request replay excludes inherited opaque continuations; new run-local continuations can be used.

`BranchOutcome` is discriminated by `status`:
- `succeeded`: `{parentId, promptId, head, result: AgentResult, newMessages: [MessageSnapshot]}`.
- `failed`: `{parentId, acceptance: "accepted" | "unknown" | "notAccepted", promptId?: MessageId, head?: MessageId,
  newMessages: [MessageSnapshot], error: ApplicationError}`. `head` is the last confirmed stored active head; an unknown
  failure does not prove no later objects exist. An attempted prompt ID can be known even if its put acknowledgement failed.

Definite preacceptance rejection uses JSON-RPC errors; prompt write uncertainty is not mislabeled notAccepted.
The application owns admitted work independently of a connection's wait. Clients never retry/replay it and have no short
read timeout for this method; configured execution budgets remain enforced. Pending writes drain and inventory is checked
before success, so the terminal head cannot refer to a missing generated object. Failures can expose verified saved partial
messages without leaking opaque continuations or provider causes. This is neither rollback/crash-atomic storage nor
exactly-once tool execution. Inspect `messages.list` after uncertain outcomes; no branch publication/recovery endpoint is added.

## Submission, continuation and failure semantics

`turn.submit` is the session-authoring operation; `branches.submit` authors an unnamed, sessionless branch. There is no `sessions.create` endpoint.
The caller explicitly selects `{kind: "new", name?: SessionId}` or `{kind: "existing", sessionId: SessionId}`.
New validates ownership, recipient configuration/capabilities and parameters before allocating a session, then submits
its first prompt in that same application-owned operation. Generated IDs use `main-<UUID>`; names are safe IDs, not
paths. A duplicate explicit name conflicts and is never treated as a resume. Existing never allocates a missing session.
`expectedHead` with New is InvalidParams. Both target variants use the same actor/recipient checks and runner.

Success and execution-failure responses carry the allocated session snapshot/ID whenever it can be established,
including a pre-acceptance execution failure. Allocation and message persistence are not a rollback transaction:
an interrupted or failed first submit may leave a recoverable empty session. Once a session is allocated, execution
is never retried by allocating another. The REPL and website send one New request for the first prompt and Existing
for later prompts. `/new [name]` and **New conversation** prepare local drafts only, without RPC or empty remote sessions.

The application owns work, not the socket. A short root-local storage mutex coordinates filesystem mutation/publication
and snapshot capture; **no provider/tool work is held under it**. Nonwaiting same-session turn exclusion rejects Busy
before a second prompt. Independent sessions/agents execute concurrently, not under a global agent/run gate.
Exactly one storage service owns a root; the cooperative standalone lease does not protect against legacy/uncooperative
same-root owners. Do not run those writers together. No crash transaction or external side-effect rollback is promised.

Under session admission, a supplied `expectedHead` must match the stored last message. Omitted/null skips the condition;
there is no invented “must be empty” condition. Busy/head/configuration rejection precedes prompt writes. The core
submission path alone saves the prompt once, then schedules ordered, asynchronous transcript saves before tools and after
each result; RPC never preappends input. Writes do not block tools or inference. Failed saves log errors and later snapshots
retry the unsaved suffix. Pending writes drain on completion, failure or cancellation; any remaining unsaved messages log a
warning. This is best-effort persistence: successful execution does not guarantee that every generated message was saved.

`resetContinuation: true` discards old opaque provider continuation from future requests, not visible transcript.
The server also resets on execution-binding change or unknown policy after restart. Resume asks for reset on the next
turn; the client sends no history offset or provider continuation. Later same-binding turns reuse the server boundary.
Remote UI runContext stays empty; public snapshots/deltas render original authors/content safely.

Terminal `TurnOutcome` is a discriminated **application result**, not a JSON-RPC envelope:

- `{status: "succeeded", session: SessionSnapshot, result: AgentResult, newMessages: [MessageSnapshot]}`.
- `{status: "failed", acceptance: "notAccepted" | "accepted" | "unknown", session?: SessionSnapshot, error: ApplicationError}`.

Success includes the actual durable suffix (prompt plus generated output) with real persisted IDs. No runId, commandId,
ledger, status, cancel, tombstone, deduplication, replay or exactly-once guarantee is active. An accepted typed failure
retains the prompt and earlier saved checkpoints and returns their safe snapshot. Missing accepted-session information is **Unknown**, never inferred
NotAccepted. Defect/shutdown returns terminal Unknown with the best safely loadable snapshot; absence is not rollback.
NotAccepted means known not persisted, Accepted means known persisted. Snapshot heads are last confirmed knowledge,
not a guarantee that an uncertain write cannot advance them.

Application-scope work survives disconnect; only the connection wait is interrupted. Terminal delivery uses an
application-owned Promise, including typed errors, defects, shutdown and already-closed-scope/startup races. Work and
its finalizers stop **before** storage/tool ownership and root lease release. Uncooperative finalizers can delay shutdown.
No terminal registry is retained. Ctrl+C abandons local waiting only; it never issues remote cancellation.

After an unknown transport outcome, refresh `/history` for a known session, or inspect `/session list` and explicitly
resume the saved conversation if the first reply was lost. Both clients block another submission until explicit recovery;
there is no automatic retry. An InternalError also means unknown, not proof that no session was allocated.
A lost submit response may still yield durable completion. Busy/stale-head retains current client
session/head; refresh before another manual attempt. There is no automatic reconciliation or safe replay claim.

Example request (one terminal result when the connection remains usable):

```json
{"jsonrpc":"2.0","id":"r3","method":"turn.submit","params":{"target":{"kind":"existing","sessionId":"s1"},"agentId":"bob","task":{"prompt":"Hello"},"actingAgentId":"user-fabio","resetContinuation":false}}
```

## Errors and disabled definitions

Standard JSON-RPC codes: -32700 Parse error, -32600 Invalid Request, -32601 Method not found,
-32602 Invalid params, -32603 sanitized Internal error.

| Code | Meaning |
| --- | --- |
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
Provider creation/editing, voice/human-interaction integration and public authentication remain separate pending work.
Provider listing/login is implemented over the existing WebSocket for browser and terminal clients.

## Validation

`YallmoProtocolSuite` freezes enabled shapes, optional fields/defaults and shared JVM/JS decoding.
`OrchestratorInvokeSuite` covers session-independent persistence on memory/filesystem storage, blob dependency waits,
invalid inputs, rejection of model-requested actions, persistence failures and interruption. `AgentsInvokeSuite` covers
authorization, direct tool execution, loop rejection, continuation privacy, owned lifetime/shutdown, and real same-socket
upload/invocation correlation. `ExecutionBoundarySuite` checks that application code cannot construct or directly execute
loop handles; session loops must enter through `Orchestrator.submit`.
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
