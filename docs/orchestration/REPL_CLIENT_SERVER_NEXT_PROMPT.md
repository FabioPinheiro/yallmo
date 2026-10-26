# Client-only REPL: feature-preserving startup cutover

## Current boundary

- `ReplMain` always connects to a separately running server. Omitted `--server` uses
  `ws://127.0.0.1:8080/rpc`; an explicit endpoint overrides it. Failure exits without embedded startup or fallback.
- `ConnectedReplApplication` retains capability-negotiated prompts and session/history operations. The server owns
  config, providers, tools, storage and execution. There is no client-local service acquisition on this entry path.
- `--user-name` is required; `--agent` is optional. `--config`/`--storage` are server-only.
  `--transcriber-agent` is rejected while connected voice integration is pending.
- `ReplStartup.make` and its implicit default-file loader are removed. The retained `fromRuntime` adapter requires
  an explicitly supplied runtime; transcription selection there is independent of CLI launch options.

## Feature preservation

The attempted broad feature removal was undone at the operator's request. Existing audio/voice/browser components,
debug snapshots/exports/provider-request captures, their supporting embedded adapters and tests are preserved.
The debug implementation was working; missing connected wiring was not a reason to delete it. Audio/voice fixes
are deferred. Do not remove or rewrite these implementations/tests just to simplify the connected startup path.

These retained features are not automatically enabled in the client-only REPL. Preserve them for later integration
without bringing back a local fallback or claiming full remote parity. Browser assets remain packaged for bridge tests.

## `/debug request` follow-up

Connected `/debug request` now uses operator-only `diagnostics.request(sessionId)`. The server captures inside
application-owned execution, retains the latest request per session even on failures/disconnects, and returns JSON
rather than a server path. Reads are bounded to 256 KiB; the 128-session in-memory index is lost on restart. Existing
continuation redaction remains, but conversation/tool text is sensitive. Help/completion follow negotiated permission.
`/debug print|export` and audio/voice are preserved but not newly enabled or modified by this follow-up.

## Preserved server semantics

Authentication remains deferred for trusted-local prompt execution. Configured backend + actual loopback peer + absent
Origin + claimed human grants Operator. Anonymous/browser-Origin/unconfigured connections remain viewers. Names are
impersonable authorship, not authentication; retain loopback/Host/Origin checks and never expose publicly.

Core owns persist–run–persist; no client/RPC preappend or automatic mutation retry. Same-session exclusion returns Busy;
independent sessions execute concurrently. Application-owned work survives disconnect; shutdown finalizes before lease
release. Unknown/lost replies require history/resume and a manual decision, not replay or a rollback guarantee.

## Run

```sh
mkdir -p ./server-storage
sbt 'server/run --storage ./server-storage --config ./yallmo.json --workspace .'
# Separate interactive terminal; config must define bob and its provider.
sbt 'repl/run --user-name fabio --agent bob'
```

## Validation

Use explicit `repl/testOnly` gates with nonzero counts and scoped formatting. Retained legacy voice tests have known
failures and are not skipped/deleted to produce a green suite. Run debug/grammar/client startup tests independently
when checking this cutover. No real-provider, microphone, OAuth or manual terminal acceptance is implied.

Request-debug validation after formatting: server 120/120; protocol JVM and JS 22/22 each; focused REPL 62/62;
existing core capture tests 3/3. Full REPL remains 231/237, with the same six pre-existing voice failures retained.
Actual socket/terminal tests use fake providers and cover request display, failed turns, reconnect, isolation, redaction,
permissions, missing captures and size/corrupt-file/symlink handling. Audio/voice implementations were not changed.
