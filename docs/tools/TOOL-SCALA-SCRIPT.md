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

The following describes the restricted `ScalaTool` (`scalamode`). Trusted in-process execution is a separate `ScalaDynamicTool` (`scalaunsafe`). `ApplicationTools` currently registers the restricted tool only; dynamic registration is disabled. Agent capability allowlists control access to registered tools. See [dynamic executor usage and security limitations](SCALA-DYNAMIC-EXECUTOR.md).

The `scalamode` agent tool accepts one JSON argument:

```json
{"code":"val numbers = List(1, 2, 3); numbers.map(_ * 2).sum"}
```

The trusted `FunctionCallOutput` is a flat object discriminated only by `name`:

```json
{"callId":"call-1","name":"scalamode","result":{"Executed":{"value":"12\n"}},"logs":[],"exitCode":0,"timedOut":false,"truncated":false}
```

`ScalaModeOutput.name` is fixed to `scalamode`; `output` is computed from `result`, never duplicated in stored JSON.
`CompilationError(diagnostics)` contains the complete retained compiler/launch-stage diagnostics when the CLI exits
nonzero before trusted Main starts (including compiler setup/dependency-resolution failures). A private fixed-size
Main-start file marks this boundary independently of the bounded log capture. It is never included in results.
`Executed(value)` represents an execution **attempt**, not guaranteed success: runtime exceptions, timeouts (including
pre-Main timeouts), and host infrastructure/validation failures carry actionable text here. Inspect `exitCode`,
`timedOut`, and `truncated`. Providers continue to receive the computed `output`, including compiler diagnostics.
Successful `output` contains only the returned value; informational messages remain in `ScalaModeOutput.logs`.
Other tools, including `scalaunsafe`, use `FunctionGenericOutput(callId,name,output)` with no logs field.
Malformed `scalamode` JSON is rejected; there is no legacy JSON fallback or migration.
Use the **last expression**, not `println`, to return a value.

### Compiled runtime API

Every invocation uses the compiled `scalacode-api` JVM module, automatically imported into the snippet:

```scala
tool.api // prints "Yes Tool API is available" through the trusted API
42  // the last expression is still returned
```

The result is `42\n`; `logs` contains `Yes Tool API is available`.

The model sees only the public contract in the tool description: `def api: Unit` prints a fixed API availability message. It does not receive the implementation source. The implementation is normal compiled code in `scalacode-api/jvm/src/main/scala/fmgp/yallmo/scalacode/ScalaToolApi.scala`. The executor locates the API module's classes directory or JAR and supplies it through Scala CLI's `--classpath`, without exposing the application's full dependency classpath. Generated snippets use `import fmgp.yallmo.scalacode.{ScalaToolApi as tool}` and `import tool.*`, so both `api` and `tool.api` work. No API source resource is loaded or copied.

Only the API availability helper implementation uses `@caps.assumeSafe` to cross the compiler's trust boundary. Its enclosing object is marked `@experimental`, allowing normal module compilation without enabling experimental mode for the rest of the application. Submitted code still cannot use annotations, print directly, or access ambient JVM authority. Adding privileged helpers expands the trusted API and requires review; this prototype is not a general tool bridge.

- Requires Scala CLI on PATH as `scala-cli`, plus a local JDK. REPL startup advertises it only when `scala-cli --version` succeeds.
- Starts a fresh background `scala-cli run .` process in an isolated temporary directory (the explicit project root) for each call, using the hard-coded `3.10.2-RC1-bin-20261021-a98b104-NIGHTLY` compiler with `--offline --jvm system --server=false`. The main project's Scala version is unchanged. The compiler and its dependencies must already be in the Scala CLI/Coursier cache; calls do not perform a latest-nightly lookup.
- Compiles the snippet with `scala.language.experimental.safe`, capture checking and modularity. The compiled `scalacode-api` module and host-configured dependencies are supplied, not the application classpath. A separate trusted main prints the returned value.
- No user-supplied dependencies, directives, annotations, Unicode source escapes, backticks or compiler safety overrides. The small validator intentionally also rejects these inside strings/comments; it is not a Scala parser or an API blacklist.
- Defaults: 45-second process deadline (including compilation), 128 MiB evaluation heap, 64 KiB shared retention budget for the result and logs, 65,536 source characters. Output beyond the cap is drained and discarded, not written to an unbounded log. Timeout/cancellation kills the process tree; temporary source/build files are removed.
- The tool deadline is shorter than the default two-minute agent budget so a stalled compiler can return diagnostics to the model before the whole turn is cancelled. Start/finish logs report the selected compiler, offline mode and timeout status without logging submitted code.
- No persistent REPL or general tool bridge. The compiled API module and host-configured dependencies are on the classpath for every call, but user definitions do not persist.

