# REPL / server prompt-submission milestone

## Completed scope

- Core A: one-owner filesystem mutation/snapshot coordination and idempotent `registerHuman`/`isHuman`.
  No provider/tool execution under the storage mutex; legacy/uncooperative same-root owners remain unsafe.
- Server B: configured backend + actual loopback + absent Origin + claimed human enables trusted-local Operator.
  Authentication is explicitly deferred for this local path; names are impersonable, never authenticated identities.
  Keep Host/Origin/loopback safeguards and do not publicly proxy/expose. Anonymous/browser-Origin clients remain viewers.
- Exactly two mutations: `sessions.create`, `turn.submit`. Core persist–run–persist saves prompt/output, not RPC preappend.
  Nonwaiting same-session exclusion returns Busy; independent sessions/agents execute concurrently, no global run gate.
  Application-owned scoped work/Promise terminal delivery survives disconnect; shutdown finalizes before lease release.
- JVM client uses approved public JDK WebSocket without synthetic Origin. No dependency/module additions.
- Component C: cached capability negotiation, configured assistant picker, real create-once/submit-once connected prompts,
  session/head retention on failure/uncertainty, safe actual durable deltas, fresh history/resume and reset intent.
  Remote runContext stays empty. No configured selection means zero prompt writes/create. `/new` explicitly creates.
- `remoteConnection` is separate from `canSubmit`/`connectedReadOnly`: all local provider/login/audio/browser/voice widgets
  remain gated off remotely even for Operator. Legacy local mode and its continuation semantics remain unchanged.

## Deliberate limits

No production ServerSubmission/startup wrapper, run registry/status/cancel, ledger, deduplication, replay, exactly-once,
tombstones, automatic mutation retry, fork/tools/upload/login/voice/diagnostic activation or public-auth project.
Existing reserved protocol draft codecs remain disabled, not prerequisites. After a lost reply, refresh/history or
reconnect/resume the known session before manual resend. Lost create response requires session-list inspection.
Accepted failures retain prompt/snapshot; absent acceptance evidence is Unknown, not rollback. Submitted human authors
may remain process-lifetime registered separately from active viewer cleanup. Provider/login/voice/human transport
remains pending, not implicitly completed by remote prompts.

## Validation / handoff

Run separate explicit testOnly commands, check nonzero counts; do not rely on multi-task/testQuick wrapper behavior.
Focused connected application, terminal no-local-service, renderer/CLI tests and actual temporary configured server with
fake provider validate create/submit, persistence, same-socket reads and disconnect/reconnect history. Recheck REPL compile,
protocol JVM/JS and server suites from final checkout. Format touched exact Scala paths only, then recheck.

Known unrelated residuals: six local REPL voice failures and two deprecated core `OrchestratorLive.turn = ???` failures
remain outside this milestone; report actual counts rather than manufacturing a full green claim. Authoritative component
handoffs and `/tmp/prompt-submission-stage-{a,b,c}.diff` contain exact incremental preservation/validation evidence.
Independent high-thinking storage and REPL reviews found no issues. Server review found one mixed-backend P1;
the shared dispatcher now rejects a configured backend whose orchestrator is not the supplied read master. The
regression fails against the old code and passes with the guard; targeted independent recheck found no issues.

Final measured gates: server 114/114 after the fix and scoped formatting; protocol JVM/JS 21/21 each; focused REPL
85/85 and compilation pass. Latest full REPL sweep (before the two-file guard) was 226/232, retaining six voice failures;
aggregated core was 248/252 with two accepted deprecated-turn failures and two existing opt-in CLI skips. No full-green
or manual real-provider/microphone/OAuth claim. Reviews inspected source/diffs/logs without rerunning tests.
The milestone is accepted; no final cutover or full remote parity claim.
