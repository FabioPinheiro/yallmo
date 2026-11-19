# Agent tool wiring implementation plan

## Current contracts

- `AgentFunction` and `AgentTool` live in shared model under `fmgp.yallmo.orchestration`.
  `type ToolWiring = Seq[AgentTool[Any]] => IO[YallmoError, Seq[AgentTool[Any]]]` is an ordinary function alias;
  `Agent.toolWirings(tools)` directly returns `IO[YallmoError, Seq[AgentTool[Any]]]`, preserving input by default.
  Agents explicitly compose utility calls; there is no registry/discovery/composition framework.
- `ToolWiringResolver` validates and filters the base generation before calling wiring once. It structurally validates
  the final output without filtering it and uses `AgentCapabilities.validateIds` to check explicit policies against
  base/final available IDs without constructing a discarded restricted context. Complete
  executable generations remain cached. Configured/Folder wrappers forward the method; Coach calls its delegate
  before adding its private notebook. Trusted wiring owns additions; lower-level request guards remain separate.
- Startup application validation still checks all configured agents against startup-available definitions, including
  Coach's notebook definitions. Later-stage support does not bypass that check. Lower-level configured turns require
  supplied membership metadata and do not execute wiring per turn. Trusted closures are not an authority sandbox.
- Canonical pure `ScalaCodeExecutor`, defaults and compiler pin belong to shared model. Shared
  `ScalamodeToolDefinition`, `ScalaTool`, `ScalamodeService`, `ScalaCodeExecutorServices` capability/extension and
  `AgentToolWirings` belong to scalacode-api. The package remains `fmgp.yallmo.tool.scalamode`.
  API depends on model/filesystem/rpc, never core/server (including tests). Core alone owns `ScalaCodeExecutors`,
  process/source/classpath/diagnostic machinery and general host execution helpers.
- Services select compiled `Binding(parameter, apiTypes, runner, products, errorType, dependencies)` metadata,
  not implementation source. Fixed safe glue preserves inferred value/effect types and calls compiled runners.
  Service classpaths include reviewed API/model/filesystem/rpc anchors and explicit narrow artifacts, never the
  application classpath. The compiler/dependencies stay pinned and offline; HTTP services retain host security pins.
- `ScalaTool.withServices(service)` is non-generic and code-only; executor `executeWithServices[R <: ScalamodeService]`
  is an API extension dispatching only to opted-in `ScalaCodeExecutorServices` implementations. Custom service
  overrides need that mixin; selective external callers import the extension explicitly. Base-only executors fail
  unsupported without pure fallback. Optional concrete `RpcBinding` is caller-owned, scoped and independently authorized.
  No automatic abstract-R RPC derivation, effect casts or reflection.

## Validation-only wiring cleanup

- Extracted `AgentCapabilities.validateIds: Either[YallmoError, Unit]`; `resolve` reuses it before restricting other
  callers' contexts. The resolver validates base/final IDs after wiring without filtering output or intermediate calls.
  Structural output validation, native/direct-request safeguards and FsWrite behavior are unchanged.
- Documented that supplied rooted `FsWrite` intentionally grants mutations in the read/list-qualified default service;
  trusted wiring owns derived APIs, with no separate write allowlist.
- Fresh validation before and after exact-file `scalafmtOnly` on the five changed Scala files:
  `sbt 'core/testOnly fmgp.yallmo.orchestration.ToolWiringSuite fmgp.yallmo.orchestration.AgentCapabilitiesSuite fmgp.yallmo.orchestration.AgentCatalogSuite'`.
  **60 passed, zero failures/skips**: wiring **13**, capabilities **21** (including startup validation), catalog **26**.
  Added validation-only/resolve and duplicate-key checks; extended unknown-ID coverage to tools. Existing pre-wiring
  denial, retained additions, skill restrictions and malformed-output tests remain. Logs:
  `/tmp/yallmo-validate-ids-tests.log`, `/tmp/yallmo-validate-ids-format.log`, `/tmp/yallmo-validate-ids-final-tests.log`.
- Scoped whitespace checks passed. Existing staged work is preserved unchanged; no staging, commits or NOTES.md edits.
  No full-workspace, provider, real-child compiler or browser acceptance is implied.

## Current cleanup: one code-only Scalamode path

Implementation scope:

- Remove the retired `mode` argument, both old allowed values, dedicated filesystem executor/tool/definition,
  raw filesystem runner/source/classpath/diagnostic branches and unused RPC adapter. Unknown fields are rejected,
  including both former mode values; there is no compatibility path.
