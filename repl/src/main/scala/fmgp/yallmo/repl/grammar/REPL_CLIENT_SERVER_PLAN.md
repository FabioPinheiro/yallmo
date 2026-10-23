# REPL → server → core: client/server redesign plan

**Status: read-only WebSocket server/clients and website migration complete; REST viewer removed. Unix-domain sockets
are deferred. Server-owned execution, connected REPL mode and runtime extraction remain pending.**

This revision records the decisions made after the transport review: **one JSON-RPC 2.0 application protocol over
WebSocket and, when resumed, Unix-domain sockets**, with one server-owned runtime as the execution target. The current
implementation is WebSocket-only and read-only. It supersedes the HTTP/REST-first recommendation.
Research checked on **2026-10-23**, including the sbt **v2.0.9** implementation. “bool” is interpreted as **Bloop**.

**Scope of this update: documentation only.** This records implementation already authorized separately; it does not
authorize further production changes, runtime extraction or new modules. The plan stays at this path until a
documentation move is separately agreed.

### Implementation snapshot

- **Complete:** derived JSON-RPC/application codecs, a shared read-only RPC handler, `OrchestratorClient`, and JVM
  `OrchestratorWSServer`/`OrchestratorWSClient`, using a supplied `OrchestratorLive` without another runtime/storage owner.
- **Complete:** browser WebSocket client, all viewer queries and bounded attachment downloads, SPA hosting, Vite `/rpc`
  proxy, and the new `OrchestratorServerMain`. The old REST server/routes, Fetch client and `ViewProtocol` are removed.
- **Validated:** 37 server tests, 22 frontend tests, Scala.js linking and production bundling. Production/Vite browser
  checks covered all five tabs, graph expansion and byte-exact downloads over one application WebSocket.
- **Deferred:** Unix-domain socket adapters. They do not block WebSocket delivery or future connected REPL work;
  WebSocket is also suitable for the initial local JVM client.
- **Still pending:** execution authorization/composition, server-owned runs, connected REPL submission and the separately
  approved extraction batches. Future operation/notification DTOs are not evidence that those handlers are enabled.

The implemented wire contract and current limits are documented in
[server/PROTOCOL.md](../../../../../../../../server/PROTOCOL.md) and
[server/README.md](../../../../../../../../server/README.md).

**Approval rule:** before moving production code, grammar types, tests or packages between modules, show the user the
exact from/to paths and dependency changes and obtain explicit approval for that batch. Agreement on the architecture
and protocol does not implicitly approve relocations or the introduction of additional modules.

## 1. Agreed direction

Target `server` as the application boundary between `core` and the REPL. The browser/read-only JVM WebSocket path
is implemented; connected REPL execution is not yet wired:

```text
Browser ----- WebSocket adapter -----------+
JVM client -- WebSocket adapter -----------+--> shared JSON-RPC dispatcher
REPL -------- WebSocket client (planned) --+            |
                                            one server application/runtime
                                                       |
                                          core: execution and persistence
                                                       |
                                               model / filesystem

Unix-domain socket adapter: deferred; reuse the same contract/dispatcher when resumed.
```

The implemented WebSocket and any future Unix adapter use the **same requests, responses, errors and notifications**.
They share application dispatch, validation, codecs and the client-facing operation contract; only connection/framing
and transport-specific security belong in the adapters. There must not be a WebSocket application and a separate Unix-socket application.

Decisions already made:

- **JSON-RPC 2.0** is the application envelope, not a custom incompatible RPC format.
- **WebSocket** is implemented for browser/JVM clients and can serve the initial connected REPL transport.
- **Unix-domain sockets are deferred**, not a prerequisite for this milestone. When resumed, add adapters over the
  same contract/dispatcher; no intervening REST execution API or second protocol redesign is needed.
- Streaming uses JSON-RPC **notifications alongside ordinary request/response messages**, following the useful sbt
  pattern. JSON-RPC does not itself define subscriptions, stream completion, replay or application backpressure.
- Skip the proposed `OrchestratorHttpClient` and HTTP/REST execution API.
- HTTP remains only as infrastructure for SPA assets and the WebSocket upgrade. A browser cannot open a Unix-domain
  socket, and WebSocket does not eliminate its HTTP handshake.
- Keep the website small and read-only initially. Server execution capabilities do not require adding browser
  execution controls now.

The website migration is complete: queries and attachment bytes use JSON-RPC over WebSocket, preserving the viewer
UI. The old Fetch client and REST GET/blob routes have been removed after migrating their consumers. HTTP now serves
only static assets and the WebSocket upgrade; do not reintroduce a second application API.

## 2. Current code versus the proposed boundary

