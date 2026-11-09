# Scala Workflow

## First Inspection

Before changing Scala code, identify:

- Scala version: Scala 2.12/2.13/3.x.
- Build tool and modules: `build.sbt`, `project/*.scala`, `mill`, `scala-cli`, Gradle/Maven if present.
- Test framework: ScalaTest, MUnit, Weaver, ZIO Test, Specs2, custom harness.
- Formatting/linting: Scalafmt, Scalafix, WartRemover, compiler flags.
- Effect stack: plain stdlib, Future, Cats Effect, ZIO, Akka/Pekko, fs2, Monix, etc.
- Existing architecture: packages, domain/application/infrastructure boundaries.

Use `rg`, `find`, and build files to discover conventions. Read nearby files before editing.

## Change Strategy

- Make the smallest change that solves the problem.
- Keep public API changes intentional and documented.
- Prefer adding domain methods near the owning model/service instead of duplicating logic in callers.
- Preserve import style and formatting conventions.
- Avoid broad rewrites unless requested.
- If a behavior change affects docs or user workflows, update docs too.

## Compatibility

For public APIs, think about:

- Source compatibility: renamed parameters, changed return types, removed defaults, stricter type bounds.
- Binary compatibility: changed method signatures, case class constructor changes, trait member changes.
- Serialization compatibility: JSON/codecs/database schema fields, enum labels, default values.
- Pattern matching compatibility: sealed trait children, case class fields, enum cases.

Call out compatibility risks in the final response.

## Validation

Prefer this order:

1. Format only touched files if supported.
2. Compile affected module.
3. Run focused tests.
4. Run broader test suite when the change is cross-cutting.

Common commands to look for:

- `sbt compile`, `sbt Test/compile`, `sbt <module>/test`
- `sbt scalafmtAll`, `sbt scalafixAll`
- `mill __.compile`, `mill __.test`
- `scala-cli test .`

If validation cannot run, explain the reason and what should be run next.
