# TOOL SCALA SCRIPT

Create a tool that allowed to create and run scala code in one shot.
Review [the Scala Days notes for inspiration](../scala_agent_orchestration_inspiration.md)

Review the scala 3 capture-checking
- [ATC: A Minimal Agent With Tracked Capabilities](https://github.com/lampepfl/atc)
- [ACIT: Tracked Agent Capabilities In Types](https://github.com/lampepfl/tacit)
- [Scala 3 Capture Checking](https://docs.scala-lang.org/scala3/reference/experimental/capture-checking/index.html)
  - [Capture Checking Basics](https://docs.scala-lang.org/scala3/reference/experimental/capture-checking/basics.html)
  - [Safe Mode](https://docs.scala-lang.org/scala3/reference/experimental/capture-checking/safe.html)
- [Securing Agents With Tracked Capabilities](https://dl.acm.org/doi/10.1145/3786335.3813127)

- Scala Days talks
  - [Łukasz Biały, Jakub Warchoł – Making the AI agents safe with Scala](https://www.youtube.com/watch?v=2QQd6i0uvMY&pp=0gcJCTsMAYcqIYzv)
  - [Salim Belkhir – AI on your desktop: secure automation using capture checking](https://www.youtube.com/watch?v=8T5o9ih7bkI)

## bash example 


```bash
echo 'println("Hello World")' | scala-cli run _.sc

# if JVM 25 use java-opt
scala-cli run _.sc --java-opt=--sun-misc-unsafe-memory-access=allow --java-opt=--enable-native-access=ALL-UNNAMED <<'EOF'
val x = 10
val y = 20
println(x + y)
EOF
```

## Minimal implementation

The restricted `ScalaTool` (`scalamode`) is registered by `ApplicationTools`. Agent capability allowlists control access to registered tools.

The `scalamode` agent tool accepts one JSON argument:

```json
{"code":"val numbers = List(1, 2, 3); numbers.map(_ * 2).sum"}
```

The trusted `FunctionCallOutput` is a flat object discriminated only by `name`:

```json
{"callId":"call-1","name":"scalamode","result":{"Executed":{"value":"12\n"}},"logs":[],"exitCode":0,"truncated":false,"compilationTime":1200,"executionTime":250,"totalTime":1500}
```

`ScalaModeOutput.name` is fixed to `scalamode`; `output` is computed from `result`, never duplicated in stored JSON.
Host-measured timings use a monotonic clock and are integer **milliseconds**:
- `compilationTime`: the explicit `scala-cli compile` phase, including CLI startup and dependency resolution.
- `executionTime`: the `scala-cli run` phase and runtime-log collection, including CLI/JVM startup and any run-stage compilation; not just snippet evaluation. Zero when compilation fails or times out.
- `totalTime`: the complete executor invocation, including validation, source setup, both phases, diagnostics and temporary-directory cleanup (excluding RPC registration/transport).
Failures and timeouts retain elapsed phase timings. Missing timing fields decode as zero for older saved outputs;
custom executors and host failures without measurements also default to zero.
`CompilationError(diagnostics)` contains formatted retained diagnostics when the explicit `scala-cli compile` stage
fails, including compiler setup/dependency-resolution failures. Only successful compilation proceeds to execution.
The completed stage distinguishes compilation from execution even if output is truncated; no start-marker file is used.
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
result wrapper to capture the last expression (and inject `fs` in filesystem mode), without requiring it in submitted
code. No bundled compiler or staging task is required.

### Compiled runtime API

Every invocation uses the compiled `scalacode-api` JVM module, automatically imported into the snippet:

```scala
tool.api // prints "Yes Tool API is available" through the trusted API
42  // the last expression is still returned
```

The result is `42\n`; `logs` contains `Yes Tool API is available`.

The model sees only the public contract in the tool description: the fixed API availability message and restricted effect constructors. It does not receive the implementation source. The implementation is normal compiled code in `scalacode-api/shared/src/main/scala/fmgp/yallmo/scalacode/ScalaToolApi.scala`. The executor locates the API module's classes directory or JAR and supplies it through Scala CLI's `--classpath`, without exposing the application's full dependency classpath. Generated snippets use `import fmgp.yallmo.scalacode.{ScalaToolApi as tool}` and `import tool.*`, so both `api` and `tool.api` work. No API source resource is loaded or copied.

The greeting helper and a narrow `ScalaToolApi.ZIO` facade use `@caps.assumeSafe` to cross the compiler's trust boundary. These methods are marked `@experimental`; the API object remains a stable classpath-location anchor. Submitted code still cannot use annotations, print directly, or access ambient JVM authority. Adding privileged helpers expands the trusted API and requires review; this prototype is not a general tool bridge.

`ZIO` is automatically imported from that facade, **not** from `zio.ZIO` (the library companion is not safe-mode compatible). It supplies `succeed`, `fail`, `attempt`, `fromEither`, `foreach` and `unit`; callbacks remain compiler-checked safe code. Trace evidence is injected by the host. Do not import `zio.ZIO` yourself. For example:

```scala
for {
  a <- ZIO.succeed(1)
  b <- ZIO.succeed(1)
} yield a + b
```

The default mode now executes a returned `ZIO[Any, E, A]` and returns its success value (`2\n` above), while ordinary values still work unchanged. The snippet result type is inferred so the trusted runner selects a typed evaluation overload; there are no runtime type tests or casts to recover effects from `Any`. Typed failures, defects and finalizers are handled by ZIOApp. No runtime, environment service lookup or ambient I/O constructors are exposed. Existing restrictions on automatic `CanFail` materialization still apply.

- Requires Scala CLI on PATH as `scala-cli`, plus a local JDK. REPL startup advertises it only when `scala-cli --version` succeeds.
- Runs `scala-cli compile .` followed by `scala-cli run . --main-class ScalaToolMain -- <log-byte-limit>` in one isolated temporary directory per call. The second command can reuse Scala CLI's compilation cache. Both share a single deadline and retention budget, using the hard-coded `3.10.1` compiler with `--offline --jvm system --server=false`. Do not add `--quiet`: it also suppresses Scala CLI dependency-resolution errors, so an offline cache miss can otherwise return exit 1 with no diagnostics. This matches the main project's Scala version. The compiler and its dependencies must already be in the Scala CLI/Coursier cache; calls do not perform a latest-nightly lookup.
- Compiles the snippet with `scala.language.experimental.safe`, capture checking and modularity. The compiled `scalacode-api` module and host-configured dependencies are supplied, not the application classpath. The separate trusted `Main.scala` extends `ZIOAppDefault`: `run` evaluates the snippet and prints its returned value with `Console.printLine`. `bootstrap` replaces default loggers with a scoped JSON file logger. No handwritten unsafe runtime launch or global stdout redirection is used. Safe mode remains scoped to `Snippet.scala`, not the trusted runner; model-supplied directives are still rejected.
- No user-supplied dependencies, directives, annotations, Unicode source escapes, backticks or compiler safety overrides. The small validator intentionally also rejects these inside strings/comments; it is not a Scala parser or an API blacklist.
- Defaults: 45-second process deadline (including compilation), 128 MiB evaluation heap, 64 KiB shared retention budget for the result and logs, 65,536 source characters. Output beyond the cap is drained and discarded, not written to an unbounded log. Timeout/cancellation kills the process tree; temporary source/build files are removed.
- The tool deadline is shorter than the default two-minute agent budget so a stalled compiler can return diagnostics to the model before the whole turn is cancelled. Start/finish logs report the selected compiler, offline mode, phase, exit/timeout status, retained stdout/stderr byte counts and truncation without logging submitted code. Nonzero exits/timeouts are warnings. Captured diagnostics are returned in the tool result, not copied to host logs, because they can contain submitted source or runtime secrets.
- No persistent REPL or general tool bridge. The compiled API module and host-configured dependencies are on the classpath for every call, but user definitions do not persist.

### Runtime logging

The generated applications use [ZIOAppDefault](https://zio.dev/reference/core/zioapp/). Their trusted bootstrap is
`Runtime.removeDefaultLoggers >>> ... Runtime.addLogger(...)`, owned by `ScalaToolRuntime` in the JVM API module.
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
new ScalaTool(dependencies = Seq.empty) // Only the mandatory ZIO/ZIO JSON runner dependencies
```

A supplied sequence replaces the defaults. The trusted runner always adds `dev.zio::zio:2.1.27` and
`dev.zio::zio-json:1.1.1`; duplicates are removed. Each coordinate is passed as a separate `--dependency` argument. Offline mode requires all selected artifacts and their transitive dependencies to be cached. Dependencies are not accepted in the model's JSON arguments or code directives. Only configure trusted libraries: adding dependencies expands the code available to snippets; compiler safety restrictions still apply.

### Pinned Scala release / offline use

The compiler version is hard-coded in `ScalaCodeExecutor.ScalaVersion`, and offline mode is enabled by default. No environment variables are required; `YALLMO_SCALA_VERSION` and `YALLMO_SCALA_OFFLINE` are no longer read. Restart the REPL after rebuilding to pick up this change.

This avoids `3.nightly` resolution, which contacts `repo.scala-lang.org` even when compiler jars are cached and can stall if the repository is unreachable. Missing cached artifacts now fail without waiting for a network download. Provision the pinned compiler in the Scala CLI/Coursier cache before running the tool on a new machine. Updating the pinned compiler is an explicit source change followed by safety tests. Exact release versions and fully pinned nightlies are accepted by trusted executor configuration; floating versions are rejected in offline mode.

If even `1 + 1` fails immediately, inspect the tool result for `Error downloading org.scala-lang:scala3-compiler_3` and `not found` cache paths. Scala CLI being installed (or `isAvailable` succeeding) does not mean the pinned compiler is cached. The Docker image currently does not provision this cache (see `docker/Dockerfile`). Warm it **as the service user, with the same cache environment** using a trusted source and without `--offline`, for example:

```bash
warmup=$(mktemp -d)
printf 'object Warmup { val value = 1 + 1 }\n' > "$warmup/Warmup.scala"
scala-cli compile "$warmup" \
  --scala 3.10.1 \
  --jvm system --server=false \
  --dependency dev.zio::zio:2.1.27 \
  --dependency dev.zio::zio-json:1.1.1
rm -rf "$warmup"
```

Filesystem mode additionally needs the streams/HTTP dependencies listed below. A cache warmed as root or under a different `COURSIER_CACHE` is not necessarily available to the service. This provisioning step requires repository/network access; tool invocations remain offline.

### Safety review / inspiration

[ATC](https://github.com/lampepfl/atc) and [TACIT](https://github.com/lampepfl/tacit) combine compiler safe mode, capture checking, lexical validation, and trusted capability APIs. Their permission checks govern operations exposed by those APIs; **they do not lock down the JVM**. An allowed external command still runs with the user's OS privileges. See ATC's README security model and both projects' `CodeValidator` / REPL implementations.

The default pure mode supplies a **fixed-message output helper and restricted ZIO constructors**; configured server execution additionally supports the explicitly selected read-only filesystem effect mode below. Safe mode, rather than regex matching of JVM API names, rejects ambient filesystem/network/process/environment access, reflection, casts and global printing. Annotations are rejected so submitted code cannot declare itself `@assumeSafe`. The compiler, JDK, Scala CLI, trusted runtime API and trusted runner remain part of the trusted computing base.

[Pi codemode](https://pi.dev) is inspiration for code-in/result-out and returning only the computation result to the agent. Its QuickJS sandbox lacks Node/filesystem/network APIs; ordinary Scala/JVM does not have that property. There is no general `tools` bridge: filesystem mode exports only `FsRead`.

**Experimental compiler restrictions, not an OS/JVM sandbox. Do not expose this prototype as a hostile-code execution service.** Compiler defects or future compiler regressions can invalidate the restrictions. Compilation runs with the host user's privileges, can download artifacts and is not given a hard OS memory/CPU/disk quota; the evaluation heap cap is not a whole-process/container quota. Compiler updates require rechecking the restrictions.

### Validation

```bash
sbt 'core/testOnly fmgp.yallmo.tool.ScalaToolSuite fmgp.yallmo.tool.ScalaCodeExecutorSuite'
# Opt-in real compiler tests (requires the pinned compiler in cache):
YALLMO_TEST_SCALA_CLI="$(command -v scala-cli)" sbt 'core/testOnly fmgp.yallmo.tool.ScalaCodeExecutorSuite'
```

For an already-running sbt server, set `core / Test / envVars` explicitly so the forked test JVM sees `YALLMO_TEST_SCALA_CLI`. Integration tests default to the same pinned/offline compiler as the tool. They also accept `YALLMO_TEST_SCALA_VERSION` and `YALLMO_TEST_SCALA_OFFLINE` for explicit test overrides.

### Phase 1 filesystem feasibility (test-only)

`ScalaCodeExecutorSuite` proves a separate, typed child-JVM entry point against the existing compiled filesystem artifact:

```scala
def result(fs: fmgp.yallmo.fs.FsRead)(using zio.Trace, zio.CanFail[fmgp.yallmo.fs.FsError])
    : zio.ZIO[Any, fmgp.yallmo.fs.FsError, String]
```

The test-only trusted main injects an in-memory `FsRead`, supplies inert `Trace.empty`/`CanFail` evidence, and runs the returned effect without erasure/casts or an ambient service environment. Real pinned/offline compiler tests exercise inherited `readUtf8`, `list`, incremental `readBytes.take(3).runCollect.map` (exactly three pulls), typed-error recovery with `fold`, and pure expressions/the existing greeting API.

This is a narrow boundary, not general ZIO compatibility: automatic trace/`CanFail` materialization is rejected in safe code, hence the explicit evidence parameters. `FsError.NotFound` extractors are also rejected; the proof recovers through a callback typed as `FsError`, not case-specific pattern matching. With the filesystem artifact visible, negative tests reject `FileSystem.open`, its private constructor, direct NIO/network/process/ZIO runtime authority, `Unsafe`, casts and access to the trusted main's capability. Compiler restrictions still are **not a sandbox**.

Run with explicit forked-test environment configuration (the Scala build definition's project value is `coreJVM`):

```bash
sbt 'set coreJVM / Test / envVars += ("YALLMO_TEST_SCALA_CLI" -> "/usr/share/coursier/scala-cli"); core/testOnly fmgp.yallmo.tool.ScalaCodeExecutorSuite -- --tests *phase*'
```

At the Phase-1 checkpoint production generation was unchanged (pure returned values only). The following Phase-2 integration supersedes that production limitation; the feasibility tests above remain intact.

### Phase 2: configured server filesystem effects

The configured standalone server owns a **separate loopback, ephemeral-port capability listener** (not the UI `/rpc` listener). It supplies its exact `RpcWebSocket.RpcRoute` and trusted endpoint to application assembly. Existing `new ScalaTool()`, executor `execute`, `ServerApplication.open`, and embedded `ReplStartup` remain pure-only and start no listener. A connected terminal uses its configured server's tools; a real connected interactive prompt was not exercised in this phase.

Filesystem-enabled `scalamode` keeps existing `{ "code": "..." }` callers unchanged. Select the effect boundary explicitly:

```json
{"mode":"filesystem","code":"for { text <- fs.readUtf8(\"notes.txt\"); names <- fs.list(\".\"); bytes <- fs.readBytes(\"notes.txt\").take(3).runCollect; recovered <- fs.readUtf8(\"missing\").fold((e: FsError) => e.toString, identity) } yield text + names.mkString(\",\") + bytes.size + recovered"}
```

`mode` is optional (`pure` by default); only `pure` and `filesystem` are accepted. In filesystem mode the code is the body of:

```scala
def result(fs: FsRead)(using zio.Trace, zio.CanFail[FsError]): zio.ZIO[Any, FsError, Any]
```

The effect's success type is widened covariantly, never erased and cast back into ZIO. `FsRead`, `FsError`, `ZIO`, `api` and `tool.api` are imported; there is no ambient service environment. Return an effect, not its executed value. Use `fold` for error recovery; automatic trace/CanFail materialization and FsError case extractors retain the Phase-1 compiler limitations. Pure mode prints ordinary last expressions or executes returned environment-free ZIO effects, using the same restricted constructors.

Each effect invocation exports only `Rpc.derived[FsRead]` (default fully-qualified identity, version 1). The trusted child Main reads endpoint and single-use bearer from **stdin**, connects once, constructs a typed proxy locally, runs the typed effect and closes its connection. Stdin forwarding was verified with the real pinned/offline Scala CLI. Credentials are absent from submitted/generated sources, command arguments, URLs and logs/output. The proxy exposes no connection or bearer. No uploads, FsWrite, reconnect or retry are added.

The trusted endpoint must be a single-line `ws`/`wss` URI with a host, at most 2048 UTF-8 bytes; validation precedes registration and process launch. Bootstrap delivery runs concurrently with the process deadline, and cancellation destroys the child to unblock a pending stdin write.

The invocation scope owns `registerScoped` authority, including authenticated socket/work cleanup on completion, timeout, cancellation, compilation or launch failure. Pending-ticket TTL equals the compilation/execution deadline (45 seconds by default); the route rejects deadlines above its configured maximum (five minutes by default). Existing process-tree cleanup, heap/output/source bounds remain. Cooperative host finalization may wait on uninterruptible work; these compiler restrictions are **not OS isolation**.

`ServerApplication` retains the workspace read capability; `AgentCatalog` retains each configured agent's exact
rooted filesystem and caches complete executable generations. Explicit native agent roots are validated at first
selection; invalid roots fail instead of falling back. `readFile`/`listFiles` always use the selected agent capability,
else workspace, including without Scala RPC. Filesystem-enabled Scala effects require both that capability and an
explicit trusted RPC route/endpoint. Coach/Folder keep private capabilities without gaining scalamode: Coach restrictions
remain unchanged; Folder programs deny tools for normal turns and direct invocation. Bash/Git remain workspace-bound.
Selected definitions and implementations travel together without duplicate keys.

Domain `FsError`s retain their JSON representation. Infrastructure failures use the integration policy `FsError.IoFailure("<rpc>", "RPC " + error.code.toString)`; `<rpc>` is a sentinel, not a relative filesystem path. This total mapper is identical in host derivation and trusted child source; raw transport exceptions/messages are not exposed. The child classpath includes only compiled scalacode-api/filesystem/RPC artifacts and pinned ZIO/streams/JSON/HTTP dependencies, not core/model/server or the whole application classpath.

Focused checks (seven tests, including real children and thirteen expanded-classpath compiler-negative probes):

```bash
sbt 'set coreJVM / Test / envVars += ("YALLMO_TEST_SCALA_CLI" -> "/usr/share/coursier/scala-cli");core/testOnly fmgp.yallmo.tool.ScalaFilesystemRpcSuite'
```

`ServerCompositionSuite` additionally checks concurrent selected roots, invoke/session parity, retained identity, workspace fallback, invalid-root rejection, consistent read/list/scala binding and Coach/Folder restrictions. These concurrent-root checks use a mocked executor/provider, not the full real stack.

`ScalaFilesystemApplicationSuite` is an opt-in configured-application acceptance test: the existing UI WebSocket turn path, `ServerApplication.openRpc`, real compiler/child and authenticated filesystem RPC use loopback listeners; a deterministic local HTTP fake provider requests the tool and returns its result. It checks advertised filesystem mode, a private root different from workspace, traversal/no-fallback errors, viewer denial and persisted response. No external provider, credentials or interactive terminal are exercised.

```bash
sbt 'set server / Test / envVars += ("YALLMO_TEST_SCALA_CLI" -> "/usr/share/coursier/scala-cli"); server/testOnly *ScalaFilesystemApplicationSuite'
```

## FUTURE (Not now)
### SANDBOX

Add an OS/container isolation boundary around **compilation as well as execution**, with no host credentials/workspace mounts, no evaluation network and hard resource limits. Keep compiler/capability checks as defense in depth, not a replacement for isolation.
