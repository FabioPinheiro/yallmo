# Managed history and message rebase

Status: implementation and independent review through step 7 and the message-head guard simplification are complete. No history revision counter remains. Final acceptance remains pending manual/live gates and disposition of qualified full-server/REPL failures; this plan stays active. General editable plans remain deferred.

Steps 1–6 below retain historical validation evidence, including their temporary source exclusions and helper-local
encoding workaround. Both blockers are fixed in step 7; no current exclusion/workaround is needed. Historical
qualified runs are not retroactively presented as unqualified or live acceptance.

## Goal

Give agent programs an orchestrator-managed history capability. Agents decide how to
use and reshape context; the orchestrator manages history, persistence and session
heads. RunContext is a temporary snapshot, never an independent source of truth.

Support program-driven decisions, extra LLM calls and System One decisions within
the same agent workflow. Failed-tool retries are one example, not the design boundary.

## Working agreement

Before **each** step:
1. Inspect the current code and discuss the smallest useful scope with the user.
2. Agree on the approach, acceptance checks and any compatibility changes.
3. Wait for user approval before starting implementation.

After approval:
- Use subagents for bounded implementation work. Parallelize only independent work.
- Review their changes and run the agreed checks before considering the step complete.
- Record the outcome, validation and open questions here.
- Stop and discuss the next step; approval does not carry forward automatically.

This plan defines outcomes, not a fixed architecture, API or delegation topology.
Keep abstractions small; do not introduce reference/range/result wrapper types
without an agreed need. Every implementation step includes relevant regression tests.
No subagents need to be launched just to prepare this plan.

## Agreed boundaries

- Agents never access storage directly.
- The orchestrator supplies and manages the history capability.
- Ordinary model/tool execution keeps asynchronous checkpointing; rebasing must
  coordinate with pending writes without silently losing or resurrecting messages.
- Rewrites create immutable messages; original execution history stays inspectable.
- Rebasing data never reruns tools or reverses their external side effects.
- A rebase selects messages after an exclusive base through an inclusive head,
  and attaches the rewritten sequence to an explicit new parent.
- Replaying unchanged data onto the same parent may preserve existing hashes.
- Persistence failures, cancellation and stale heads must have explicit behavior;
  this work does not imply exactly-once execution or crash-transaction guarantees.

## Steps

### 1. Establish managed history for ordinary execution

- [x] Agent execution receives an orchestrator-managed history capability.
- [x] Active messages have stable identities usable by agent programs.
- [x] Ordinary appends and asynchronous persistence have one coordinated history owner.
- [x] Existing successful, failed and cancelled execution behavior is preserved or
      any intentional change is agreed and tested.

Independent review found no blocking issues; these checks do not approve step 2.

### 2. Make RunContext temporary

- [x] Each agent/model turn receives a snapshot of current managed history.
- [x] No independently accumulated RunContext can overwrite or diverge from it.
- [x] All relevant execution paths use the same ownership rules.
- [x] Existing agent capabilities and tool-loop behavior remain intact.

### 3. Provide basic message rebase

- [x] A complete source range can be replayed onto a chosen parent.
- [x] Rewritten messages retain their data except for parent references and derived IDs.
- [x] Missing history and invalid ranges are rejected clearly.
- [x] Original messages remain unchanged; empty and no-op behavior is agreed and tested.

Implementation, validation and independent review are complete; no blocking findings.

### 4. Apply rebase through managed history

- [x] An authorized rebase updates active history; session-head publication is separate and best-effort.
- [x] Subsequent agent turns see the new history, not an older snapshot.
- [x] Pending checkpoints cannot restore discarded context or attach stale output.
- [x] Stale heads, persistence failures and cancellation do not silently discard work.
- [x] Original branches remain discoverable, and rewrite origin can be inspected.

### 5. Enable safe tool-history rebase (narrowed scope)

- [x] Valid, complete tool-call/result pairs can be rebased through managed history.
- [x] Invalid or misleading tool-call/result combinations are rejected before activation.
- [x] Invalidated provider reasoning is excluded from rewritten active context; originals remain inspectable.
- [x] A failed attempt, successful retry, rebase and continued execution are demonstrated without rerunning tools.
- [x] Existing freshness, persistence, audit and session-conflict behavior remains intact.

General pick/edit/drop/squash plans are deferred until a concrete workflow needs them.
No new interactive editing API is part of this step.

### 6. Enable mixed program/model-driven context decisions

- [x] An agent program can inspect history, run deterministic logic and optionally
      make a typed System One call to decide on a rewrite.
- [x] These decision paths use the same managed capability and validation boundary.
- [x] Advisory model calls do not automatically become conversation messages.
- [x] A representative workflow demonstrates rewriting and then continuing execution.
- [x] The example does not hard-code the architecture around failed-tool retries.

Implementation, validation and independent review are complete; no blocking findings. At step-6 completion, step 7 was not yet approved.

### 7. Validate integration and document the new flow

Automated implementation/documentation and independent-review gates (not live/manual acceptance):

- [x] In-memory and filesystem behavior agree, including reload after a rewrite.
- [x] Normal execution, rewrite/continue and checkpoint interactions have regression coverage.
- [x] Original and active branches are inspectable through existing API inventory/parents;
      published-session versus local-branch and graph/recovery limitations are documented.
- [x] Architecture documentation explains history ownership and temporary RunContext.
- [x] Remaining limitations and follow-up work are recorded without expanding this scope.
- [x] Both known blockers fixed and full model/core JVM+JS suites run without exclusions.
- [x] Independent step-7 review complete, including rereview of P1/P2 repairs.
- [ ] Manual graph rendering inspection complete.
- [ ] Explicitly opted-in synthetic-data live System One + conversational-provider smoke complete.

Full server/REPL runs retain asset-host/log-count assertion failures; see qualified results below. Their implicated sources are unchanged, but no clean-baseline run establishes that the failures predate all work.
[Acceptance procedure and gates](MANAGED_HISTORY_ACCEPTANCE.md) distinguish automated, review and live/manual evidence.

## Decisions left for step discussions

- Future writable capability API and the lifetime of rebase/session bindings.
- Rebase coordination with checkpoints, publication and failure semantics.
- Supported destinations and when active-run rewrites are allowed.
- Plan validation, provenance and recovery/undo expectations.
- The smallest useful model-driven demonstration and client inspection changes.

## Progress

### Step 1 — implemented and reviewed

Approved direction: preserve asynchronous execution, **no session-wide locking**, and
prioritize messages over the session pointer. Programs initially receive read-only
history; runner-controlled mutation is the explicit transitional boundary.

- Added `ManagedHistory`, wired through `AgentExecution`, `AgentRunner` and
  `AgentInstance.nextManaged`. Native programs inherit the entry point and still
  render payload snapshots. The capability exposes no storage handle.
- The orchestrator validates full RunContext checkpoints against the **active**
  prefix even after failed writes. It allocates each generated Message identity,
  timestamp and parent once, atomically/interruption-safely with active append and
  enqueue. Responses derive from managed active history; prompt acceptance and
  execution-author/capability checks remain intact.
- One scoped writer independently puts exact immutable objects in parent order,
  tracking object persistence separately from session publication. Failures retry
  the same objects on later checkpoints and once more during final drain.
- Existing session CAS is best-effort, after independent object saves. A pointer
  conflict leaves the competing head untouched and generated objects inspectable
  through `listMessages`, including after filesystem reload. It neither regenerates
  identities nor warns that already-stored objects are unsaved. Post-publication
  failures reconcile exact published prefixes before advancing any remaining suffix.
- Draining remains mandatory on success, inference/tool failure, timeout and
  cancellation. It does not make inference or tools await disk checkpoints.
- No backend/storage-format changes, new pointer/rebase policy, interactive flow,
  UI, model-decision workflow, session lock or step-2 migration was introduced.

Validation (sbt 2 project ID is `core`, not `coreJVM`):

- Added 26 backend-parameterized `ManagedHistorySubmitSuite` checks: stable observed
  identities, a Promise-gated object writer versus tool execution, pre-/post-object
  failures, post-session publication/reload failures, concurrent ordinary append
  with independently stored branches, failure/timeout/cancellation draining, and
  invalid active prefix/result author after persistence failure.
- Updated `OrchestratorSubmitSuite`, runner harness and execution boundary tests;
  external programs can read IDs but cannot construct/mutate history authority.
- Focused six-suite run passed **69 tests**, including both storage batch suites.
- `sbt ';core/testFull;coreJS/testFull;server/compile;repl/compile'` passed:
  JVM **442 passed / 17 environment-dependent skipped**, JS **50 passed**;
  affected server and repl compilation passed.
- JVM validation used this temporary sbt setting (no source edit):
  `set coreJVM / Test / sources := (coreJVM / Test / sources).value.filterNot(_.getName == "StorageFSMessageMaybeSuite.scala")`.
  Without it, pre-existing `StorageFSMessageMaybeSuite.scala:20` fails compilation
  because it calls private `StorageFS.messagesFromMaybe`. That unrelated blocker
  remains unchanged. `core/test` and `coreJS/test` also passed (incremental sbt 2
  tasks); full validation above deliberately used `testFull`. A final `sbt reload`
  discarded the temporary session settings; no build configuration was changed.
- After validation, scoped `scalafmtOnly` ran on the 13 touched Scala files only;
  the full suites and affected compilation passed again after formatting.
  `git diff --check` passed. No commits or staged files.

Remaining limitations until step 2: `RunContext` is still accumulated by ordinary
runner recursion and consumed by extensions/provider rendering; only ordinary
orchestrated submissions expose identified history. Invocation and deprecated turn
paths have not migrated. Programs cannot yet mutate/rebase managed history. A stuck
storage operation can delay finalizer draining indefinitely, permanent object
failures remain best-effort, and no crash-transaction/exactly-once guarantee is made.
A returned session is the last acknowledged publication, not necessarily the active
branch or the latest competing pointer. Independent branches are inspectable via
message IDs/parents, not a new branch/recovery UI.

