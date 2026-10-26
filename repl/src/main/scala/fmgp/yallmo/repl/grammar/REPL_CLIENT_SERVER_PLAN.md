# Client-only REPL startup

`ReplMain` always connects to the server, defaulting to `ws://127.0.0.1:8080/rpc`. `--server` overrides the endpoint;
connection failure never starts the embedded application or acquires local recorder/browser/provider/login services.
`ReplLaunchOptions.server` is a concrete endpoint, not an optional choice between execution modes.

The server retains persist–run–persist, capability-negotiated submission, same-session exclusion, independent-session
concurrency and application-owned work surviving disconnect. Lost replies require history/resume and manual action,
never automatic mutation retries. Trusted-local authentication remains deferred; loopback/Host/Origin checks are unchanged.

## Preserve features for later integration

Audio/voice/browser components, debug snapshots/exports/request captures, their supporting embedded adapters and tests
are retained. Their removal during the cleanup was undone. Debug was working; audio/voice fixes are deferred.
Do not delete working features or tests because they are not yet connected to remote capabilities.

`ConnectedReplApplication` remains the client-only application. Existing `ReplApplication`/`ReplStartup.fromRuntime`
code supports retained feature work/tests but is not a CLI startup path. No implicit local loader or fallback remains.
Connected `/debug request` now uses the negotiated operator-only `diagnostics.request` read. Capture association
lives inside server-owned work and survives client reconnects, not server restart. Voice, debug print/export, provider
administration and other feature wiring remain pending; preservation does not claim parity.

Validate client startup/default/override/failure behavior and retained debug tests explicitly. Report existing audio
failures rather than deleting or disabling tests. Format touched Scala files and recheck. See `docs/REPL.md` and
`docs/orchestration/REPL_CLIENT_SERVER_NEXT_PROMPT.md` for the current boundary.