Today, [build.sbt](../../../../../../../../build.sbt) declares `repl.dependsOn(core)` and `server.dependsOn(core)`.
The REPL starts its own runtime through `ReplMain`/`ReplStartup`; the server is a separate read-only viewer.
`OrchestratorServerMain` opens filesystem storage with an empty registry and starts the WebSocket/SPA host.
Embedded use supplies the existing `OrchestratorLive`. The server is not yet the REPL's execution daemon.

The target compile dependency is **`repl → server → core`**, with no `server → repl` dependency. Merely changing
`.dependsOn(core)` to `.dependsOn(server)` does not implement the process split: the client must stop constructing its
own `OrchestratorLive`, provider clients and tool services in connected mode.

Initially, keeping client/API packages inside `server` is the smallest module change. That makes the REPL a thin
*runtime client*, not a minimal distribution: `core` remains a transitive dependency. Extracting a shared `protocol`
and/or JVM `client` module later could reduce coupling and packaging size, but requires separate approval. Do not add
modules or move transport types into `model` just to make the diagram look cleaner.

Scala.js now compiles the existing model sources (excluding experimental System One/macros) plus the server's
`jsonrpc/JsonRpc.scala` and `protocol/YallmoProtocol.scala` as shared source. It has no JVM `core` dependency or duplicate
viewer DTOs. `ViewProtocol` is removed. A dedicated JVM/JS protocol module remains optional and requires separate approval.

### What stays where

| Owner | Responsibility |
| --- | --- |
| `core` | Agent/provider execution, tool implementations, storage implementations, domain invariants, persistence lifecycle |
| `server` | Application composition, configuration/credential ownership, agent binding, run coordination, API authorization, transport adapters, SPA hosting |
| `repl` | TTY/JLine acquisition and restoration, keybindings, draft/history, rendering, local export destination, client connection lifecycle |
| `view-web` | Simple browser presentation over the same authorized API |
| Client-local | Selected session/agent preference, focus, selected message, terminal log filter |
| Server-owned | Actual session contents, validated execution configuration, run records/fibers, pending interactions, cancellation and storage ownership |

A selected agent is sent explicitly with a turn; it is not a mutable global selection shared by all clients. The
server validates it against its registry and policy. Human authorship comes from the authenticated/configured
identity, not an arbitrary client-provided author ID.

No durable client UI state is required. Ephemeral connection subscriptions, authentication sessions and interaction
leases are legitimate server state; the old prohibition on *all* attached-client state was too broad.

## 3. Proposed code ownership changes — all pending confirmation

Paths below are repository-relative. Destination names are proposals, not instructions to move whole files verbatim.

| Existing code | Proposed treatment | Reason / caution |
| --- | --- | --- |
| `core/.../OrchestratorLive.scala`, `AgentRunner.scala`, `storage/*`, provider/tool implementations | **Keep in core** | Reusable runtime/domain mechanisms must not depend on the server or terminal |
| `core/.../orchestration/config/*` | Keep initially; consider moving only application-specific config loading/composition later | Config definitions and runtime construction are already coupled; do not relocate the whole package speculatively |
| `repl/.../ReplStartup.scala` | Extract runtime assembly into `server/.../application/` | Mixes provider registry, storage, configured agent binding, tool context, login and voice setup; terminal options stay client-side |
| `repl/.../ReplTools.scala` | Propose extracting the startup tool catalog/composition into server | Tools execute in the server's workspace; implementations stay in core, not server |
| `repl/.../ReplSession.scala`, `ReplOrchestrator.scala` | Extract application submission/session operations into server | Do not move `ReplState` wholesale: it contains terminal log level, a local `Path`, and continuation policy |
| `repl/.../ReplApplication.scala`, `ReplEvent.scala` | Split client commands/render events from server application operations | Existing events contain presentation choices and filesystem paths; they are not a ready-made wire protocol |
| `repl/.../grammar/ReplCommand.scala` | Review the semantic cases individually for a server API package | Session commands are good candidates; `/exit`, `/log`, and export destinations are client concerns |
| `repl/.../grammar/ReplGrammar.scala` | Prefer keeping slash parsing/completion/help in REPL initially | It is editor-independent but not entirely server-domain-independent; sharing the pure grammar is a separate choice |
| `repl/.../HumanAgent.scala` | Review extraction of a server-owned pending-human-interaction service | A terminal-owned queue cannot simply cross a process boundary; retain a client adapter for responses |
| `repl/.../ReplProviderCatalog.scala`, `ProviderLoginService.scala` | Propose moving credential mutation/login orchestration to server | Client renders prompts/URLs; it must not receive reusable provider secrets |
| `repl/.../voice/*`, `browser/*`, terminal classes and `ReplLoggers` | **No blanket move** | Split capture/playback, provider work and logging separately; existing browser-audio service is not automatically merged |

