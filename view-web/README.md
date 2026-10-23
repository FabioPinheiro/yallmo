# View web

Minimal Scala.js / Laminar session viewer. See [the server guide](../server/README.md) for development,
production bundling, embedding, API documentation and security boundaries.

```sh
# Repository root:
npm --prefix view-web ci
sbt 'viewWeb/fastLinkJS'
npm --prefix view-web run dev
```

The backend must be running on loopback port 8080 (`sbt 'server/run --storage ./zzz'`). Vite uses `host: true` to listen
on all interfaces, including Docker, and proxies `/rpc` with `ws: true` to `ws://127.0.0.1:8080`, preserving browser
Origin checks. Keep the dev server/proxy private: publish Docker's Vite port on host loopback only (for example
`127.0.0.1:5173:5173`), or use a trusted network. Backend loopback binding alone does not protect the Vite proxy.

Static assets still use HTTP; application queries and attachment bytes use JSON-RPC exclusively. No Scala.js/Vite sbt
bridge is needed: Vite imports the output of the separately-running linker. Five hash-linked tabs show sessions,
all messages, a small CSS conversation tree,
registry agent IDs and stored blob IDs. Refresh reloads the active tab; requests from an unmounted tab cannot update
the current view. Function-call JSON is pretty-printed. Tool-result objects show compact inline scalar fields
(e.g. exitCode, timedOut, truncated) and labeled output with decoded line breaks; unknown fields are retained and
invalid JSON falls back to plain text. Long text starts collapsed in native details elements. Graph nodes expand to
the same message cards, including tool results and attachment links.

One application-root WebSocket is shared across tabs, initialized on mount and closed on unmount/unload. Refresh
explicitly reconnects after a disconnect; failed queries are not replayed automatically. Attachment downloads validate
and assemble bounded base64 chunks into a local browser Blob (64 MiB maximum), with local object-URL cleanup and
visible partial-transfer errors. There is no Fetch client or HTTP blob fallback.

The browser compiles the existing model sources and server JSON-RPC/application protocol sources, using Scala.js
ZIO/streams dependencies for their shared domain types. No JVM core dependency or duplicate browser DTOs are used.
The experimental System One/macros package is excluded; see `build.sbt` for the source-sharing boundary.
