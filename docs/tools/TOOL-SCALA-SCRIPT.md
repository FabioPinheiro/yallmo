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

The `scala` agent tool accepts one JSON argument:

```json
{"code":"val numbers = List(1, 2, 3); numbers.map(_ * 2).sum"}
```

It returns `{"exitCode":0,"timedOut":false,"output":"12\n","truncated":false}`.
Compiler diagnostics and runtime exceptions appear in `output` with a nonzero exit code.
Use the **last expression**, not `println`, to return a value.

- Requires Scala CLI on PATH as `scala-cli`, plus a local JDK. REPL startup advertises it only when `scala-cli --version` succeeds.
- Starts a fresh background `scala-cli run .` process in an isolated temporary directory (the explicit project root) for each call, using the hard-coded `3.10.2-RC1-bin-20261021-a98b104-NIGHTLY` compiler with `--offline --jvm system --server=false`. The main project's Scala version is unchanged. The compiler and its dependencies must already be in the Scala CLI/Coursier cache; calls do not perform a latest-nightly lookup.
- Compiles the snippet with `scala.language.experimental.safe`, capture checking and modularity. No machine capabilities or application classpath are supplied. A separate trusted main prints the returned value.
- No dependencies, directives, annotations, Unicode source escapes, backticks or compiler safety overrides. The small validator intentionally also rejects these inside strings/comments; it is not a Scala parser or an API blacklist.
- Defaults: 45-second process deadline (including compilation), 128 MiB evaluation heap, 64 KiB retained combined stdout/stderr, 65,536 source characters. Output beyond the cap is drained and discarded, not written to an unbounded log. Timeout/cancellation kills the process tree; temporary source/build files are removed.
- The tool deadline is shorter than the default two-minute agent budget so a stalled compiler can return diagnostics to the model before the whole turn is cancelled. Start/finish logs report the selected compiler, offline mode and timeout status without logging submitted code.
- No persistent REPL, tool bridge or additional dependencies yet.

### Pinned nightly / offline use

The compiler version is hard-coded in `ScalaCodeExecutor.ScalaVersion`, and offline mode is enabled by default. No environment variables are required; `YALLMO_SCALA_VERSION` and `YALLMO_SCALA_OFFLINE` are no longer read. Restart the REPL after rebuilding to pick up this change.

This avoids `3.nightly` resolution, which contacts `repo.scala-lang.org` even when compiler jars are cached and can stall if the repository is unreachable. Missing cached artifacts now fail without waiting for a network download. Provision the pinned compiler in the Scala CLI/Coursier cache before running the tool on a new machine. Updating to a newer nightly is an explicit source change followed by safety tests.

### Safety review / inspiration

[ATC](https://github.com/lampepfl/atc) and [TACIT](https://github.com/lampepfl/tacit) combine compiler safe mode, capture checking, lexical validation, and trusted capability APIs. Their permission checks govern operations exposed by those APIs; **they do not lock down the JVM**. An allowed external command still runs with the user's OS privileges. See ATC's README security model and both projects' `CodeValidator` / REPL implementations.

This first version supplies **no effect capabilities at all**. Safe mode, rather than regex matching of JVM API names, rejects ambient filesystem/network/process/environment access, reflection, casts and global printing. Annotations are rejected so submitted code cannot declare itself `@assumeSafe`. The compiler, JDK, Scala CLI and trusted runner remain part of the trusted computing base.

[Pi codemode](https://pi.dev) is inspiration for code-in/result-out and returning only the computation result to the agent. Its QuickJS sandbox lacks Node/filesystem/network APIs; ordinary Scala/JVM does not have that property. There is deliberately no `tools` bridge here yet.

**Experimental compiler restrictions, not an OS/JVM sandbox. Do not expose this prototype as a hostile-code execution service.** Compiler defects or future nightly regressions can invalidate the restrictions. Compilation runs with the host user's privileges, can download artifacts and is not given a hard OS memory/CPU/disk quota; the evaluation heap cap is not a whole-process/container quota. Nightly compiler updates require rechecking the restrictions.

### Validation

```bash
sbt 'core/testOnly fmgp.yallmo.tool.ScalaToolSuite fmgp.yallmo.tool.ScalaCodeExecutorSuite'
# Opt-in real compiler tests (requires the pinned compiler in cache):
YALLMO_TEST_SCALA_CLI="$(command -v scala-cli)" sbt 'core/testOnly fmgp.yallmo.tool.ScalaCodeExecutorSuite'
```

For an already-running sbt server, set `core / Test / envVars` explicitly so the forked test JVM sees `YALLMO_TEST_SCALA_CLI`. Integration tests default to the same pinned/offline compiler as the tool. They also accept `YALLMO_TEST_SCALA_VERSION` and `YALLMO_TEST_SCALA_OFFLINE` for explicit test overrides.

## FUTURE (Not now)
### SANDBOX

Add an OS/container isolation boundary around **compilation as well as execution**, with no host credentials/workspace mounts, no evaluation network and hard resource limits. Keep compiler/capability checks as defense in depth, not a replacement for isolation.
