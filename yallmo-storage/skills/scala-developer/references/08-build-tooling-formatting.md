# Build, Tooling, and Formatting

## sbt

Inspect:

- `build.sbt`
- `project/*.scala`
- `project/plugins.sbt`
- `.sbtopts`, `.jvmopts`

Common commands:

- `sbt compile`
- `sbt Test/compile`
- `sbt <module>/test`
- `sbt scalafmt` / `sbt scalafmtAll`
- `sbt scalafixAll`

Use quoted commands for multiple tasks when needed: `sbt "project core" test`.

## Mill

Inspect `build.sc` / `build.mill`.

Common commands:

- `mill __.compile`
- `mill module.test`
- `mill __.fix` or project-specific format commands.

## Scala CLI

Inspect `//> using` directives and `scala-cli` config.

Common commands:

- `scala-cli compile .`
- `scala-cli test .`
- `scala-cli fmt .`

## Formatting

- Respect `.scalafmt.conf`.
- Do not hand-format against the formatter.
- After modifying Scala code, run validation first, then run scalafmt; `scalafmtCheck` only reports problems, while `scalafmt` fixes them.
- Prefer formatting touched files/modules only when possible.
- If formatter output causes broad unrelated changes, stop and report.

## Dependencies

When adding dependencies:

- Check existing library ecosystem first.
- Use versions aligned with the project.
- Avoid adding large dependencies for small utilities.
- Consider Scala cross-version availability.
- Update lockfiles, dependency docs, or generated files if the project uses them.

## Compiler Warnings

Do not ignore new warnings. Common important warnings:

- Non-exhaustive matches.
- Unused imports/values.
- Deprecated APIs.
- Inferred `Any` or discarded non-Unit values.
- Unsafe nulls or unchecked type tests.