Independent review found no blocking issues and confirmed the focused/JS test reports.
The parent inspected the implementation and reran the managed-history and submission
checks: **39 passed**, with the same unrelated test-source exclusion and a final
`reload` to discard temporary settings. `git diff --check` passed. The full JVM
suite is not an unqualified green run: the existing private-method-access blocker
above remains. Concurrency coverage includes submit-versus-append; two simultaneous
complete submissions remain an additional coverage opportunity.

Step 1 is complete.

### Step 2 — implemented and reviewed

User approved simplifying ManagedHistory to a trait, explicitly marking the existing
checkpoint adapter as temporary with a FIXME, then removing that adapter while
making RunContext a fresh managed-history snapshot for each turn. Ordinary generated
messages will be appended directly; asynchronous message-first persistence and the
existing capability/tool boundaries must remain intact. Rebase and new session
policies remain outside this step. Implementation and independent review used subagents.

Implementation:

- `ManagedHistory` is a trait exposing identified reads and an orchestration-scoped
  `append(Chunk[MessagePayload])`. The temporary checkpoint method and its migration
  FIXME are removed; no full-transcript save adapter remains.
- `AgentExecution.run` no longer accepts a parallel accepted-context snapshot.
  Each ordinary turn reads current managed history, validates the extension output
  against that turn's prefix and appends only its suffix before inference. Generated
  assistant/additional/call payloads append before tools, and each tool/RAG result
  appends at completion. Recursion and tool execution carry no accumulated RunContext.
- `AgentTurnResult` remains compatibility output only. The orchestrator validates
  the result author but never reapplies the returned snapshot; response messages
  derive from managed history. The accepted prompt still appears exactly once.
- Continuation-reset wrappers now forward `nextManaged` and the original history
  capability while filtering only request snapshots. Audited native bound instances
  and folder bindings: they inherit the managed entry point, preserving capability
  resolution. Invocation remains a single temporary-input turn; deprecated raw
  provider turns remain request/response, not an alternate managed transcript.
- Message-first object saves, stable identities, independent parent-order puts,
  asynchronous inference/tools, best-effort pointer CAS and final draining are
  unchanged. No session lock, storage format, rebase, UI or new session policy.

Validation before formatting:

- Added 12 backend-parameterized checks (managed suite now 38): latest-history reads
  across turns with trusted concurrent appends, append-only extensions on each turn,
  both calls and each output exactly once, rejected truncation/replacement extensions
  after failed writes, and empty/replaced/duplicated returned snapshots never rewriting
  saved history. Existing failed-write snapshot tests now assert returned snapshots
  are ignored, while invalid result authors still fail.
- Updated runner support and external typecheck boundaries for the trait and payload
  append; outside code may implement the trait but cannot call managed mutation or
  execute storage-backed loops. The continuation-reset server test now requires the
  managed entry point and verifies canonical reasoning is not filtered or duplicated.
- Initial focused JVM run passed 65 tests; server continuation/submission suite passed
  5. Full JVM run passed **454 / 17 environment-dependent skipped**, JS **50 passed**,
  server submission suite **5 passed**, and server/repl compilation passed.
- Commands (one semicolon command string per sbt invocation):
  `sbt ';set coreJVM / Test / sources := (coreJVM / Test / sources).value.filterNot(_.getName == "StorageFSMessageMaybeSuite.scala");core/testOnly *ManagedHistorySubmitSuite *OrchestratorSubmitSuite *AgentRunnerSuite *ExecutionBoundarySuite;server/testOnly *SessionSubmissionSuite;reload'`
  and
  `sbt ';set coreJVM / Test / sources := (coreJVM / Test / sources).value.filterNot(_.getName == "StorageFSMessageMaybeSuite.scala");core/testFull;coreJS/testFull;server/testOnly *SessionSubmissionSuite;server/compile;repl/compile;reload'`.
  Both explicitly excluded only the pre-existing private-method-access blocker noted
  in step 1, without editing its source or build configuration; final `reload`
  discarded the temporary settings. This is not an unqualified green JVM run.

Post-validation formatting used module-scoped `scalafmtOnly` / `Test/scalafmtOnly`
with explicit paths for exactly the 16 touched Scala files. An initial glob-based
formatter invocation failed because this plugin accepts literal paths; it changed
no files. The corrected explicit-path invocation passed. The full JVM/JS suites,
server submission suite and server/repl compilation were then repeated after
formatting with the same results (**454 JVM passed / 17 skipped**, **50 JS passed**,
**5 server tests passed**) and the same one-source exclusion plus final `reload`.
`git diff --check` passed. `git ls-files -s` compares byte-for-byte with the initial
index snapshot: no entries were staged, unstaged or otherwise changed by this step.

Independent review found no blocking issues and corroborated the JVM/JS/server test
reports. The parent inspected the final implementation, confirmed there are no
checkpoint method calls in the relevant sources, and reran the focused regression
command above: **71 core tests and 5 server tests passed**. This used the same
one-source exclusion and final `reload`; the unrelated JVM compilation blocker
remains unchanged. `git diff --check` passed.

Existing durability/drain limitations from step 1 still apply. `AgentTurnResult`
is a compatibility snapshot, not another history owner. No commits/staging/reset
were performed; pre-existing user index entries, NOTES.md and tmp.json are preserved.
Step 2 is complete.

### Step 3 — implemented and reviewed

User approved the basic `rebase(base: MessageId, head: MessageId,
onto: Option[MessageId]): IO[YallmoError, Chunk[Message]]` method. The base is
exclusive and the head inclusive; returned messages are chronological. This step
creates and persists immutable rewritten messages without changing any session head
or active managed history. Editable plans and integration remain later steps.
Implementation and independent review use subagents.

Implementation:

- Added exactly the approved public signature on `Orchestrator`, implemented in
  `OrchestratorLive`; the REPL adapter forwards it. No range/result/reference wrapper
  types, agent storage handles or new backend API/format were introduced.
- One persisted-object snapshot resolves IDs. Stack-safe parent traversal validates
  complete source and destination ancestry, hashes and registered authors before any
  candidate writes. The source base must be reachable from the head; timestamps and
  session ownership do not determine order. Missing/invalid history uses existing
  typed errors. Snapshotting the full object inventory is intentionally simple but
  costs memory/time proportional to the store, not just the selected range.
- Parent-first independent puts replay only the exclusive-base/inclusive-head suffix.
  Every copy preserves original author, timestamp and payload bytes as serialized;
  only parent and derived hash change. `base == head` is empty after validation;
  `onto == Some(base)` preserves hashes with idempotent writes. Any existing source
  object is a valid destination without treating the retained original as a cycle.
- Session refs and active managed history never change, no session is created, and
  no inference/tools run. Blob references/provider continuations/call IDs remain
  unchanged; blob bytes are not duplicated and provider replay safety is not promised.
  Successful IO means all returned objects are saved. Failure/interruption may leave
  a saved prefix, never a promised batch rollback. Only persisted messages are visible;
  this operation does not drain or coordinate an active submission writer.

Validation:

- Added `OrchestratorRebaseSuite`: **47 checks** across memory and filesystem storage,
  covering exclusive/inclusive bounds, alternate/root/inside-source destinations,
  unchanged metadata and payload serialization (including blobs, opaque provider
  continuations and tool IDs), original preservation, isolated objects, unchanged
  session refs/no session creation, empty/no-op/idempotent behavior, missing refs even
  for empty ranges, unreachable base, missing intermediate and pre-base ancestors,
  injected hash failures, unregistered authors, parent-order persistence, failure and
  interruption leaving only a saved prefix, filesystem reopen, and 12,001-object
  stack-safe ancestry. No provider/tool implementations are needed by these fixtures.
- Added shared `ExecutionBoundarySuite` compile checks for the exact application API
  and absence of model-facing managed-history rebase. The suite passes on JVM and JS.
- Focused command passed **55 tests**:
  `sbt ';set coreJVM / Test / sources := (coreJVM / Test / sources).value.filterNot(_.getName == "StorageFSMessageMaybeSuite.scala");core/testOnly *OrchestratorRebaseSuite *ExecutionBoundarySuite;reload'`.
- Full pre-format validation passed **502 JVM / 17 environment-dependent skipped**,
  **51 JS**, **5 server submission tests**, and server/repl compilation:
  `sbt ';set coreJVM / Test / sources := (coreJVM / Test / sources).value.filterNot(_.getName == "StorageFSMessageMaybeSuite.scala");core/testFull;coreJS/testFull;server/testOnly *SessionSubmissionSuite;server/compile;repl/compile;reload'`.
  These full suites include the step-1/2 managed-history/submission/runner regressions.
- The same pre-existing private-method-access blocker in
  `StorageFSMessageMaybeSuite.scala` was excluded only by the temporary sbt setting,
  not by source/build edits. Final `reload` discarded the setting. This is not an
  unqualified green JVM run.
- Scoped formatting ran after validation on exactly the five step-3 Scala files:
  `sbt ';model/scalafmtOnly ../shared/src/main/scala/fmgp/yallmo/orchestration/Orchestrator.scala;core/scalafmtOnly src/main/scala/fmgp/yallmo/orchestration/OrchestratorLive.scala;core/Test/scalafmtOnly src/test/scala/fmgp/yallmo/orchestration/OrchestratorRebaseSuite.scala ../shared/src/test/scala/fmgp/yallmo/api/ExecutionBoundarySuite.scala;repl/scalafmtOnly src/main/scala/fmgp/yallmo/repl/ReplOrchestrator.scala'`.
  An initial attempt used repository-relative paths, but this plugin requires paths
  relative to each project (`model/jvm`, `core/jvm`, `repl`); it failed to read the
  first path and changed no files. The corrected command above passed without
  unrelated rewrites.
