# Scalamode JVM / Scala.js backend parity

Status: implemented in the working tree and accepted by independent code-level re-review. The blocking transport-ordering finding is resolved. Deployment/manual acceptance remains pending; full JVM core is not green.

This document retains the approved design/acceptance contract and records delivery evidence below. It is not a
claim that every required validation item or the external review gate has passed.

## Goal and acceptance contract

Selecting JVM or Scala.js / Node changes compilation and execution, not the selected agent's Scalamode function.
Both backends must retain:

- The same code-only arguments, advertised API, bound services, rooted filesystem and read/write permissions.
- The same argument validation, approval boundary, configured timeout and output budget.
- The same value/effect evaluation semantics, typed service errors, streaming and cancellation contract.
- Scoped single-use RPC authority, credential redaction, cleanup and fresh evaluation processes.
- Explicit opt-in for custom functions; never identify replaceable implementations by name/schema alone.

No silent service stripping, reduced pure-only mode, direct Node filesystem replacement or JVM fallback.
Pure snippets remain supported, but are an ordinary service-free execution, not an alternate playground contract.
Browser snippet execution is not part of this change.

Parity does not mean identical compilation diagnostics, timings, JVM/JS platform libraries or arbitrary value rendering.
Custom compiled helpers need reviewed artifacts for the selected platform. Missing platform products produce an explicit
unsupported-configuration error before execution; they must not silently change the function's capabilities.

## Delivered seams

| Location | Implementation |
| --- | --- |
| `model/.../ScalamodeFunction.scala` | Explicit `withExecutor` and default-binding integration, forwarded through environment and approval wrappers. |
| `scalacode-api/.../ScalamodePlan.scala` | Immutable original settings and optional exact descriptor; default wiring and rebinding invoke one operation. |
| `core/jvm/.../ScalaCodeExecutors.scala` | JVM host factory with JVM engine and scoped Node provisioning; no second production Node engine. |
| `core/jvm/.../ScalamodeInvocation.scala`, `ScalamodeProcess.scala`, `ScalamodeProgram.scala` | Shared validation, service authority/deadline, bounded child lifecycle, source generation and diagnostic/result handling. |
| `core/jvm/.../ScalaJsCompiler.scala` | Retained compiler/linker, original queue-inclusive deadline, invocation-scoped RPC and fresh Node children. |
| `rpc/shared`, `rpc/jvm`, `rpc/js` | Shared client protocol/state and token parsing; JVM/JS adapters; host-only ticket registry and dispatcher. |
| `scalacode-api/.../ScalamodeService.scala` | Portable metadata with explicit reviewed per-platform products; JVM transport dependency pins moved to the runner. |
| `server/.../ScalaJsRuntime.scala` | Startup probe and service-preserving sanitized failure boundary, no JVM fallback. |
| `scalamode-test-fixtures/src/main/scala` | Unpublished `CrossType.Pure` reviewed helper/runner products shared by JVM and JS, independent of API suites and framework dependencies. |

Paths abbreviate existing `src/main/scala/fmgp/...` package directories. Removed source/API names are listed only in
the migration table below, not as live source paths.

## Approved design and implementation contract

```text
selected agent's Scalamode function
  validation + approval + original configuration + bound service descriptor
    request-local executor selection
      common execution preparation and result handling
        JVM compiler/runtime | Scala.js compiler/linker + fresh Node runtime
          common RPC client protocol
            zio-http socket adapter | native JS WebSocket adapter
              same host dispatchers and rooted capabilities
```

### 1. One tool operation, replaceable executor

Replace the pure-only `ManualScalamode` hook with explicit executor rebinding on integrated Scalamode functions.
The implemented hook is `withExecutor(executor)`, returning a rebound function or no supported integration.
The rebound function keeps the same definition, service descriptor and configuration; only the execution engine changes.
Normal execution and backend-selected execution invoke the same tool operation.

