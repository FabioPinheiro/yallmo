# Handoff: server-owned remote prompt submission complete

## Current status

Implementation and independent reviews are COMPLETE. No active subagent/writer remains. Do not restart previous
workflows. User explicitly approved authentication DEFERRED for trusted-local prompt execution; do not reinstate
an authentication blocker for this already-approved local milestone. Public deployment still requires authentication.

## Implemented behavior

- Configured backend + actual loopback peer + absent Origin + claimed human grants trusted-local Operator capabilities.
  Anonymous/browser-Origin/unconfigured hosts remain viewers. Names are impersonable, not authenticated identities.
- Active mutations are only sessions.create + turn.submit. Core owns persist–run–persist; no RPC preappend/retry.
  Sessions run concurrently; overlapping turns in the same session fail Busy. Short storage locking never spans providers.
- Application-scoped work survives connection disconnect. Shutdown waits for cleanup before root lease release.
  Accepted failures keep prompts/snapshots. Unknown/lost replies require history or reconnect/resume and manual action.
- Server owns config/providers/tools/storage. REPL connected prompts use cached capabilities, configured assistant IDs,
  safe snapshots/deltas, expected heads and boolean continuation resets. No client continuation offsets or fake messages.
  Remote execution never starts local configuration/tool/provider/audio/browser/login services or local fallback.
- JVM WebSocket internals use approved dependency-free public JDK API (JDK21+, project tested Java25), avoiding Netty's
  synthetic Origin without weakening server policy. Bounded server workers8/client pending128/1MiB message limits.
- Shared dispatcher enforces configured.assembly.orchestrator reference-identical to read master; no split backend.

No production ServerSubmission wrapper, global execution gate, run/status/cancel registry, ledger/dedup/tombstones,
replay/exactly-once, automatic mutation retries, new modules/dependencies or additional file relocations.
Remote provider administration/login, voice/TTS, human-response interactions, diagnostics, fork and uploads remain pending.
Tools invoked by configured agents DO execute server-side; remote /tools query is separately not enabled.

## Run

With private yallmo.json defining bob and a usable provider:
```
mkdir -p ./server-storage
sbt 'server/run --storage ./server-storage --config ./yallmo.json --workspace .'
# separate interactive terminal
sbt 'repl/run --user-name fabio --server ws://127.0.0.1:8080/rpc --agent bob'
```
Press i, enter a prompt. /history and /session list use server state. Without server --config the host is viewer-only.
Do not concurrently open another legacy/embedded storage owner on that root; the lease is cooperative, not universal.

## Actual validation / independent review

- Final server114/114 after the backend-identity fix/formatting; protocolJVM+JS21/21 each; focused REPL85/85 and compile.
- Latest full REPL226/232 (before final two-file guard): six retained local voice failures. Aggregated core248/252:
  two accepted unchanged deprecated OrchestratorLive.turn=??? failures plus two existing opt-in CLI skips.
- Storage31/31, new human2/2; core focused51/52/broad111/113 earlier with those accepted deprecated cases.
- No real external provider/mic/OAuth/manual terminal acceptance. Reviews inspect exact diffs/sources/tests/logs,
  not independent test reruns. No all-green checkout/exactly-once/crash-transaction/full-remote-parity claim.
- High-thinking storage and REPL reviews found no issues. Server P1 mixed read/execution backend fixed with red/green
  regression, all114 server tests pass; targeted reviewer e1a44199-c0d5-412f-a0fc-75f7fcef2a62 verdict OK.

Evidence: original BEFOREs /tmp/prompt-submission-baselines/<repo-relative-path>; exact stage-{a,b,c} patches
/tmp/prompt-submission-stage-{a,b,c}.diff; final guard patch /tmp/prompt-backend-identity-fix.diff. Full worker handoffs
under /home/fmgp/.pi/agent/sessions/--Users-fabio-workspace-yallmo--/subagent-artifacts/outputs/7ca73465-90b6-4ab5-a2ba-95fec433ba11/handoffs/{server-submission,prompt-ui}.md;
core handoff under outputs/40b1ad86-82a3-4aac-a84b-1e7cb7c2265b/handoffs/storage-authors.md in that artifact tree.
Completed reviews under outputs/32cd61e5-39ea-4672-b8f4-3ebc266385bd/reviews/{storage,server,repl}.md in same tree.
Final logs /tmp/prompt-backend-identity-fix-postformat-server.log; /tmp/prompt-submission-c-final-*.log.

## Continuing work

Ask the user for the next feature; don't automatically launch another extraction or revive completed workflow.
Keep main implementation delegated to high-thinking subagents. No future module/source/test relocation without exact
from/to paths and approval. Preserve large inherited dirty/staged checkout: no stage/reset/checkout/commit.
Actual cwd /home/fmgp/workspace/yallmo; Git sometimes reports /Users/fabio/workspace/yallmo. Use explicit cwd and
per-command git -c safe.directory='*' -c core.worktree=/home/fmgp/workspace/yallmo. No persistent Git config edits.
Read focused chunks, bounded logs, shell timeout seconds120 normally. Local sbt wrapper may skip multi-task/testQuick;
use explicit nonzero testOnly gates, scoped formatting and rechecks. Failed quota/setup lanes require reported preserved
state and same-protocol retry or explicit owner-approved fallback, never a hidden execution-mode switch.