- Post-format validation repeated the full command, additionally compiling REPL tests:
  `sbt ';set coreJVM / Test / sources := (coreJVM / Test / sources).value.filterNot(_.getName == "StorageFSMessageMaybeSuite.scala");core/testFull;coreJS/testFull;server/testOnly *SessionSubmissionSuite;server/compile;repl/compile;repl/Test/compile;reload'`.
  Results remained **502 JVM passed / 17 skipped**, **51 JS passed**, **5 server tests
  passed**; server/repl and REPL test compilation passed. `git diff --check` passed.

Index integrity note: staged step-1/2 entries changed externally during this worker
run; the actor/intent is unconfirmed. The worker issued no staging/reset/restore or
commit commands and paused for supervisor coordination. The supervisor authorized
continuing while preserving the current index exactly, without restoring the
initial snapshot. That coordinated index snapshot remained byte-for-byte unchanged
through formatting/revalidation. Pre-existing working changes, NOTES.md and tmp.json
were left untouched; no step-3 entries were staged by this worker.

Independent review found no blocking issues and checked the saved rebase and
execution-boundary test reports. The parent inspected the final code and reran
`OrchestratorRebaseSuite`, `ManagedHistorySubmitSuite`, `OrchestratorSubmitSuite`
and `ExecutionBoundarySuite`: **106 passed**, including all **47 rebase checks**.
This rerun used the same unrelated one-source exclusion and final `reload`;
`git diff --check` passed. The qualified JVM result and inventory-scan/partial-write
limitations above remain explicit.

Step 3 is complete. At its completion, step 4+ had not started.

### Step 4 — implemented and reviewed

User approved applying rebase through managed history, with message/context success
independent of session publication. A changed session head must not invalidate the
immutable rebase or undo the updated active history. Attempt pointer publication
separately; log errors and preserve the competing pointer and inspectable branches.
No automatic fork naming/recovery policy is selected in this step.

Pending writes and subsequent appends must not resurrect discarded context or lose
messages. Keep asynchronous ordinary execution and avoid session-wide locks.
Original history and rewrite origin must remain inspectable. Editable operation
plans, UI/protocol changes and mixed model decision workflows remain later steps.
Implementation and independent review use subagents.


Implementation (including reviewed repair below; does not approve step 5):

- Public managed `rebase(base, head, onto)` uses message IDs only, returns the immutable suffix and activates full
  destination ancestry plus copies. Current local head is required, empty ranges activate destination/root, and exact
  no-ops preserve IDs. Standalone `Orchestrator.rebase` remains unrestricted and context/session-neutral.
- A per-submission gate serializes append/rebase/close, not sessions or inference/tools. Rebase waits for the single
  writer's required object barrier. The never-replaced object journal/cursor prevents stale snapshot counts from
  skipping new branch objects. Pre-activation object/validation failure or interruption leaves active context unchanged;
  activation/enqueue is short and interruption-safe. Closing expires mutations before final drain.
- Private storage head CAS validates already-stored ancestry, preserves latest participants and never weakens the
  strict append API. Memory independently retains session-only originals before ref replacement. Publication failures
  (including post-publication acknowledgement/read failures) log ERROR with session/old/new IDs and never fail or undo
  managed rebase. Reconciliation accepts only an exact previously attempted chain, never a competing newer head.
- Responses use accepted prompt once plus actual execution appends, not a drop by accepted-prefix size. Rebased-away
  generated objects remain inspectable; copies retain original authors and are not counted as execution. Logs expose
  base/head/onto, old/new active heads and ordered original/copy IDs, plus publication outcome. This is log-only
  provenance: no durable queryable operation journal or automatic branch names/recovery are claimed.
- Supervisor-approved bounded safety choice: internal revision fences preserve stale extension/model/action results
  on their original parent branch and stop continuation without replaying side effects. A program rebasing inside
  nextManaged must explicitly read history and rebuild its request before returning output. That read is a trusted
  program contract, not proof of provider input; it cannot authorize unrelated stale extension/tool work. New turns
  always read fresh history. Retained capabilities cannot mutate after close, including after cancellation.
- FIXME for step 5: changed active contexts with any opaque Reasoning or tool-call/output in either old/new history
  are conservatively rejected (even a complete pair/retained prefix); exact no-ops remain allowed. Standalone rebase
  still preserves all payloads without replay claims. Complete-pair/continuation replay safety and editable protocol
  policy remain unapproved, as do UI/model decision workflows and automatic fork naming/recovery.

Validation results and commands follow. Remaining limitations include
inventory-wide scans, an unbounded queue, stuck storage delaying drain, best-effort ordinary writes, partial immutable
copy prefixes on failure, log-only operation provenance, and no crash transactions/exactly-once guarantees.


Initial step-4 validation (historical, before independent review found the action-fence defect):

- Managed suite now **84 checks** (46 added, memory + FS): explicit fresh-read/rebuilt request, fresh subsequent turn,
  shorter/longer/root/empty/no-op/overlap/repeated rewrites; originals after memory replacement and FS reload;
  correct prompt/execution audit despite absent or differently sized accepted prefix; object/barrier/copy failures;
  pointer conflict and pre-/post-publication/read failures; ERROR identifier-only pointer logging; pending snapshots;
  both local append/rebase orderings; cancellation before/after activation, final drain and expired capabilities;
  stale model/extension output retained on original parents; conservative protocol/continuation boundary, no-op
  exceptions and tool side effect executed once. Promise gates, not sleeps, coordinate the added races.
- Shared storage batch suite adds a head-replacement contract check for memory/FS (memory also on JS): independent
  retention of session-only originals, latest participants, strict stale expected head, missing destination and root
  replacement. Existing invalid/stale append checks are unchanged. Updated trusted harness, storage delegates and
  public managed-rebase compile boundary; standalone's 47 checks remain passing.
- Focused seven-suite validation passed **173 tests** before the last fresh-turn and storage additions. Earlier
  exploratory focused runs exposed outdated publication injection/log expectations and a parser/import error; these
  were corrected, not excluded. Updated focused managed run passed **84** before formatting.
- Full pre-format and post-format commands both passed **550 JVM / 17 environment-dependent skipped**, **52 JS**,
  **5 server submission tests**, and server/repl compilation:
  `sbt ';set coreJVM / Test / sources := (coreJVM / Test / sources).value.filterNot(_.getName == "StorageFSMessageMaybeSuite.scala");core/testFull;coreJS/testFull;server/testOnly *SessionSubmissionSuite;server/compile;repl/compile;reload'`.
  Only the pre-existing private-method-access blocker `StorageFSMessageMaybeSuite.scala` was temporarily excluded;
  its source and build configuration are unchanged. Each final `reload` discarded the exclusion. The qualified JVM
  result is not an unqualified green run.
- Scoped scalafmt ran AFTER full validation, on exactly 13 touched Scala files (three model main, five core main,
  five core tests), then the full command above passed again. Command:
  `sbt ';model/scalafmtOnly ../shared/src/main/scala/fmgp/yallmo/orchestration/ManagedHistory.scala ../shared/src/main/scala/fmgp/yallmo/orchestration/Storage.scala ../shared/src/main/scala/fmgp/yallmo/orchestration/AgentExecution.scala;core/scalafmtOnly src/main/scala/fmgp/yallmo/orchestration/OrchestratorLive.scala src/main/scala/fmgp/yallmo/orchestration/storage/StorageFS.scala ../shared/src/main/scala/fmgp/yallmo/orchestration/AgentRunner.scala ../shared/src/main/scala/fmgp/yallmo/orchestration/storage/StorageInMemory.scala ../shared/src/main/scala/fmgp/yallmo/orchestration/storage/StoreageUtils.scala;core/Test/scalafmtOnly src/test/scala/fmgp/yallmo/orchestration/ManagedHistorySubmitSuite.scala src/test/scala/fmgp/yallmo/orchestration/OrchestratorRebaseSuite.scala ../shared/src/test/scala/fmgp/llm/orchestration/RunnerTestSupport.scala ../shared/src/test/scala/fmgp/yallmo/api/ExecutionBoundarySuite.scala ../shared/src/test/scala/fmgp/yallmo/orchestration/storage/StorageBatchSuite.scala'`.
- `git diff --check` passed. No commits, staging, reset or restore operations. Pre-existing staged edits remain;
  the cached binary diff SHA-256 stayed `a3e8e69116a8ddc2195561ba534814b016be9d8f2c1201327b0fd715fd89f71d`
  across scoped formatting and final full validation. Existing NOTES.md, tmp.json and unrelated working edits remain
  untouched. Full command logs are `/tmp/step4preformat.log`, `/tmp/step4format.log`, `/tmp/step4postformat.log`.

Initial review found a blocking action-fence defect; the repair and final acceptance are recorded below. Step 5 remains unapproved.


### Step-4 independent review — P1 action fence repaired and re-reviewed

Independent review blocked acceptance: `executeActions` adopted a fresh revision for each action, so a RAG query
chosen by an older accepted decision could execute and attach its result to rewritten context. Review also noted
that the existing `repeat` shape's second rebase was only a no-op, not another changed-history activation. Prior
validation remains historical evidence, not acceptance of the defective action boundary.

Approved narrow repair:

- Pass the accepted decision/work revision into `executeActions`. Before each action starts, compare its history
  snapshot against that same revision and reject a mismatch before executing the queued effect. Never adopt a new
  revision for an old batch. Keep the original decision revision through result append: already-started stale output
  is still journaled/audited under its original execution parent, and the runner stops without replaying effects.
- No new abstraction, public API, session lock or protocol policy. The previously approved conservative tool/opaque
  continuation restriction and standalone rebase contract are unchanged.
- Added 12 deterministic Promise-gated RAG checks across memory/FS: rewrite after decision acceptance but before the
  first action, between batch actions, and while the first action is running; each also runs with/without an external
  competing session append. They assert queued stale effects never start, started work runs once and persists on its
  old parent, replacement context receives no stale append, inference does not continue, originals/copies survive
  reload, and competing session heads remain unchanged with ERROR pointer logs.
- Added two backend checks with two genuinely changed active histories in one submission. Each publication is
  awaited/observed, with an append between rewrites and after the second. Both branches' objects, final active/session
  state, parent links and original execution audit (not copies) are verified, including filesystem reload. The earlier
  no-op repeat check is retained as separate coverage.