- Keep ordinary computation/environment-free effect `execute`, compiled service execution/runtime, generic RPC,
  filesystem module/native tools and permissions. Rename the single ordinary definition to `Default`.
- Remove ApplicationTools' automatic filesystem Scalamode replacement and its `scalaRpc` argument. Remove the
  legacy-only workspace/open factory parameters and standalone private loopback capability listener. The actual
  UI/server `/rpc` endpoint remains. Default server Scalamode grants no filesystem API.
- Generic native-read opt-in remains an explicit `Agent.toolWirings` stage with a caller-owned listener. It captures
  only the resolver's already-filtered exact native read/list implementations and selected schemas, never raw
  unfiltered `FsRead`. Partial views and independent host denial stay enforced. The utility is identity without usable native reads or
  default Scalamode, and preserves custom service bindings; the private no-read service needs no RPC.
- Browser Scalamode sends only code and defaults to `List(1, 2, 3).map(_ * 2).sum`. No unrelated AgentTools UI edits.
- Delete redundant legacy-only suites/raw compiler feasibility fixtures rather than create replacement frameworks.
  Canonical real child service/partial-view/denial/cancellation coverage stays in `ScalamodeServiceSuite`.
  Consolidated server composition checks native private/workspace roots for direct and session calls, invalid-root
  no-fallback, shared SDLC storage and code-only Scala. Folder tool-denial checks still reject before capability use.
  Generic bootstrap tests retain endpoint/credential validation and cleanup in both compilation and execution phases.
- Retain shared process deadline/byte budget, process-tree cleanup, source-line/EOF mapping, exact success text,
  setup diagnostic hiding, output credential redaction, typed results/logs and monotonic phase timings.
  Remove obsolete Phase-1/2 instructions and summarize history rather than advertise retired APIs.

### Cleanup validation

Final post-format validation passed in `/tmp/scalamode-removal-final.log`:

- API: `ScalaToolSuite` **5 passed**, `ScalaToolRuntimeSuite` **8 passed**.
- Core: `ScalaCodeExecutorSuite` **47 passed**, `ScalamodeServiceSuite` **12 passed**, `ToolFunctionsSuite` **2 passed**.
  Both engine and service suites used the real pinned offline compiler with explicit
  `coreJVM / Test / envVars += ("YALLMO_TEST_SCALA_CLI" -> "/usr/local/bin/scala-cli")`. All **61 passed**, zero skips.
  The service suite retains 25 actual compiler-negative probes, replacing the deleted-factory probe with `System.getenv`.
  The test opt-in was removed afterward; `show core / Test / envVars` printed `Map()`.
- Wiring/policy/Coach/catalog regressions: **62 passed** (11/18/7/26).
- Server: ApplicationTools **12**, ConfiguredProgram **4**, ServerComposition **17**, ServerApplication **17**,
  OrchestratorWebHost **6**: **56 passed**, zero skips. These include real UI WebSocket/host checks, not a replacement
  filesystem-mode application test; native-root composition uses mocked providers/executor.
- Web `ScalamodeModelSuite`: **14 passed**. Combined final run: **206 passed**, zero failures/skips.
- `modelJS/compile`, `scalaCodeApiJS/compile`, `coreJS/compile`, `webHarness/compile`: **passed**, with all four
  module products printed. No tracked generated/minified assets needed rebuilding or hand editing.
- Exact-file formatting after initial validation: **17 Scala files**, no broad task. Full command in
  `/tmp/scalamode-removal-format-command`, output `/tmp/scalamode-removal-format.log`. The final run above followed it.
- Final retired-API/source/docs audit excludes target/node_modules/dist. Removed identifiers have no live references;
  old mode JSON remains only in rejection regressions, with migration/history mentions in docs. `git diff --check`
  passed and `git diff --cached --name-only` was empty. Thirty unrelated pre-existing uncommitted files were
  hash-checked against the starting snapshot and remained byte-identical, including ToolWiring and AgentTools UI work.

The actual final sequence was one semicolon-separated sbt invocation:

