# Core ZIO Effect Model

Sources: ZIO core/reference docs around `ZIO`, `IO`, `Task`, `UIO`, `RIO`, `URIO`, `Exit`, `Cause`, `Runtime`, `ZIOApp`.

## Mental Model

`ZIO[-R, +E, +A]` is an immutable, lazy description of a workflow that:

- requires an environment `R`,
- may fail with expected error `E`,
- may succeed with value `A`.

Read it as: `R => Either[E, A]`, but asynchronous, concurrent, interruptible, resource-safe, and lazy.

Common aliases:

```scala
type Task[A]    = ZIO[Any, Throwable, A]
type UIO[A]     = ZIO[Any, Nothing, A]
type IO[E, A]   = ZIO[Any, E, A]
type RIO[R, A]  = ZIO[R, Throwable, A]
type URIO[R, A] = ZIO[R, Nothing, A]
```

## Design Rules

- Use `UIO[A]` for infallible effects.
- Use `IO[DomainError, A]` for expected domain failures.
- Use `Task[A]` mainly at integration boundaries or where exceptions are truly external.
- Use `ZIO[R, E, A]` when dependencies are required from the environment.
- Keep constructors lazy: `ZIO.succeed(value)` for pure values, `ZIO.attempt(expr)` for exception-catching side effects.
- Avoid `unsafeRun`/runtime execution inside business code; run once at `ZIOApp` boundary.

## Composition Patterns

Sequential:

```scala
for {
  user <- userRepo.find(id)
  _    <- audit.log(s"loaded ${user.id}")
} yield user
```

Parallel:

```scala
fetchUser(id).zipPar(fetchOrders(id))
ZIO.foreachPar(ids)(fetchUser)
```

Validation that accumulates errors may need validation-specific combinators instead of short-circuiting `foreach`/`zip`.

## Environment Pattern

A method requiring services should expose them in `R`:

```scala
def program(id: UserId): ZIO[UserRepo & AuditLog, DomainError, User]
```

Then provide layers at the edge:

```scala
program(id).provide(UserRepoLive.layer, AuditLogLive.layer)
```

## Boundary Pattern

- Pure domain: plain Scala values/functions.
- Effectful domain/application: `ZIO` with precise `R` and `E`.
- Edge (HTTP, DB, files): translate protocols/exceptions into domain values/errors.

## Anti-patterns

- Using `Future` internally when `ZIO` can model the effect.
- Throwing exceptions for expected business failures.
- Hiding dependencies in globals/singletons instead of the environment.
- Returning `Task` everywhere and losing domain error information.
- Calling unsafe runtime methods from services.
