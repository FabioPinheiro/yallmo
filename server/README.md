# Orchestrator WebSocket server and website

`OrchestratorWSServer(master: OrchestratorLive[?])` hosts the website and the read-only JSON-RPC WebSocket endpoint
on the **same listener**, using the supplied runtime/storage instance. The old REST viewer/server, Fetch client and
viewer-specific protocol have been removed. HTTP serves static assets and the WebSocket upgrade only; there are no
conversation/blob REST endpoints.

The server does not invoke agents, expose execution controls, or create another runtime when embedded. Transcript
text and tool output can contain secrets. Bind locally; this is not an authenticated public-hosting service.

## Development

Prerequisites: the repository's JDK/sbt, Node 22.12+ (or 20.19+) and npm.

```sh
# Repository root:
npm --prefix view-web ci
sbt 'viewWeb/fastLinkJS'
sbt 'server/run --storage ./zzz'
```

In another terminal:

```sh
npm --prefix view-web run dev
```

Open **http://127.0.0.1:5173**. Vite uses `host: true`, listening on all interfaces, including the Docker network.
It proxies WebSocket `/rpc` to `127.0.0.1:8080` with `ws: true`, preserving the browser Origin for backend validation.
If the backend uses another port, update `view-web/vite.config.js`. Vite's development/HMR socket is separate from the
website's single application socket.

The development server/proxy is network-reachable; the backend's loopback binding does not make Vite private.
For Docker, publish the Vite port on host loopback only (for example `127.0.0.1:5173:5173`), or use a trusted network.
Browser Origin checks still apply; `host: true` does not authorize arbitrary browser origins.

For continuous Scala.js linking, run `sbt '~viewWeb/fastLinkJS'`. Vite bundles already-linked JavaScript; it does not
launch sbt or compile Scala.

## Production

```sh
sbt 'server/bundle'
sbt 'server/run --storage ./zzz'
```

Open **http://127.0.0.1:8080**. The bundle task full-links Scala.js, runs Vite, and packages the SPA in the server JAR.
Ordinary JVM compilation/test/run does not invoke npm or build the frontend. Without packaged assets, `/` returns 503;
`/rpc` still works. Asset/API misses return 404, never fallback HTML. The module JAR includes assets, not JVM dependencies;
use sbt's runtime classpath or a normal JVM distribution.

The entry point is `fmgp.yallmo.server.OrchestratorServerMain`. Options remain:

```text
--storage <existing-directory> [--port <1-65535>] [--log-level <trace|debug|info|warning|error|fatal|off>]
```

It opens filesystem storage with an empty agent registry; initialization can create missing storage subdirectories.
Historical authors are visible in transcripts, but the Agents tab is empty unless an embedded application's master
has registered agents. The standalone host is for inspecting a root not being concurrently written by another process.

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
    port <- new OrchestratorWSServer(master).start(0) // loopback, ephemeral port
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

Mutation/execution/diagnostic methods remain definitions only and return Method not found; no progress notifications
are advertised. Unix-domain sockets, execution authorization and connected REPL submission remain deferred.

The website uses `view-web/.../OrchestratorWSClient.scala`, a browser-native WebSocket adapter with a Future API.
`build.sbt` compiles the **same model sources and server protocol sources** for Scala.js, excluding the experimental
System One/macros package. No model files are moved, no duplicate browser DTOs/new module are introduced, and the
browser does not depend on JVM core/providers/tools. ZIO/streams Scala.js dependencies support those existing model types;
unused runtime code is removed by the linker. The JVM client remains in `server/`.

Both clients correlate concurrent requests over one connection and bound pending requests to 128. Request deadlines
are 15 seconds by default. Timeout/interruption abandons the local wait, not server work. Close/error fails pending
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

- Standalone binding: **127.0.0.1 only**. No operator authentication or public/remote-hosting mode.
- Host validation rejects DNS-rebinding hosts; Origin checks allow same origin and loopback Vite origins only.
- `local-viewer` / `viewer` is a local marker, not an authenticated OS principal.
- Static responses use no-store, nosniff, no-referrer, frame denial and a restrictive CSP allowing the same-host WebSocket.
- HTTP writes are rejected. Filesystem paths, agent objects and private provider continuations are not normal RPC values.
- JSON messages are bounded to 1 MiB assembled, batches to 64 members. Oversized responses close with 1009. Large snapshots
  can hit the message limit; pagination is not implemented. Core's existing full-blob in-memory reads are unchanged.
- Frames are assembled with a total byte bound. In pinned ZIO HTTP 3.0.1, bytes already lost by decoding an initial
  fragment that splits a UTF-8 code point cannot be recovered; that case (and non-final initial U+FFFD) is rejected with
  1007 rather than silently corrupted. Splits across continuation frames are decoded together.
- ZIO HTTP's own inbound event queue is unbounded. These limits do not make the listener a hardened public server.

Do not publicly proxy/tunnel this listener without adding authentication and reviewing the deployment/origin policy.

## Validation

```sh
sbt ';server/testOnly *;viewWeb/testOnly *;viewWeb/fastLinkJS'
sbt 'server/bundle'
```

Server tests cover real WebSocket reads/errors, shared-master visibility, blob chunks, request correlation, timeout,
close/scope cleanup, protocol/fragment bounds, SPA asset boundaries, Host/Origin checks, removed REST routes, CLI
options, connection-ID logging and INFO/DEBUG/TRACE/OFF filtering. Frontend tests cover shared-domain wire decoding, hash navigation, text/tool formatting, socket lifecycle,
reconnect without replay, deadlines, out-of-order replies and partial-download validation.

Validation passed: **44 server tests + 22 frontend tests**. A real standalone `--log-level trace` smoke check verified
the startup warning, connection/disconnection ID and both RPC payload directions. Scala.js linking and the production
bundle were validated during the website migration. Vite reports
its size advisory for the current approximately 1 MB JavaScript bundle (about 288 KB gzip); bundling succeeds.

Browser smoke checks exercised the packaged production host and Vite proxy using an isolated three-message fixture:
all five tabs, graph expansion, literal HTML text, multiline tool output, a byte-exact attachment download, one
application WebSocket/initialization, and zero legacy API requests. The prior broken REST-viewer suite was replaced
by tests for the new host; no test-source exclusions are needed.