Before each batch, list affected files/tests, visibility changes such as `private[repl]`, package imports, compatibility
adapters and build edges. Obtain approval, then make one small independently tested extraction. Never introduce
`server.dependsOn(repl)` as a temporary shortcut: it prevents the requested dependency direction.

### Grammar decisions

Recommended wire operations are typed application requests, not terminal command strings:

| REPL input | Client behavior / server operation |
| --- | --- |
| Prompt / `//literal-leading-slash` | Parse locally, send validated prompt text and explicit session/agent IDs; server revalidates and authorizes |
| `/new`, `/session list`, `/session resume` | Create/list/load a session; selecting/resuming it is client state |
| `/history`, `/tools` | Query the server, render locally |
| `/help`, `/log`, `/exit` | Client presentation, filtering or disconnect; never daemon shutdown |
| `/debug print`, `/debug export`, `/debug request` | Request authorized diagnostic data where necessary; render/export locally, not to a client-selected server path |

Moving `UserPrompt`, `SessionCommand` or other pure cases may make sense **after agreement on this split**. Do not
serialize the entire `ReplCommand` enum unchanged. For example, `LogLevel` currently controls terminal output, and
`DebugTarget.Export` describes client behavior.

An optional server-side `submitLine` compatibility adapter could parse the shared grammar and delegate to the same
application operations. That requires a grammar-sharing decision first. Typed JSON-RPC operations do not need slash
syntax to be secure: server validation and capability checks remain authoritative regardless of client parsing.

## 4. Application API, not a remote object proxy

The implemented read-only `OrchestratorRpc` handler is independent of transport IO, and `OrchestratorClient` exposes
the supported read operations. Future application/execution services and the deferred Unix adapter must reuse this
boundary, not create separate orchestrators, storage roots, tool policies or run queues.

Only `initialize`, session listing/loading, messages, agent IDs and blob listing/chunk reads are enabled today.
The planned execution-capable surface below is not all implemented:

- Existing read operations: sessions, messages, agent metadata/IDs and blob IDs/downloads.
- Session creation/loading and explicit fork of the **current head**, matching current core behavior.
- Start a turn with `commandId`, `sessionId`, `agentId`, prompt text and an optional expected-head precondition.
- Query run status/result and request cancellation by `runId`.
- Query authorized tool availability and selected-message diagnostics.
- Subscribe to typed events and respond to explicitly identified human/approval interactions when those features
  are introduced; use the same RPC protocol, not an HTTP polling layer.

Do **not** mirror every method of `Orchestrator` onto JSON. `getAgent` returns an executable object;
`AgentExecution[R]`, ZIO environments and blob-writer callbacks are not wire values. The server creates configured
execution from trusted services. The deprecated `turn` method bypasses session persistence and is not the remote
submission API.

### Planned names and shared layers

- **`OrchestratorServer`**: future execution composition/lifecycle boundary; the current read-only host is
  `OrchestratorWSServer`, started by `OrchestratorServerMain`.
- **`OrchestratorClient`**: implemented transport-neutral read operations; connected REPL use remains pending.
- **`OrchestratorWSServer` / `OrchestratorWSClient`**: implemented JVM adapters; the browser has its native WS client
  using the same application/model types and wire contract.
- **`OrchestratorSocketServer` / `OrchestratorSocketClient`**: deferred Unix-domain socket adapters.
- **Shared JSON-RPC layer**: envelopes, JSON codecs, method schemas, dispatch, response/error handling and notification
  conventions. Platform-specific connection management may differ between JVM and Scala.js clients.

The previous `OrchestratorHttpServer` / `OrchestratorHttpClient` proposal is superseded. The separately approved website
migration removed `OrchestratorViewServer`, `ViewServerMain`, `ViewProtocol` and the Fetch `ApiClient`, replacing them
with the WebSocket/SPA host, new entry point and browser WS client. This does not approve unrelated REPL/core moves.
Serving assets over HTTP does not make HTTP the application API.

### Run and message semantics

- Keep **request ID**, **command ID**, **run ID** and **subscription ID** distinct. A JSON-RPC ID correlates one
  request/reply; it is not a durable execution identity, retry key or replay cursor.
- For a long-running `turn.submit`, the planned sbt-style flow is intermediate notifications followed by **one final
  JSON-RPC response** for that request. Include a `runId` in progress and the terminal outcome. Rejection, acceptance
  and execution failure must remain distinguishable; finalize their schemas before implementation.
- If a separate immediately-returning `runs.start` operation becomes necessary, define it explicitly. Its response
  means registered/queued, not “prompt durably persisted”; later completion is a notification or a status query, not
  a second response with the same JSON-RPC ID.
