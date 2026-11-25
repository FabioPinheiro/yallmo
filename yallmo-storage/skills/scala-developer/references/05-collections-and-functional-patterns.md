# Collections and Functional Patterns

## Collections

- Choose collection types by contract: `List` for sequences/prepending, `Vector` for indexed immutable sequences, `Set` for membership, `Map` for lookup.
- Avoid relying on map/set iteration order unless using an ordered collection or documenting the ordering.
- Avoid repeated `++`, `:+`, or nested traversals in hot paths.
- Prefer `iterator`/streaming for large data when materialization is unnecessary.
- Be explicit about sorting and grouping semantics.

## Functional Patterns

- Prefer pure functions for domain transformations.
- Keep effects at the edges or in effect types.
- Use `map`, `flatMap`, `fold`, `traverse`, `collect`, and `collectFirst` when they clarify intent.
- Use for-comprehensions for sequencing dependent computations.
- Avoid deeply nested `map`/`flatMap`; extract named helpers.
- Avoid `get`, `head`, `tail`, `last`, `apply` on maps/sequences unless non-emptiness/key existence is guaranteed and documented.

## Totality

Prefer total functions:

- `headOption` instead of `head`.
- `map.get(key).toRight(...)` instead of `map(key)`.
- Exhaustive pattern matches.
- Non-empty collection types if the project uses Cats or custom types.

## Performance Awareness

Do not prematurely optimize, but watch for:

- Quadratic list appends.
- Unbounded `toList` / `toVector` on streams/iterators.
- Large `groupBy` materializations.
- Synchronous blocking inside parallel collection/effect code.
- Excessive allocations in tight loops.

If performance is the task, measure or design a focused benchmark/test rather than guessing.