Repair validation:

- Focused command passed **158 tests**, including **98 managed-history** and **47 standalone rebase** checks:
  `sbt ';set coreJVM / Test / sources := (coreJVM / Test / sources).value.filterNot(_.getName == "StorageFSMessageMaybeSuite.scala");core/testOnly *ManagedHistorySubmitSuite *AgentRunnerSuite *OrchestratorRebaseSuite;reload'`.
- Full pre-format and post-format runs passed **564 JVM / 17 environment-dependent skipped**, **52 JS**,
  **5 server submission tests**, plus server/repl compilation:
  `sbt ';set coreJVM / Test / sources := (coreJVM / Test / sources).value.filterNot(_.getName == "StorageFSMessageMaybeSuite.scala");core/testFull;coreJS/testFull;server/testOnly *SessionSubmissionSuite;server/compile;repl/compile;reload'`.
  Only the same unrelated `StorageFSMessageMaybeSuite.scala` private-access source blocker was temporarily excluded;
  no source/build fix was made. Each final `reload` discarded the exclusion. This remains a qualified JVM result.
- After pre-format validation, scoped formatting touched only the two repair Scala paths; then full validation above
  was repeated:
  `sbt ';core/scalafmtOnly ../shared/src/main/scala/fmgp/yallmo/orchestration/AgentRunner.scala;core/Test/scalafmtOnly src/test/scala/fmgp/yallmo/orchestration/ManagedHistorySubmitSuite.scala'`.
- `git diff --check` passed. No staging, reset, restore or commits; the existing cached binary diff SHA-256 remains
  `a3e8e69116a8ddc2195561ba534814b016be9d8f2c1201327b0fd715fd89f71d`, matching repair-start inspection.
  NOTES.md, tmp.json and all unrelated source/working/index edits were preserved.
- Logs: `/tmp/step4repair-focused.log`, `/tmp/step4repair-preformat.log`, `/tmp/step4repair-format.log`,
  `/tmp/step4repair-postformat.log`.

Fresh independent rereview found no blocking issues and verified the P1 correction, deterministic race regressions
and true repeated activations. The parent inspected the final implementation and reran managed-history, standalone
rebase, submission and execution-boundary suites: **166 core tests passed**, plus **5 server submission tests passed**.
The parent run used the same unrelated one-source JVM exclusion and final `reload`; `git diff --check` passed.

Step 4 is complete. All documented limitations remain: log-only operation provenance, the temporary conservative
protocol restriction (FIXME step 5), trusted explicit program refresh, unbounded queue and potentially indefinite
storage draining. Session-pointer failures log ERROR and do not undo message/context success. No automatic fork
naming policy was added. At step-4 completion, step 5 had not started.

### Step 5 — narrowed implementation complete and reviewed

User approved only the useful next increment: remove the blanket tool/reasoning
restriction, allow complete valid tool exchanges, safely handle affected opaque
provider reasoning, and demonstrate retry cleanup followed by continued execution.
Keep implementation small; do not add general editing operations, wrapper types,
automatic retry classification, new session policy or model-decision frameworks.
Original messages and actual execution audit must remain unchanged/inspectable.
Implementation and independent review use subagents.


Implementation (narrowed step 5 only; independently reviewed):

- Removed the managed blanket protocol/reasoning rejection and its temporary FIXME.
  The existing `rebase(base exclusive, head inclusive, onto)` signature is unchanged.
  Destination ancestry and selected source must each contain complete independent
  tool exchanges, with unique call IDs, matching names and exactly one later output.
  The final chain is checked too. A retained output must still have its unique original
  call in the selected range; earlier same-ID calls cannot be hidden by a cut to
  manufacture another pairing. No cross-cut pairing/repair is attempted.
- Changed active chains conservatively omit **all** `Reasoning`, including destination
  ancestry, before constructing final parents/hashes or saving candidates. Original
  immutable objects and actual execution audit stay intact. Ciphertext/summary are
  neither inspected nor repackaged nor logged. Identifier-only logs map retained
  originals to actual copies and separately record removed reasoning IDs.
- The return value remains the retained source suffix, possibly empty for a reasoning-only
  selection. Managed history/session publication use the actual sanitized full chain.
  Exact unchanged no-ops preserve original payloads, reasoning and IDs. Standalone
  `Orchestrator.rebase` remains context-neutral and payload-preserving, unchanged.
- Object validation precedes candidate saves; failed saves leave active history and
  publication target unchanged, although immutable saved prefixes may remain. Existing
  submission gates, writer/barriers, lifecycle expiry, original-parent stale audit,
  accepted-decision revision across queued actions and best-effort publication remain.
  Pointer conflicts still log ERROR independently of successful active rebase.
- No editing operations/framework, wrapper reference/range/result types, agent Storage
  access, automatic retry classifier, model-decision framework, session/fork policy,
  UI/protocol changes, session-wide lock or tool execution during rebase was added.

Regression evidence:

- `ManagedHistorySubmitSuite` now has **136 memory/FS checks** (38 net additions over
  reviewed step 4): complete and batched exchanges with reverse result ordering,
  payload/arguments/provider-item/call-ID/author/timestamp retention, missing calls/results,
  duplicate calls/results, mismatched name/ID, output-first, both incomplete cuts,
  cross-cut pairing, destination/source ID collision and false original reassociation.
- Exact no-op protocol/reasoning preservation, destination+source reasoning removal,
  reasoning-only selected range, full parent/hash correctness, correct retained-ID
  provenance, no reasoning content in logs, FS reopen, partial immutable save failure
  and unchanged active/pointer assertions are covered. A blank-ID fixture uses a
  `StorageProbe.listMessages` inventory-only overlay, never writing undecodable objects.
- A recording fake provider uses the real `NativeFunctionAgent` request mapping and
  `AgentRunner`: a fake tool actually fails first, is explicitly retried successfully,
  then the program uses the failure result as exclusive base and the failed call's
  pre-failure parent as destination. It rebases, explicitly reads/rebuilds the next
  request, and continues tool execution/inference in the **same submission**. The failed
  pair is absent from active/request input but remains stored/audited; the successful
  pair is copied intact. Exactly three effects occur (failure, retry, continuation),
  never during rebase. This runs with/without competing session publication; it proves
  request input, not live-provider acceptance. No automatic model retry policy is implied.
- A Promise-gated tool finishing after rebase is retained under its original call parent,
  never attached to active replacement, and stops continuation without rerunning. The
  prior stale model/extension/RAG and multi-action accepted-decision fence tests still pass.

Validation:

- Final focused command passed **217 tests**:
  `sbt ';set coreJVM / Test / sources := (coreJVM / Test / sources).value.filterNot(_.getName == "StorageFSMessageMaybeSuite.scala");core/testOnly *ManagedHistorySubmitSuite *OrchestratorRebaseSuite *AgentRunnerSuite *OrchestratorSubmitSuite *ExecutionBoundarySuite;reload'`.
- Full qualified core JVM/JS plus affected server/repl checks passed before and after
  formatting: **602 JVM passed / 17 environment-dependent skipped**, **52 JS passed**,
  **5 server submission tests passed**; server/repl and REPL test compilation passed:
  `sbt ';set coreJVM / Test / sources := (coreJVM / Test / sources).value.filterNot(_.getName == "StorageFSMessageMaybeSuite.scala");core/testFull;coreJS/testFull;server/testOnly *SessionSubmissionSuite;server/compile;repl/compile;repl/Test/compile;reload'`.
  Only the known unrelated private-method-access blocker in `StorageFSMessageMaybeSuite.scala`
  was temporarily excluded, without source/build edits; final `reload` discarded settings.
  This is not an unqualified green JVM run.
- After tests, scoped formatting ran on exactly the three step-5 Scala paths:
  `sbt ';model/scalafmtOnly ../shared/src/main/scala/fmgp/yallmo/orchestration/ManagedHistory.scala;core/scalafmtOnly src/main/scala/fmgp/yallmo/orchestration/OrchestratorLive.scala;core/Test/scalafmtOnly src/test/scala/fmgp/yallmo/orchestration/ManagedHistorySubmitSuite.scala'`.
  Full validation was repeated afterward. Logs: `/tmp/step5-focused.log`,
  `/tmp/step5-preformat.log`, `/tmp/step5-format.log`, `/tmp/step5-postformat.log`.
- Exploratory focused runs initially found test syntax/import errors and a blank-ID
  fixture that wrote an intentionally undecodable FS object. Those fixture issues were
  corrected; blank-ID coverage remains via the read overlay, with no production codec
  relaxation or new test exclusion. All final tests pass. `git diff --check` passes.
- No staging/reset/restore/commit commands were issued. The existing cached binary diff
  SHA-256 remained `da8858d1b08a9aedc49ae4ec3fa1fdc6bcc89642e5e8370decbec37017902476`
  across this worker's implementation/formatting/validation. Existing user/index edits,
  `NOTES.md` and `tmp.json` were preserved.

Limitations/policy: conservative complete-independent-exchange cuts and removal of all
reasoning may reject/omit otherwise provider-acceptable context. Trusted explicit request
refresh is still a program responsibility, not proof of provider consumption. No live
service acceptance is claimed. Inventory scans, unbounded queues, possibly indefinite
storage draining, best-effort ordinary persistence, partial immutable prefixes, log-only
provenance and lack of crash transactions/exactly-once guarantees remain unchanged.
General editable plans are deferred.

Independent review found no blocking issues, checked the retry demonstration and
pair/reasoning validations, and corroborated the focused and full validation logs.
The parent inspected the final code and reran the focused command plus the server
submission suite: **217 core tests and 5 server tests passed**. The same existing
one-source JVM compilation exclusion was used and discarded by final `reload`;
`git diff --check` passed. No live-provider acceptance is claimed.

Narrowed step 5 is complete.

### Step 6 — implemented and reviewed