Approval and environment wrappers must forward rebinding structurally and rewrap the resulting function. Custom opaque
closures remain opaque. Custom integrated functions may explicitly opt in; do not reconstruct functions from inspection
metadata or capture a broader filesystem capability.

Capture an immutable `ScalamodePlan` in the API module: original limits/dependency grants plus optional services.
Replace `bindDefaultScalamode(replacementFunction)` with a plan-preserving service-binding transformation; default binding
eligibility remains explicit and idempotent. `ScalaTool.withServices` must create an integrated Scalamode function, not
an opaque `AgentFunction`, so reviewed service tools can explicitly support backend substitution too.

Make the original configured tool limits authoritative for both normal service execution and alternate execution.
Do not preserve today's accidental distinction between original pure-tool limits and replacement wiring defaults.
Keep the service-aware executor capability in the API module rather than introducing a model-to-API dependency cycle.
Keep `ScalaCodeExecutorServices` for the current module boundary/custom base executors; remove production wrappers that
erase it. Unsupported service execution must fail closed. Moving it into the model is not necessary for parity.

### 2. Separate shared execution mechanics from backend compilation

Keep `ScalaCodeExecutor` as the public host contract. Rename the concrete factory `ScalaCodeExecutorJVM` to
`ScalaCodeExecutors`: both runtime choices are hosted by the JVM application.
Extract only meaningful internal units:

- `ScalamodeProgram`: generated pure/service sources and submitted-code positions.
- `ScalamodeInvocation`: validation, scoped service preparation, deadline/output budget, execution and result assembly.
- `ScalamodeProcess`: bounded child processes, injection-variable filtering, descendant cleanup and log collection.
- Backend compiler implementations for JVM and Scala.js; Node owns retained compiler/linker state, not tool semantics.

Keep diagnostics/redaction in a single implementation used by both backends. Share log record encoding while leaving
platform file writes and execution markers in platform-specific runtime code.

Keep the retained Scala.js compiler/linker and fresh Node evaluation process. Do not retain submitted evaluation state.
Remove the separate non-incremental Node production route once its useful tests cover the retained implementation;
do not keep two production Node engines as compatibility wrappers.

A Node execution must hold invocation-scoped RPC registration through child exit and use the same stdin-only bootstrap
channel as JVM. Reused compiler workspaces must never store tickets, endpoints, dispatchers or runtime connections.
The combined deadline includes queue wait, compilation, linking, bootstrap and evaluation. Compilation caching is an
optimization, not a second set of service semantics.

### 3. Share the RPC client, not the HTTP library

Move client request correlation, pending requests, stream state, limits and protocol validation into `rpc/shared`.
Keep the host server and ticket registry on JVM. Add a small socket abstraction for text/binary send, ordered incoming
messages and scoped close; do not introduce a general networking framework.

The JVM adapter uses zio-http. The JS adapter uses the standard native `WebSocket` API with a small Scala.js facade;
no zio-http JS dependency and no npm WebSocket dependency. Require Node 22.4+ (unflagged stable native WebSocket), and
probe the actual child runtime during provisioning rather than only checking `node --version`.

For binary RPC, set `binaryType = "arraybuffer"`, serialize message delivery, preserve byte/chunk bounds and stop-and-wait
pulls, fail all pending work on disconnect, and cancel/close streams on scope exit. Bound the callback-to-ZIO queue and
outbound `bufferedAmount`; close on overflow rather than buffering without limit. Decode wire request IDs with Scala
`Long`, never JavaScript `Number`. Existing unary cancellation removes the local waiter; do not claim it cancels remote
unary work more strongly than the current JVM contract.

Native WebSocket delivers complete messages rather than raw frames and cannot enforce Netty's decoder-level bound
before allocating a message. Enforce shared message byte limits before parsing and retain server-side frame limits,
but do not claim identical malicious-server memory protection or fragmentation visibility. This is an unavoidable
native-API limitation, not a reason to weaken host permissions or output/deadline enforcement.

#### Authentication decision

Use one subprotocol-based handshake for both clients:

