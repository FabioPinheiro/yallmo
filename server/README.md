# Orchestrator WebSocket server and website

`OrchestratorWSServer(master, configured = None)` hosts static assets and JSON-RPC `/rpc` on the same listener
(fixed to `0.0.0.0`) using one supplied runtime/storage owner. A supplied master alone is read-only. Standalone `--config`
provides the existing configured backend for server-owned prompt execution; no additional runner/service is created.
HTTP serves static assets and upgrade only, not conversation/blob REST endpoints.

**Trusted environment only; access is assumed already authenticated outside this server.** A configured backend and
initialized claimed human `userId` enable Operator with `sessions.create`, `turn.submit`, `agents.invoke`,
`blobs.upload` and `diagnostics.request`,
including browser and remote-machine clients. Anonymous connections remain read-only. Host, Origin, peer address and
`clientName` do not grant or restrict access. There is no server authentication or Origin allowlist. Anyone who can
reach the listener (including through a browser or proxy) can claim an author and execute configured agents/tools.
User names are impersonable metadata/authorship, **not authenticated identities**. Never expose it publicly.

## Connected terminal prompts or viewer

```sh
# Private config contains a configured agent bob and its provider. Use separate storage ownership.
mkdir -p ./server-storage
sbt 'server/run --storage ./server-storage --config ./private/server.json --workspace .'
# Another interactive terminal (no client-local config/provider/audio bootstrap):
sbt --server -no-server 'repl/run --user-name alice --server ws://127.0.0.1:8080/rpc --agent bob --log-level warning'
```

