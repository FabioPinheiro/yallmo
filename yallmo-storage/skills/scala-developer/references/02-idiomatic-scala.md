# Idiomatic Scala

## General Style

- Prefer clarity over cleverness.
- Use names that expose domain intent, not implementation detail.
- Keep methods short enough that types and names tell the story.
- Avoid unnecessary inheritance; prefer composition.
- Prefer immutable data and values (`val`) over mutation (`var`).
- Use `final` for concrete classes when extension is not intended.
- Use `private`, `private[package]`, and small APIs to reduce accidental coupling.

## Scala 3 vs Scala 2

Follow the project version and conventions.

Scala 3:

- Use `enum` for closed alternatives when appropriate.
- Use `given` / `using` intentionally; avoid hidden magic for domain logic.
- Use extension methods for syntax enrichments, not core behavior that should be discoverable on a model/service.
- Use significant indentation only if the project already does.

Scala 2:

- Use `sealed trait` + case objects/classes for ADTs.
- Use implicits carefully, with explicit imports and stable implicit scopes.
- Avoid advanced type-level encodings unless the project already uses them.

## Pattern Matching

- Pattern match on ADTs and stable structures.
- Keep matches exhaustive; avoid wildcard cases that hide missing domain states.
- Prefer named extractors only when they improve readability.
- For booleans with domain meaning, consider replacing with an ADT.

## Methods and APIs

- Keep public signatures explicit.
- Avoid returning `Any`, raw tuples, or unconstrained maps from public APIs.
- Use default arguments sparingly in public APIs because they affect compatibility.
- Avoid side effects in constructors and case class bodies.
- Put validation in smart constructors or service boundaries, not scattered at call sites.

## Imports

- Follow local import grouping and wildcard conventions.
- Do not reorder all imports unless the formatter/linter expects it.
- Prefer explicit imports for unfamiliar or conflicting names.
