# Effects, Concurrency, and Resources

This file is framework-neutral. If the project uses ZIO, Cats Effect, Akka/Pekko, fs2, or another stack, prefer that stack's conventions and load any more specific skill/reference available.

## Effects

- Do not execute effects during construction unless the framework requires it.
- Keep pure domain logic separate from effectful adapters.
- Make asynchronous/effectful behavior visible in the return type.
- Do not swallow errors in callbacks/fibers; propagate, log with context, or supervise intentionally.

## Future

For `scala.concurrent.Future`:

- Require an appropriate `ExecutionContext` at boundaries.
- Do not block on `Await.result` in application logic.
- Use a dedicated blocking dispatcher/execution context for blocking I/O.
- Remember Future starts eagerly; avoid constructing it before you mean to start work.
- Use `recover`/`recoverWith` for expected fallback behavior, not broad exception hiding.

## Resources

- Use `try/finally`, `Using`, or the project's resource abstraction for closeable resources.
- Tie resource lifetime to a clear scope.
- Do not leak file handles, streams, clients, database sessions, or subscriptions.
- Document ownership: who creates, who closes, and whether callers may reuse the resource.

## Concurrency

- Prefer high-level combinators over manual thread/fiber management.
- Bound parallelism for external calls or large collections.
- Make shared mutable state explicit and protected.
- Avoid blocking CPU pools.
- Preserve cancellation/interruption semantics where the effect system supports them.

## Time

- Use `Instant` for machine time and `LocalDate`/`ZonedDateTime` for domain/calendar time as appropriate.
- Document time zone assumptions and units.
- Inject clocks for testable time-dependent logic.