- Offer `yallmo.rpc.v2` plus `yallmo.ticket.<43-character-ticket>`.
- Validate exactly one expected protocol and ticket; reject ambiguous/duplicate/conflicting credentials.
- Redeem the existing single-use scoped ticket before upgrade, retaining expiry/revocation/Origin checks.
- Negotiate/echo only `yallmo.rpc.v2`, never the ticket; redact request protocol headers wherever logged.
- Remove the old Authorization credential path rather than permanently supporting two authentication mechanisms.

Do not use URL tickets, weaken Origin checks, or add a nonstandard WebSocket implementation. This changes the internal
RPC handshake, so update JVM and JS together and document it for external RPC consumers. Prove zio-http 3.11.6
subprotocol negotiation in a focused integration test before the full refactor.

Native error events do not reliably expose a rejected upgrade's HTTP status. Therefore normalize rejected-upgrade
errors on **both** clients to `Disconnected` (or `TimedOut` when the handshake deadline expires), rather than claiming
JS can reproduce today's JVM-specific 401-to-`Unauthorized` mapping. Local malformed-token parsing remains
`Unauthorized`; domain service errors are unchanged. This is an explicit diagnostic change to the existing JVM
transport. If preserving exact 401 diagnostics is required, revisit authentication before implementation: a shared
post-upgrade authentication/acknowledgement exchange would need bounded unauthenticated connection lifetimes and no
service dispatch before authentication. Do not add that second protocol alongside the recommended one.

Move token parsing/redacted representation to shared code; secure ticket generation, digesting and registry storage
remain host-only. Do not move host authority into the JS client artifact just to reuse parsing.

### 4. Portable bindings, platform-owned artifacts

Keep parameter/type/runner names, constants, documentation and typed error metadata portable.
Move transport dependencies out of default service metadata: zio-http and JVM security pins belong to the JVM transport
runner's reviewed dependency set, not every service binding.

Resolve reviewed service artifacts per platform. Built-in hello/filesystem/native-read contracts already have shared
sources. Custom helpers require an explicit reviewed JVM/Scala.js product mapping; JVM `Class` anchors alone cannot
locate Scala.js IR. Preserve narrow snippet classpaths and reject application/core/server classpath leakage.

Keep arbitrary dependency grants out of model arguments. Translate only trusted cross-platform coordinates, and reject
JVM-only dependencies clearly rather than pretending arbitrary JVM libraries work under Scala.js.

### 5. Naming and retirement

| Existing | Proposed |
| --- | --- |
| `ScalaCodeExecutorJVM` | `ScalaCodeExecutors` (host factory; compiler/runtime implementations internal) |
| `ScalaCodeExecutorNodeIncremental` | Internal `ScalaJsCompiler` / retained linker session |
| `ManualScalamode` / `manualScalamode` | Executor rebinding hook; remove the separate pure-only operation |
| `ManualJsScalamode` | `ScalaJsRuntime` (provisioning and service-preserving failure boundary) |
| `purePlayground` | Remove; backend selection is not a service policy |

Keep public tool name `scalamode`, argument schema, protocol backend identifiers and `ScalaCodeExecutor` stable.
Update internal source references directly rather than leaving deprecated forwarding objects. Call out source/API
breakage for users of the removed host integration hooks; this is not a wire-level tool schema change.

## Approved delivery sequence

1. Characterize current guards, configurations, service selection, diagnostics and cleanup. Add parity expectations
   that fail for the intended reasons; do not weaken permission tests.
2. Extract shared execution mechanics and RPC client state without changing behavior. Keep JVM suites green.
3. Implement native JS socket/authentication/bootstrap and standalone client-to-JVM-host integration tests, including
   binary streams. Resolve authentication/error-mapping design before connecting it to tools.
4. Enable bound-service execution in the retained Node engine, using reviewed platform artifacts and scoped bootstrap.
5. Replace pure-only switching with executor rebinding; preserve services through server wrappers and update UI text.
6. Delete superseded routes/hooks, update documentation and run the full parity/security matrix.