### Host-configured dependencies

`ScalaTool` accepts a trusted host-side `Seq[String]` of Scala CLI dependency coordinates. It defaults to `ScalaTool.DefaultDependencies` (`Seq("dev.zio::zio:2.1.27")`):

```scala
new ScalaTool() // ZIO by default
new ScalaTool(dependencies = ScalaTool.DefaultDependencies :+ "dev.zio::zio-streams:2.1.27")
new ScalaTool(dependencies = Seq.empty) // No external dependencies
```

A supplied sequence replaces the defaults. Each entry is passed as a separate `--dependency` argument. Offline mode requires all selected artifacts and their transitive dependencies to be cached. Dependencies are not accepted in the model's JSON arguments or code directives. Only configure trusted libraries: adding dependencies expands the code available to snippets; compiler safety restrictions still apply.

### Pinned nightly / offline use

The compiler version is hard-coded in `ScalaCodeExecutor.ScalaVersion`, and offline mode is enabled by default. No environment variables are required; `YALLMO_SCALA_VERSION` and `YALLMO_SCALA_OFFLINE` are no longer read. Restart the REPL after rebuilding to pick up this change.

This avoids `3.nightly` resolution, which contacts `repo.scala-lang.org` even when compiler jars are cached and can stall if the repository is unreachable. Missing cached artifacts now fail without waiting for a network download. Provision the pinned compiler in the Scala CLI/Coursier cache before running the tool on a new machine. Updating to a newer nightly is an explicit source change followed by safety tests.

### Safety review / inspiration

