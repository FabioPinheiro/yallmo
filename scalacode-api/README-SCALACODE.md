# scalacode-api

Runtime API for restricted `scalamode` snippets, separate from the application classpath.

- CrossProject: `scalaCodeApi`, with `scalaCodeApiJVM` and `scalaCodeApiJS` variants.
- sbt task IDs: `scalaCodeApi` (JVM) and `scalaCodeApiJS` (Scala.js).
- Shared sources: `shared/src/main/scala`.
- Artifact: `yallmo-scalacode-api`
- Packages: `fmgp.yallmo.tool.scalamode`
- Dependencies: matching model, filesystem and rpc CrossProjects plus Scala/ZIO, upickle 4.3.2 for shared tool argument parsing, and Scala.js java-time compatibility. No core or server dependency (including tests); WebSocket bootstrap is platform-owned (JVM and explicitly opted-in Node). File logging has JVM and explicitly opted-in Node implementations.

## Host-facing contracts and module boundary

The package `fmgp.yallmo.tool.scalamode` is preserved across the source relocation:

| Source set | Ownership |
| --- | --- |
| Model shared | Canonical `ScalaCodeExecutor` pure execution/availability contract, default dependencies and compiler pin. |
| API shared | `ScalaToolApi`, `ScalamodeToolDefinition`, `ScalaCodeExecutorServices` capability and `executeWithServices` extension, `ScalamodeService` and binding metadata, `ScalaTool` and argument validation, `AgentToolWirings`, native read contracts/codecs and `NativeReadToolsRuntime`, and pure `ScalaToolRuntime` evaluation. |
| API platform-specific | `ScalamodePlatform.RpcRoute` aliases the existing `RpcWebSocket.RpcRoute` on JVM and is an unavailable placeholder handle on JS. `ScalaToolRuntimePlatform` provides JVM WebSocket/stdin bootstrap and file logging. Native JS WebSocket supplies Node RPC; non-Node bootstrap fails explicitly with `UnsupportedOperationException`. The trusted Node runner also supports bounded file logging and the execution marker. |
| Core JVM only | `ScalaCodeExecutors.live` / `.layer(...)`: Scala CLI/process engine, generated source, classpath assembly, diagnostics, cleanup. `LocalProcess` and `CommandOutput` stay core-only too. |

`ApplicationTools.live` uses `ScalaCodeExecutors.live`. Host callers configuring the executable use
`ScalaCodeExecutors.layer(...)`; custom executors and consumer environments still use the canonical model interface
`ScalaCodeExecutor` at the same FQN. Service-aware implementations additionally mix in the API-only
`ScalaCodeExecutorServices` capability (self-typed to the canonical executor); it declares only
`executeWithServices[R <: ScalamodeService]`, not another pure execution contract. Callers with selective imports
must add `import fmgp.yallmo.tool.scalamode.executeWithServices`. Base-only executors fail with
`UnsupportedOperationException("Service Scala execution is not enabled")`, without pure fallback or transport access.
Existing custom service overrides need the capability mixin; moving the trait between artifacts requires rebuilding
consumers. Compiled `Binding` now selects platform-owned `Products`; transport dependency pins are JVM-runner-owned, not portable metadata.
Pure-call defaults have one model owner, `ScalaCodeExecutor.DefaultDependencies`; `ScalaTool.DefaultDependencies`
forwards to it. Deadlines/output limits are unchanged. The operator hook also accepts this canonical executor,
while preserving approval, original tool limits and sanitized JS / Node failures without JVM fallback.

The contracts, service inspection, tool validation, hello-world wiring, native RPC contract derivation and pure
runner helpers are shared with Scala.js. Custom executors can implement the same interface on either platform;
the Scala CLI/process engine remains JVM-only. Default filesystem services use executor-provided loopback RPC.
`ScalaCodeExecutors.live` / `.layer(...)` lazily start one listener per layer lifetime, shared across executions;
service dispatchers, single-use tickets and connections remain invocation-scoped. The application already shares this
layer through `ApplicationTools.live`. Explicit route overloads and native exact-delegation wiring retain caller-owned listeners;
sharing the API does not install a browser transport or grant browser filesystem access. JS `runRpc` and logger `bootstrap`
without Node opt-in fail through their effect/layer error channels without evaluating submitted callbacks.

