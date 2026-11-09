# Typed Errors and Resource Safety

Sources: ZIO error management reference, `Cause`, `Exit`, resource/scope docs.

## Error Taxonomy

ZIO distinguishes:

1. Expected failures: typed error channel `E` (`ZIO.fail`).
2. Defects: unexpected bugs/throwables (`ZIO.die`, exceptions caught by `attempt`).
3. Interruptions: fiber cancellation.

Typed errors do not mean defects cannot happen; they model recoverable/expected failures.

## Domain Error Pattern

Use ADTs for expected domain errors:

```scala
sealed trait UserError extends Product with Serializable
object UserError {
  final case class NotFound(id: UserId) extends UserError
  final case class InvalidEmail(value: String) extends UserError
  final case class Storage(message: String) extends UserError
}
```

Translate external exceptions at boundaries:

```scala
db.lookup(id)
  .mapError(t => UserError.Storage(t.getMessage))
```

## Recovery Patterns

- `orElse` for fallback effect.
- `catchAll` for typed error recovery.
- `foldZIO` for handling both success and error effectfully.
- `retry(Schedule...)` for transient failures.
- `timeout` / `timeoutFail` for latency boundaries.
- `tapError` for observation only, not reflexive logging everywhere.

Example:

```scala
callRemote(id)
  .retry(Schedule.exponential(100.millis) && Schedule.recurs(3))
  .catchAll(err => ZIO.fail(ApiError.Unavailable(err)))
```

## Cause and Exit

Use `Exit[E, A]` when you need a value describing effect completion.
Use `Cause[E]` when you need full failure detail including defects/interruption/parallel errors.
Do not expose `Cause` in normal business APIs unless callers need low-level diagnostics.

## Resource Safety

Use `Scope` and `ZIO.acquireRelease` for resources:

```scala
val scoped: ZIO[Scope, Throwable, Connection] =
  ZIO.acquireRelease(openConnection)(conn => conn.close.orDie)

val use: Task[Unit] =
  ZIO.scoped {
    scoped.flatMap(conn => conn.query("select 1"))
  }
```

Use `ZLayer.scoped` for app-scoped resources.

## Interruption Safety

ZIO ensures finalizers run on interruption. Keep acquisition/release in scoped constructs instead of ad-hoc `try/finally`.

## Error Design Rules

- Model expected failures in `E` with ADTs.
- Do not type every possible unexpected exception.
- At HTTP boundary, map domain errors to status codes/responses.
- Avoid logging and rethrowing at every layer; log at boundaries or when adding useful context.
- Preserve important cause/context in errors without leaking secrets.

## Anti-patterns

- `catchAll(_ => ZIO.succeed(default))` hiding failures without intent.
- Converting all errors to `Throwable` too early.
- Using exceptions for validation/domain failures.
- Acquiring resources in service constructors without scoped layers.
