# ZSink, ZPipeline, and ZChannel Patterns

Sources: ZIO Stream reference sections on `ZSink`, `ZPipeline`, `ZChannel`, sink operations, creating sinks, leftovers, parallel operators, channels.

## ZSink Mental Model

A `ZSink[R, E, In, L, Z]` consumes input values `In`, may fail with `E`, may leave leftovers `L`, and eventually produces `Z`.

Use sinks when the consuming logic is reusable, composable, or stateful:

- aggregation,
- parsing,
- writing to a destination,
- validation with summary,
- batching and flushing,
- protocol decoding.

Common pattern:

```scala
val total: ZIO[Any, Nothing, Int] =
  ZStream(1, 2, 3).run(ZSink.sum[Int])
```

## Sink Design Rules

- Use existing sinks when possible (`count`, `sum`, `collectAll`, folds, etc.).
- Keep sink state internal and resource-safe.
- Be explicit about leftovers when parsing/framing.
- Compose sinks when multiple independent summaries are needed.
- Avoid collecting all input unless bounded.

## ZPipeline Mental Model

A `ZPipeline[R, E, In, Out]` transforms streams:

```scala
ZStream[R, E, In] => ZStream[R, E, Out]
```

Use pipelines for reusable transformation stages:

- decode bytes to text,
- split lines,
- parse CSV/JSON frames,
- validate and map records,
- compress/decompress,
- throttle/buffer/batch.

Example shape:

```scala
val normalize: ZPipeline[Any, Nothing, String, String] =
  ZPipeline.map[String, String](_.trim.toLowerCase)

val processed = rawLines.via(normalize)
```

Exact constructors vary by ZIO version; follow project APIs.

## Pipeline Architecture Pattern

Build stream programs as named stages:

```scala
source
  .via(decodeUtf8)
  .via(splitLines)
  .via(parseRecords)
  .via(validateRecords)
  .run(writeBatches)
```

Benefits:

- each stage is testable,
- error types are localized,
- resource ownership is clearer,
- performance tuning is easier.

## Error Types Across Pipelines

If stages have different errors, deliberately map them into a common domain error:

```scala
source
  .via(parsePipeline.mapError(ParseFailed.apply))
  .via(validatePipeline.mapError(ValidationFailed.apply))
```

Do not collapse all errors to `Throwable` unless this is an integration boundary.

## ZChannel

`ZChannel` is the lower-level abstraction for stream processing. It supports both reading and writing and underlies streams/sinks/pipelines.

Reach for `ZChannel` when:

- you need custom pull/push protocol behavior,
- `ZStream`/`ZSink`/`ZPipeline` composition is insufficient,
- leftovers/framing/back-pressure require low-level control,
- building library-level streaming primitives.

For normal application code, prefer `ZStream`, `ZSink`, and `ZPipeline`.

## Testing Pipelines and Sinks

Test pipelines with small streams:

```scala
val input = ZStream(" A ", "B ")
val out   = input.via(normalize).runCollect
```

Test sinks with finite streams and edge cases:

- empty stream,
- one element,
- multiple chunks,
- malformed input,
- upstream failure,
- interruption/resource cleanup if relevant.

## Anti-patterns

- Encoding a large stream workflow in one anonymous `mapZIO` chain with no named stages.
- Using `ZChannel` prematurely for ordinary transformations.
- Ignoring leftovers in parsers/framing logic.
- Using sinks that collect everything in production paths with unbounded input.
- Losing error precision between stages.
