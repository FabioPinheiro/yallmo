# ZIO Streams Deep Dive

Sources: ZIO Stream reference: https://zio.dev/reference/stream/ including `ZStream`, `ZSink`, `ZPipeline`, `ZChannel`, `Chunk`, resourceful streams, scheduling, error handling, and stream operations.

## Purpose of ZIO Streams

ZIO Streams provide a high-level, resource-safe, back-pressured abstraction for reading, transforming, and writing sequences of values from sources to destinations.

Use streams when data is:

- potentially large or unbounded,
- produced/consumed incrementally,
- asynchronous,
- resource-backed,
- back-pressured,
- transformed through multiple stages,
- consumed into sinks or emitted over HTTP/WebSockets/SSE.

## Core Types

### `ZStream[-R, +E, +O]`

A stream that requires environment `R`, may fail with `E`, and emits zero or more values of type `O`.

Mental model:

```scala
ZStream[R, E, O] ~= ZIO[R, E, Chunk[O]] repeated until done
```

Do not treat it as a strict `List`. It is lazy, effectful, pull-based, and resource-aware.

### `Chunk[A]`

`Chunk[A]` is an efficient immutable sequence used internally and externally by streams. It is often backed by arrays, avoids boxing where possible, and is optimized for stream throughput.

Prefer `Chunk` for batches in streaming code instead of repeatedly converting to/from `List`.

### `ZSink[-R, +E, -In, +L, +Z]`

A sink consumes stream elements of type `In`, may leave leftovers `L`, and produces a result `Z`.

Typical sinks:

- collect values,
- sum/count/fold,
- write to file/socket,
- parse protocol frames,
- aggregate windows.

### `ZPipeline[-R, +E, -In, +Out]`

A reusable stream transformer:

```scala
ZStream[R, E, In] => ZStream[R, E, Out]
```

Use pipelines for reusable transformations such as decoding, filtering, batching, compression, framing, or parsing.

### `ZChannel`

`ZChannel` is the lower-level primitive behind streams/sinks/pipelines. Use it for advanced streaming abstractions when `ZStream` / `ZSink` / `ZPipeline` are not expressive enough.

Most application code should use `ZStream`, `ZSink`, and `ZPipeline` first.

## Stream Construction Patterns

Pure values:

```scala
val s: ZStream[Any, Nothing, Int] = ZStream(1, 2, 3)
```

From collections:

```scala
ZStream.fromIterable(items)
ZStream.fromChunk(chunk)
```

From effects:

```scala
ZStream.fromZIO(fetchOne)
ZStream.repeatZIO(fetchNext)
```

From scoped resources:

```scala
ZStream.scoped(openResource)
ZStream.acquireRelease(acquire)(release)
```

Use resourceful constructors for files/readers/sockets or anything that must be closed.

## Stream Consumption Patterns

Run effects per element:

```scala
stream.foreach(process)
```

Run with a sink:

```scala
stream.run(ZSink.count)
stream.run(ZSink.collectAll)
stream.run(ZSink.foldLeft(0)(_ + _))
```

Fold:

```scala
stream.runFold(initial)(accumulate)
```

Only consume at the boundary of the streaming workflow. Keep transformation logic as streams/pipelines until the final sink.

## Transformation Patterns

Common transformations:

```scala
stream.map(f)
stream.mapZIO(f)
stream.filter(p)
stream.filterZIO(p)
stream.collect { case x if valid(x) => transform(x) }
stream.mapError(toDomainError)
```

Use `map` for pure transformations and `mapZIO` for effectful transformations.

## Back-pressure

ZIO Streams are pull-based and back-pressured. Downstream demand controls upstream pulling. Preserve this property by avoiding unbounded buffering or eager collection.

Rules:

- Avoid `runCollect` on large/unbounded streams.
- Prefer streaming transformations over collecting into memory.
- Use bounded queues/buffers where explicit buffering is required.
- Keep external integrations back-pressure-aware when possible.

## Resource Safety

Resourceful streams acquire resources when run and release them when the stream completes, fails, or is interrupted.

Use:

```scala
ZIO.scoped {
  stream.run(sink)
}
```

or resource-safe constructors that embed scopes.

## Error Handling

Streams have typed errors just like `ZIO`:

```scala
stream.mapError(toDomainError)
stream.catchAll(recoverStream)
stream.orElse(fallbackStream)
stream.retry(Schedule.exponential(100.millis) && Schedule.recurs(3))
```

Map low-level errors to domain errors at boundaries.

## Design Rules

- Keep streaming workflows streaming until the edge.
- Use `ZPipeline` for reusable transformations.
- Use `ZSink` for reusable consumers/aggregators.
- Use `Chunk` for batch efficiency.
- Use scoped/resourceful constructors for resource-backed streams.
- Bound parallelism and buffering.
- Consider interruption and finalizers for infinite/long-running streams.

## Anti-patterns

- `runCollect` on large or unbounded streams.
- Converting every chunk to `List` and back.
- Opening files/sockets outside scoped stream constructors.
- Using manual recursion when stream combinators are clearer.
- Manual fiber/queue streaming when `ZStream` operators solve the problem.
- Swallowing stream errors with broad fallbacks.
