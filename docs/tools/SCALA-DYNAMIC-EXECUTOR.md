# Dynamic Scala executor (trusted code only)

`ScalaCodeDynamicExecutor` compiles a snippet into a library JAR with Scala CLI, then loads it with a fresh classloader and runs its ZIO effect inside the host JVM. The restricted tool uses `scalamode`; trusted in-process execution uses `scalaunsafe`. The dynamic registration in `ApplicationTools` is currently disabled.

**Dynamic execution grants generated code the server's privileges. It is not a sandbox. Use only in a trusted environment.**

## Configuration at creation, services at execution

The executor holds compiler configuration only. Its layer does not require or capture a `ZEnvironment`:

```scala
val config = ScalaCodeDynamicExecutor.Config(
  // Retain scalacode-api and explicitly add the filesystem artifact and its dependencies.
  classpath = filesystemCompilerClasspath,
  apiDescription = "fmgp.yallmo.fs.FsRead: def readUtf8(path: String): zio.IO[fmgp.yallmo.fs.FsError, String]"
)
val compiler = ScalaCodeDynamicExecutor.layer(config)
```

`execute[R]` returns `ZIO[R, Throwable, CommandOutput]`. Select the script-facing subset of the calling agent's environment with `R`; the executor obtains those services at execution time and prunes the runtime environment to that subset before running the script.

```scala
import fmgp.yallmo.tool.*
import fmgp.yallmo.fs.{FileSystem, FsRead}
import zio.*

val execution: ZIO[ScalaCodeDynamicExecutor & FsRead, Throwable, CommandOutput] =
  ZIO.serviceWithZIO[ScalaCodeDynamicExecutor](
    _.execute[FsRead](
      """ZIO.serviceWithZIO[fmgp.yallmo.fs.FsRead](
        _.readUtf8("example.txt").mapError(error => new java.io.IOException(error.toString))
      )""",
      timeout = 30.seconds,
      maxOutputBytes = 4096
    )
  )
```

The caller supplies `FsRead` directly through its normal ZIO environment when running `execution`, not when constructing the executor. Open `FileSystem.open(trustedRoot)` in trusted host code and provide it with `ZEnvironment[FsRead](fs)`; there is no script-specific filesystem adapter. Operations use root-relative paths; absolute paths, `..`, and symlinks are rejected. Map `FsError` to a `Throwable` at the script boundary as shown above. `filesystemCompilerClasspath` is trusted host configuration containing scalacode-api, filesystem, zio-streams, zio-json and their dependencies; the default classpath intentionally remains scalacode-api only.

A snippet must return a ZIO effect whose error type is `Throwable` (or a subtype). The host selects `R` at each execution; no environment type string is needed in compiler configuration. Intersections such as `example.Database & example.Notifications` are supported. Include the available service types and signatures in `Config.apiDescription`.

The shared contract is `ScalaScript` with `def program[R]: ZIO[R, Throwable, Any]`. A concrete service requirement cannot be proven for an arbitrary method-level `R`: the caller must supply all services the snippet uses, and the host prunes the environment to the selected subset. **Existing limitation:** the current generated bridge does not adapt a concrete service requirement to its generic `R`, so service-dependent snippets such as the `FsRead` examples above currently fail compilation. The opt-in filesystem integration test exposes this pre-existing issue; the examples describe the intended host provisioning, not a workaround. Dynamic registration remains disabled.

## Use from an agent

Create an agent tool with the subset scripts should receive:

```scala
// Construction requires neither an executor instance nor script-facing services.
val tool = new ScalaDynamicTool[FsRead](config)
// AgentTool[ScalaCodeDynamicExecutor & FsRead]

val invocation: ZIO[ScalaCodeDynamicExecutor & FsRead,
  fmgp.yallmo.model.YallmoError, fmgp.yallmo.orchestration.ExternalToolResult] =
  tool.execute(
    fmgp.yallmo.orchestration.ExternalToolCall(
      fmgp.yallmo.model.ToolKey.ScalaUnsafe,
      """{"code":"ZIO.serviceWithZIO[fmgp.yallmo.fs.FsRead](_.readUtf8(\"example.txt\").mapError(error => new java.io.IOException(error.toString)))"}"""
    )
  )
```