These are reviewable implementation steps, not permanent feature flags or parallel public APIs.

## Required validation

- Run common tool/contract/runtime tests on JVM and JS; test shared RPC state independently of either transport.
- Run real pinned compiler/child integration against both backends, not only fake process scripts.
- Exercise `hello`, pure values/effects, typed failures, read-only/write-only/read-write filesystem views and exact native
  read/list delegation (including partial grants and transformed tool text).
- Verify configured limits and approval denial before every backend invocation, including through environment wrappers.
- Verify private-root precedence, permission denial, path traversal rejection, and rejection of wider API views.
- Exercise binary reads, non-ASCII UTF-8, large files, lazy streams, early cancellation, disconnect and finalizers.
- Verify expired/replayed/revoked tickets, invalid/duplicate credentials, handshake deadlines, Origin policy and no
  credential leakage through source, argv, URLs, compiler workspace, logs or truncated diagnostics.
- Exercise output/log truncation, queue wait, deadline exhaustion, child termination, worker rotation, stale-program
  rejection and consecutive/concurrent agents with no cross-invocation authority or evaluation-state leakage.
- Verify custom reviewed cross-published service artifacts work; unsupported platform dependencies fail explicitly.
- Update existing tests that intentionally enforce pure-only JS behavior rather than leaving contradictory contracts.

## Review evidence

Two independent read-only subagent reviews covered RPC/native transport and execution/wiring respectively.
A local Node v26.13.0 smoke probe confirmed `typeof WebSocket === "function"`; an HTTP upgrade probe with synthetic
subprotocols confirmed the native client sends them, with no Authorization header and no Origin header in this setup.
This is not evidence for the minimum supported Node release, zio-http protocol negotiation or end-to-end service parity.

### Recovery checkpoint (2026-11-19)

The implementation retry preserved all partial work and user `NOTES.md` edits. It diagnosed the JS validation stall
before any suite started: filtering `scalaCodeApi / Test / packageBin / mappings` to fixture-only entries removed
suite IR from sbt 2's linked test artifact. The linked `main.js` had no `ScalaToolSuite`. This was a build artifact
problem, not a hanging test body. Removing the filter restored all five JS suites. The reviewed compiled helpers
now live in the separate unpublished `scalamodeTestFixtures` crossProject, used only by core tests. An inspection
test that relied on replacing an opaque default-looking function was corrected to opt in through `ScalaTool`.
A regression test also verifies that a pure JVM invocation's deadline starts when its effect runs, not at construction.

Use explicit `testOnly *` for whole-suite evidence: sbt 2's `test` currently invokes `testQuick`, which can report
**zero tests** for unchanged suites. Earlier `/tmp/yallmo-parity-wide-modules.log` contains such zero-test API/RPC
entries; they are not whole-module acceptance evidence. Fresh explicit runs are recorded below.

- `sbt ';scalaCodeApi/testOnly *;rpc/testOnly *;rpcJS/testOnly *;coreJS/testOnly *;webHarness/testOnly *;server/compile'`:
  **43 API JVM, 87 RPC JVM, 34 RPC JS, 115 core JS, 179 web tests passed**, zero failures/skips. Native JS RPC tests
  include a real Node-to-JVM host and binary streams. Log: `/tmp/yallmo-parity-recovery-wide.log`.
- `sbt 'scalaCodeApiJS/test'`: **21 passed**, all five suites discovered, no failures/skips after the fix.
  API/core JVM and JS, web and server test compilation also passed. Log: `/tmp/yallmo-parity-recovery-build.log`.
- Prior real-child evidence: `/tmp/yallmo-parity-native-final.log` **11 passed**, including services, typed failures,
  binary streaming, cancellation/finalizers and 64-call worker rotation; `/tmp/yallmo-parity-jvm-compiler.log`
  **69 passed**. Recovery reruns against the separated fixture artifact are recorded in the final checkpoint below.