The JVM host selects `ScalaCodeExecutors.node(reviewedArtifacts)` for Scala.js compilation with a retained linker
and fresh Node evaluation children. Service preparation, bounded processes, diagnostics and result assembly are shared
with JVM through `ScalamodeInvocation`, `ScalamodeProcess` and `ScalamodeProgram`. Node 22.4+ with native WebSocket
is probed during provisioning. Bound services use the same invocation-scoped host dispatchers and stdin-only bootstrap.
Custom `Binding.products` map reviewed JVM class anchors and Scala.js IR artifacts explicitly; unsupported/missing
platform products and dependency mappings fail before execution. No service stripping or JVM fallback is allowed.
See [backend parity delivery and validation](../docs/tools/SCALAMODE-BACKEND-PARITY-PLAN.md).

The API artifact contains host-facing factories, but they are **not** safe snippet capabilities.
`FilesystemModes` adds narrow `assumeSafe` accessors for immutable mutation-mode values, not filesystem authority.
Real compiler probes reject acquiring executor services, constructing broader
tool/RPC authority, reaching private checks or looking up the host-only engine. Classpath/artifact tests verify the
engine and general host helpers are absent. Compiler checks remain defense in depth, not OS isolation.

`ScalaToolSuite` and `ScalamodeSharedSuite` run on both JVM and JS; JS additionally tests explicit unsupported
runner failures. JVM-only suites cover bounded file logging, listener-backed wiring and inspection. Live/fake engine
and generic service suites remain in core, which consumes the unpublished `scalamodeTestFixtures` products via `test->compile`. The fixture project
depends on this API but never appears on production or bundled child classpaths unless explicitly granted by a test binding.
API tests never depend on core. Run `sbt ';scalaCodeApi/testOnly *;scalaCodeApiJS/testOnly *'` (explicit suites avoid sbt 2 testQuick caching).

## Runtime API

`ScalaToolApi.api` prints a fixed API availability message directly to stderr, without changing global stdout. This method is `@experimental` because it uses
`caps.assumeSafe`; the API object itself is a stable anchor for locating the compiled module.

`ScalaToolApi.ZIO` is a restricted facade, automatically imported as `ZIO` alongside the matching type alias.
It exposes `succeed`, `fail`, `attempt`, `fromEither`, `foreach` and `unit`, not the full unsafe library companion.
Each constructor is a narrow `@caps.assumeSafe` boundary; supplied expressions/callbacks are still checked by the
snippet compiler. The host supplies Trace evidence. For example, `ZIO.succeed(1 + 1)` returns `2`: the trusted
runner executes returned environment-free effects, retaining inferred types rather than recovering effects from
`Any` with casts. Plain values still work. Direct `import zio.ZIO`, runtime access and ambient I/O remain rejected.

Core's `ScalaCodeExecutors` supplies this module's classes directory or JAR to Scala CLI and imports `ScalaToolApi` as
`tool`, along with its members. No API source resource is copied into snippets. Both `core` variants depend
on the matching API variant; compilation and execution remain JVM-only.

`jvm/src/main/scala/fmgp/yallmo/tool/scalamode/ScalaToolRuntimePlatform.scala` supplies the trusted
`ScalaToolRuntime.bootstrap` logger layer used by `ZIOAppDefault`. It replaces the default logger with INFO-and-above JSON-lines events in a scoped, bounded
`runtime.jsonl` file. This JVM-only infrastructure is not annotated `assumeSafe`; snippets cannot access it as a
capability. The host collects retained events before cleaning up the invocation's temporary directory.

Filesystem capabilities live in the separate `filesystem` module (`fmgp.yallmo.fs.FsRead` / `FsWrite`).
Native filesystem tools retain their permissions. Scalamode itself is code-only. `ChatGPTAgent` installs hello and,
when both native read/list functions are permitted, the selected rooted filesystem API by default. The workspace
composition supplies read/write capabilities; a configured private root takes precedence for both.
Opt-in `AgentToolWirings.withFilesystem` instead delegates only already-filtered native read/list functions over scoped RPC,
with a caller-owned listener; it never captures unfiltered `FsRead`. Retired `mode` arguments are rejected as unknown.