Register this tool in the agent's `AgentRunner` and advertise `tool.definition` in its `StaticContext`. The runner must supply `ScalaCodeDynamicExecutor & FsRead` and may have additional services: `AgentTool` is contravariant, so this tool works in a larger agent environment. Each invocation looks up its current executor and script services; only the `FsRead` subset is passed to the script, not the executor itself or the agent's other services. The same tool can be reused by concurrent agents with different environments.

`ScalaDynamicTool` captures configuration only. Use `Config.apiDescription` to advertise the services provided by the tool's `R`, and keep its compiler/API configuration consistent with `ScalaCodeDynamicExecutor.layer`. The LLM calls `scalaunsafe` with `{"code":"..."}`; include `"scalaunsafe"` in an explicit tool allowlist.

The two tools are independent: `ScalaTool` requires `ScalaCodeExecutor` and advertises `scalamode`; `ScalaDynamicTool[R]` requires `ScalaCodeDynamicExecutor & R` and advertises `scalaunsafe`. They have no mode switch and can coexist in one runner. With `R = Any`, the dynamic tool requires only `ScalaCodeDynamicExecutor`.

`ApplicationTools` registers only the restricted tool; dynamic probing, service wiring and registration are disabled. Custom trusted host wiring may explicitly supply `FsRead` as above; startup does not create a dynamic filesystem wrapper. Rooted filesystem validation is not an OS sandbox and does not constrain arbitrary dynamically loaded JVM code.

There is no dynamic-execution configuration flag. If trusted host wiring registers the dynamic tool, agents without an explicit tool allowlist can use it. To deny trusted in-process execution, exclude `scalaunsafe` from the agent's `capabilities.tools` allowlist. Custom host wiring can still use `ScalaDynamicTool[R]` with a different service subset.

Key migration: restricted-tool callers and allowlists must change `scalasafemode` (or `scalasafe`) to `scalamode`. Dynamic-tool callers must change their former `scalamode` (or `scalaDynamic`) key to `scalaunsafe`. These are not compatibility aliases: `scalamode` now selects restricted execution, and only `scalaunsafe` selects trusted in-process execution.

## Compiler and runtime boundaries

- Match the host's Scala/ZIO versions (defaults: `3.10.0` / `2.1.27`). Scala CLI must be available on PATH.
- The script contract and portable service interfaces live in the independent JVM/JS [`scalacode-api`](../../scalacode-api/README-SCALACODE.md) module. Compilation and classloading remain JVM-only.
- The extra compiler classpath defaults to the `scalacode-api` artifact only, not the host classpath. Add custom service API artifacts and their dependencies explicitly through `Config.classpath`, retaining the API artifact itself.
- Deploy the API as a separate artifact to preserve this compiler dependency boundary. For a host fat JAR, point `Config.classpath` at a separate, matching `scalacode-api` JAR.
- The generated JAR contains script code only. `ScalaScript` (with method-level `program[R]`), service interfaces, Scala, and ZIO resolve through the host parent classloader.
- Filesystem service contracts live in the separate `filesystem` module, not `scalacode-api`. `FsRead` uses root-relative string paths and typed `FsError` failures.
- Compiler directives, annotations, and other compiler-configuration escape hatches are rejected both by the tool and direct executor calls.
- The timeout covers compilation and execution. Compiler processes are terminated on timeout/cancellation. In-process ZIO cancellation is cooperative: non-cooperative loops, blocking calls, and finalizers may outlive the deadline.
- Results contain compiler diagnostics and the returned value or failure, bounded by `maxOutputBytes`. Excess compiler output is drained without an unbounded log file. Global stdout/stderr and ZIO logs are not captured.
- Selecting/pruning `R` limits ZIO service provisioning, not JVM privileges. Generated code still has access beyond the advertised API.

Integration tests opt in with `YALLMO_TEST_SCALA_CLI=/absolute/path/to/scala-cli`. With a persistent sbt server, set that variable in `coreJVM / Test / envVars` for executor tests or `server / Test / envVars` for application-wiring tests instead of the client shell.
