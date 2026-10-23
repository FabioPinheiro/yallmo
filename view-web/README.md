# View web

Minimal Scala.js / Laminar session viewer. See [the server guide](../server/README.md) for development,
production bundling, embedding, API documentation and security boundaries.

```sh
# Repository root:
npm --prefix view-web ci
sbt 'viewWeb/fastLinkJS'
npm --prefix view-web run dev
```

The backend must be running on loopback port 8080. No Scala.js/Vite sbt bridge is needed: Vite imports the output of
the separately-running linker. Five hash-linked tabs show sessions, all messages, a small CSS conversation tree,
registry agent IDs and stored blob IDs. Refresh reloads the active tab; requests from an unmounted tab cannot update
the current view. Function-call JSON is pretty-printed. Tool-result objects show compact inline scalar fields
(e.g. exitCode, timedOut, truncated) and labeled output with decoded line breaks; unknown fields are retained and
invalid JSON falls back to plain text. Long text starts collapsed in native details elements. Graph nodes expand to
the same message cards, including tool results and attachment links. No new dependency is added.