[ATC](https://github.com/lampepfl/atc) and [TACIT](https://github.com/lampepfl/tacit) combine compiler safe mode, capture checking, lexical validation, and trusted capability APIs. Their permission checks govern operations exposed by those APIs; **they do not lock down the JVM**. An allowed external command still runs with the user's OS privileges. See ATC's README security model and both projects' `CodeValidator` / REPL implementations.

The default pure mode supplies a **fixed-message output helper**; configured server execution additionally supports the explicitly selected read-only filesystem effect mode below. Safe mode, rather than regex matching of JVM API names, rejects ambient filesystem/network/process/environment access, reflection, casts and global printing. Annotations are rejected so submitted code cannot declare itself `@assumeSafe`. The compiler, JDK, Scala CLI, trusted runtime API and trusted runner remain part of the trusted computing base.

[Pi codemode](https://pi.dev) is inspiration for code-in/result-out and returning only the computation result to the agent. Its QuickJS sandbox lacks Node/filesystem/network APIs; ordinary Scala/JVM does not have that property. There is no general `tools` bridge: filesystem mode exports only `FsRead`.

**Experimental compiler restrictions, not an OS/JVM sandbox. Do not expose this prototype as a hostile-code execution service.** Compiler defects or future nightly regressions can invalidate the restrictions. Compilation runs with the host user's privileges, can download artifacts and is not given a hard OS memory/CPU/disk quota; the evaluation heap cap is not a whole-process/container quota. Nightly compiler updates require rechecking the restrictions.

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

The configured standalone server owns a **separate loopback, ephemeral-port capability listener** (not the UI `/rpc` listener). It supplies its exact `RpcWebSocket.RpcRoute` and trusted endpoint to application assembly. Viewer mode creates no capability listener. Existing `new ScalaTool()`, executor `execute`, `ServerApplication.open`, and embedded `ReplStartup` remain pure-only and start no listener. A connected terminal uses its configured server's tools; a real connected interactive prompt was not exercised in this phase.

Filesystem-enabled `scalamode` keeps existing `{ "code": "..." }` callers unchanged. Select the effect boundary explicitly:

```json
{"mode":"filesystem","code":"for { text <- fs.readUtf8(\"notes.txt\"); names <- fs.list(\".\"); bytes <- fs.readBytes(\"notes.txt\").take(3).runCollect; recovered <- fs.readUtf8(\"missing\").fold((e: FsError) => e.toString, identity) } yield text + names.mkString(\",\") + bytes.size + recovered"}
```

`mode` is optional (`pure` by default); only `pure` and `filesystem` are accepted. In filesystem mode the code is the body of:

```scala
def result(fs: FsRead)(using zio.Trace, zio.CanFail[FsError]): zio.ZIO[Any, FsError, Any]
```

The effect's success type is widened covariantly, never erased and cast back into ZIO. `FsRead`, `FsError`, `ZIO`, `api` and `tool.api` are imported; there is no ambient service environment. Return an effect, not its executed value. Use `fold` for error recovery; automatic trace/CanFail materialization and FsError case extractors retain the Phase-1 compiler limitations. Pure mode still prints the last expression without executing returned effects.

Each effect invocation exports only `Rpc.derived[FsRead]` (default fully-qualified identity, version 1). The trusted child Main reads endpoint and single-use bearer from **stdin**, connects once, constructs a typed proxy locally, runs the typed effect and closes its connection. Stdin forwarding was verified with the real pinned/offline Scala CLI. Credentials are absent from submitted/generated sources, command arguments, URLs and logs/output. The proxy exposes no connection or bearer. No uploads, FsWrite, reconnect or retry are added.

The trusted endpoint must be a single-line `ws`/`wss` URI with a host, at most 2048 UTF-8 bytes; validation precedes registration and process launch. Bootstrap delivery runs concurrently with the process deadline, and cancellation destroys the child to unblock a pending stdin write.

The invocation scope owns `registerScoped` authority, including authenticated socket/work cleanup on completion, timeout, cancellation, compilation or launch failure. Pending-ticket TTL equals the compilation/execution deadline (45 seconds by default); the route rejects deadlines above its configured maximum (five minutes by default). Existing process-tree cleanup, heap/output/source bounds remain. Cooperative host finalization may wait on uninterruptible work; these compiler restrictions are **not OS isolation**.

Assembly retains the workspace capability and the exact per-agent filesystem opened during lazy initialization. Native OAIAgent/ChatGPTAgent **explicit filesystem configuration is now activated and validated at selection**; invalid private roots fail rather than falling back. In RPC-enabled assembly native readFile/listFiles/filesystem-scala all use that selected capability; native agents without private roots use the already-open workspace. Coach/Folder retain their exact private capability too, without gaining scalamode: Coach allowlists remain unchanged; Folder binding enforces an empty tool allowlist for both normal turns and direct ToolCall invocation. Bash/Git remain workspace-bound. Tool definitions and execution share the same selection binding without duplicate keys.

Domain `FsError`s retain their JSON representation. Infrastructure failures use the integration policy `FsError.IoFailure("<rpc>", "RPC " + error.code.toString)`; `<rpc>` is a sentinel, not a relative filesystem path. This total mapper is identical in host derivation and trusted child source; raw transport exceptions/messages are not exposed. The child classpath includes only compiled scalacode-api/filesystem/RPC artifacts and pinned ZIO/streams/JSON/HTTP dependencies, not core/model/server or the whole application classpath.

Focused checks (seven tests, including real children and thirteen expanded-classpath compiler-negative probes):

```bash
sbt 'set coreJVM / Test / envVars += ("YALLMO_TEST_SCALA_CLI" -> "/usr/share/coursier/scala-cli");core/testOnly fmgp.yallmo.tool.ScalaFilesystemRpcSuite'
```

`ApplicationRuntimeSuite` additionally checks concurrent selected roots, invoke/session parity, retained identity, workspace fallback, invalid-root rejection, consistent read/list/scala binding and Coach/Folder restrictions. These concurrent-root checks use a mocked executor/provider, not the full real stack.

`ScalaFilesystemApplicationSuite` is an opt-in configured-application acceptance test: the existing UI WebSocket turn path, `ServerApplication.openRpc`, real compiler/child and authenticated filesystem RPC use loopback listeners; a deterministic local HTTP fake provider requests the tool and returns its result. It checks advertised filesystem mode, a private root different from workspace, traversal/no-fallback errors, viewer denial and persisted response. No external provider, credentials or interactive terminal are exercised.

```bash
sbt 'set server / Test / envVars += ("YALLMO_TEST_SCALA_CLI" -> "/usr/share/coursier/scala-cli"); server/testOnly *ScalaFilesystemApplicationSuite'
```

## FUTURE (Not now)
### SANDBOX

Add an OS/container isolation boundary around **compilation as well as execution**, with no host credentials/workspace mounts, no evaluation network and hard resource limits. Keep compiler/capability checks as defense in depth, not a replacement for isolation.
