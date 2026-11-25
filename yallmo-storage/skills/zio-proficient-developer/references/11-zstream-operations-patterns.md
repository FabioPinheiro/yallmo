# ZStream Operations and Patterns

Sources: ZIO Stream reference sections on creating, consuming, operations, error handling, scheduling, resourceful streams, and chunking.

## Creation Decision Guide

Use the constructor that preserves intent:

| Source | Prefer |
|---|---|
| Small known values | `ZStream(a, b, c)` |
| Existing collection | `ZStream.fromIterable(xs)` |
| Existing `Chunk` | `ZStream.fromChunk(chunk)` |
| One effect | `ZStream.fromZIO(effect)` |
| Repeated effect | `ZStream.repeatZIO(effect)` plus schedule/take logic |
| Resource-backed source | scoped/resourceful stream constructor |
| Queue/event source | queue/event-stream integration provided by ZIO Streams/project |

## Pure vs Effectful Operators

Pure:

```scala
stream.map(f)
stream.filter(p)
stream.collect { case ... => ... }
```

Effectful:

```scala
stream.mapZIO(f)
stream.filterZIO(p)
stream.tap(o => log(o))
```

Use pure operators when possible. Effectful operators can introduce latency, errors, dependencies, and concurrency concerns.

## Parallelism

For effectful work per element, use the project/ZIO-version parallel operators where appropriate, and bound concurrency.

Pattern:

```scala
stream.mapZIOPar(n)(process)
```

Rules:

- Use bounded parallelism for remote calls, database calls, and CPU-heavy work.
- Preserve order only when required; unordered variants may improve throughput if available.
- Ensure effects are safe to run concurrently.

## Chunking and Batching

Streams are chunked by default. Use batching to improve throughput:

```scala
stream.grouped(100)
stream.mapChunks(chunk => transformChunk(chunk))
```

Prefer chunk-level transformations when per-element overhead is high.

## Flattening

When an element expands into many outputs:

```scala
stream.flatMap(item => ZStream.fromIterable(expand(item)))
```

Use with care: unbounded inner streams can starve or complicate shutdown. Consider merge/parallel variants only when semantics are clear.

## Error Handling Patterns

Map infrastructure errors:

```scala
stream.mapError(e => DomainError.SourceFailed(e.getMessage))
```

Recover with alternate stream:

```scala
stream.catchAll { err =>
  ZStream.fromZIO(ZIO.logWarning(s"recovering: $err")) *>
  fallbackStream
}
```

Retry transient source failures:

```scala
stream.retry(Schedule.exponential(100.millis) && Schedule.recurs(5))
```

Do not retry non-idempotent downstream writes without understanding duplicate effects.

## Scheduling Patterns

Use scheduling for polling, rate limiting, recurrence, retries, or timed emissions.

Examples of intent:

- poll every N seconds,
- retry source on transient failure,
- throttle a stream,
- timeout or interrupt long operations.

Prefer declarative schedules over manual sleeps inside loops.

## Resourceful Stream Pattern

```scala
def lines(path: Path): ZStream[Any, Throwable, String] =
  ZStream.fromFile(path).via(textDecode).via(splitLines)
```

Exact APIs depend on ZIO version/modules, but the rule is stable: resource acquisition/release belongs in the stream/scoped constructor, not around an already-created stream in unsafe code.

## Boundary Patterns

HTTP response streaming:

- expose `ZStream` directly if framework supports streaming bodies,
- map domain errors to HTTP errors carefully,
- avoid collecting full response unless small and bounded.

File/import jobs:

- parse as stream,
- validate records,
- batch writes,
- report structured errors,
- preserve back-pressure.

Message processing:

- source stream from queue/broker,
- decode,
- process with bounded parallelism,
- commit/ack only after successful processing,
- define retry/dead-letter behavior.

## Anti-patterns

- `mapZIO` with unbounded slow remote calls causing serial bottlenecks when parallelism is required.
- `mapZIOPar` on operations that must be ordered or exclusive.
- Broad `catchAll` that restarts forever without logging/metrics/backoff.
- Using stream scheduling where a single `ZIO.retry` around a simple call is clearer.
- Accidental repeated subscriptions/runs of a stream when it has side effects.