- The earlier broad JVM core run is **not green**: `/tmp/yallmo-parity-core-wide.log` reports **782/792 passed,
  10 failed**. Seven failures are SDLC checkpoint/move errors (`Cross-filesystem moves are not supported`;
  six `SdlcSafetySuite`, one `SdlcToolsSuite`) outside this change. The isolated recovery command
  `sbt 'core/testOnly *SdlcSafetySuite *SdlcToolsSuite'` reproduced **22 passed / 7 failed**; log:
  `/tmp/yallmo-parity-recovery-sdlc.log`. A direct Java 25 probe on this overlay filesystem reports equal printed
  source/parent stores (`/ (overlay)`) but `FileStore.equals` is false, even though a same-directory `Files.move`
  succeeds. The existing `filesystem/.../FileSystem.scala` line 127 store-equality guard therefore rejects these
  checkpoint moves in this environment. It was not weakened as part of backend parity. Three Scalamode assertions were corrected and
  later passed narrow pinned-compiler validation; do not represent that as a green broad core run.

Only proven task-owned dangling validation processes were stopped (old sbt 422087 and its five stalled Node runners;
then the isolated pre-fix probe daemon/runners). User server 405247, user sbt 511 and Playwright were not stopped.

### Final automated checkpoint

- Real pinned offline compiler/child recovery run (`core/testOnly *ScalaJsServiceParitySuite *ScalaCodeExecutorNodeSuite
  *ScalaCodeExecutorSuite *ScalamodeServiceSuite *FilesystemServiceCompilerSuite *ScalamodeRebindingSuite
  *ScalamodeProcessSuite`, with the explicit test environment shown below): **89 passed, zero skipped**. This includes
  service identity/original limits, approval denial and approval-once, native dispatch, custom reviewed helper/RPC
  products, pure lazy deadlines, UTF-8/binary streams, cancellation/deadline finalizers and retained-worker rotation.
  Full `server/testOnly *`: **248 passed / 10 skipped**, zero failures. Log: `/tmp/yallmo-parity-recovery-integration.log`.
- After formatting only changed Scala/build files, explicit full runs passed **93 model JVM, 93 model JS, 43 API JVM,
  21 API JS, 115 core JS and 179 web tests**. Server wiring/provisioning subset: **53 passed / 4 skipped**.
  Log: `/tmp/yallmo-parity-recovery-final.log`. Skipped server tests are opt-in real-runtime cases requiring their
  own fixture environment; passing core actual-child tests do not turn those server skips into executed tests.
- Formatting: `cs launch org.scalameta:scalafmt-cli_2.13:3.11.4 -- --non-interactive <changed Scala/build paths>`.
  Exact list: `/tmp/yallmo-parity-recovery-format-files.txt`; formatter log: `/tmp/yallmo-parity-recovery-format.log`.
  `git diff --check -- . ':!NOTES.md'` passes. User `NOTES.md` whitespace was preserved rather than reformatted.
- Reviewed custom JS fixture JAR contains 25 entries under only the fixture package/manifest/directories, with IR and
  no suites/framework classes. It is not included in normal server runtime dependencies or bundled reviewed products.

Reproduce the real child tests using the matching service-user cache and actual executable paths:

```bash
sbt ';scalamodeTestFixturesJS/packageBin;set coreJVM / Test / envVars ++= Map("YALLMO_TEST_SCALA_CLI" -> "/usr/local/bin/scala-cli", "YALLMO_TEST_NODE" -> "/usr/local/bin/node", "YALLMO_TEST_NODE_ARTIFACT_ROOT" -> "/Users/fabio/workspace/yallmo", "YALLMO_TEST_JS_SERVICE_ARTIFACT" -> "/Users/fabio/workspace/yallmo/target/out/sjs1/scala-3.10.1/yallmo-scalamode-test-fixtures/yallmo-scalamode-test-fixtures_sjs1_3-2.0.0-SNAPSHOT.jar");core/testOnly *ScalaJsServiceParitySuite *ScalaCodeExecutorNodeSuite *ScalaCodeExecutorSuite *ScalamodeServiceSuite *FilesystemServiceCompilerSuite *ScalamodeRebindingSuite *ScalamodeProcessSuite'
```