User approved starting with System One and using subagents. Keep the workflow opt-in
and small: trusted agent code chooses a candidate message range, a closed decision
asks keep versus rebase, and trusted code validates/applies the existing managed
operation before refreshing context and continuing. Advisory output is not appended
to the conversation automatically. Do not enable default cleanup or add general
editing operations, storage/session APIs, a policy framework or UI/protocol changes.
Use the existing typed System One boundary; preserve history integrity and existing
reasoning/tool protections. Demonstrate with deterministic provider tests and do not
claim live-service acceptance. Step 7 remains separate.

Implementation:

- Added production `SystemOneHistoryDecision.prepare(history)(select)`. The program
  chooses a tuple of existing active exclusive-base/inclusive-head/onto IDs plus
  explicitly bounded/sanitized plain-text evidence. It makes at most one advisory
  call per preparation through existing typed `SystemOneDecisionAgent`/`SOMProvider`
  and returns a fresh canonical RunContext. No default registration, automatic
  cleanup, retries, tool execution, new storage/session API, range/result wrappers,
  permissions or UI/protocol/config changes were introduced.
- Host policy is deliberately small: closed Keep/Rebase criteria; finite in-range
  official confidence/probabilities; exactly both probability keys; normalization
  within `1e-6`; unique rebase winner; both rebase probability and official confidence
  meet caller-owned threshold (default `0.9`, valid finite `(0.5,1]`). Laya extensions
  are ignored as authority. Missing/invalid candidate IDs or no selection skip
  evaluation. Invalid/uncertain/schema answers and typed provider failures KEEP.
  No raw provider error/evidence is logged. Cancellation/defects and real managed
  rebase validation/object failures propagate, never reporting an application success.
- Supervisor approved the minimal internal `ManagedHistory.rebaseAt` revision fence.
  It shares the existing locked rebase body; revision validation and activation run
  under the same mutation gate, without nested acquisition. After advisory latency
  the helper also checks the full source snapshot. Local append or rewrite (including
  away/back to the same head) stops continuation; external session-pointer changes
  remain independent. The fresh `history.messages` read refreshes the runner's
  program fence; the caller passes it and the original capability to delegate
  `nextManaged`. Advisory prompts/answers are never appended to conversation/audit.
  Public rebase, async journal/barriers, complete-pair validation, reasoning removal,
  author/capability checks, ERROR/CAS publication and lifetime expiry remain intact.
- A real-wire test discovered a pre-existing general typed-question encoder issue:
  deriving an encoder for a concrete Choice field omits `type: "choice"`. With
  supervisor approval the helper's private question encoder uses the existing sealed
  question trait to include the discriminator, while retaining SOSchema typed answer
  validation. Generic SORequest/SOMProvider encoding was deliberately not refactored;
  that broader limitation is a documented follow-up, not silently claimed fixed.
- Added a concrete research-note cleanup program demonstration through ordinary
  AgentRunner and the bound native provider mapper. A recording SOMProvider decodes
  the actual SystemOneRequest wire (model/state/question/closed criteria) and returns
  realistic typed responses; the recording conversational adapter sees the refreshed
  canonical history plus existing instructions. No IDs/raw messages/reasoning are
  sent as advisory evidence; rebased inference omits the discarded note/ciphertext.
  Originals remain inspectable after filesystem reopen. The existing failed-tool
  retry demonstration is unchanged, not the architectural boundary.
- Added 28 shared JVM/JS policy/wire checks and 28 backend-parameterized integration
  checks: KEEP/REBASE, low confidence/ties, malformed/schema/provider errors, missing
  candidates/no advisory request, invalid thresholds, cancellation, stale local
  append, source-revision ABA, ABA between recheck and atomic application, real object
  failure/incomplete pair, lifetime expiry, and competing session publication logging
  ERROR while immutable rebased branch and continuation succeed.
- [Usage snippet, policy and limitations](../system_one/MANAGED_HISTORY_DECISIONS.md)
  show an agent program calling prepare then delegate.nextManaged. Mental-model and
  System One overview docs link the explicit opt-in flow. Step 7 remains unapproved.

Validation:

- Focused final command passed **274 JVM tests** and **28 JS tests**:
  `sbt ';set coreJVM / Test / sources := (coreJVM / Test / sources).value.filterNot(_.getName == "StorageFSMessageMaybeSuite.scala");core/testOnly *SystemOneHistoryDecisionSuite *ManagedHistorySubmitSuite *OrchestratorRebaseSuite *AgentRunnerSuite *OrchestratorSubmitSuite *SystemOneDecisionAgentSuite *ExecutionBoundarySuite;coreJS/testOnly *SystemOneHistoryDecisionSuite;reload'`.
- Before and after scoped formatting, full qualified validation passed **658 JVM /
  17 environment-dependent skipped**, **80 JS**, **5 server submission tests**, plus
  server/repl compilation:
  `sbt ';set coreJVM / Test / sources := (coreJVM / Test / sources).value.filterNot(_.getName == "StorageFSMessageMaybeSuite.scala");core/testFull;coreJS/testFull;server/testOnly *SessionSubmissionSuite;server/compile;repl/compile;reload'`.
  Only the known unrelated private-method-access blocker in StorageFSMessageMaybeSuite
  was temporarily excluded. No source/build fix; final reload discarded the setting.
  This is not an unqualified green JVM run or a live-provider acceptance claim.
- After tests, scoped formatting ran on exactly the eight changed Scala paths:
  `sbt ';model/scalafmtOnly ../shared/src/main/scala/fmgp/yallmo/orchestration/ManagedHistory.scala;core/scalafmtOnly src/main/scala/fmgp/yallmo/orchestration/OrchestratorLive.scala ../shared/src/main/scala/fmgp/yallmo/orchestration/AgentRunner.scala ../shared/src/main/scala/fmgp/yallmo/orchestration/SystemOneHistoryDecision.scala;core/Test/scalafmtOnly src/test/scala/fmgp/yallmo/orchestration/ManagedHistorySubmitSuite.scala ../shared/src/test/scala/fmgp/llm/orchestration/RunnerTestSupport.scala ../shared/src/test/scala/fmgp/yallmo/api/ExecutionBoundarySuite.scala ../shared/src/test/scala/fmgp/yallmo/orchestration/SystemOneHistoryDecisionSuite.scala'`.
  Full validation repeated afterward; `git diff --check` passed.
- Logs: `/tmp/step6-focused-final.log`, `/tmp/step6-preformat.log`,
  `/tmp/step6-format.log`, `/tmp/step6-postformat.log`. Exploratory runs first found
  a test MessageId accessor typo, expected native instructions missing in assertions,
  and the genuine discriminator issue above; final checks pass after correcting these.
- No staging/reset/restore/commit commands were issued. The pre-existing index was
  preserved byte-for-byte: cached binary diff SHA-256 before/after is
  `7be764596bf7d8b8e9dafb1a2a02961f820e7f31b859a60d74618ebed58e4d22`.
  Existing staged changes and unrelated `NOTES.md`, `tmp.json`, `plan-tool/` and
  pre-existing documentation content were not rolled back or overwritten.

Remaining limitations: evidence curation/bounding and selection semantics belong to
trusted caller code; there is no general detector of disposable context or automatic
retry. The helper restricts candidates to inspected active IDs, though public managed
rebase supports other persisted destinations. Conservative confidence and pair/reasoning
rules may KEEP/reject otherwise useful candidates. At step-6 completion, generic typed
question encoding still needed follow-up (fixed in step 7 below). Existing inventory scans, unbounded queues, indefinite stuck
storage drains, best-effort ordinary persistence, partial immutable saves, log-only
provenance and lack of crash transactions/exactly-once guarantees are unchanged.

Independent review found no blocking issues and checked the opt-in helper, real-wire
request tests, source/ABA fences, non-retry cleanup demonstration and recorded full
validation. The parent inspected final code/docs and reran the focused suites:
**274 JVM core tests, 28 JS tests and 5 server tests passed**. This used the same
existing one-source JVM compilation exclusion and final `reload` to discard it;
`git diff --check` passed. No live-service acceptance is claimed.

Step 6 is complete. At its completion, the generic typed-question encoder limitation
remained a separate follow-up; the helper's local wire encoding is covered by its
real-envelope tests.

### Step 7 — approved, in progress

User approved final integration/acceptance, including fixing the two known blockers:
the StorageFSMessageMaybeSuite private-method test access and generic typed System
One question serialization. Remove the need for any test-source exclusion and for
the helper-local encoding workaround, with regressions for the actual wire format.
Review the full flow for unnecessary complexity/leftover migration code, verify
persistence/reload, competing session writes, cancellation and inspection of original
versus active history, and finalize current documentation/limitations. No new editing
API or UI expansion is approved. Use subagents for implementation and independent review.

Parent prerequisite check found JEV_SYSTEM_ONE_URL and TYPESAFE_API_KEY unset, with
no corresponding .env entries. No live System One + conversational-model run is
claimed. Use synthetic, non-sensitive fixtures for automated acceptance and document
an explicitly configured opt-in live smoke procedure. Keep live acceptance visibly
pending if the required endpoints are unavailable; do not turn skips into acceptance.

#### Step-7 implementation and automated evidence

The following is the initial worker evidence. Independent review subsequently **blocked**
this version on P1/P2; see the repair record below. It is not acceptance of the defective
continuation-reset boundary.

**IMPLEMENTATION_READY_FOR_REVIEW** — automated-ready, not final acceptance. No
live services were called, configured, started or authenticated for this work.

Fixes (narrowly within approved scope):

- `StorageFSMessageMaybeSuite` no longer calls private `messagesFromMaybe`. It tests
  chronological complete traversal through `loadSessionParcial` and an actual empty
  session for absent head. All four existing checks now compile/run, including missing
  head/ancestor warnings, strict failures, read-only behavior and malformed/absent refs.
  No production visibility change, storage relaxation or test exclusion was needed.