```sh
sbt 'scalaCodeApi/testOnly fmgp.yallmo.tool.scalamode.ScalaToolSuite fmgp.yallmo.tool.scalamode.ScalaToolRuntimeSuite; set coreJVM / Test / envVars += ("YALLMO_TEST_SCALA_CLI" -> "/usr/local/bin/scala-cli"); core/testOnly fmgp.yallmo.tool.scalamode.ScalaCodeExecutorSuite fmgp.yallmo.tool.scalamode.ScalamodeServiceSuite fmgp.yallmo.orchestration.ToolFunctionsSuite; set coreJVM / Test / envVars -= "YALLMO_TEST_SCALA_CLI"; show core / Test / envVars; core/testOnly fmgp.yallmo.orchestration.ToolWiringSuite fmgp.yallmo.orchestration.AgentCapabilitiesSuite fmgp.yallmo.agents.CoachAgentSuite fmgp.yallmo.orchestration.AgentCatalogSuite; server/testOnly fmgp.yallmo.server.application.ApplicationToolsSuite fmgp.yallmo.server.application.ConfiguredProgramSuite fmgp.yallmo.server.application.ServerCompositionSuite fmgp.yallmo.server.application.ServerApplicationSuite fmgp.yallmo.server.website.OrchestratorWebHostSuite; webHarness/testOnly fmgp.yallmo.webharness.ScalamodeModelSuite; modelJS/compile; show modelJS/Compile/products; scalaCodeApiJS/compile; show scalaCodeApiJS/Compile/products; coreJS/compile; show coreJS/Compile/products; webHarness/compile; show webHarness/Compile/products'
```

Earlier cleanup runs: `/tmp/scalamode-removal-unit.log` passed 13 API, 51 core plus **10 explicit integration skips**,
33 focused server and 14 web tests, plus JS compile. Its `MainServerSuite` selector matched no suite; the final run
correctly uses `OrchestratorWebHostSuite` and `ServerApplicationSuite`. `/tmp/scalamode-removal-real.log` then passed
59 real engine/service and 23 host/application tests with no skips, reset the opt-in and compiled JS. An initial
compile attempt found one leftover `Pure.description` rename; it was corrected before these passing runs.
The redundant online nightly-resolution integration test was removed with the raw filesystem fixtures: floating
version validation and bounded deadline coverage remain, and all real execution here stays pinned/offline.

No full-repository, external-provider or interactive browser acceptance is implied. Compiler safety is not an OS
sandbox. No commits, staging, dependency downloads or fallbacks. Independent cleanup review returned **OK with notes**;
review finalization is recorded below. Final acceptance remains with the parent.

### Cleanup review finalization — no defects identified

The independent read-only review found **no issues** and no missing must-have tests. Its notes describe validation
boundaries, not defects. No production/test changes or replacement scaffolding were warranted. Only this durable
record was updated; all 56 other existing uncommitted files remained byte-identical to the finalization snapshot.

Fresh bounded finalization validation:

```sh
sbt 'scalaCodeApi/testOnly fmgp.yallmo.tool.scalamode.ScalaToolSuite; set coreJVM / Test / envVars += ("YALLMO_TEST_SCALA_CLI" -> "/usr/local/bin/scala-cli"); core/testOnly fmgp.yallmo.tool.scalamode.ScalaCodeExecutorSuite fmgp.yallmo.tool.scalamode.ScalamodeServiceSuite; set coreJVM / Test / envVars -= "YALLMO_TEST_SCALA_CLI"; show core / Test / envVars'
bash /tmp/scalamode-removal-format-command
git diff --check; git diff --cached --name-only
```

- **64 passed, zero failures/skips**: API tool **5**, core engine **47**, generic service **12**. The real compiler/child
  paths used pinned offline Scala 3.10.1 through `/usr/local/bin/scala-cli`, including rooted read/list delegation,
  partial views, host denial, authenticated cancellation and ambient-authority probes. The test opt-in was removed
  successfully; the final environment display was `Map()`.
  Log: `/tmp/scalamode-mode-removal-finalization-validation.log`.
- Exact-file formatter rerun on the same 17 affected Scala sources: **passed, no reformatting**. All source/test
  hashes match the reviewed checkpoint. Command arguments: `/tmp/scalamode-removal-format-command`;
  output: `/tmp/scalamode-mode-removal-finalization-format.log`.
- Fresh removed-name audit found no legacy executor/factory/definition/adapter/open-factory references. Both old
  mode values remain only in rejection regression inputs (four matches), not public calls or examples. Search excludes
  target/node_modules/dist; log: `/tmp/scalamode-mode-removal-finalization-audit.log`.
- `git diff --check` passed; staging remained empty. No commits, subagents, generated/minified edits, dependency
  downloads or fallbacks. Unrelated ToolWiring/compiled-runtime/relocation and AgentTools UI work was preserved.
- The earlier **206-test** cleanup run and JS compilation were inspected, not repeated as a new 206-test run.
  Fresh finalization did not rerun server/web/JS suites, full-repository, REPL, external-provider or interactive-browser
  acceptance. Compiler restrictions remain experimental defense in depth, not OS/JVM isolation.

