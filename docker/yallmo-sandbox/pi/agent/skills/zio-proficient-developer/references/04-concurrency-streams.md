# Concurrency, State, Scheduling, and Streams

Sources: ZIO fiber, concurrency, state management, STM, schedule, and stream references.

## Fibers

Fibers are lightweight virtual threads managed by ZIO. Prefer high-level operators before manual fiber management:

- `zipPar`, `foreachPar`, `collectAllPar` for parallel composition.
- `race`, `raceFirst` for racing.
- `fork` only when you need explicit lifecycle control.

Manual fibers are low-level; always join/interrupt/supervise intentionally.

## Shared State

Use the smallest primitive that matches the problem:

- `Ref[A]` for atomic mutable reference to immutable data.
- `Ref.Synchronized[A]` for atomic updates that require effects.
- `Queue[A]` for async producer/consumer with back-pressure.
- `Hub[A]` for publish/subscribe broadcast.
- `Promise[E, A]` for one-shot completion.
- `Semaphore` for limiting concurrent access.
- STM (`TRef`, `TQueue`, `TMap`, etc.) for composable transactions across multiple refs/structures.

Example:

```scala
for {
  ref <- Ref.make(Map.empty[UserId, User])
  _   <- ref.update(_ + (user.id -> user))
  all <- ref.get
} yield all
```

## Back-pressure Pattern

For background workers:

```scala
def worker(queue: Queue[Job]): ZIO[JobProcessor, Nothing, Unit] =
  queue.take.flatMap(process).forever.catchAllCause(ZIO.logErrorCause(_))
```

Use bounded queues to avoid memory growth.

## Scheduling

`Schedule` describes recurrence/retry policy and composes declaratively.

Common patterns:

```scala
Schedule.recurs(3)
Schedule.exponential(100.millis)
Schedule.spaced(1.second)
```

Use with:

```scala
effect.retry(policy)
effect.repeat(policy)
```

## Streams

`ZStream[R, E, O]` describes a stream that may emit zero or more `O` values, require `R`, and fail with `E`.

Use streams for:

- incremental processing,
- chunked data,
- back-pressured pipelines,
- server-sent events / request-response streaming in HTTP.

Key types:

- `ZStream` — producer.
- `ZSink` — consumer.
- `ZPipeline` — transformer.
- `Chunk` — efficient immutable chunk.

## Design Rules

- Prefer structured concurrency: scope child fibers to parent lifetime.
- Use `foreachParN(n)` / semaphores to bound parallelism.
- Use queues/streams to communicate, not shared mutable variables.
- Consider interruption and finalizers for long-running work.
- Avoid unbounded queues unless justified.

## Anti-patterns

- Fire-and-forget `fork` without supervision.
- Blocking JVM threads without marking/blocking appropriately or using ZIO integrations.
- Using `var`/synchronized where `Ref`, `Queue`, STM, or `Semaphore` is clearer.
- Parallelizing non-idempotent operations without considering failure/cancellation semantics.
