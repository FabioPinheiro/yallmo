# Restricted Scala execution (`scalamode`)

The restricted `ScalaTool` (`scalamode`) is registered by `ApplicationTools`. Agent capability allowlists control access to registered tools.

The default/model path remains JVM. The operator page can select **JS / Node (`js-node`)** through
`ScalaJsRuntime.provisionBundled`, retaining the selected function's bound services, original configuration and approval.
`ScalaCodeExecutors.node(reviewedArtifacts)` provisions the single retained Scala.js compiler/linker engine;
every evaluation launches a fresh Node child. There is no alternate fresh-build production Node route or JVM fallback.
Node 22.4+ with native WebSocket and the pinned offline compiler/dependencies must be provisioned on the server.
Pure values and environment-free effects work on both backends, as do explicitly bound services. This is not an OS sandbox.
See the [backend parity delivery record](SCALAMODE-BACKEND-PARITY-PLAN.md) for validation and remaining acceptance work.

Module ownership (the package remains `fmgp.yallmo.tool.scalamode`):

- Shared API declaration: [ScalamodeToolDefinition](../../scalacode-api/shared/src/main/scala/fmgp/yallmo/tool/scalamode/ScalamodeToolDefinition.scala).
- Shared model: [ScalaCodeExecutor interface/defaults](../../model/shared/src/main/scala/fmgp/yallmo/tool/scalamode/ScalaCodeExecutor.scala).
- Shared API: [ScalaTool](../../scalacode-api/shared/src/main/scala/fmgp/yallmo/tool/scalamode/ScalaTool.scala),
  [ScalaCodeExecutorServices capability/extension](../../scalacode-api/shared/src/main/scala/fmgp/yallmo/tool/scalamode/ScalaCodeExecutorServices.scala),
  [ScalamodeService](../../scalacode-api/shared/src/main/scala/fmgp/yallmo/tool/scalamode/ScalamodeService.scala) and
  [AgentToolWirings](../../scalacode-api/shared/src/main/scala/fmgp/yallmo/tool/scalamode/AgentToolWirings.scala).
- Core host only: [ScalaCodeExecutors](../../core/jvm/src/main/scala/fmgp/yallmo/tool/scalamode/ScalaCodeExecutors.scala)
  owns Scala CLI/process execution, generated source, classpaths and diagnostics. `LocalProcess` and `CommandOutput` also stay in core. None is on the child classpath; the small compiled child runners remain in API.

`ApplicationTools.live` supplies `ScalaCodeExecutors.live`; custom trusted configuration uses
`ScalaCodeExecutors.layer(executable, scalaVersion = ScalaCodeExecutor.ScalaVersion, offline = true)`.
Consumers/custom test doubles still require or implement `ScalaCodeExecutor`, not a new service type.
The API depends on model/filesystem/rpc, never core/server. Its shared JSON argument parsing uses the same pinned upickle
as core. Scala.js can use the shared contracts and service/wiring factories; host execution remains core JVM-only.
Relocation changes module ownership and the host factory name, not packages, callable IDs or tool input schemas.
Service-overriding custom executors must add `with ScalaCodeExecutorServices`; selective external callers import
`fmgp.yallmo.tool.scalamode.executeWithServices`. Base-only executors continue to reject services explicitly.

Host **source/API migration**: use `ScalaCodeExecutors` instead of the removed JVM-named factory, and
`ScalamodeFunction.withExecutor` instead of the removed manual/pure-operation hooks. `Binding.artifacts` becomes
`Binding.products: ScalamodeService.Products`; service-aware executor implementations now receive dependency grants
as the fifth `executeWithServices` parameter. `AgentToolWirings.withFilesystem` takes only executor/route/endpoint;
limits come from the original selected tool instead of wiring defaults. Recompile host integrations. The public
`scalamode` name, code-only schema and `jvm`/`js-node` wire identifiers are unchanged.


The operator-only selector uses `scalamodeExecution: "js-node"` (enum `JsNode`), with capability field
`jsNodeAvailable`. Rebuild/deploy browser and server together: the old `js-pure` value, `js`, `js-browser` and
explicit null are rejected; no browser backend is implemented. Omitted selectors retain byte-compatible JVM defaults.
The JS / Node route preserves selected-agent services, rooted permissions, approval, original timeout/output limits
and lifecycle cleanup. This selector is not a tool input field.

The `scalamode` agent tool accepts only a `code` string. Unknown fields, including the retired `mode` field
(with either former value), are rejected; there is no compatibility branch:

```json
{"code":"val numbers = List(1, 2, 3); numbers.map(_ * 2).sum"}
```

The trusted `FunctionCallOutput` is a flat object discriminated only by `name`:

