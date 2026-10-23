# Orchestrator view

Two modules:

- `server/`: JVM ZIO HTTP adapter over an existing `Orchestrator[?]` (normally `OrchestratorLive`).
- `view-web/`: Scala.js + Laminar SPA. Vite serves development assets and bundles production assets.

The API is read-only. There are no execution, append, create, membership or fork endpoints. It exposes transcripts
(including reasoning summaries, instructions and tool output) and attachment downloads, not agent instances,
configuration, credentials or opaque provider continuations. **Transcript text itself may contain secrets.**

## Development

Prerequisites: the repository's JDK/sbt, Node 22.12+ (or 20.19+) and npm.

From the repository root:

```sh
npm --prefix view-web ci
sbt 'viewWeb/fastLinkJS'
sbt 'server/run --storage ./zzz'
```

In another terminal:

```sh
npm --prefix view-web run dev
```

Open **http://127.0.0.1:5173**. Vite proxies `/api` to `127.0.0.1:8080`; the SPA always uses relative API URLs.
If you select another backend port with `--port`, update the proxy target in `view-web/vite.config.js`.
The current Vite configuration binds all interfaces for Docker access. Its API proxy can expose transcripts to your
network; use a trusted network or run `npm --prefix view-web run dev -- --host 127.0.0.1` for loopback-only development.

For continuous Scala.js linking, run `sbt '~viewWeb/fastLinkJS'` in another terminal/sbt session. Vite watches the imported
linker output and reloads after it changes. It does not compile Scala itself. CSS and HTML edits are handled by Vite.
The fixed `target/scalajs-dev` and `target/scalajs-prod` directories avoid a Vite plugin launching sbt recursively.

The standalone entry point requires an **existing** storage directory. It opens standard filesystem storage, whose
initialization can create missing storage subdirectories; HTTP requests only read. No provider configuration or
network client is required to inspect existing transcripts. Session selection is bookmarkable using hash URLs.
Use **Refresh** to fetch current data; this first version does not poll or stream updates.

## Website tabs

- **Sessions**: session picker and transcript.
- **All messages**: each stored message once, ignoring sessions and deduplicating forked history.
- **Conversation graph**: a plain HTML/CSS parent/child tree, with full message and parent IDs. Expand a node to see
  the same message card used in transcripts, including formatted tool results and attachment links. No graph library,
  pan or zoom. Rendering stops at 32 levels; all messages remain available in the message tab.
- **Agents**: configured and caller-supplied registry IDs, including agents not used by any session. Listing does not
  initialize agents. The standalone filesystem viewer has an empty registry; embed with your application's
  orchestrator to see its registered agents. Historical authors are still visible in transcripts.
- **Blobs**: actual stored IDs with download links, including unreferenced blobs. Missing referenced blobs are not
  listed; arbitrary files, directories and symlinks in the blob directory are ignored.

All tabs use hash links and browser back/forward. Only the mounted tab fetches data. **Refresh** reloads that tab.
Function-call arguments pretty-print valid JSON. Tool-result JSON objects display scalar fields such as exitCode,
timedOut and truncated inline, with string fields (including output) decoded into readable multiline blocks. Unknown
fields are retained; other JSON values use the existing JSON/text view. Invalid JSON and ordinary transcript text are
unchanged. Text over 1,200 characters or 16 lines is collapsed by default using native `details` / `summary` elements,
including decoded output inside expanded graph nodes. JSON strings are decoded once, not by replacing literal
backslash sequences in arbitrary text.

The live orchestrator exposes `listMessages`, `listAgentIds` and `listBlobIds`. Filesystem message listing includes
objects even if no session points to them. Older custom orchestrators keep compiling but must override the new listing
methods to support these tabs; their defaults report unsupported listing.

## Production assets

```sh
npm --prefix view-web ci
sbt 'server/bundle'
sbt 'server/run --storage ./zzz'
```

Open **http://127.0.0.1:8080**. The explicit `bundle` task:

1. Runs `viewWeb/fullLinkJS`.
2. Runs Vite's production build.
3. Copies `view-web/dist` into the server's managed resources and packages the server module JAR.

No Node/Vite process is needed at runtime. The module JAR includes the SPA, **not** its JVM dependencies; use sbt's
runtime classpath or include this module in your application's normal JVM distribution. It is not an executable fat JAR.

Ordinary JVM compile/test/run tasks do **not** build the frontend or run npm. If a `dist` already exists it is copied
into server resources; otherwise API routes still work and `/` returns a clear `503 spa_not_built` error.
Re-run `bundle` after frontend changes for a production deployment. Stale managed assets are removed when resources
are regenerated. Missing assets/API routes return 404; there is no catch-all HTML fallback because routing uses hashes.

For a manual frontend-only build: `sbt 'viewWeb/fullLinkJS'`, then `npm --prefix view-web run build`.

## Embed with your existing orchestrator

In your application's scoped startup, where `orchestrator` is the already-created live instance:

```scala
import fmgp.yallmo.view.OrchestratorViewServer

for {
  port <- new OrchestratorViewServer(orchestrator).start(8080)
  _    <- runYourApplication
} yield ()
```

`start` requires `Scope`, returns after binding, and closes the listener when that scope ends. It does not create
another orchestrator or execute agents. This module does not change REPL startup or automatically open a browser.

## API

| GET | Response |
| --- | --- |
| `/api/sessions` | Array of `{id, messageCount, agentIds}`, sorted by ID |
| `/api/sessions/{id}` | `{id, agentIds, messages}` |
| `/api/messages` | All stored messages, unique by ID, ordered by timestamp then ID |
| `/api/agents` | Sorted array of registry agent IDs |
| `/api/blobs` | Sorted array of stored blob UUIDs (no bytes loaded) |
| `/api/blobs/{uuid}` | Raw bytes as an attachment, `application/octet-stream` |

Messages contain `id`, `agentId`, optional `parentId`, `timestamp` (ISO instant), `role`, plain-text `content`
and `blobIds`, plus optional `toolName` for function calls/results. Binary bytes are never embedded in JSON. IDs are validated before reads. Error bodies are
`{code, message}`: 400 invalid ID, 404 missing data/route, 405 non-GET, 500 unexpected storage failure.
Internal exception messages are logged server-side, not returned to the browser.

The small wire contract `ViewProtocol.scala` is compiled as source by both modules. The frontend does not depend on
the JVM-only model/core projects. Do not add JVM-specific imports to that shared file.

## Local security and scope

- Binds **127.0.0.1 only**. No public-hosting mode or authentication.
- Rejects foreign Host/Origin headers; allows same-origin and the two loopback Vite origins on port 5173.
- Emits no CORS permissions. Uses no-store, nosniff and restrictive production CSP headers.
- Transcript content is rendered as text, never injected HTML. Blobs download instead of rendering inline.
- The minimal API currently returns full session lists/transcripts; pagination and very large stores are out of scope.

Do not expose the listener through a public proxy/tunnel without adding authentication and reviewing the origin policy.

## Validation

```sh
sbt ';server/testOnly *;viewWeb/testOnly *;viewWeb/fastLinkJS'
sbt 'server/bundle'
```

Server tests exercise real loopback HTTP against an in-memory `OrchestratorLive`, including writes performed by
application code after the listener starts, binary downloads, errors, asset boundaries, read-only enforcement,
origin/host validation and scope cleanup. Frontend tests cover tab/session hash navigation, JSON formatting, collapse
thresholds and the shared JSON contract. Core listing tests cover both storage backends, shared history, orphaned
objects/blobs, ignored blob-directory entries and lazy registry listing.