- Generic `SORequest` encoding retains the supplied state/Q encoders and normalizes
  only per-question discriminator metadata using existing `SOSchema.fields`. All
  Choice/Score/Noul types, criteria, instructions, `@jsonField` aliases, custom fields,
  state/model shape and optional Noul criteria are covered by structural full-envelope
  assertions. Map-backed `SystemOneRequest` encoding is unchanged. No macro derivation
  rewrite was needed; existing field metadata already suffices.
- Approved compatibility tightening: direct fixed-schema request encoding now requires
  `SOSchema[Q]`, not only `JsonEncoder[Q]`. Dynamic question objects should use
  `SystemOneRequest`. Custom encoders must emit exactly the schema IDs with object
  payloads and missing/matching hints. Missing/extra/duplicate question IDs, duplicate
  fields, conflicting hints and non-object shapes fail explicitly. Direct serialization
  throws `IllegalArgumentException`; typed `SOMProvider.evaluate` maps encoding failure
  to `ProtocolFailure` before transport. Supplied request decoders remain unchanged;
  this is not a new general strict protocol validator. Current docs and Scaladoc record
  this change, with compile/use tests rather than an unsafe arbitrary-Q fallback.
- Removed `SystemOneHistoryDecision`'s private encoder workaround; its question schema
  now normally derives `JsonCodec, SOSchema`. Typed provider and decision-agent tests
  record/decode the actual full envelope; existing helper policy/wire and integration
  tests continue to pass. No helper-specific serialization path remains.
- Added two deterministic Promise-gated complete-submission races (memory/FS): both
  submissions are alive on the same session, one rewrites/finishes while the other is
  suspended, then the second completes. They assert independent active histories and
  exact audits, ERROR publication conflict, competing pointer retention, unchanged
  original objects, all branches after reopen, full parents and lifecycle expiry.
- Inspected managed execution/journal, runner fences, standalone/managed replay,
  pair/reasoning validation, storage CAS and advisory preparation. No migration
  checkpoint API remains. The local writer `checkpoint` function is actual asynchronous
  object persistence/publication, not the removed full-RunContext migration adapter;
  it remains intentionally. No architecture refactor or unrelated TODO cleanup.
- Current docs explain global inventory versus published session/last acknowledged
  response/local context, graph depth/shared ancestry, log-only rewrite provenance and
  lack of arbitrary-head recovery. Added an explicitly consented synthetic live
  procedure using `JevHttpProvider.live`, `OAILlmProvider.live`, `OAIAgent.make`, the
  existing opt-in wrapper and ordinary `AgentRunner`/filesystem orchestration. It is
  not a framework, `/so` implementation or claim that a fake/KEEP/skip proves rebase.

Exact step-7 changed files (prior steps/user edits are preserved):

- `core/jvm/src/test/scala/fmgp/yallmo/orchestration/storage/StorageFSMessageMaybeSuite.scala`
- `core/jvm/src/test/scala/fmgp/yallmo/orchestration/ManagedHistorySubmitSuite.scala`
- `core/shared/src/main/scala/fmgp/yallmo/orchestration/SystemOneHistoryDecision.scala`
- `core/shared/src/test/scala/fmgp/yallmo/orchestration/SystemOneDecisionAgentSuite.scala`
- `model/shared/src/main/scala/fmgp/yallmo/model/som/SOMacros.scala`
- `model/shared/src/main/scala/fmgp/yallmo/model/som/SOMProvider.scala`
- `model/shared/src/test/scala/fmgp/yallmo/model/som/SORequestEncodingSuite.scala` (new)
- `docs/MENTAL_MODEL.md`
- `docs/PLANS.md`
- `docs/orchestration/MANAGED_HISTORY_AND_REBASE_PLAN.md`
- `docs/orchestration/MANAGED_HISTORY_ACCEPTANCE.md` (new)
- `docs/system_one/MANAGED_HISTORY_DECISIONS.md`
- `docs/system_one/System_One_Overview.md`

Validation (ONE semicolon-separated command string per sbt invocation; **no source
exclusion/filter, temporary settings or reload needed**):

1. Initial blocker command:
   `sbt ';model/testOnly *SORequestEncodingSuite *SystemOneModelsMacroSuite *SOMProviderSuite;modelJS/testOnly *SORequestEncodingSuite *SystemOneModelsMacroSuite *SOMProviderSuite;core/testOnly *StorageFSMessageMaybeSuite *SystemOneHistoryDecisionSuite *SystemOneDecisionAgentSuite'`.
   Final pre-format rerun: **19 model JVM + 19 model JS + 33 core passed**, including
   all four former-blocker tests. `SORequestEncodingSuite` has **14** new shared tests.
   Earlier exploratory invocations found new-given syntax and explicit schema-instance
   wiring errors; fixed before any successful result was recorded. Log:
   `/tmp/step7-blockers-final.log` (earlier successful 17/17/33 in `/tmp/step7-blockers.log`).
2. `sbt ';core/testOnly *StorageFSMessageMaybeSuite *ManagedHistorySubmitSuite *SystemOneHistoryDecisionSuite *SystemOneDecisionAgentSuite;coreJS/testOnly *SystemOneHistoryDecisionSuite *SystemOneDecisionAgentSuite'`
   passed **199 core JVM + 29 JS**; managed suite **166** checks. Log:
   `/tmp/step7-focused-core.log`.
3. Pre-format full run:
   `sbt ';model/testFull;modelJS/testFull;core/testFull;coreJS/testFull;server/testFull;server/compile;repl/compile;repl/Test/compile;repl/testFull'`.
   Passed **72 model JVM + 72 JS**, **664 core JVM / 17 skipped**, **80 core JS**;
   full server **170 passed / 2 failed / 1 skipped** stopped the command before later
   compile/REPL tasks. Two subsequent model tests (custom state encoding/duplicate
   schema aliases) passed in command 1 and the post-format full run below. Log:
   `/tmp/step7-preformat.log`.
4. `sbt ';server/compile;repl/compile;repl/Test/compile;repl/testFull'` separately
   passed compilation/test compilation; REPL **240 passed / 1 failed**. Log:
   `/tmp/step7-repl-preformat.log`.
5. AFTER tests, literal-path scoped formatting on exactly the seven step-7 Scala files:
   `sbt ';model/scalafmtOnly ../shared/src/main/scala/fmgp/yallmo/model/som/SOMacros.scala ../shared/src/main/scala/fmgp/yallmo/model/som/SOMProvider.scala;model/Test/scalafmtOnly ../shared/src/test/scala/fmgp/yallmo/model/som/SORequestEncodingSuite.scala;core/scalafmtOnly ../shared/src/main/scala/fmgp/yallmo/orchestration/SystemOneHistoryDecision.scala;core/Test/scalafmtOnly src/test/scala/fmgp/yallmo/orchestration/storage/StorageFSMessageMaybeSuite.scala src/test/scala/fmgp/yallmo/orchestration/ManagedHistorySubmitSuite.scala ../shared/src/test/scala/fmgp/yallmo/orchestration/SystemOneDecisionAgentSuite.scala'`.
   Passed, no unrelated Scala paths formatted. Log: `/tmp/step7-format.log`.
6. Post-format full run:
   `sbt ';model/testFull;modelJS/testFull;core/testFull;coreJS/testFull;server/testFull'`.
   **74 model JVM + 74 model JS passed; 664 core JVM passed / 17 skipped; 80 core JS
   passed; full server 170 passed / 2 failed / 1 skipped**. No managed-history,
   storage traversal, generic-wire or helper regression failed. Log:
   `/tmp/step7-postformat.log`.
7. `sbt ';server/compile;repl/compile;repl/Test/compile;server/testOnly *SessionSubmissionSuite *ServerSubmissionSuite;repl/testFull'`.
   Compile/test compile passed; affected server integration **34 passed**; full REPL
   **240 passed / 1 failed** again. Log: `/tmp/step7-repl-postformat.log`.
8. `git diff --check` passed. Cached binary diff SHA-256 stayed
   `7be764596bf7d8b8e9dafb1a2a02961f820e7f31b859a60d74618ebed58e4d22`.
   Existing index entries are intentionally still staged: **no new staging, reset,
   restore or commits**. `NOTES.md`, `tmp.json`, `plan-tool/` and unrelated user changes
   were untouched.

Qualified failures/skips (not hidden, removed or fixed out of scope):

- Full server `OrchestratorWebHostSuite.scala:33,100`: requests for fixture
  `/assets/test.js` return **404, expected 200** in packaged-assets and Origin tests.
  The existing fixture is under `server/src/test/resources/view-web/assets/test.js`;
  current `WebAssets` hosting uses `web-harness`. Those host/fixture sources are unchanged
  from HEAD, unrelated to managed history or typed question encoding. Both full runs
  reproduce these failures; affected submission integrations pass.
- Full REPL `ReplStartupSuite.scala:687`: selection log file has **3 lines, expected 2**.
  Existing `ApplicationRuntime.resolve` also logs a missing-agent ERROR, in addition
  to selection INFO/WARN. Relevant startup/logger/assertion sources are unchanged
  from HEAD. Both full runs reproduce this unrelated assertion; it was not weakened.
  No clean-baseline checkout was run, so no broader claim about baseline suite health.
- **17 core + 1 server skipped** checks require the explicitly opted-in
  `YALLMO_TEST_SCALA_CLI` real pinned-nightly/cross-JVM filesystem environment. These
  are existing assumptions, not managed-history acceptance or fake live success.
  All model/JS suites have zero skips. No provider/hardware/live test was claimed.

Open gates: fresh independent review, manual graph inspection and real synthetic
System One + conversational-provider acceptance remain pending. Required live
prerequisites were absent; no secret/config values were printed. Do not archive or
mark step 7 finally complete on automated fixture evidence alone. Existing durability,
queue/scan, trusted-refresh, complete-pair/reasoning, provenance and pointer-conflict
recovery limitations remain; no new product/session policy was introduced.

#### Step-7 independent review — P1/P2 repaired and rereviewed

Initial independent review returned **BLOCK**, not acceptance. The parent accepted:

