# scalacode-api

Runtime API for restricted `scalamode` snippets, separate from the application classpath.

- CrossProject: `scalaCodeApi`, with `scalaCodeApiJVM` and `scalaCodeApiJS` variants.
- sbt task IDs: `scalaCodeApi` (JVM) and `scalaCodeApiJS` (Scala.js).
- Shared sources: `shared/src/main/scala`.
- Artifact: `yallmo-scalacode-api`
- Package: `fmgp.yallmo.scalacode`
- Dependencies: Scala/ZIO, JVM-only ZIO JSON and Scala.js java-time compatibility; no core, server, model or filesystem modules.

## Runtime API

`ScalaToolApi.api` prints a fixed API availability message directly to stderr, without changing global stdout. This method is `@experimental` because it uses
`caps.assumeSafe`; the API object itself is a stable anchor for locating the compiled module.

`ScalaCodeExecutor` supplies this module's classes directory or JAR to Scala CLI and imports `ScalaToolApi` as
`tool`, along with its members. No API source resource is copied into snippets. Both `core` variants depend
on the matching API variant; compilation and execution remain JVM-only.

`jvm/src/main/scala/fmgp/yallmo/scalacode/ScalaToolRuntime.scala` supplies the trusted `ZIOAppDefault.bootstrap`
logger layer. It replaces the default logger with INFO-and-above JSON-lines events in a scoped, bounded
`runtime.jsonl` file. This JVM-only infrastructure is not annotated `assumeSafe`; snippets cannot access it as a
capability. The host collects retained events before cleaning up the invocation's temporary directory.

Filesystem capabilities live in the separate `filesystem` module (`fmgp.yallmo.fs.FsRead` / `FsWrite`).
Restricted filesystem execution uses a scoped RPC connection rather than loading generated code into the host JVM.

See [restricted Scala tool](../docs/tools/TOOL-SCALA-SCRIPT.md).