Type a prompt: the client creates once if necessary, retains that ID, then submits once. Later prompts reuse it.
`/new [name]` creates explicitly; `/session list`, `/session resume <id>` and `/history` read server storage freshly.
No selected configured agent means guidance with **zero prompt writes/session creation**; choose `--agent` or use the agent picker from the [client controls guide](../docs/CLIENT_CONTROLS.md#connected-repl-keys).
Initialize's optional `configuredAgentIds` supplies assistant choices, not the mixed human/presence `agents.list`.
Remote provider/model metadata remains unknown; selection is per-client, never a global execution preference.

Without server `--config`, the same client is a read-only viewer; prompts and `/new` are omitted from help/completions.
Without client `--server`, the REPL connects to `ws://127.0.0.1:8080/rpc`. It is client-only; connection failure
never starts a local application.
`--transcriber-agent` is rejected before networking, with or without an endpoint override. `/tools`, `/debug print|export`, provider catalog/login,
voice/TTS and attachment upload remain unavailable remotely, even for Operator. See [REPL](../docs/REPL.md).

`/debug request` is enabled only when `diagnostics.request` is negotiated for a trusted-environment Operator. It returns the
latest actual request capture for the selected session, including failed turns, without inference or client-local file
access. Captures retain metadata and existing continuation redaction; conversation/tool text remains sensitive.
The index retains 128 sessions in memory, survives client reconnect, and is lost on server restart. Reads reject captures
over 256 KiB, symlinks and corrupt data without exposing server paths. See [protocol](PROTOCOL.md#provider-request-diagnostics).

Initialized human presence is connection-owned and deduplicated. Dynamically registered submitted authors remain for
runtime/process lifetime after disconnect, independently of viewer cleanup. Configured/supplied ID collisions and
identity rebinding are rejected; known humans may reconnect. Human labels do not make executable assistants.

## Independent agent calls

The generic JSON-RPC `agents.invoke` method accepts `{agentId, input: Message}` and returns the recipient's response
`Message`, without imposing a payload role. It saves input and response independently of sessions, preserving the
input's content address and linking the response to it. The input author must match the initialized identity.
An existing parent is allowed without inheriting its history. An input can reference a blob still arriving through
`blobs.upload`: invocation waits without blocking subsequent upload commands on the socket.

Conversational agents retain their normal text-loop behavior. A direct `ToolCall` executes through the selected
agent's authorized runner and returns a correlated `ToolOutput` without inference. Registered approval gates,
argument validation and deadlines still apply. No session is required or created.
The browser's [Scalamode page](../web-harness/README.md#scalamode) uses this path with separate **Run scalamode** (restricted) and **Run scalaunsafe** (trusted dynamic) buttons. `ApplicationTools` currently registers only `scalamode`; dynamic registration is disabled. Custom trusted host wiring can explicitly supply the `filesystem` module's read-only `fmgp.yallmo.fs.FsRead` capability and matching compiler classpath; no script-specific filesystem API or adapter remains. Neither button bypasses server permissions. Agents without a tool allowlist can use registered `scalaunsafe`; exclude it from an explicit `capabilities.tools` allowlist to deny trusted in-process execution.

Failures can leave accepted input; disconnect abandons the local wait, not admitted execution. Repeating an invocation
can repeat provider/tool side effects. Inspect message history before manually repeating uncertain requests.
See [wire contract](PROTOCOL.md#independent-agent-invocation) for authorization, limits and persistence semantics.
The [browser dictation UI](../web-harness/README.md#dictation) uses this path and generic uploads; no voice-specific
endpoint or session is introduced. General execution cancellation remains separate pending work.

## Submission lifetime and uncertainty

Core persist–run–persist alone saves the prompt, executes and saves generated output; RPC never preappends it.
The configured application owns work beyond disconnect. Closing/interruption abandons the local wait, **not remote
execution**. Reconnect, resume the retained session and read `/history` to observe completion. Never automatically
resubmit/replay after a lost reply. A lost create reply may leave an unknown empty session; inspect `/session list`.

Success returns a sanitized snapshot/result and the actual durable message suffix. Accepted failures retain the prompt
and snapshot. Storage/defect/shutdown uncertainty is Unknown, not an assertion of nonacceptance or rollback. A known
head is sent as `expectedHead`; mismatch/Busy retains client session state and requests refresh before manual retry.
Resume resets old opaque continuation on the next turn; binding changes and restart also derive server boundaries.
Visible history is preserved; the client never sends continuation offsets or stores provider state.

A short root-local filesystem mutation mutex coordinates publication/snapshot capture, **not provider/tool execution**.
Nonwaiting same-session turn exclusion rejects Busy; independent sessions execute concurrently. There is no global
agent/run gate. Work/finalizers stop before root lease/tools release on shutdown; already-closed scope cannot hang waits.
There is no status/cancel API, run registry, command ledger, deduplication, replay or exactly-once/crash transaction claim.
Legacy/uncooperative separate storage owners still must not write the same root concurrently.

## Development

Prerequisites: the repository's JDK/sbt, Node 22.12+ (or 20.19+) and npm.

```sh
# Repository root:
npm --prefix web-harness/vite ci
sbt 'webHarness/fastLinkJS'
sbt 'server/run --storage ./zzz'
```

In another terminal:

```sh
npm --prefix web-harness/vite run dev
```

Open **http://127.0.0.1:5173**. Vite uses `host: true`, listening on all interfaces, including the Docker network.
It proxies WebSocket `/rpc` to `127.0.0.1:8080` with `ws: true`, preserving the real browser Origin.
If the backend uses another port, update `web-harness/vite/vite.config.js`. Vite's development/HMR socket is separate from the
website's single application socket.

Both the development server/proxy and backend listen on all interfaces.
For Docker, publish the Vite port on host loopback only (for example `127.0.0.1:5173:5173`), or use a trusted network.
The backend accepts arbitrary browser Origins; network access must be restricted to trusted users.

For continuous Scala.js linking, run `sbt '~webHarness/fastLinkJS'`. Vite bundles already-linked JavaScript; it does not
launch sbt or compile Scala.

## Production

```sh
sbt 'server/bundle'
sbt 'server/run --storage ./zzz'
```

Open **http://127.0.0.1:8080**. The bundle task full-links Scala.js, runs Vite from `web-harness/vite/`,
and packages `web-harness/vite/dist/` in the server JAR.
Ordinary JVM compilation/test/run does not invoke npm or build the frontend. Without packaged assets, `/` returns 503;
`/rpc` still works. Asset/API misses return 404, never fallback HTML. The module JAR includes assets, not JVM dependencies;
use sbt's runtime classpath or a normal JVM distribution.

The entry point is `fmgp.yallmo.server.OrchestratorServerMain`:

```text
--storage <existing-directory> [--config <path> [--workspace <existing-directory>]]
  [--port <1-65535>] [--log-level <trace|debug|info|warning|error|fatal|off>]
```

The listener always binds to `0.0.0.0` for trusted Docker/multi-machine access; the bind address is not configurable:

```sh
sbt 'server/run --storage ./server-storage --config ./private/server.json --workspace . --port 8080'
```

Publish/map port 8080 only onto the trusted network and connect to `ws://<docker-host>:8080/rpc` (or open
`http://<docker-host>:8080` for packaged assets). Do not publish it to the public internet. Embedded callers can use
`start(port = 8080)`; it also binds to `0.0.0.0`. No Origin rewriting or stripping is needed. FIXME

### Viewer and configured startup

Without `--config`, the existing viewer mode opens storage with an empty agent registry. It does not load a config or
probe tools. Historical authors remain visible in transcripts; the Agents tab is empty. In either mode, the storage
root must already exist; opening storage can initialize missing storage files/subdirectories.

Configured startup is optional:

```sh
mkdir -p ./server-storage
sbt 'server/run --storage ./server-storage --config ./private/server.json --workspace ./my-project'
```

Server-owned `ConfigFile` loading reads the private configuration or initializes an empty catalog (private POSIX
permissions). Keep it private: it can contain provider credentials. Standalone startup registers configured agents
only, without a fake local human, OS-name lookup, selection, login or inference. `userName`, `defaultAgentId` and
`transcriberAgent` were removed from Config; old terminal preferences no longer apply. Unknown raw fields survive
catalog edits but do not become active server preferences.

The client-only REPL requires `--user-name <id>` and accepts optional `--agent <id>`, `--log-level` and `--server`.
The default endpoint is `ws://127.0.0.1:8080/rpc`; there is no embedded application or local execution fallback.
It never loads `yallmo.json`, probes tools, constructs providers or acquires local audio/login services.
`--config`/`--storage` belong to server startup; the REPL rejects them and `--transcriber-agent`.
Claimed human IDs use `user-<user-name>` and are sufficient authorship for this trusted-environment deployment, not authentication.
Provider menus/login, voice and interactive human transport remain pending; configured prompts and session/history
commands use standalone server storage. Existing embedded adapters, audio/voice and debug implementations and their tests are retained for follow-up
integration; none is entered by the client-only startup path.

`--workspace` requires `--config` and defaults to the server process working directory. It must be an existing directory
and is canonicalized once. It roots the core readFile/listFiles/bash/repositoryChanges tools; the Scala tool reuses
the existing core implementation. Startup only probes directory access, Bash/Scala CLI availability and Git worktree
membership, then validates configured capabilities against the available catalog. It does not execute tools or agents.
Tool composition, availability models/probes and service layers live in server-module `ApplicationTools`.
`ApplicationRuntime.openWorkspace` owns scoped tool services and execution assembly on the server. The REPL
connects over WebSocket and has no local tool probing or execution path. Tool implementations remain in core;
read-only file tools now use the shared `filesystem` capabilities.

File tools use one rooted `filesystem` instance opened at startup and receive only its `FsRead` capability.
Paths must be workspace-relative (`.` lists the root); empty/absolute paths, `..` and symlinks are rejected.
A listing fails if any direct entry is a symlink. Direct module callers must also supply a root with no symlink
ancestors; server workspace configuration is canonicalized before opening. Missing or invalid
roots omit the file tools from availability; filesystem failures become tool execution errors. Bash/Git behavior is unchanged.

These workspace roots are **not a sandbox**. Configured agent filesystem roots retain core's existing cwd-relative
semantics; `--workspace` does not silently rewrite them.

Configured mode retains one `ApplicationRuntime` and its opened assembly, with exactly one filesystem master supplied
to the same WebSocket host. Client and core tool-service layers surround the entire host scope, so captured HTTP
services outlive application work. Configured connections with a claimed human ID can execute; anonymous connections remain viewers.
Authentication is explicitly deferred; a claimed human ID is author metadata, not an authenticated principal.

### Startup validation failures

Startup exceptions retain their underlying storage or capability-validation cause. For `objects.jsonl`, diagnostics
identify the physical 1-based line (including blank lines), the message ID when decodable, and the failed check.
Hash mismatches show the stored ID and the ID computed from the current canonical message encoding; this can indicate
changed data or incompatible serialization, not necessarily intentional tampering. Missing-parent errors identify both
the child and the missing parent. Invalid JSON/schema errors omit stored content and decoder input.

Loading remains fail-fast: the reported record is the **first** invalid record, not an exhaustive corruption report.
Startup does not repair records or rewrite their IDs. Stop writers and back up the store before attempting recovery;
removing/replacing a message can break later parent references and session heads. These are local startup diagnostics,
not additional details exposed through RPC errors.

### Standalone storage ownership lease

Both standalone modes canonicalize the existing storage root and hold a nonblocking OS `FileChannel.tryLock` on
`.yallmo-server.lock` for the host scope. Concurrent standalone owners (including same-JVM owners and symlink root
aliases) are rejected. Symlink lock files are rejected. Scope exit or failed startup releases the lock/channel, but
**never deletes the lock file**, avoiding replacement-inode races.

This is a cooperative standalone ownership lease, **not** a storage mutation mutex, authentication boundary or full
external-writer exclusion. Legacy local and embedded users do not yet participate in the lease: they still **must not
write this root concurrently**. Admission/cutover for those users remains future work; do not remove/replace the lock
file while an owner is running.

## Logging

The standalone server defaults to **INFO**. Choose a minimum level at startup:

```sh
sbt 'server/run --storage ./zzz --log-level debug'
sbt 'server/run --storage ./zzz --port 8080 --log-level=trace'
```

Levels are case-insensitive; both `--log-level debug` and `--log-level=debug` work, in any option order.
`warning`, `error` and `fatal` select higher thresholds; `off` suppresses application ZIO logs (not sbt/SLF4J output).
The configured console logger is installed before HTTP startup so connection fibers inherit it.

| Level | Server output |
| --- | --- |
| INFO (default) | Startup/listening/shutdown and each established WebSocket connection/disconnection. Connection events include a shared `connectionId`; the connected event includes peer IP when available. |
| DEBUG | INFO plus incoming/outgoing JSON-RPC message directions and byte counts, close status, and startup storage path. No full RPC payloads. |
| TRACE | DEBUG plus full incoming/outgoing JSON-RPC text payloads, including malformed incoming JSON. Payloads are JSON-string escaped so control characters/newlines do not create extra log lines. |

**TRACE is sensitive:** transcript/tool text can contain secrets and outgoing blob chunks contain base64 attachment
bytes. Enable it only for private local debugging; do not publish/share raw logs. Startup emits a warning when TRACE
is selected. Upgrade headers, cookies and query strings are not included in the new connection/payload logs. These
are application-message logs, not HTTP asset bodies or WebSocket ping/pong dumps.

Embedded servers use their caller's ZIO logging configuration; configure the logger before calling `start`.

## Embed and connect

With an already-created master:

```scala
import fmgp.yallmo.server.{OrchestratorWSServer, OrchestratorWSClient}
import zio.*

ZIO.scoped {
  for {
    port <- new OrchestratorWSServer(master).start(0) // all interfaces, ephemeral port
    client <- OrchestratorWSClient.connect(s"ws://127.0.0.1:$port/rpc")
    sessions <- client.listSessions
  } yield sessions
}
```

`routes` can also be installed in an existing host. Construction starts no listener. Scope exit shuts down the listener
and client resources. The JVM client implements the transport-neutral `OrchestratorClient`, not the in-process execution
interface. `connect` completes initialization before returning; `close` is available for explicit disconnection.

## Protocol and clients

[PROTOCOL.md](PROTOCOL.md) specifies exact JSON-RPC method names, params/results, errors and notifications. Wire DTOs
use derived zio-json codecs. Domain IDs, session summaries, tasks/results and tool definitions reuse the existing
model types. Only message/session snapshots exclude private provider continuations.

Enabled methods:

- `initialize`
- `sessions.list`, `sessions.get`
- `messages.list`, `agents.list`
- `blobs.list`, `blobs.read`

Configured trusted-environment Operators additionally enable `sessions.create` and `turn.submit`. Other mutation/control/
diagnostic definitions remain disabled; no progress notifications are advertised. Unix-domain sockets remain deferred.
The JVM client uses dependency-free public JDK WebSocket (JDK 21+ resource lifecycle), not a synthetic-Origin transport;
it never bypasses server policy. Read `capabilities` from cached initialization, not a second initialize.

The website uses `web-harness/.../OrchestratorWSClient.scala`, a browser-native WebSocket adapter with a Future API.
`webHarness` depends on `protocolJS`, which depends on `modelJS`. The `protocol/shared/src` directory owns the
JSON-RPC envelopes and wire types; their existing `fmgp.yallmo.server` packages are unchanged. The JVM-only server
depends on `protocolJVM` and `coreJVM`. There is no direct source inclusion or duplicate browser DTO layer.
Models, including System One/macros, compile and test on both platforms; the browser does not depend on core/providers/tools.
The JVM client and transport adapters remain in `server/`.

Both clients correlate concurrent requests over one connection and bound pending requests to 128. Request deadlines
are 15 seconds by default for reads/create; JVM `turn.submit` has no read deadline. Timeout/interruption abandons the local wait, not server work. Close/error fails pending
requests; late replies are ignored. Neither client automatically reconnects or replays requests.

The browser socket belongs to the application root, opens on mount and closes on unmount/page unload. Tabs reuse it.
Refresh explicitly reconnects if needed, then refreshes the current tab. Reconnection never replays abandoned queries.

## Website behavior and downloads

Five hash-linked tabs preserve browser back/forward and bookmarked session selection:

- Sessions: session picker and ordered transcript.
- All messages: each stored message once, including shared/forked history.
- Conversation graph: expandable HTML/CSS parent/child tree with the same message cards, bounded to 32 levels.
- Agents: registered IDs, without initializing agents.
- Blobs: actual stored IDs, including unreferenced blobs.

Only the mounted tab queries data. Transcript text is rendered as text, never HTML. Function-call arguments are
pretty-printed; tool-result JSON fields/output and long-text collapse behavior are preserved.

Attachment links now download via sequential `blobs.read` RPC chunks on the same socket, not Fetch or a blob HTTP URL.
The browser validates IDs, offsets, total size, EOF and decoded chunk sizes before constructing a local Blob/download.
Chunks are at most 64 KiB decoded; downloads are capped at **64 MiB** in the browser. Partial-transfer errors are shown
next to the link. Leaving the application closes requests and discards partial data. Object URLs and timers are cleaned
up. Other tab queries can interleave with chunk requests.

## Security and transport limits

- The listener always binds to **0.0.0.0** for trusted-network access. No operator authentication.
- Host and Origin are unrestricted, including browser WebSocket upgrades. Network reachability is the trust boundary.
- `local-viewer` / `viewer` is a local marker, not an authenticated OS principal.
- Static responses use no-store, nosniff, no-referrer, frame denial and a restrictive CSP allowing the same-host WebSocket.
- HTTP writes are rejected. Filesystem paths, agent objects and private provider continuations are not normal RPC values.
- Eight nonwaiting connection workers keep reads responsive during execution; overload closes with 1013. Batches
  process sequentially within a worker; writes/control sends serialize. JSON messages are bounded to 1 MiB assembled, batches to 64 members. Oversized responses close with 1009. Large snapshots
  can hit the message limit; pagination is not implemented. Core's existing full-blob in-memory reads are unchanged.
- Frames are assembled with a total byte bound. In pinned ZIO HTTP 3.0.1, bytes already lost by decoding an initial
  fragment that splits a UTF-8 code point cannot be recovered; that case (and non-final initial U+FFFD) is rejected with
  1007 rather than silently corrupted. Splits across continuation frames are decoded together.
- ZIO HTTP's own inbound event queue is unbounded. These limits do not make the listener a hardened public server.

Do not publicly proxy/tunnel this listener. Keep every direct/proxied access path inside the trusted environment.

## Validation

```sh
sbt 'server/testOnly *'
sbt 'protocol/testOnly fmgp.yallmo.server.jsonrpc.YallmoProtocolSuite'
sbt 'protocolJS/testOnly fmgp.yallmo.server.jsonrpc.YallmoProtocolSuite'
sbt 'repl/testOnly fmgp.yallmo.repl.Connected* fmgp.yallmo.repl.terminal.ConnectedTerminalSuite'
sbt 'webHarness/testOnly *'
sbt 'webHarness/fastLinkJS'
sbt 'server/bundle'
```

Server tests cover real WebSocket reads/errors, shared-master visibility, blob chunks, request correlation, timeout,
close/scope cleanup, protocol/fragment bounds, SPA asset boundaries, unrestricted trusted Host/Origin access, removed REST routes, CLI
options, configured composition/tool availability, service lifetime, viewer guards and configured REPL/browser execution, concurrent independent turns, Busy/head conflict, detached
completion, shutdown/closed-scope delivery and overload, standalone root
lease contention/cleanup/aliases, connection-ID logging and INFO/DEBUG/TRACE/OFF filtering. Frontend tests cover shared-domain wire decoding, hash navigation, text/tool formatting, socket lifecycle,
reconnect without replay, deadlines, out-of-order replies and partial-download validation.

Historical website migration validation: **44 server tests + 22 frontend tests** (not current full-suite counts). A real standalone `--log-level trace` smoke check verified
the startup warning, connection/disconnection ID and both RPC payload directions. Scala.js linking and the production
bundle were validated during the website migration. Vite reports
its size advisory for the current approximately 1 MB JavaScript bundle (about 288 KB gzip); bundling succeeds.

Browser smoke checks exercised the packaged production host and Vite proxy using an isolated three-message fixture:
all five tabs, graph expansion, literal HTML text, multiline tool output, a byte-exact attachment download, one
application WebSocket/initialization, and zero legacy API requests. The prior broken REST-viewer suite was replaced
by tests for the new host; no test-source exclusions are needed.