```json
{"callId":"call-1","name":"scalamode","result":{"Executed":{"value":"12\n"}},"logs":[],"exitCode":0,"truncated":false,"compilationTime":1200,"executionTime":250,"totalTime":1500,"executionSetupTime":100}
```

`ScalaModeOutput.name` is fixed to `scalamode`; `output` is computed from `result`, never duplicated in stored JSON.
Host-measured timings use a monotonic clock and are integer **milliseconds**:
- `compilationTime`: the explicit `scala-cli compile` phase on JVM, including CLI startup and dependency resolution. The Node backend compiles Scala.js IR and uses a retained fast linker, charging compilation **and linking** here.
- `executionTime`: the `scala-cli run` phase on JVM, including CLI/JVM startup and any run-stage compilation; on Node, the direct fresh Node child's launch/bootstrap/evaluation/cleanup. Includes runtime-log collection, not just snippet evaluation. Zero when compilation/linking fails or times out.
- `executionSetupTime`: optional launch-to-evaluation time, included within `executionTime`. The trusted runtime creates an empty marker immediately before evaluating plain code or constructing/running its returned effect, after RPC connection when applicable. The host polls every 5 ms using its own monotonic clock, so the measurement includes observation/scheduling delay. It is absent if evaluation is not observed (including startup failure/timeout, older outputs and custom runners without instrumentation); marker-write failure never changes the program outcome.
- `totalTime`: the complete executor invocation, including validation, source setup, both phases, diagnostics, temporary-directory cleanup, service registration/transport and Node queue wait.
Failures and timeouts retain elapsed phase timings. Missing timing fields decode as zero for older saved outputs;
custom executors and host failures without measurements also default to zero.
`CompilationError(diagnostics)` contains formatted retained diagnostics when the explicit `scala-cli compile` stage
fails, including compiler setup/dependency-resolution failures. Only successful compilation proceeds to execution.
The completed stage distinguishes compilation from execution even if output is truncated; the setup-timing marker is diagnostic only and never determines the result type or consumes the output/log budget.
`TimedOut(diagnostics)` represents the compilation/execution deadline being exceeded, including pre-Main timeouts;
retained diagnostics remain available through `output`. `Executed(value)` represents an execution **attempt**, not
guaranteed success: runtime exceptions and host infrastructure/validation failures carry actionable text here.
Inspect `result`, `exitCode`, and `truncated`. The former `timedOut` boolean is no longer emitted or exposed on the
Scala output types; older saved flags decode into `TimedOut` results. Providers continue to receive the computed `output`, including compiler diagnostics.
Successful `output` contains only the returned value; informational messages remain in `ScalaModeOutput.logs`.
On compilation failure, runtime failure or timeout, captured logs are included in the diagnostic result only, leaving `logs` empty.
Other tools use `FunctionGenericOutput(callId,name,output)` with no logs field.
Malformed `scalamode` JSON is rejected; it never falls back to a generic tool result.
Use the **last expression**, not `println`, to return a value.

### Submitted source and diagnostic lines

Submit only the block body. Imports, experimental compiler flags and the safe/trusted boundary are host setup.
Compiler positions and source gutters for submitted code are reported starting at **line 1 of the submitted body**,
not at the generated wrapper's line number. Temporary snippet paths and generated snippet source are hidden.
Body columns, source text and carets are unchanged. Syntax errors in the generated closing braces instead point to
the submitted EOF, with its source/caret and an end-of-submitted-code message, not the wrapper's braces. Errors in
generated imports/setup are labeled `<executor setup>` without showing generated source or blaming a submitted line.
Other source files are left unchanged. Runtime `Snippet.scala` stack frames use submitted lines too. Rendering keeps
the shared output byte budget; successful values are never rewritten.

Safe mode is always enabled by the host's file-level `import scala.language.experimental.safe`. The host also sets
capture-checking/modularity flags; submitted code cannot disable them or supply its own language imports/directives.
The executor uses ordinary `Snippet.scala` and `Main.scala` files, not a REPL or `.sc` script. We retain the internal
result wrapper to capture the last expression (and the explicit compiled API parameter for service bindings), without requiring it in submitted
code. No bundled compiler or staging task is required.

### Compiled runtime API

Every invocation uses the matching compiled `scalacode-api` JVM or Scala.js module, automatically imported into the snippet:

```scala
tool.api // prints "Yes Tool API is available" through the trusted API
42  // the last expression is still returned
```

The result is `42\n`; `logs` contains `Yes Tool API is available`.