- **P1:** `SessionSubmission.withCurrentContinuations` compared mutable message indices
  with a fixed pre-reset offset. After shortening managed rebase, newly generated
  reasoning could fall below that offset and be incorrectly omitted on the next turn.
- **P2:** public `Orchestrator.submit` Scaladoc incorrectly described active-history
  responses and mandatory preservation of the accepted prefix. Actual responses use
  append-only execution audit, and authorized managed rebase may replace the prefix.

Approved narrow repair (no lifecycle factory, editing framework or new session policy):

- Added `ManagedHistory.initialMessages`, the immutable accepted per-submission snapshot
  including the prompt. `OrchestratorLive` returns its original accepted chunk; runner
  views forward this read **without refreshing work fences**. Implementations of this
  trait must now provide that immutable origin; there is no mutable-read default.
  Test histories/delegates were updated accordingly. Existing source/revision/action
  fences, object journal, audit, CAS publication and expiry behavior are unchanged.
- Managed reset filtering derives excluded **ProviderContinuation values**, not summary
  text or mutable current indices. Replayed old values stay excluded even with changed
  summaries; new different continuation values survive shortening. The wrapper owns
  no mutable state, so a reusable AgentExecution/AgentInstance cannot share reset state
  across simultaneous or sequential submissions. Exclusion sets/ciphertext are never
  logged or serialized by this policy. Canonical archives are never filtered.
- Supervisor additionally approved fixing the normal server's persisted-in-memory
  offset map: `ServerApplication.Configured` now captures a stable exclusion set once
  at first selection, reset or agent switch and reuses it across later submissions.
  A package-local `SessionSubmission.submitExcludingContinuations` path carries that
  set; no public policy framework or protocol change. Existing raw-Int in-process
  compatibility entrypoints remain: the offset is interpreted against each submission's
  initial history, and callers must recalculate after a persisted rewrite. They do not
  promise historical cross-submission stability. Single-message `next` filtering is
  unchanged for that compatibility path; ordinary invocation coverage remains passing.
- Corrected `Orchestrator.submit` docs: newMessages is accepted prompt once plus actual
  generated appends from immutable execution audit, not the current active transcript;
  copies are not new execution. Ordinary appends preserve the active prefix, authorized
  managed rebase may replace it, and returned session is the last acknowledged pointer.
  Current architecture/acceptance docs explain origin/reset semantics and pending gates.
- Former FS-private-access and generic typed-wire fixes remain intact; no helper-local
  encoder workaround or source exclusion was reintroduced.

Repair regression evidence:

- New `SessionSubmissionSuite` test seeds ten-message histories with pre-reset reasoning,
  reuses **one configured execution** concurrently for A/B via Promise gates, then reuses
  it again for C. Each program shortens to the accepted prompt, actually calls the native
  recording provider, receives fresh reasoning plus a tool call, executes the tool once
  and makes a second provider turn. It asserts complete ordered request envelopes,
  fresh reasoning forwarded, replayed old continuation excluded despite changed summary,
  exact call/result order with no duplication, preserved original objects/parents and
  configured tool restrictions. A's fresh value is B's excluded value, detecting reset
  state leakage across simultaneous executions.
- Extended `ServerSubmissionSuite`'s real adapter/recording transport test: capture a
  reset policy, shorten through managed rebase, produce old replay plus fresh reasoning,
  then submit through the normal configured server again. The actual provider request
  contains only the fresh continuation. Switch/back/restart behavior, FS inventory and
  original preservation remain asserted.
- Extended both-backend managed tests to assert immutable origin after rebase/close,
  and that reading origin after rewrite does **not** refresh a stale model-work fence.
  Existing 166 managed checks and lifecycle/cancellation coverage still pass.

Exact repair changed files (all previous work/index entries preserved):

- `model/shared/src/main/scala/fmgp/yallmo/orchestration/ManagedHistory.scala`
- `model/shared/src/main/scala/fmgp/yallmo/orchestration/Orchestrator.scala`
- `core/jvm/src/main/scala/fmgp/yallmo/orchestration/OrchestratorLive.scala`
- `core/shared/src/main/scala/fmgp/yallmo/orchestration/AgentRunner.scala`
- `core/jvm/src/test/scala/fmgp/yallmo/orchestration/ManagedHistorySubmitSuite.scala`
- `core/shared/src/test/scala/fmgp/llm/orchestration/RunnerTestSupport.scala`
- `core/shared/src/test/scala/fmgp/yallmo/api/ExecutionBoundarySuite.scala`
- `core/shared/src/test/scala/fmgp/yallmo/orchestration/SystemOneHistoryDecisionSuite.scala`
- `server/src/main/scala/fmgp/yallmo/server/application/SessionSubmission.scala`
- `server/src/main/scala/fmgp/yallmo/server/application/ServerApplication.scala`
- `server/src/test/scala/fmgp/yallmo/server/application/SessionSubmissionSuite.scala`
- `server/src/test/scala/fmgp/yallmo/server/application/ServerSubmissionSuite.scala`
- `server/src/test/scala/fmgp/yallmo/server/application/AgentsInvokeSuite.scala`
- `docs/MENTAL_MODEL.md`
- `docs/orchestration/MANAGED_HISTORY_ACCEPTANCE.md`
- `docs/orchestration/MANAGED_HISTORY_AND_REBASE_PLAN.md`

Commands/results (each sbt invocation uses ONE semicolon-separated string; no source
exclusions, filters or temporary settings):

1. `sbt ';server/testOnly *SessionSubmissionSuite *ServerSubmissionSuite;core/testOnly *ManagedHistorySubmitSuite;model/testOnly *SORequestEncodingSuite *SystemOneModelsMacroSuite *SOMProviderSuite;core/testOnly *SystemOneHistoryDecisionSuite'`.
   Server **35 passed** (6 SessionSubmission + 29 ServerSubmission); the command then
   exposed a newly edited test indentation warning under Werror. An earlier exploratory
   run found a fixture stop-reason spelling error. Both fixture issues were corrected,
   not excluded. `/tmp/step7repair-focused.log` retains the server pass and indentation failure.
2. `sbt ';core/testOnly *ManagedHistorySubmitSuite;model/testOnly *SORequestEncodingSuite *SystemOneModelsMacroSuite *SOMProviderSuite;core/testOnly *SystemOneHistoryDecisionSuite'`.
   **166 managed + 19 model + 28 helper passed**. Log: `/tmp/step7repair-focused-core.log`.
3. `sbt ';model/testFull;modelJS/testFull;core/testFull;coreJS/testFull;server/testOnly *SessionSubmissionSuite *ServerSubmissionSuite *AgentsInvokeSuite;server/compile;repl/compile;repl/Test/compile'`.
   Passed before formatting, after formatting, and after the final Scaladoc-only edit:
   **74 model JVM + 74 model JS; 664 core JVM passed / 17 skipped; 80 core JS;
   51 affected server integration tests**. Server/REPL compile and REPL test compile
   passed. Logs: `/tmp/step7repair-preformat.log`, `/tmp/step7repair-postformat.log`,
   `/tmp/step7repair-final.log` (final result).
4. AFTER tests, literal-path formatting on exactly the thirteen changed Scala files:
   `sbt ';model/scalafmtOnly ../shared/src/main/scala/fmgp/yallmo/orchestration/ManagedHistory.scala ../shared/src/main/scala/fmgp/yallmo/orchestration/Orchestrator.scala;core/scalafmtOnly src/main/scala/fmgp/yallmo/orchestration/OrchestratorLive.scala ../shared/src/main/scala/fmgp/yallmo/orchestration/AgentRunner.scala;core/Test/scalafmtOnly src/test/scala/fmgp/yallmo/orchestration/ManagedHistorySubmitSuite.scala ../shared/src/test/scala/fmgp/llm/orchestration/RunnerTestSupport.scala ../shared/src/test/scala/fmgp/yallmo/api/ExecutionBoundarySuite.scala ../shared/src/test/scala/fmgp/yallmo/orchestration/SystemOneHistoryDecisionSuite.scala;server/scalafmtOnly src/main/scala/fmgp/yallmo/server/application/SessionSubmission.scala src/main/scala/fmgp/yallmo/server/application/ServerApplication.scala;server/Test/scalafmtOnly src/test/scala/fmgp/yallmo/server/application/SessionSubmissionSuite.scala src/test/scala/fmgp/yallmo/server/application/ServerSubmissionSuite.scala src/test/scala/fmgp/yallmo/server/application/AgentsInvokeSuite.scala'`.
   Passed (`/tmp/step7repair-format.log`). After a final pointer-description Scaladoc
   clarification, `sbt ';model/scalafmtOnly ../shared/src/main/scala/fmgp/yallmo/orchestration/Orchestrator.scala'`
   passed too (`/tmp/step7repair-format-doc.log`), followed by command 3 again.
5. `sbt ';server/testFull'`: **171 passed / 2 failed / 1 skipped**.
   Same previously observed `OrchestratorWebHostSuite.scala:33,100` fixture-asset
   assertions (404 versus 200). Log: `/tmp/step7repair-server-full.log`.
6. `sbt ';repl/testFull'`: **240 passed / 1 failed**.
   Same previously observed `ReplStartupSuite.scala:687` log-count assertion
   (3 versus 2). Log: `/tmp/step7repair-repl-full.log`.
   These are qualified full-suite results, not unqualified green or definitive
   clean-baseline proof. Unchanged implicated sources alone do NOT prove a failure
   pre-existed all work. No clean-baseline checkout/run was performed; no unrelated
   fixture/logger/voice behavior or assertion was changed.
7. `git diff --check` passed. Cached binary diff SHA-256 remains
   `7be764596bf7d8b8e9dafb1a2a02961f820e7f31b859a60d74618ebed58e4d22`, identical
   to repair start. Existing staged entries remain staged; no staging/reset/restore/
   commits issued. `NOTES.md`, `tmp.json`, `plan-tool/` and unrelated edits untouched.

The **17 core + 1 server skips** remain explicitly opted-in real Scala CLI/nightly
checks, not provider/live acceptance. Synthetic local fixtures only were used;
no external live calls, authentication, service startup or credential values.