The final native-dispatch extension explicitly executes transformed list-only delegation and denies its absent read
view, alongside the read-only and consecutive-agent checks. Rerun: **4 Node service parity tests passed**, plus
**36 protocol JVM and 36 protocol JS tests passed** after replacing a stale pure-only reason fixture. Log:
`/tmp/yallmo-parity-recovery-native-list.log`. The same command inspected server runtime dependencyClasspath;
the unpublished fixture module is absent. Final scalafmt check passed for all 72 changed Scala/build files.

The absolute paths above identify this validation environment, not configurable production RPC arguments. Adapt them
to your checkout. That recovery run used **Node v26.13.0**; the minimum supported runtime remains 22.4+.
The independent-review follow-up below additionally executes Node 22.4.0.

### Independent-review follow-up (2026-11-19)

The read-only review blocked acceptance because normal remote Close could discard or overtake already-accepted
responses in both socket adapters. The follow-up keeps remote termination ordered behind received text/binary
messages; explicit local aborts, overflow and protocol violations remain fail-fast. Deterministic native bridge
regressions and a shared real-adapter reply/binary-chunk/end/domain-failure-then-close test exercise this distinction.
No selected service, filesystem guard, approval, permission or tool-limit behavior was changed.

A real **Node v22.4.0** run also exposed synchronous `onerror` recursion when closing a rejected upgrade. The native
adapter now detaches that callback before closing. The original crash is retained in
`/tmp/yallmo-parity-review-node22-route.log`; a subsequent new-test fixture used a noncanonical ticket and failed
locally with Unauthorized (`/tmp/yallmo-parity-review-node22-route-fixed.log`), then was corrected to a valid unknown
ticket. These failed attempts are not passing validation evidence.

Final follow-up evidence (all commands use explicit `testOnly`, not cached `testQuick`):

- Full RPC JVM: **88 passed, zero skipped**. Full RPC JS on **Node 22.4.0**: **41 passed, zero skipped**.
  The native suite now exercises actual-peer absent/unoffered protocol echoes, oversized text and binary messages,
  outbound `bufferedAmount` overflow across consecutive sends, rejected upgrades, and queued final replies before Close.
  Log: `/tmp/yallmo-parity-review-adversarial-empty-cache.log`; exact command:
  `/tmp/yallmo-parity-review-adversarial-command.sh`.
- Actual pinned compiler/service-child suites on **Node 22.4.0**: **11 passed, zero skipped**
  (`ScalaJsServiceParitySuite`, `ScalaCodeExecutorNodeSuite`), including binary reads, typed errors, native dispatch,
  cancellation and retained-worker rotation. The downloaded Linux arm64 runtime archive was checked against the
  official Node 22.4.0 SHA-256 manifest. Log: `/tmp/yallmo-parity-review-node22-route-final.log`; command:
  `/tmp/yallmo-parity-review-node22-route-command.sh`.
- `ProductionIncrementalRouteSuite` with `YALLMO_TEST_PRODUCTION_INCREMENTAL=1`: **5 passed / 1 skipped** on installed
  Node 26.13.0, including queue expiry/busy admission, runtime timeout, interrupted linking, forced link-publication
  failure, fresh-source recovery, application-owned child shutdown and 64-call rotation. The skipped empty-cache case
  was then run separately with `YALLMO_TEST_EXPECT_EMPTY_CACHE=1`, live cases disabled and a fresh per-process
  `COURSIER_CACHE`: **1 passed / 5 intentionally skipped**. All six distinct cases have therefore executed across
  the two environments, not six passes in either single run. Logs: `/tmp/yallmo-parity-review-node22-route-final.log`
  and `/tmp/yallmo-parity-review-adversarial-empty-cache.log`.
