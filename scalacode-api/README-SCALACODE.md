# scalacode-api

Portable JVM/Scala.js contracts shared by application code and dynamically compiled Scala programs. Compilation, classloading and host adapters remain JVM-only.

- CrossProject: `scalaCodeApi`, with `scalaCodeApiJVM` and `scalaCodeApiJS` variants.
- sbt task IDs: `scalaCodeApi` (JVM, retained) and `scalaCodeApiJS` (Scala.js).
- Shared sources: `shared/src/main/scala`; shared tests run on both platforms.
- JVM runtime API: `jvm/src/main/scala/fmgp/yallmo/scalacode/ScalaToolApi.scala`, compiled into the JVM API artifact and imported by `scalamode`.
- Artifact: `yallmo-scalacode-api`
- Package: `fmgp.yallmo.scalacode`
- Dependencies: Scala/ZIO, plus Scala.js java-time compatibility; no core, server, model or filesystem modules.

## Contracts

- `ScalaScript`: `def program[R]: ZIO[R, Throwable, Any]`, with the environment type chosen separately for each method call.
- `ScalaToolApi` (JVM-only): trusted fixed-message output helper for restricted `scalamode` snippets. The object is `@experimental` because it uses `caps.assumeSafe`; the rest of the module does not require experimental compilation. `ScalaCodeExecutor` supplies only this module's classes directory or JAR to Scala CLI, imports the object as `tool`, and imports its members. No API source resource is copied into snippets.

Place script-facing service interfaces and their request/result case classes here. Keep implementations, adapters, compilation, classloading and application configuration outside this module. Both `core` variants depend on the matching API variant. Filesystem capabilities live in the separate `filesystem` module (`fmgp.yallmo.fs.FsRead` / `FsWrite`), not here. Trusted scripts can receive `FsRead` directly when its artifact and dependencies are explicitly included in the compiler classpath.

Generated JVM code compiles against the JVM API artifact and Scala/ZIO. Additional service API artifacts must be explicitly supplied to the executor's compiler classpath. The host supplies the same API classes through the parent classloader; do not bundle duplicate API classes into generated JARs.

The method-level `R` does not prove a generated snippet's concrete service requirements. The trusted generated bridge typechecks the ZIO effect and its `Throwable` error channel, then adapts its environment type with an unchecked cast. The caller must provide the services used by the snippet; missing services are runtime failures. The executor still prunes the supplied environment to the selected `R`.

This separation is not a sandbox: dynamically loaded code still has the host JVM's privileges.

See [dynamic executor setup and usage](../docs/tools/SCALA-DYNAMIC-EXECUTOR.md).
