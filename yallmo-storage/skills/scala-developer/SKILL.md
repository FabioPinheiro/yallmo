---
name: scala-developer
description: "Use for most Scala work: design, implementation, refactoring, review, tests, docs, build files, domain models, APIs, and idiomatic style."
---

# Scala Developer

Inspect project first: Scala/build versions, modules, dependencies, formatter/linter, tests, and style. Prefer local conventions. Load only relevant references:
- `references/01-workflow.md`
- `references/02-idiomatic-scala.md`
- `references/03-domain-models-and-scaladoc.md`
- `references/04-types-errors-and-apis.md`
- `references/05-collections-and-functional-patterns.md`
- `references/06-effects-concurrency-and-resources.md`
- `references/07-testing.md`
- `references/08-build-tooling-formatting.md`
- `references/09-scala-3-macros.md` — load for Scala 3 inline/quoted macros, reflection, `derives`, `Mirror`, or macro-backed DSLs.
- `checklists/review-checklist.md`

Rules:
- Make small, type-safe, readable changes.
- Preserve public API compatibility or call out risks.
- Prefer expressive domain types over unstructured strings/maps/tuples.
- Add/improve Scaladoc for models, public APIs, and fields needing future context.
- Update tests for non-trivial behavior changes.
- Validate with the narrowest useful command.
- If Scala changed, run scalafmt after validation; avoid silently accepting broad unrelated formatter rewrites.

Output: be concise, show file paths, explain important type/API choices, and include validation results or why validation was skipped.