Remaining usage is code-only: `{"code":"List(1, 2, 3).map(_ * 2).sum"}` works without bound services. An explicitly
installed filtered read-service stage with a caller-owned listener may instead accept
`{"code":"reads.readFile(\"notes.txt\")"}`. Ordinary `execute`, generic `executeWithServices`, native filesystem tools
and permissions and actual UI/server `/rpc` remain; no automatic raw read authority or compatibility mode was restored.

## Historical checkpoints (not current cleanup evidence)

These counts describe previously reviewed uncommitted work, not a requirement to retain retired test scaffolding.
Old filesystem-mode instructions and command transcripts have been retired; current commands are in the cleanup
validation section and [Scala tool documentation](tools/TOOL-SCALA-SCRIPT.md#validation).

- Wiring foundation: 62 core tests (ToolWiring 11, AgentCapabilities 18, Coach 7, Catalog 26), 34 focused server tests,
  model/core JS compilation; independent review passed. Ordered stages, filtering and cached generations established.
- Initial source-based service slice: 82 Scalamode tests with real pinned/offline compiler and RPC. A review fix split
  compiler/runtime setup-diagnostic filtering from successful stdout, preserving literal source-looking file text;
  `withServices` lost an unused type parameter. The source-based descriptor was superseded by compiled bindings.
- Compiled binding slice: 84 then-current Scalamode tests, 8 API runtime tests, 62 core regressions, 34 server tests and
  model/API/core JS compilation. Separate finalization reran 12 service and 8 runtime tests, no skips. Compiled custom
  arithmetic/greeting fixtures, selected partial views, typed failures/finalizers and ambient-authority probes passed.
- Source relocation: 84 then-current Scalamode tests (5 API tool, 56 engine, 11 legacy RPC, 12 service), 8 runtime,
  62 core regressions, 34 server regressions and one then-current configured real-child test. JS module products and
  empty test environment maps were checked. Independent review found no relocation-induced defects. Old task/file
  locations in earlier records were superseded by the API/core ownership above. Unrelated server/web work was
  hash-checked and preserved. The retired suites contribute only to these explicitly historical counts.

## Safety and remaining boundaries

Compiler safe mode, capture checking and lexical validation remain experimental defense in depth, **not OS/JVM
isolation**. The compiler/JDK/Scala CLI/compiled trusted APIs/runners are in the TCB. Dependency caches must be
provisioned for the service user; no implicit online fallback. Host-authored artifacts, API docs and error text must
not smuggle broader authority or secrets. Endpoint/ticket are stdin-only, with scoped registration and cleanup.

No automatic configuration opt-in, startup validator relaxation, writes, general registry export,
dynamic API derivation or OS sandbox is introduced. Setup-signature breakage has fake-compiler diagnostic coverage,
not a dedicated broken-runner real-child fixture; intersected custom API views lack a dedicated real-child fixture.
Full repository and external-provider/interactive server sessions remain outside the narrow validation scope.

## Historical standalone utility checkpoint (superseded below)

`AgentToolWirings` is the single JVM utility object: `addHelloWorld: ToolWiring` and
`withFilesystem(executor, route, endpoint, timeout = 45.seconds, maxOutputBytes = 64 * 1024): ToolWiring`.
The old public read-wiring facade is removed; the private read service and its `scalaApi` metadata are retained.
Hello uses local tool/function key `helloWorld`, strict `{}` input and exactly `Hello world` output, without
inference or services. Identical-instance installation is idempotent; other key collisions are typed InvalidConfig.
No global catalog keys/default installation are added. Filesystem replacement selects the default definition by
value/key, not wrapper class. Missing tools/usable reads and custom services are identity, preserving order.
`ChatGPTAgent` remains final, now with an optional single `ToolWiring` constructor parameter (`toolWiring`). Catalog
construction stays unchanged. The [usage example](tools/TOOL-SCALA-SCRIPT.md#native-readlist-utility-exact-delegation-not-a-raw-filesystem-grant)
uses a caller-owned existing listener and records unchanged eager startup validation for explicit new hello IDs.

### Utility review finalization — no defects identified

Independent review returned **OK**, with no issues or missing required tests for the utility/hook/constructor delta.
No production/test fix or additional scaffolding was warranted. This finalization updates only this plan; all
**618 other checkpoint files** remain byte-identical, including existing user metadata, Seq work, compiled services,
mode cleanup and unrelated UI changes.

Fresh bounded finalization reran the existing exact-file formatter and complete focused utility validation:

```sh
bash /tmp/agent-utilities/format-command.sh
bash /tmp/agent-utilities/validation-command.sh
```

- **104 passed, zero failures/skips**: API utility **6**, inspection **4**, ScalaTool **5**, runtime **8**;
  core ToolWiring **11**, capabilities **18**; subscription/provider **19**, Codex **21**; service **12**.
- All **12 service tests** used the explicit real pinned/offline compiler opt-in via
  `coreJVM / Test / envVars += ("YALLMO_TEST_SCALA_CLI" -> "/usr/local/bin/scala-cli")`, then reset it;
  the final environment display was `Map()`. Exact selected delegation/schema/text, partial APIs/docs,
  independent host denial, rooted child RPC, cancellation and compiler-negative probes remain covered.
- `modelJS/compile`, `scalaCodeApiJS/compile` and `coreJS/compile` passed in that same fresh invocation.
  Complete command: `/tmp/agent-utilities/validation-command.sh`;
  output: `/tmp/agent-utilities/finalization-validation.log`.
- Exact-file scalafmt on the same **seven** Scala sources passed without changing any source/test bytes.
  Complete command: `/tmp/agent-utilities/format-command.sh`;
  output: `/tmp/agent-utilities/finalization-format.log`.
- Assigned-path whitespace checks passed; full-workspace `git diff --check` still reports only pre-existing
  `NOTES.md` whitespace at lines 7–8. That file is byte-identical to the finalization checkpoint and was not fixed.
  Removed-object search found no live references; staging remained empty. No commits, nested agents, downloads,
  generated edits, automatic installation, listener or catalog changes.

At that historical checkpoint, the API was `AgentToolWirings.addHelloWorld` and `.withFilesystem(executor, route, endpoint)` can be explicitly
composed and passed as `new ChatGPTAgent(id, provider, toolWiring = composedFunction)`. The caller owns the existing listener and its
lifetime. Hello returns exactly `Hello world` for `{}`; collisions follow the documented identity-or-InvalidConfig
contract. Missing native reads/default Scalamode and custom service bindings remain identity operations.

No full-repository, server-session, interactive-browser or external-provider acceptance is implied. Eager startup
validation for new explicit hello policy IDs is unchanged; trusted closures remain authoritative and compiler safety
is not OS isolation. Independent review passed; this finalization introduces no new implementation delta.


## Current greeting binding: Scalamode service on ChatGPTAgent

The standalone native hello tool is removed. `HelloWorldService` selects the compiled pure `HelloWorldApi` and
`HelloWorldRuntime`, advertising `hello.helloWorld: String` through the same structured service metadata used by the
model and inspector. Snippets may return the value directly or as `ZIO.succeed(hello.helloWorld)`.

`ChatGPTAgent.toolWirings` runs explicit custom composition and then `AgentToolWirings.withDefaults(executor, filesystem, filesystemWrite)`.
Only an existing default Scalamode function is replaced; missing Scalamode, tool order, unrelated functions and custom
service bindings are preserved. There is no separate hello tool/function key and no implicit service-descriptor merge.
`withService(service, executor)` centralizes exact schema/implementation replacement and executor capture.

AgentCatalog passes its executor to configured ChatGPT adapters (including Coach delegates). ServerApplication.workspace
passes the original configured executor through to the catalog, rather than silently replacing injected executors.
Standalone construction uses `ScalaCodeExecutors.make()`, a resource-free description with no eager compilation.
Resolution filtering remains only before wiring. Existing resolved generations require an application restart to
pick up changed code. Default filesystem services use executor-owned local RPC when the complete native read/list grant is present; exact native delegation remains an explicit caller-owned-listener choice.

Validation includes portable wiring/executor tests, ChatGPT default/custom composition tests, a configured `sol`
server integration test verifying inspection and direct invocation, and a real pinned/offline Scala compiler test
executing both greeting forms. JVM/server compilation and shared Scala.js test compilation are checked separately.

## Backend parity follow-up

The current integration uses explicit `ScalamodeFunction.withExecutor`, an immutable API `ScalamodePlan` and
plan-preserving default binding. JVM and Scala.js / Node execute the same selected operation with original limits,
services, rooted grants and approval. `ScalaTool.withServices` explicitly opts in; opaque default-looking closures
remain unsupported for backend substitution. Custom helper products are platform-reviewed, and the unpublished
`scalamodeTestFixtures` crossProject keeps compiler fixtures outside API suites/frameworks and production bundles.
See [the parity delivery record](tools/SCALAMODE-BACKEND-PARITY-PLAN.md) for fresh nonzero suite counts, the recovered
Scala.js test artifact hang, broad-core SDLC failures and pending independent/manual acceptance. Older checkpoints
above describe their original delivery, not current backend restrictions or a new green full-suite claim.