- Final full RPC JS on installed **Node 26.13.0: 41 passed**, core JS **115 passed**, API JS **21 passed**, all with
  zero skips. Server and web compilation passed. Log: `/tmp/yallmo-parity-review-final.log`; command:
  `/tmp/yallmo-parity-review-final-command.sh`.
- Scoped scalafmt was applied only to the seven Scala files touched by this follow-up. A check over all **74**
  changed/new Scala/build files passes (`/tmp/yallmo-parity-review-format-check.log`). Scoped `git diff --check`
  excludes user-owned `NOTES.md`, which remains untouched. No files were staged or committed. The task-owned idle
  sbt daemon was stopped after validation; existing user sbt/server processes were not stopped.

The earlier `/tmp/yallmo-parity-review-validation.log` has 88 passing JVM RPC tests followed by an invalid temporary
sbt JS-environment setting (missing `Def.uncached`); the corrected commands above supersede that failed setup.
No failing validation attempt was converted to a skip or counted as a passing run. The follow-up was submitted for
independent re-review rather than self-overriding the initial BLOCK verdict. No complete end-to-end selected-agent
root/approval/non-default-limit matrix, green full-core build or deployed/browser parity is claimed.

### Final code-level acceptance

The original independent reviewer re-inspected both transport fixes, deterministic and actual-peer regressions, and
minimum-Node/production-route evidence. Its updated verdict is **ACCEPT**, lifting the original ordering-regression
BLOCK; no new actionable correctness or security findings were reported. This accepts the code-level change, not the
remaining deployed/operator acceptance matrix.

Parent spot checks after the final fixes also passed:

- `sbt 'core/testOnly *ScalamodeRebindingSuite'`: **5 passed**; `/tmp/yallmo-parity-parent-check.log`.
- `sbt 'scalaCodeApiJS/testOnly *ScalamodeSharedSuite'`: **7 passed**; `/tmp/yallmo-parity-parent-js-check.log`.
- `git diff --check -- . ':!NOTES.md'`: passed. No files were staged or committed; user `NOTES.md` edits remain untouched.

### Retired linker lab

Removed the four-file experimental incremental benchmark/lab/worker/test stack and core's direct test-only
`scalajs-linker` dependency. The isolated production `linker-worker` retains its dependency. The remaining
`ScalaCodeExecutorNodeBenchmark` measures the actual production executors and now documents Node workspace reuse.

Existing production tests cover retained-link identity, edited inputs, compile/link failure, recovery, safety and
scope cleanup. The lab's stalled-link deadline/cancellation check now runs against the bundled production backend,
including worker termination, workspace removal and fresh-worker recovery. Production-route checks also assert
execution timing/markers are absent after compilation failure.

Cleanup validation: core test compilation passed; actual Node/process suites **14 passed, zero skipped**;
`ProductionIncrementalRouteSuite` **6 passed / 1 skipped** (only the separate empty-cache case was skipped in this
provisioned-cache run). Logs: `/tmp/yallmo-scalamode-cleanup-core.log`, `/tmp/yallmo-scalamode-lab-cleanup.log`.

### Remaining acceptance and limits

- Independent code-level review passed; it does not certify deployment/manual acceptance.
- Full JVM core remains affected by the SDLC move/checkpoint failures above; no unrelated filesystem safety change was made.
- Real browser/operator UI and deployed-server acceptance are not established by Node model tests. Node 22.4.0
  native transport and actual-child tests now have explicit evidence above; that is not a deployed/runtime-platform matrix.
- A single actual server invocation combining selected-agent private-root precedence, permission filtering, approval
  and non-default limits on both runtimes is not established by the separate recording-executor and child tests.
- Native WebSocket receives complete messages before shared byte-limit enforcement, so malicious-server allocation
  protection and fragmentation visibility are not identical to JVM Netty. Unary cancellation removes the local waiter;
  it does not promise stronger remote unary cancellation. Domain errors and scoped authority remain unchanged.
- OS sandboxing, browser snippet execution and arbitrary JVM dependency compatibility with Scala.js remain out of scope.