See [restricted Scala tool](../docs/tools/TOOL-SCALA-SCRIPT.md).

## Agent-defined services and native read tools

The shared `NativeReadTools.scala` contains narrow snippet-facing contracts. `NativeReadFile.readFile` and
`NativeListFiles.listFiles` return `IO[ReadToolError, String]`: **the exact selected native tool text**, normally UTF-8
contents and newline-separated direct entry names. `NativeReadTools` combines the views. `ReadToolError` retains native
failure kind/message, not exception causes. `NativeReadToolsRuntime` owns the once-derived concrete contract, sanitized
infrastructure error mapper, proxy creation and partial-view wrappers. Host delegation still uses the exact filtered
native functions/schemas, never a broader raw filesystem grant; missing methods are independently denied on the host.

`ScalaToolRuntime.runPure` / `runRpc(concreteContract)` implement shared lazy callback execution, printing and scoped
stdin bootstrap/connect/cleanup. The generic callback returns `ZIO[Any, E, A]`, preserving inferred errors and values.
The thin generated entry calls the overloaded `evaluate` where the snippet result type is known. No effect is erased
and recovered with runtime casts. Unsafe runners, tokens and connections are not ambient safe snippet capabilities.

Host `ScalamodeService.Binding` is validated names/metadata only: parameter, compiled API views, compiled runner method, optional
platform-owned `Products` (JVM class anchors, Scala.js IR paths and reviewed dependency mappings), error type and pinned dependencies. It has no whole-program/preamble source fields.
Pure and service classpaths explicitly include scalacode-api/model/filesystem/rpc, not core/server or full application
artifacts. Pure runners also require those reviewed signature dependencies because ScalaToolRuntime exposes RPC
methods; mandatory runner dependencies include pinned zio-streams as well as ZIO/ZIO JSON. Custom helpers and RPC contracts must live in reviewed narrow compiled artifacts; test fixtures demonstrate
both a pure arithmetic helper and a non-filesystem greeting RPC without injected source implementations.
`ScalaTool.withServices(service)` captures an agent-chosen descriptor behind the existing code-only Scalamode function.
See [compiled bindings and agent opt-in](../docs/tools/TOOL-SCALA-SCRIPT.md#agent-defined-service-bindings-opt-in)
for examples, provisioning, failure semantics and compiler-only safety limits.

## Validation

Current commands/results and historical relocation/compiled-service checkpoints are recorded in
[the durable plan](../docs/AGENT_TOOL_WIRING_PLAN.md). Core's `ScalamodeServiceSuite` is the canonical real pinned/offline
compiler and child RPC integration suite; `ScalaCodeExecutorSuite` covers the shared bounded process engine. Compiler
safety is experimental defense in depth, not OS isolation.

`HelloWorldService` supplies the pure compiled `hello.helloWorld: String` API. `ChatGPTAgent` wires it into an
existing permitted Scalamode function by default, including catalog-created agents such as `sol`. It is **not** a
separate native tool; calls remain `scalamode({"code":"hello.helloWorld"})`. The model description and inspector
list the same bound service. `ZIO.succeed(hello.helloWorld)` is also supported.

`AgentToolWirings.withHelloWorld(executor)` and `.withService(service, executor)` replace only default Scalamode;
missing Scalamode and custom service bindings are preserved. Server composition reuses the configured executor.
Native read service selection remains explicit through
`withFilesystem(executor, route, endpoint): ToolWiring` and a
caller-owned existing RPC listener. See the [composition example](../docs/tools/TOOL-SCALA-SCRIPT.md#native-readlist-utility-exact-delegation-not-a-raw-filesystem-grant).
`AgentToolWirings.withDefaults(executor, filesystem, filesystemWrite)` is ChatGPTAgent's default stage after custom
wiring. With both native filesystem functions permitted it binds `fs` and retains `hello`; without the complete
filesystem grant it binds only hello. Missing Scalamode and existing custom bindings are preserved. Catalog-created
agents receive their private filesystem when configured, otherwise the server workspace. **Permitting both native
filesystem functions and Scalamode therefore enables rooted mutations when that root has an FsWrite capability.**
A read-only embedded root remains read-only. Resolution opens no listener and executes no snippet. Keep the live
executor layer alive for the application lifetime: the first local-service execution starts its shared listener, and
layer shutdown closes it. Each invocation owns only its ticket, connection and service-work cleanup. Pure executions
and explicit caller-owned routes do not start this listener. The resource-free `ScalaCodeExecutors.make(...)`
compatibility constructor has no application scope and retains per-invocation listeners for local bindings; embedded
applications wanting reuse should build `.layer(...)` once and keep its scope alive while using the executor.

`Binding.constants` supports additional reviewed pure singleton parameters (such as `hello`) using validated names
and `.type` references only, not injected source. All parameters share the same model/inspector projection.

## Explicit FsRead / FsWrite services

`FilesystemService` also supports explicit composition of rooted capabilities behind the code-only Scalamode tool:

- `FilesystemService.read(read, route, endpoint)` exposes the actual `FsRead` interface as `fs`: lazy streamed
  `readBytes`, UTF-8 `readUtf8`, and structured `list: IO[FsError, Chunk[String]]`.
- `FilesystemService.write(write, route, endpoint)` exposes `FsWriteApi` as `fs`: `writeUtf8`, `createDirectories`,
  `deleteFile`, `deleteDirectory`, and `move`, retaining explicit modes and typed `FsError` failures.
- `FilesystemService.readWrite(read, write, route, endpoint)` explicitly combines both views as `FilesystemApi`.

The overloads `read(read)`, `write(write)` and `readWrite(read, write)` need no listener configuration; the JVM executor
provides scoped local RPC on either backend. `FilesystemService.default(read, optionalWriter)` also includes the pure hello parameter.

The write view deliberately omits `writeBytes` and `openUtf8Writer`: RPC has no stream arguments/uploads or exported
scopes. UTF-8 writes are unary requests and subject to RPC payload limits; reads remain pull-driven streams.
Domain errors round-trip unchanged; infrastructure failures map to `FsError.IoFailure(".", "RPC <code>")`.
Neither read-only nor write-only descriptors register the other view's contract.

For custom caller-owned-listener wiring, use:

```scala
val wiring = AgentToolWirings.withFilesystemServices(
  read = agentFs,
  write = None, // Some(explicitlyAuthorizedWriter) opts into mutations
  executor = executor,
  route = route,
  endpoint = endpoint
)
```

This explicit helper is separate from the automatically installed `withDefaults` stage. It replaces only default Scalamode, and only
when **both** native `readFile` and `listFiles` functions belong to the selected filesystem tool. Missing/partial
filesystem tools, missing Scalamode, and custom service bindings are preserved. Apply it before hello-world fallback
wiring. The host must supply the intended agent root; raw capabilities intentionally do not preserve custom native
tool transformations. Keep `withFilesystem` for exact delegation of selected native functions, including partial grants.
A read-only binding is never implicitly upgraded to write access. Standalone write-only services can be bound with
`withService` when explicitly authorized by the host.

Snippet example with an explicitly writable combined service:

```scala
import fmgp.yallmo.tool.scalamode.FilesystemModes

for {
  _ <- fs.writeUtf8("note.txt", "hello", FilesystemModes.createNew)
  text <- fs.readUtf8("note.txt")
} yield text
```

Safe mode values are `FilesystemModes.createNew`, `replace`, `append`, `emptyOnly`, `recursive`, `failIfExists`,
and `replaceFile`. The original enum companions are not safe snippet constructors. Public API inspection includes
these instructions, but never endpoints, dispatchers, tickets, or runtime implementation details.

Validation: `scalaCodeApi/testOnly *FilesystemServicesSuite *AgentToolWiringsSuite` covers real RPC, typed errors,
permission separation, and wiring. `YALLMO_TEST_SCALA_CLI=/path/to/scala-cli sbt 'core/testOnly *FilesystemServiceCompilerSuite'`
checks actual pinned/offline snippet compilation and child execution, including rejecting read/write view expansion.