Fresh independent rereview found no blocking findings, verified both P1/P2 repairs,
reviewed the full integration seams and confirmed that both original validation
blockers are fixed. The parent inspected final code/docs and reran, WITHOUT exclusions:
`sbt ';model/testFull;modelJS/testFull;core/testFull;coreJS/testFull;server/testOnly *SessionSubmissionSuite *ServerSubmissionSuite *AgentsInvokeSuite;server/compile;repl/compile;repl/Test/compile'`.
Results: **74 model JVM + 74 model JS passed; 664 core JVM passed / 17 skipped;
80 core JS passed; 51 server integration tests passed**. All four formerly excluded
FS tests ran. Server/REPL compilation and REPL test compilation passed.
Log: `/tmp/yallmo-step7-parent-validation.log`. `git diff --check` passed.

Implementation/review is finished; no more implementation work is running. Step 7
remains active for manual graph inspection, explicitly opted-in live System One plus
conversational-provider smoke, and disposition of the qualified broader server/REPL
failures recorded above. Live prerequisites remain unavailable; no fake/skip/KEEP
result is presented as live rebase acceptance. No commits or index changes were made
by the parent or subagents.

### Follow-up — message-head guards (implemented and reviewed)

User prefers captured message-head identity instead of expectedRevision/Long rewrite
counters. Remove that extra runtime identity and guard active application atomically
using existing MessageId ancestry instead. Restoring the exact original head (and
therefore identical immutable ancestry) is valid, not a reason to reject work merely
because a rewrite happened in between. A genuinely different local head must not
silently receive stale model/tool/extension output; retain stale execution on its
original parent and stop continuation as appropriate. Session pointer changes remain
independent of immutable rebase and active-history validity.

Keep normal multi-action/tool loops correct: guards advance only after their own
accepted appends, not by adopting an unrelated latest head. Preserve async saves,
originals, audit, reasoning/tool validation, reset-origin handling and capability
lifetime. No new wrapper/token/version types or wider policy changes. Use subagents
for implementation and independent review; historical revision-counter evidence above
remains a record of earlier behavior, not the new contract.

#### Head-identity implementation outcome — independently reviewed

`IMPLEMENTATION_READY_FOR_REVIEW`. This is a bounded follow-up to reviewed steps
1–7, not a new editing/session policy. Independent review of this follow-up is
pending. Historical revision-counter/ABA rejection evidence above remains historical.
No live provider request or manual graph inspection was performed.

Implementation:

- Removed `expectedRevision`, `historyRevision`, `startRevision`, `decisionRevision`,
  all Long history/work snapshots and the redundant private `rebaseAt` API.
  `snapshot` returns only immutable `Chunk[Message]` and does not refresh model work.
- `appendAt(payloads, expectedParent: Option[MessageId])` compares the captured
  active head under the existing submission-local semaphore. It returns its own
  resulting head from the same accepted atomic append, including empty batches and
  `None` history. On mismatch, produced payloads are journaled/audited under their
  original parent, persisted without activating them, and continuation fails.
- Runner head guards advance only through their own accepted writes: extension
  suffix → model work; assistant/calls → action batch; each result → next queued
  action. Checks precede model/action effects; an unlocked read never promotes a
  queued decision to an unrelated head. No effects are retried.
- The advisor rechecks the captured source head, then calls public `rebase`, whose
  existing gated source-head check closes the application gap. Exact restoration
  A → B → A is accepted because the immutable ancestry is identical. Ordinary local
  appends invalidate old work; session pointer-only competitors do not.
- Immutable/non-refreshing `initialMessages`, explicit trusted program request
  refresh, stable continuation exclusions, managed/standalone rebase semantics,
  tool-pair/reasoning checks, lifecycle, audit/journal/barriers and independent
  best-effort ERROR/CAS publication are preserved.

Changed follow-up files (prior user/staged work remains intact):

- `model/shared/src/main/scala/fmgp/yallmo/orchestration/ManagedHistory.scala`
- `core/jvm/src/main/scala/fmgp/yallmo/orchestration/OrchestratorLive.scala`
- `core/shared/src/main/scala/fmgp/yallmo/orchestration/AgentRunner.scala`
- `core/shared/src/main/scala/fmgp/yallmo/orchestration/SystemOneHistoryDecision.scala`
- `core/jvm/src/test/scala/fmgp/yallmo/orchestration/ManagedHistorySubmitSuite.scala`
- `core/shared/src/test/scala/fmgp/llm/orchestration/RunnerTestSupport.scala`
- `core/shared/src/test/scala/fmgp/yallmo/api/ExecutionBoundarySuite.scala`
- `core/shared/src/test/scala/fmgp/yallmo/orchestration/SystemOneHistoryDecisionSuite.scala`
- `docs/MENTAL_MODEL.md`, this plan, `MANAGED_HISTORY_ACCEPTANCE.md`,
  `docs/system_one/MANAGED_HISTORY_DECISIONS.md`, `docs/system_one/System_One_Overview.md`

Automated evidence:

1. Focused `sbt 'core/testOnly *ManagedHistorySubmitSuite *SystemOneHistoryDecisionSuite *ExecutionBoundarySuite *WorkflowSuite'`.
   Initial run found two old fixtures that intentionally appended during a tool
   and expected continuation. That old expectation contradicts the new contract;
   the normal two-tool fixture now contains only runner-owned writes, and separate
   Promise tests assert concurrent-append rejection/original-parent retention.
   Focused reruns passed. The final pre-format focused invocation was combined in
   one semicolon string with command 2 below; log `/tmp/head-preformat-final.log`.
2. Full required validation, no source exclusions/settings:
   `sbt ';model/testFull;modelJS/testFull;core/testFull;coreJS/testFull;server/testOnly *SessionSubmissionSuite *ServerSubmissionSuite *AgentsInvokeSuite;server/compile;server/Test/compile;repl/compile;repl/Test/compile'`.
   Passed before and after formatting (`/tmp/head-preformat-final.log`,
   `/tmp/head-postformat.log`): **model JVM 74, model JS 74, core JVM 728 passed /
   17 environment skips, core JS 80, affected server 51 passed**. Managed suite:
   **230 passed** on memory+FS; helper **28 passed** on each platform; affected
   server suites **6 SessionSubmission + 29 ServerSubmission + 16 AgentsInvoke**.
   Server/REPL compile and test compile passed. `testFull` forces complete model/core
   runs; no exclusions or selective replacement of their full runs were used.
3. AFTER tests, literal-path formatting on only the eight changed Scala files:
   `sbt ';model/scalafmtOnly ../shared/src/main/scala/fmgp/yallmo/orchestration/ManagedHistory.scala;core/scalafmtOnly src/main/scala/fmgp/yallmo/orchestration/OrchestratorLive.scala ../shared/src/main/scala/fmgp/yallmo/orchestration/AgentRunner.scala ../shared/src/main/scala/fmgp/yallmo/orchestration/SystemOneHistoryDecision.scala;core/Test/scalafmtOnly src/test/scala/fmgp/yallmo/orchestration/ManagedHistorySubmitSuite.scala ../shared/src/test/scala/fmgp/llm/orchestration/RunnerTestSupport.scala ../shared/src/test/scala/fmgp/yallmo/api/ExecutionBoundarySuite.scala ../shared/src/test/scala/fmgp/yallmo/orchestration/SystemOneHistoryDecisionSuite.scala'`.
   Passed (`/tmp/head-format.log`), followed by full command 2 again.
4. `git diff --check` passed. Index entries captured via `git ls-files -s` before
   and after are identical (`cmp /tmp/head-index-before /tmp/head-index-final`).
   No commits, staging, reset/restore, unrelated file edits or external calls.

New/updated deterministic tests assert advisory different-head rejection both during
latency and after recheck, positive exact restoration at both boundaries, model and
extension restoration, post-extension atomic-append/read gap, RAG queued-action
prevention before first/between actions and original-parent in-flight output, normal
multi-RAG/tool writes, local concurrent append mismatch, session pointer independence,
root/empty/no-op append results and late closed-handle rejection. Existing retry →
rebase → continue, effect counts, cancellation/reset and generic System One encoding
regressions remain included in full runs.

Limitations remain unchanged: trusted explicit request refresh, inventory scans,
unbounded writer queue, delayed drains under stuck storage, log-only provenance,
partial immutable prefixes and no crash/exactly-once guarantee. The broader server
asset failures (2) and REPL log-count failure (1) recorded above were not rerun or
repaired by this follow-up; there is no verified clean baseline for them and no
whole-repository-green claim. Manual/live acceptance remains pending.

Final audit-evidence refinement (same scope/counts): the existing Promise-gated
stale-tool test now uses a trusted test execution to observe the runner's stopped
error and return without resuming it, exposing `SessionTurn.newMessages`. It asserts
that the completed stale tool output is included once in the execution audit, has
its original immutable parent, and is absent from active replacement context. Effects
and model-turn counts remain one. Focused managed tests plus full command 2 passed
(`/tmp/head-audit-preformat.log`); then literal-path formatting of only
`core/Test/scalafmtOnly src/test/scala/fmgp/yallmo/orchestration/ManagedHistorySubmitSuite.scala`
passed (`/tmp/head-audit-format.log`), followed by full command 2 again
(`/tmp/head-audit-postformat.log`), with the same **74/74/728 (+17 skips)/80/51**
results and **230 managed tests**. `git diff --check` and index equality passed again.
Fresh independent review found no blocking issues and verified positive exact-restoration behavior, atomic own-append head advancement, stale-result parent/audit retention and session-pointer independence. The parent inspected the final API/implementation and reran full core JVM/JS plus affected server integrations without exclusions: **728 core JVM passed / 17 environment skips, 80 core JS passed, 51 server integration tests passed**. Server/REPL compilation and REPL test compilation passed. Log: `/tmp/yallmo-head-guards-parent-validation.log`. `git diff --check` passed. This simplification is complete; no broader-suite or live/manual acceptance is claimed.