- Current `OrchestratorLive.submit` owns persist–run–persist and has no public prompt-commit notification hook.
  Keep that lifecycle intact: do not save the prompt in the RPC adapter and then call `submit`, duplicating it.
  Preserve `SessionSubmitFailure.acceptedSession` when execution fails after acceptance. Do not announce a committed
  prompt until the application can actually observe that fact.
- Use a bounded run registry, explicit retention and process-lifetime command deduplication initially. Do not claim
  restart-safe deduplication, durable event replay or exactly-once tools without approved persistence changes.
- A disconnected connection or lost final reply creates an uncertain outcome. Reconcile using command/run identity;
  never automatically resubmit a turn just because its JSON-RPC response was not received.
- Run fibers belong to the server's lifetime scope, not the WebSocket/Unix connection. Disconnect is not cancel.
- `runs.cancel` is a separate RPC request through a control path **not queued behind the turn it cancels**. It cannot
  undo an already accepted prompt or an external side effect.
- Distinguish protocol errors, unauthorized/rejected commands and accepted-run failures. Domain failures are not all
  malformed JSON-RPC requests; use an agreed terminal outcome/application error contract.

### Concurrency and branching prerequisites

The [filesystem contract](../../../../../../../../docs/orchestration/ATTACHMENTS_AND_BLOB_STORAGE_PLAN.md#filesystem-concurrency-contract)
requires one storage owner and serialized message/session mutations across a root. Per-session locks alone do not
make concurrent sessions safe.

The smallest MVP can admit one execution at a time globally and coordinate **all** mutating API paths, including
session creation/forking, with that policy. Non-run mutations must queue or reject while a run owns write
admission; status queries and cancellation stay responsive. Later parallel sessions need an approved storage-boundary
writer coordinator. Do not hold a storage mutation lock across provider/tool execution; wrapping the entire `submit`
effect in such a lock would serialize long runs and can block control operations.

The previous plan implied that an arbitrary `parentMessageId` already supported historical branching. It does not:
current `submit` reads a session's stored linear history, and `fork` copies its current head. Initially reject a stale
expected head or require an explicit fork. Forking from an older message requires an approved core/storage change,
ancestry reconstruction and continuation tests; it is not a transport feature to invent in a handler.

### Events and reconnect

Keep snapshot queries as JSON-RPC methods; do not replace snapshots with an event log before an event log exists.
The protocol supports typed notifications from the beginning, even if initial read-only methods need only responses.
Distinguish transient progress from replayable persisted facts. A `Hub` alone is neither a durable journal nor a safe replay/live handoff.

Before promising resumable events, define event offsets and retention, an atomic snapshot/cursor boundary, replay/live
handoff, gap/expired-cursor recovery, bounded subscriber buffers and a slow-consumer disconnect policy. Reconnect can
fall back to a fresh snapshot. Do not block execution indefinitely behind an inactive viewer.

Normal events contain sanitized message data and attachment references, not provider credentials, opaque provider
continuations, filesystem paths or raw provider payloads. Diagnostics require an explicit authorized query.

## 5. Shared JSON-RPC protocol and transport adapters

WebSocket is implemented; Unix-domain sockets are deferred. This section describes the shared contract and the
requirements for a future Unix adapter, not a requirement to implement both before shipping WebSocket.
Neither JSON-RPC nor either socket type automatically provides run ownership, idempotency, replay or backpressure.

### Same application messages on both connections

An illustrative query has identical JSON on WebSocket and Unix-domain sockets:

```json
{"jsonrpc":"2.0","id":"request-42","method":"sessions.list","params":{}}
```

```json
{"jsonrpc":"2.0","id":"request-42","result":[]}
```

Read-only names/schemas are implemented and specified in `server/PROTOCOL.md`, including `initialize`, `sessions.list`,
`sessions.get`, `messages.list`, `agents.list`, `blobs.list` and `blobs.read`. Execution/run/notification DTOs are defined
but not enabled; runtime semantics still need their relevant approvals. No endpoint may deserialize arbitrary
executable agents, ZIO effects, provider clients or server-side callbacks.

The shared layer must define and test:

- Standard `jsonrpc: "2.0"`, IDs, `method`, named `params`, and exactly one of `result` or `error` in each response.
- Standard errors for parse failure, invalid request, missing method, invalid params and internal failure; agreed
  application errors in addition. Never return raw stack traces or credentials.
- The distinction between an absent request ID (notification) and an explicit `id: null`. Clients should generate
  non-null string IDs. Notifications receive no response, including on failure.
- Bounded batch behavior, invalid/empty batches and malformed batch members. Do not accidentally claim full JSON-RPC
  conformance while silently treating a batch as several responses to one request.
- An `initialize` handshake for the **Yallmo protocol version**, capabilities, limits and authorized identity.
  This application version is distinct from JSON-RPC's fixed `"2.0"`; initialization is not authentication by itself.
- A bounded pending-request table, independent request deadlines, cleanup on close and handling of late responses.
  Both adapters must allow control requests to progress while a run is active; a read loop must not wait for an entire
  turn to finish before it can read `runs.cancel`.

### Streaming is notifications, not repeated responses

For execution, use the sbt-style pattern over the already-open connection:

```text
client -> server: turn.submit request (id=request-42, commandId=command-7)
server -> client: turn.started notification (requestId=request-42, runId=run-9)
server -> client: message.delta / tool.progress notifications (runId=run-9)
server -> client: one final response for request-42 (terminal outcome, runId=run-9)
```

These event names are illustrative and depend on runner instrumentation that does not exist yet. “Started” must not
be mislabeled as “prompt committed”. Do not invent token/tool progress solely in a transport handler.

For an ongoing feed, use a different explicit convention: `sessions.subscribe` returns a `subscriptionId` once;
subsequent `subscription.event`, `subscription.end` or `subscription.error` notifications identify that subscription.
Unsubscribe is an RPC method. Completion/error rules, event ordering, buffer limits and any replay cursor are our
application protocol, **not** a built-in JSON-RPC stream feature.

Socket flow control does not provide application demand/credits or durable replay. Initially use bounded buffers
and a documented slow-consumer policy, with snapshot recovery where necessary. Progress notifications are transient
unless deliberately persisted. Exactly one final response is owed while its connection remains usable; after a
connection loss, reconcile the run instead of trying to reuse an old connection's request ID.

### Framing belongs only in the adapters

The Unix column below is a deferred design, not an implemented listener. Current WebSocket bounds and the pinned
library's initial-fragment UTF-8 limitation are documented in `server/README.md`.

| Concern | WebSocket adapter | Unix-domain socket adapter (deferred) |
| --- | --- | --- |
| Message boundary | One JSON-RPC document per complete WebSocket text message | A byte stream; assemble an explicitly length-framed JSON document |
| Framing | WebSocket handles frame/message boundaries; handle fragmentation and enforce total message limits | Plan LSP/sbt-style `Content-Length: <UTF-8 byte count>\r\n\r\n` followed by that many bytes |
| Security | Upgrade/authentication, browser Origin checks and connection limits | Private socket directory, authentication and optional peer credentials |
| Lifecycle | Upgrade, close/error/ping handling | Connect/accept, partial reads/writes, EOF and socket-file cleanup |
| Shared layer receives | A complete decoded JSON-RPC document | The same complete decoded JSON-RPC document |

Do not put a `Content-Length` header inside every WebSocket message. Do not assume one Unix-socket read contains one
complete request. Bounds apply to the assembled message, not only individual frames. Decode UTF-8 correctly across
read/frame boundaries, and keep protocol traffic separate from logs or terminal escape sequences.

Unix-domain sockets are not Linux-only: macOS supports them too. [JEP 380](https://openjdk.org/jeps/380) added JDK
Unix-domain socket channels in Java 16, including supported Windows AF_UNIX platforms. Windows named pipes are a
different transport. Validate the agreed platform matrix, path limits, interruption and resource cleanup before
choosing the implementation library; do not assume ZIO HTTP 3.0.1's WebSocket API also provides raw Unix sockets.
No HTTP-over-Unix-socket adapter is needed for this design.

### Website migration and blob transfer — complete for read-only use

- The Fetch client is replaced by the browser `OrchestratorWSClient` using the same protocol/model sources as the JVM.
- One application WebSocket is shared by all tabs. Replies are correlated, pending requests fail on close, and Refresh
  deliberately reconnects without replaying abandoned requests. The application root owns connection cleanup.
- Session/message/agent/blob queries, graph expansion, tool-result formatting and attachment downloads are migrated.
- `blobs.read` returns bounded base64 chunks with offsets, total bytes and EOF. The browser validates partial transfers,
  caps downloads at 64 MiB and cleans up temporary object URLs. There is no HTTP blob fallback. Uploads remain deferred.
- The WebSocket host serves HTML/JS/CSS and the upgrade. Vite proxies `/rpc` with `ws: true` and preserves browser Origin.
  Its `host: true` remains intentional for Docker; the development server/proxy is network-reachable.
- REST viewer routes, the old viewer DTOs/server/client and old entry point are removed. Production and Vite browser
  smoke checks verified all five tabs and a byte-exact attachment download without legacy API requests.

When Unix support resumes, its adapter must reuse the same handler/application services and storage owner. The small
remaining transport plan is Content-Length framing, private endpoint permissions/scoped cleanup, and shared operation
fixtures covering partial/coalesced reads. A stdio bridge remains a separate later tooling proposal.

## 6. What sbt, Bloop, LSP/BSP and Metals actually do

The cited documentation describes particular/current versions. Recheck version-specific defaults before copying
implementation details; this repository pins sbt **2.0.9** and ZIO HTTP **3.0.1**.

| System | Documented arrangement | Relevant lesson |
| --- | --- | --- |
| sbt / `sbtn` | sbt 2.x runner normally uses the native thin client; the server owns the build. The sbt server reference documents local Unix sockets / Windows named pipes, optional TCP, endpoint discovery and JSON-RPC/LSP-style framing/custom methods | Separate launcher/client/server; discover and reuse an owned runtime. Do not infer that every sbt internal API is a portable LSP service |
| Bloop | Current server docs describe a shared per-user daemon with a Unix-domain socket and lock/PID/log directory. Bloop uses Nailgun internally; CLI/Rifle can start and connect to it | Runtime reuse, private daemon directories and startup coordination matter more than copying Nailgun |
| Bloop Rifle / BSP discovery | A BSP connection file supplies an `argv` launcher. The client-facing contract is stdin/stdout; the launcher may connect to a daemon through a different underlying transport. Bloop's BSP CLI also exposes local-socket/TCP options | The visible client connection can be a bridge, not the daemon's own listening socket |
| LSP | JSON-RPC requests/responses/notifications with `Content-Length` framing in its base protocol | A message/framing contract, not a mandate to use HTTP, WebSocket or Unix sockets |
| Metals | Editor-facing JSON-RPC normally runs over stdio. Metals separately acts as a BSP client of Bloop/sbt/another build server | Two links with different purposes: editor ↔ language server, language server ↔ build server |

```text
Editor -- LSP / usually stdio --> Metals -- BSP / selected launcher --> build server
sbtn   -- local daemon connection --------------------------------> sbt
Bloop CLI / Rifle -- daemon and BSP connection setup -------------> Bloop

REPL   -- chosen application client transport --------------------> Yallmo server --> core
Browser -- JSON-RPC / WebSocket ---------------------------------> same Yallmo server
SPA assets -- HTTP only -----------------------------------------> same host
```

We should borrow the daemon boundary, private discovery, request correlation, notification-based output and
lifecycle discipline. We should
**not** implement LSP, BSP or Nailgun merely because they solve similar process-boundary problems. Yallmo operations
are conversation/run operations, not text-document synchronization or build targets.

### sbt 2.0.9 source check: how output streams

The implementation confirms that `sbt/exec` is a JSON-RPC request, while incremental output uses notifications such
as `build/logMessage`, `sbt/systemOut` and `sbt/systemErr`. Stdout/stderr notifications carry byte chunks; stdout is
batched. A non-interactive command's final status is returned against its original request ID, after output is
flushed. Cancellation is handled by a separate `sbt/cancelRequest` request. Interactive attach/terminal control has
additional messages and is not the Yallmo application protocol to copy verbatim.

This is evidence for the chosen request + notifications + final response pattern, not evidence that JSON-RPC itself
provides streams, persistence, reconnect or flow-control semantics.

### Primary sources consulted

- [sbt 2.x runner and server](https://www.scala-sbt.org/2.x/docs/en/reference/sbt.html).
- [sbt server reference: modes, discovery, authentication and framing](https://www.scala-sbt.org/1.x/docs/sbt-server.html)
  — this page documents the 1.x server lineage; do not assume every detail is unchanged in 2.x.
- [Bloop server reference](https://scalacenter.github.io/bloop/docs/server-reference).
- [Bloop Rifle: launcher and BSP connections](https://scalacenter.github.io/bloop/docs/bloop-rifle).
- [Bloop BSP CLI options](https://scalacenter.github.io/bloop/docs/cli/reference#bloop-bsp).
- [BSP server discovery specification](https://github.com/build-server-protocol/build-server-protocol/blob/master/docs/overview/server-discovery.md).
- [BSP base protocol and relationship to LSP](https://build-server-protocol.github.io/docs/specification).
- [Metals editor integration: stdio transport](https://scalameta.org/metals/docs/integrations/new-editor/).
- [LSP 3.17 base protocol](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/).
- [JSON-RPC 2.0 specification](https://www.jsonrpc.org/specification).
- [sbt v2.0.9 request handling](https://github.com/sbt/sbt/blob/v2.0.9/main/src/main/scala/sbt/internal/server/LanguageServerProtocol.scala).
- [sbt v2.0.9 output notifications](https://github.com/sbt/sbt/blob/v2.0.9/main/src/main/scala/sbt/internal/server/NetworkChannel.scala).
- [sbt v2.0.9 terminal command completion](https://github.com/sbt/sbt/blob/v2.0.9/main/src/main/scala/sbt/MainLoop.scala).
- [JEP 380: Unix-domain socket channels](https://openjdk.org/jeps/380).

## 7. Process lifecycle, security and existing REPL features

### Daemon identity and startup

Prefer one runtime per **user + canonical workspace/storage/config identity**, rather than copying Bloop's per-user
singleton blindly. Workspaces can have different credentials and tool roots. Do not run a second REPL-owned storage
service beside the server for the same root. Legacy/local mode must use a different root or refuse to start
against a daemon-owned root.

Start with explicit `server` and `connect` modes. Keep existing local/in-process REPL mode during migration until
connected-mode parity is approved. Automatic discovery/start is a later milestone with tests for concurrent starts,
PID reuse, stale files, incompatible versions and graceful/forced shutdown.

Endpoint metadata belongs in a private user runtime directory, not a public workspace file. Include protocol/build
versions, workspace identity, endpoint and process-instance identity. Use atomic publication and a startup lock;
PID presence alone is not proof of ownership. Never delete an arbitrary/still-live socket or log endpoint secrets.
Use short socket filenames to respect platform path limits. Shutdown of a client is not shutdown of the daemon.

### Authorization before write API exposure

The present loopback Host/Origin restrictions are not an execution authorization design. Before enabling submission,
cancellation, uploads, diagnostics or credential mutation:

- Define operator versus viewer permissions and authentication for every enabled transport.
- Use private local endpoint permissions plus an authenticated handshake; optional peer-credential checks are extra
  defense where supported. A loopback fallback must not silently disable authentication.
- Choose browser WS credential/bootstrap handling and Unix-socket authentication. If cookies/bootstrap HTTP
  are used, review their CSRF/session policy as well. Never put long-lived tokens in query strings or logs.
- Validate WS Origin and authenticate before privileged messages. Browser WS is not protected by normal CORS checks.
- Review the Vite proxy: its current `host: true` exposes development access on the network; it is not a security
  boundary merely because the backend binds loopback.
- Bound message/frame/body sizes, output buffers, pending interactions, run queues and subscriber queues.
- Keep provider secrets server-side; raw debug captures need separate permissions/redaction and no server-path RPC.

Remote hosting, multi-user tenancy and TLS deployment remain a separate design. Local sockets do not grant tools
or let a client choose arbitrary server commands, workspaces, environments or filesystem paths.

### Voice, human input and PTY are distinct concerns

- Client owns microphone capture, playback and TTY mode. Provider-backed transcription/TTS belongs server-side if
  credentials are server-only. The old “transcribe locally and send text” suggestion is valid only for an actually
  local transcription implementation; preserve existing voice behavior until its split/upload policy is approved.
- `HumanAgent`/approval requests need server-generated interaction IDs, an authorized responder/lease, bounded
  pending state, timeout and disconnect policy. Requests and responses use the same JSON-RPC protocol over the
  selected connection. Do not route them through an arbitrary terminal callback or let a second viewer silently
  approve a tool.
- Preserve current continuation reset behavior on agent changes/session resume. `continuationStart` and opaque
  provider binding rules need explicit server-side ownership/tests; do not trust a client-supplied offset or simply
  serialize `ReplState` to solve this.
- A TTY is the client's terminal. A PTY is a separately authorized server-side child process. Keep the existing
  [TUI/PTY plan](../terminal/TUI.md) separate: no PTY multiplexer, agent keystroke control or remote shell is implied
  by adding a WebSocket. ProcessBuilder pipes are not a PTY implementation.

## 8. Approval gates and delivery milestones

### Resolved architecture/protocol decisions

- **G1 resolved:** JSON-RPC 2.0 with WebSocket implemented and Unix-domain sockets explicitly deferred. Any future
  adapter shares the contract/application runtime; Unix parity does not gate WebSocket delivery. No REST execution API.
  Notifications are the future incremental-output convention, not multiple responses to a request. HTTP serves only
  browser assets/bootstrap/upgrade infrastructure.
- **G4 resolved for this slice:** protocol/JVM client packages remain inside `server`; Scala.js compiles the same
  protocol/model sources. No new module was introduced. Any later extraction still requires separate approval.

### Decisions still requiring confirmation

- **G2 — grammar:** keep slash grammar client-side with typed RPC operations (recommended), or share/move approved
  grammar cases and add a server-side line adapter?
- **G3 — extraction batch:** which exact runtime/application files may move from REPL/core to server? Review section 3
  and confirm paths/tests/imports before each batch. No such move is approved merely by selecting JSON-RPC.
- **G5 — remaining lifecycle/security:** read-only explicit start/connect, version negotiation and scoped client cleanup
  exist; execution authentication/roles, daemon endpoint ownership, run retention and shutdown policy remain pending.
- **G6 — remaining operation semantics:** read-only methods/errors and message/batch/download bounds are implemented;
  execution outcomes/admission/cancellation, command reconciliation and subscription backpressure still need agreement.
  Durable runs, replay, historical branching, voice/human interactions and PTYs remain separate requirements.

### Delivery milestones and remaining sequence

1. **Complete — read-only protocol foundation:** derived envelopes/application codecs, initialization, method dispatch,
   typed errors and transport-neutral read client. Execution/run/notification DTOs are definitions only, not live features.
2. **Complete — WebSocket website conversion and cleanup:** JVM/browser clients, shared-master WS host, static assets,
   Vite proxy, query/blob migration, new entry point and tests. Old REST routes/server/DTOs/Fetch client are removed.
3. **Deferred — Unix adapter and parity:** when requested, add framing, private endpoint/scoped cleanup and shared
   operation fixtures over the same handler. This does not block milestones 1–2 or initial connected REPL work.
4. **Pending approval — application seam:** perform only separately approved runtime extraction batches, retaining
   `server → core` with no dependency on REPL. Test application operations in-process and retain legacy local REPL mode
   under the storage ownership rules while connected mode is developed.
5. **Pending — connected REPL/execution:** after the seam and authorization exist, extend `OrchestratorClient` and map
   terminal commands onto the WebSocket client initially. Add server-owned runs, incremental notifications, one terminal
   response and responsive cancellation under the agreed reconciliation contract. Verify agent binding, history, tools,
   diagnostics and accepted-prompt failures. Do not silently drop existing voice/human behavior to claim completion.
6. **Later — subscriptions/durability:** extend the same protocol only with explicit ordering/retention/reconnect
   guarantees. Approve storage changes separately before claiming restart-safe deduplication or replay.
7. **Later — feature parity/optional PTY:** agree deferred voice, human-input, login and PTY contracts. Remove compatibility
   paths only with explicit confirmation. Automatic daemon launch and a stdio bridge remain separate later increments.

## 9. Acceptance tests and definition of done

The read-only WebSocket/website milestone is complete with the validation recorded above. The execution/REPL items
below remain future acceptance criteria; Unix-only checks are deferred with that transport.

- The server owns the single execution/storage runtime; connected REPL mode does not instantiate another one.
- `core` imports neither server nor REPL. `server` imports no REPL classes after each approved extraction is complete.
- Existing viewer tabs, tool-result rendering, graph expansion, attachments and the production bundle keep working.
- When Unix support resumes, run the same JSON-RPC conformance/operation fixtures against both adapters:
  parse/validation errors, IDs and null/absent IDs, exactly one result/error response, notifications without responses,
  bounded batches and consistent error mappings. Verify there is one dispatcher, not two application implementations.
- Transport tests cover WebSocket message fragmentation and, when resumed, Unix partial/coalesced reads, UTF-8 byte
  lengths, message limits, malformed frames, EOF/close, correlation of concurrent requests and pending-request cleanup.
- Execution tests cover notifications interleaved with a long-running request, exactly one final response, no output
  silently lost at completion, and cancellation/control requests processed while execution is still active.
- Contract tests cover version/auth failures, command ID conflicts, uncertain responses, status reconciliation,
  bounded blob chunks and partial-transfer cleanup. Socket support does not imply any durable-stream guarantee.
- Fake agents/tools verify accepted-prompt persistence on failure/cancellation, one prompt per accepted submission,
  correct agent binding, current-head conflict behavior and the actual storage serialization contract.
- Disconnect/reconnect does not duplicate a turn or implicitly cancel it; a slow viewer cannot stall execution.
- If replay is advertised, test the snapshot/event handoff, missed/expired cursors, retained gaps and server restart.
- Terminal tests verify local `/exit` and `/log`, local export destinations, restoration of TTY mode, agent switching
  and session resume without leaking server paths/secrets. Deferred voice/human behavior is documented, not silently lost.
- When implemented, the Unix adapter gets Linux/macOS and agreed Windows tests for endpoint permissions,
  stale/contended startup, wrong-instance detection and shutdown cleanup. The WS/browser path gets Origin, auth, proxy and reconnect tests.

The completed first milestone is the shared JSON-RPC read layer exercised through WebSocket, with the website
converted and REST removed. Unix-domain sockets are deferred and do not gate this delivery. The subsequent
client/server milestone adds the approved execution boundary and connected REPL, initially over WebSocket.
Neither milestone implies durable event sourcing, historical branching, remote control or a PTY service.
Record actual validation results; unrelated existing failures such as the current deprecated
`OrchestratorLive.turn = ???` must not be concealed by claiming the whole suite passed.