The model sees only the public contract in the tool description: the fixed API availability message and restricted effect constructors. It does not receive the implementation source. The implementation is normal compiled code in `scalacode-api/shared/src/main/scala/fmgp/yallmo/tool/scalamode/ScalaToolApi.scala`. The executor locates the API module's classes directory or JAR and supplies it through Scala CLI's `--classpath`, without exposing the application's full dependency classpath. Generated snippets use `import fmgp.yallmo.tool.scalamode.{ScalaToolApi as tool}` and `import tool.*`, so both `api` and `tool.api` work. No API source resource is loaded or copied.

The greeting helper, narrow `ScalaToolApi.ZIO` facade, and immutable `FilesystemModes` accessors use `@caps.assumeSafe` to cross the compiler's trust boundary. These methods are marked `@experimental`; the API object remains a stable classpath-location anchor. Submitted code still cannot use annotations, print directly, or access ambient JVM authority. Adding privileged helpers expands the trusted API and requires review; this prototype is not a general tool bridge.

`ZIO` is automatically imported from that facade, **not** from `zio.ZIO` (the library companion is not safe-mode compatible). It supplies `succeed`, `fail`, `attempt`, `fromEither`, `foreach` and `unit`; callbacks remain compiler-checked safe code. Trace evidence is injected by the host. Do not import `zio.ZIO` yourself. For example:

```scala
for {
  a <- ZIO.succeed(1)
  b <- ZIO.succeed(1)
} yield a + b
```

The default executor executes a returned `ZIO[Any, E, A]` and returns its success value (`2\n` above), while ordinary values still work unchanged. The snippet result type is inferred so the trusted runner selects a typed evaluation overload; there are no runtime type tests or casts to recover effects from `Any`. Typed failures, defects and finalizers are handled by ZIOApp. No runtime, environment service lookup or ambient I/O constructors are exposed. Existing restrictions on automatic `CanFail` materialization still apply.

- Requires Scala CLI on PATH as `scala-cli`, plus a local JDK. REPL startup advertises it only when `scala-cli --version` succeeds.
- Runs `scala-cli compile .` followed by `scala-cli run . --main-class ScalaToolMain -- <log-byte-limit>` in one isolated temporary directory per call. The second command can reuse Scala CLI's compilation cache. Both share a single deadline and retention budget, using the hard-coded `3.10.1` compiler with `--offline --jvm system` and Scala CLI's default Bloop compilation server. Scala CLI starts/reuses the background server; the pre-compilation log reports `backend=bloop` (configuration, not a liveness check). Do not add `--quiet`: it also suppresses Scala CLI dependency-resolution errors, so an offline cache miss can otherwise return exit 1 with no diagnostics. This matches the main project's Scala version. The compiler and its dependencies must already be in the Scala CLI/Coursier cache; calls do not perform a latest-nightly lookup.
- Compiles the snippet with `scala.language.experimental.safe`, capture checking and modularity. The compiled `scalacode-api` module and host-configured dependencies are supplied, not the application classpath. The separate trusted `Main.scala` extends `ZIOAppDefault`: `run` evaluates the snippet and prints its returned value with `Console.printLine`. `bootstrap` replaces default loggers with a scoped JSON file logger. No handwritten unsafe runtime launch or global stdout redirection is used. Safe mode remains scoped to `Snippet.scala`, not the trusted runner; model-supplied directives are still rejected.
- No user-supplied dependencies, directives, annotations, Unicode source escapes, backticks or compiler safety overrides. The small validator intentionally also rejects these inside strings/comments; it is not a Scala parser or an API blacklist.
- Defaults: 45-second process deadline (including compilation), 128 MiB evaluation heap, 64 KiB shared retention budget for the result and logs, 65,536 source characters. Output beyond the cap is drained and discarded, not written to an unbounded log. Timeout/cancellation kills the process tree; temporary source/build files are removed.
- The tool deadline is shorter than the default two-minute agent budget so a stalled compiler can return diagnostics to the model before the whole turn is cancelled. Start/finish logs report the selected compiler, offline mode, phase, exit/timeout status, retained stdout/stderr byte counts and truncation without logging submitted code. Nonzero exits/timeouts are warnings. Captured diagnostics are returned in the tool result, not copied to host logs, because they can contain submitted source or runtime secrets.
- No persistent REPL or general tool bridge. The compiled API module and host-configured dependencies are on the classpath for every call, but user definitions do not persist.

### Runtime logging

The generated applications use [ZIOAppDefault](https://zio.dev/reference/core/zioapp/). Their trusted bootstrap is
`Runtime.removeDefaultLoggers >>> ... Runtime.addLogger(...)`, owned by the platform-specific `ScalaToolRuntime` in the API module. JVM and explicitly opted-in Node runners share the log record encoding.
INFO-and-above records are written to the invocation-local `runtime.jsonl` file with timestamp, level, fiber,
message, optional cause and annotations. JSON encoding escapes quotes, newlines and other control characters.
ZIOApp handles effect execution, failure reporting, resource finalization and the process exit code.

The logger keeps at most the configured byte budget plus one overflow byte, then discards further records. The
host reads only the remaining shared result/log budget and sets `truncated` when bytes were omitted; the last JSON
record may therefore be partial when truncated or the process is killed. Retained records appear in `logs` on success, or in the single
failure diagnostic on error. The file is removed with the scoped sources/build directory, not retained globally.
Compiler diagnostics and non-ZIO stderr notices remain plain text. `tool.api` writes its fixed notice directly to
stderr; there is no `System.setOut` mutation.

### Host-configured dependencies

`ScalaTool` accepts a trusted host-side `Seq[String]` of Scala CLI dependency coordinates. It defaults to `ScalaTool.DefaultDependencies` (`Seq("dev.zio::zio:2.1.27")`):

```scala
new ScalaTool() // ZIO by default
new ScalaTool(dependencies = ScalaTool.DefaultDependencies :+ "dev.zio::zio-streams:2.1.27")
new ScalaTool(dependencies = Seq.empty) // Only the mandatory ZIO/ZIO JSON/ZIO Streams runner dependencies
```

A supplied sequence replaces the defaults. The trusted runner always adds `dev.zio::zio:2.1.27`,
`dev.zio::zio-json:1.1.3` and `dev.zio::zio-streams:2.1.27`; duplicates are removed. Each coordinate is passed as a separate `--dependency` argument. Offline mode requires all selected artifacts and their transitive dependencies to be cached. Dependencies are not accepted in the model's JSON arguments or code directives. Only configure trusted libraries: adding dependencies expands the code available to snippets; compiler safety restrictions still apply.

### Pinned Scala release / offline use

The compiler version is hard-coded in `ScalaCodeExecutor.ScalaVersion`, and offline mode is enabled by default. No environment variables are required; `YALLMO_SCALA_VERSION` and `YALLMO_SCALA_OFFLINE` are no longer read. Restart the REPL after rebuilding to pick up this change.

This avoids `3.nightly` resolution, which contacts `repo.scala-lang.org` even when compiler jars are cached and can stall if the repository is unreachable. Missing cached artifacts now fail without waiting for a network download. Provision the pinned compiler in the Scala CLI/Coursier cache before running the tool on a new machine. Updating the pinned compiler is an explicit source change followed by safety tests. Exact release versions and fully pinned nightlies are accepted by trusted executor configuration; floating versions are rejected in offline mode.

If even `1 + 1` fails immediately, inspect the tool result for `Error downloading org.scala-lang:scala3-compiler_3` and `not found` cache paths. Scala CLI being installed (or `isAvailable` succeeding) does not mean the pinned compiler is cached. The Docker image currently does not provision this cache (see `docker/Dockerfile`). Warm it **as the service user, with the same cache environment** using a trusted source and without `--offline`, for example:

```bash
warmup=$(mktemp -d)
printf 'object Warmup { val value = 1 + 1 }\n' > "$warmup/Warmup.scala"
scala-cli compile "$warmup" \
  --scala 3.10.1 \
  --jvm system \
  --dependency dev.zio::zio:2.1.27 \
  --dependency dev.zio::zio-json:1.1.3
rm -rf "$warmup"
```

The default Bloop server and its dependencies must also be cached for offline startup. RPC service bindings additionally need the pinned streams/HTTP dependencies described below. A cache warmed as root or under a different `COURSIER_CACHE` is not necessarily available to the service. This provisioning step requires repository/network access; tool invocations remain offline.

### Safety review / inspiration

[ATC](https://github.com/lampepfl/atc) and [TACIT](https://github.com/lampepfl/tacit) combine compiler safe mode, capture checking, lexical validation, and trusted capability APIs. Their permission checks govern operations exposed by those APIs; **they do not lock down the JVM**. An allowed external command still runs with the user's OS privileges. See ATC's README security model and both projects' `CodeValidator` / REPL implementations.

The base code-only binding supplies a **fixed-message output helper and restricted ZIO constructors**. ChatGPTAgent
adds hello and, when both filesystem read/list functions are permitted, the selected rooted filesystem API by default.
Workspace/private-root bindings include writes; a read-only embedded capability does not. Other filesystem operations
require native tools or explicit service bindings as described below. Safe mode, rather than regex matching of JVM API names, rejects ambient filesystem/network/process/environment access, reflection, casts and global printing. Annotations are rejected so submitted code cannot declare itself `@assumeSafe`. The compiler, JDK, Scala CLI, trusted runtime API and trusted runner remain part of the trusted computing base.

[Pi codemode](https://pi.dev) is inspiration for code-in/result-out and returning only the computation result to the agent. Its QuickJS sandbox lacks Node/filesystem/network APIs; ordinary Scala/JVM does not have that property. There is no automatic general `tools` bridge or ambient filesystem authority; the default filesystem grant is rooted and tool-gated.

**Experimental compiler restrictions, not an OS/JVM sandbox. Do not expose this prototype as a hostile-code execution service.** Compiler defects or future compiler regressions can invalidate the restrictions. Compilation runs with the host user's privileges, can download artifacts and is not given a hard OS memory/CPU/disk quota; the evaluation heap cap is not a whole-process/container quota. Compiler updates require rechecking the restrictions.

### Validation

```bash
sbt 'scalaCodeApi/testOnly fmgp.yallmo.tool.scalamode.ScalaToolSuite fmgp.yallmo.tool.scalamode.ScalaToolRuntimeSuite; core/testOnly fmgp.yallmo.tool.scalamode.ScalaCodeExecutorSuite fmgp.yallmo.tool.scalamode.ScalamodeServiceSuite'
# Opt-in real compiler tests (requires the pinned compiler in cache):
sbt 'set coreJVM / Test / envVars += ("YALLMO_TEST_SCALA_CLI" -> "/usr/local/bin/scala-cli"); core/testOnly fmgp.yallmo.tool.scalamode.ScalaCodeExecutorSuite fmgp.yallmo.tool.scalamode.ScalamodeServiceSuite; set coreJVM / Test / envVars -= "YALLMO_TEST_SCALA_CLI"'
```

For an already-running sbt server, set `coreJVM / Test / envVars` explicitly so the forked test JVM sees `YALLMO_TEST_SCALA_CLI`. Integration tests default to the same pinned/offline compiler as the tool. They also accept `YALLMO_TEST_SCALA_VERSION` and `YALLMO_TEST_SCALA_OFFLINE` for explicit test overrides.

## Agent-defined service bindings (opt-in)

`ScalamodeService` is a small **host-authored descriptor**, not a ZIO environment. Implement any subtype with
`apiDocs`, `binding: ScalamodeService.Binding`, and optionally `rpcBinding`. Override `scalaApi: Option[String]` with
public Scala interface declarations for the exact selected API views. `ScalaTool.withServices` includes those declarations
in the model-facing description and attaches a safe documentation projection (`parameter`, `apiTypes`, `description`,
`scalaApi`) to the function definition. The Agents page (`#/agents` → **Inspect tools** → **Available Scalamode services**)
shows every bound service/API view and a built-in `tool`/restricted `ZIO` reference. No host reflection is used; missing
interface source is explicitly marked unavailable. Only effective bindings are shown, never all server/classpath APIs.
This metadata does not include RPC endpoints/tickets, dispatchers, runners or artifact paths, and changing it grants no authority.
The explicit
`executeWithServices[R <: ScalamodeService](code, services, timeout, maxOutputBytes)` parameter preserves the
host descriptor type; neither the executor nor `ScalaTool` derives an RPC contract from abstract `R`.
`ScalaTool` remains non-generic. `ScalaTool.withServices(service)` captures the descriptor behind the existing
`scalamode` ID, **code-only** schema and public API docs. No source implementation or transport is a model argument.
Custom executors retain their unsupported-service default. Ordinary `execute` remains a value/environment-free
effect path. ChatGPTAgent resolution installs the rooted filesystem service when both native filesystem functions
are permitted, retaining hello alongside fs. No listener starts at construction/selection. The application's shared
`ScalaCodeExecutors.live` / `.layer(...)` lazily starts one loopback listener on the first local-service execution
and closes it at layer shutdown. Concurrent and sequential executions reuse it, with independently scoped dispatchers,
single-use tickets and connections; one execution's cleanup does not close the listener or other executions.
Keep the layer scope alive while using its executor. The resource-free `.make(...)` compatibility constructor retains
per-invocation listeners for local bindings; explicit caller-owned routes are unchanged. The existing UI/server RPC
endpoint is unchanged.

### Compiled binding contract and thin generated glue

A binding names a parameter, compiled API view(s), compiled runner method and optional additional artifact anchors.
Optional `constants` add reviewed pure singleton parameters through validated names and `.type` references, never source.
The default filesystem descriptor uses this to keep `hello` available alongside `fs`.
Names are validated identifiers/qualified names, not arbitrary Scala source. API views may be intersected; singleton
views may end in `.type`. `errorType` supplies explicit `CanFail` evidence and is imported for submitted code.
The executor generates only safe imports, `ScalaToolSnippet.result(parameter: Api)(using Trace, CanFail[Error])`
around the validated submitted body, and this fixed runner entry (with the descriptor's names substituted):

```scala
object ScalaToolMain extends ZIOAppDefault {
  override val bootstrap = ScalaToolRuntime.bootstrap
  def run = CompiledBinding.run { service =>
    ScalaToolRuntime.evaluate(ScalaToolSnippet.result(service)(using Trace.empty, summon[CanFail[Error]]))
  }
}
```

`evaluate` stays at the inferred snippet type. The compiled runner accepts a callback returning `ZIO[Any, E, A]`;
there are no effect casts, runtime type tests, reflective construction or generic service registries. The compiled
`ScalaToolRuntime.runPure` and `runRpc(concreteContract)` share printing, lazy evaluation and scoped connection
lifecycle. `runRpc` consumes endpoint/ticket from stdin, creates the concrete proxy and closes the connection on
scope exit. The generated main contains no proxy implementation, RPC derivation, bootstrap parsing or cleanup logic.
Runners are unsafe infrastructure, not ambient safe capabilities; do not mark them `assumeSafe`.

For a custom compiled helper, put both helper and runner in a **narrow reviewed artifact**, for example:

```scala
package example

import fmgp.yallmo.tool.scalamode.ScalaToolRuntime

// Compiled narrow artifact, not generated source (no core dependency):
@scala.annotation.experimental @caps.assumeSafe
object Arithmetic {
  @caps.assumeSafe def twice(value: Int): Int = value * 2
}
object ArithmeticRuntime {
  @scala.annotation.experimental
  def run[E, A](evaluate: Arithmetic.type => zio.ZIO[Any, E, A]) =
    ScalaToolRuntime.runPure(_ => evaluate(Arithmetic))
}
```

Host/agent code, with that artifact as an explicit dependency (separate from the narrow artifact):

```scala
import example.ArithmeticRuntime
import fmgp.yallmo.tool.scalamode.{ScalaTool, ScalamodeService}

object ArithmeticService extends ScalamodeService {
  val apiDocs = "math.twice(value: Int): Int doubles an integer; values and environment-free ZIO effects work."
  val binding = ScalamodeService.Binding(
    parameter = "math",
    apiTypes = Seq("example.Arithmetic.type"),
    runner = "example.ArithmeticRuntime.run",
    products = ScalamodeService.Products(
      jvm = Seq(ArithmeticRuntime.getClass),
      scalaJs = Some(Seq("/reviewed/arithmetic_sjs1_3.jar"))
    )
  )
}
val arithmeticTool = ScalaTool.withServices(ArithmeticService)
// Agent explicitly chooses this bound function; request: {"code":"ZIO.succeed(math.twice(21))"}
```

A custom RPC binding instead compiles its narrow trait and a runner delegating to
`ScalaToolRuntime.runRpc(onceDerivedConcreteContract)(evaluate)`. The host provides
`RpcBinding(endpoint, route, concreteContract.bind(exactImplementation))`. The caller owns the listener; the executor
registers only that dispatcher for the invocation. A pure binding needs no RPC configuration.
Endpoints must be single-line `ws`/`wss` URIs with a host, at most 2048 UTF-8 bytes and no user info/query/fragment;
validation precedes registration and launch. Credentials travel only over child stdin, concurrently with the process
deadline. The invocation scope owns `registerScoped` cleanup on success, failure, timeout and cancellation, including
authenticated host work. Ticket TTL equals the process deadline and must fit the route's configured maximum.
No reconnect, retry or raw `FsRead` export is implied. The proxy exposes neither connection nor bearer. Executable custom pure
and non-filesystem greeting RPC fixtures live in the unpublished, test-only crossProject
[`CompiledServices.scala`](../../scalamode-test-fixtures/src/main/scala/fmgp/yallmo/scalacode/fixture/CompiledServices.scala),
selected by descriptor subtypes in `ScalamodeServiceSuite`; they do not inject implementations as source strings.

Pure and service classpaths explicitly contain **scalacode-api + model + filesystem + rpc** to resolve the shared
runtime signatures; service bindings additionally include their reviewed artifact anchors. No full application classpath, core or server artifact is added. The API CrossProject depends on
those reviewed modules on JVM/JS; runtime helpers have JVM and Node implementations; the listener/registry remain JVM-only. Abstract capability views reuse existing
types where appropriate, but adding filesystem/model classes does **not** export `FileSystem.open` or other ambient
authority: compiler-negative tests check those restrictions. The expanded API artifact also contains the host-facing
executor interface, ScalaTool and wiring/service factories, but no assumeSafe authority grant was added. Real compiler
probes reject executor service acquisition, broader tool/RPC construction, private validator access and host engine
lookup. Artifact/classpath tests separately prove the engine and general host execution helpers are absent. This is not an OS sandbox or a capability grant inferred
from classpath presence. Host authors must not add broader artifacts or hide extra authority in compiled helpers.

`dependencies` are pinned host-supplied Scala CLI coordinates. Mandatory ZIO/ZIO JSON/ZIO Streams are added by both
runners. JVM HTTP transport/BouncyCastle pins belong to the JVM runner, not portable service metadata. Scala.js
translates only built-in reviewed cross-platform coordinates or explicit `Products.scalaJsDependencies` mappings;
JVM-only/unsupported grants fail before execution. Custom bindings need reviewed `Products.scalaJs` IR products;
JVM class anchors alone cannot locate them. Always provision every selected/transitive artifact in the
service user's cache. No uncached compiler fallback or silent network download is used.
All phases retain the existing deadline, byte budget, process-tree cleanup and monotonic timings. Submitted code
retains its 65,536-character bound. Diagnostics use the fixed generated preamble length; trusted setup failures and
warning source excerpts are hidden. Successful returned stdout, including literal source-looking gutters such as
`Main.scala:3:1`, is never treated as setup diagnostics. User-body positions remain mapped to submitted lines.
Never put credentials in docs, source, model JSON, process arguments or URLs. Endpoint validation and retained-output
credential redaction remain in place. Host authors still review compiled helper authority and public error messages.

### Explicit rooted FsRead / FsWrite services

`FilesystemService.read(read, route, endpoint)`, `.write(write, route, endpoint)`, and
`.readWrite(read, write, route, endpoint)` expose host-supplied capabilities as `fs` behind the existing code-only
Scalamode tool. Read services use the actual `FsRead` interface, including streamed binary reads and structured listings.
Write services expose `FsWriteApi`: UTF-8 writes and filesystem mutations with explicit modes.
Overloads without route/endpoint use executor-owned scoped local RPC; `FilesystemService.default(read, optionalWriter)`
includes hello. ChatGPTAgent installs this default view automatically for a complete native filesystem grant. Stream uploads and scoped
writers are not exposed because the RPC transport does not support them. Domain `FsError` failures are preserved;
infrastructure failures become `FsError.IoFailure(".", "RPC <code>")`.

`AgentToolWirings.withFilesystemServices(read, write: Option[FsWrite], executor, route, endpoint)` opts in only when
both native read/list functions and default Scalamode survive filtering. `None` grants reads only; supplying a writer
is an explicit host authorization, never inferred from the read-only native filesystem tool. Missing/partial native
grants and custom Scalamode bindings are preserved. Apply before hello-world fallback. The caller owns the listener
and must supply the intended agent root; this deliberately grants raw capabilities rather than preserving native
functions' custom behavior. Use the exact-delegation utility below when that behavior or partial grants matter.

Safe snippets select modes with `import fmgp.yallmo.tool.scalamode.FilesystemModes`, for example
`fs.writeUtf8("note.txt", "hello", FilesystemModes.createNew)`. Available values are `createNew`, `replace`, `append`,
`emptyOnly`, `recursive`, `failIfExists`, and `replaceFile`. The original enum companions are not safe constructors.
See [service examples and validation](../../scalacode-api/README-SCALACODE.md#explicit-fsread--fswrite-services).

### Native read/list utility: exact delegation, not a raw filesystem grant

`ApplicationTools.selected` binds native `readFile`/`listFiles` to the selected private root, otherwise the workspace.
Invalid explicit roots fail at first selection rather than falling back. The complete executable generation is cached;
direct invocation and session execution share it. Coach/Folder restrictions remain unchanged; Bash/Git stay
workspace-bound. The filesystem module and its read/write/patch permissions remain independent of Scalamode.

`AgentToolWirings.withFilesystem(executor, route, endpoint): ToolWiring` returns one ordinary
function. Add it explicitly to an agent's `toolWirings`; no agent receives it by default. It replaces an existing,
permitted **default** `scalamode` binding only (explicit `ScalamodeFunction.bindDefaultScalamode` opt-in, never definition equality alone), without new model-visible names or a global registry lookup. The caller owns the listener and its lifetime; server startup never substitutes an unfiltered `FsRead` binding.

The stage receives the resolver's **already-filtered** `AgentTool[Any]` generation. It captures the exact native
`filesystem/readFile` and `filesystem/listFiles` implementations and their bound definitions, not `FsRead`. Every
RPC invocation builds `{ "path": ... }`, validates it against the **selected** native schema, and calls that exact
implementation. Selected roots and custom read-tool behavior are therefore retained. Writes, patches, recursive
Scalamode and other groups are not exported. Read-only and list-only permissions produce different compiled parameter
views and docs. Missing operations fail on the host even if a caller bypasses the typed facade. Missing filesystem,
Scalamode or usable bound reads leaves the generation unchanged, as does an already custom/service-bound Scalamode.
The stage cannot add Scalamode back or overwrite a custom service. Unrelated tools/functions and order are preserved.

The narrow JVM scalacode-api contract uses `NativeReadFile`, `NativeListFiles`, their combined RPC contract
`NativeReadTools`, and `ReadToolError(kind, message)`. Both methods return the native tool's **text**:
`readFile` normally returns UTF-8 contents; `listFiles` normally returns newline-separated entry names. They do not
parse formatted JSON or pretend tool text is structured filesystem data. Native failures preserve their concrete
error class name and public message (including filesystem domain details); exception causes/stacks are not serialized.
Infrastructure errors use `ReadToolError("RpcError", code)`. Custom native tools must keep their public messages safe.

Explicit host composition uses the caller's **existing** RPC listener. `route` must already be mounted at
`endpoint`; its application-owned scope must outlive the resolved tools. The utility never starts a hidden listener.
`provider` is the caller's existing `OpenAiCodexClient` and `executor` is the supplied `ScalaCodeExecutor`:

```scala
import fmgp.yallmo.provider.openai.ChatGPTAgent
import fmgp.yallmo.tool.scalamode.AgentToolWirings

val agent = new ChatGPTAgent(
  id,
  provider,
  toolWiring = AgentToolWirings.withFilesystem(executor, route, endpoint),
  scalaCodeExecutor = executor
)
// Custom service selection happens before ChatGPTAgent's default greeting binding.
// An already-custom Scalamode service is preserved, not implicitly merged with hello.
// A custom non-final Agent can instead override def toolWirings(tools).
// Resolution filters only before wiring, not between composed calls or on their output.
// Resolver supplies filtered tools; do not supply an unrestricted application registry instead.
```

Without a custom binding, `new ChatGPTAgent(id, provider)` binds the compiled `HelloWorldService` to an existing,
permitted Scalamode function. Snippets use `hello.helloWorld` or `ZIO.succeed(hello.helloWorld)`; the returned value is
`Hello world` (the child runner's output includes a trailing newline). It requires no RPC or filesystem service.
There is **no separate native hello tool**, no `{}` hello call, and no `helloWorld` capability key to allowlist.
The Scalamode inspector lists the bound `hello` API using the same metadata advertised to the model.

`AgentToolWirings.withHelloWorld(executor): ToolWiring` is idempotent and preserves missing/custom Scalamode.
`withService(service, executor): ToolWiring` binds another explicitly selected compiled descriptor.
`withFilesystem` defaults to `45.seconds` and `64 * 1024` output bytes and still requires an existing caller-owned
listener. Startup validation continues to check existing configured tool/function IDs; the greeting adds no new IDs.

For an agent with both selected reads, a code-only request can be:

```json
{"code":"for { text <- reads.readFile(\"notes.txt\"); entries <- reads.listFiles(\".\") } yield text + \"|\" + entries"}
```

The utility uses the bounded reviewed service classpath above (not core/server/application artifacts), with the
existing ZIO/streams/JSON/HTTP dependencies. Child BouncyCastle coordinates are pinned to 1.86, matching the existing host
security overrides, instead of resolving zio-http's older transitives. Provision those exact versions offline.

Agent source is trusted authority. Policy filtering enforces declared/catalog operations, **not hidden captured
capabilities**; this utility's delegation discipline is what preserves the native read boundary. Eager server startup
capability validation is unchanged; this stage uses only the existing filesystem/scalamode IDs.

Run the service tests, including actual offline compiler/child RPC and authenticated cancellation:

```sh
sbt 'set coreJVM / Test / envVars += ("YALLMO_TEST_SCALA_CLI" -> "/usr/local/bin/scala-cli"); core/testOnly fmgp.yallmo.tool.scalamode.ScalamodeServiceSuite; set coreJVM / Test / envVars -= "YALLMO_TEST_SCALA_CLI"'
```

Without the opt-in variable, real-child tests skip. Unit tests exercise selected schemas, partial/denied RPC,
two independently bound agents, malformed JSON, diagnostics, output limits, cleanup/cancellation/timeouts and old
executor compatibility; they are not evidence of real child integration on their own. There is no new retry,
download policy, OS sandbox or automatic service derivation.


Current tests live in API `ScalaToolSuite`/`ScalaToolRuntimeSuite`, core `ScalaCodeExecutorSuite`/`ScalamodeServiceSuite`,
server composition/program suites and web `ScalamodeModelSuite`. The dedicated legacy filesystem suites and raw
filesystem compiler feasibility fixtures have been removed; canonical real-child read delegation, partial-view
host denial, cancellation and authority-negative checks remain in `ScalamodeServiceSuite`. Current validation and
historical checkpoints are recorded in [the durable plan](../AGENT_TOOL_WIRING_PLAN.md).
