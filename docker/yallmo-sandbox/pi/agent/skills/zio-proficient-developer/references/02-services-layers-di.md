# Services, Layers, and Dependency Injection

Sources: ZIO contextual data types, `ZEnvironment`, `ZLayer`, DI reference, service pattern docs.

## Service Pattern

The ZIO service pattern has four elements:

1. Service definition: trait with effectful methods.
2. Service implementation: class implementing the trait.
3. Layer: constructor lifted into `ZLayer`.
4. Usage: access service through environment or `ZIO.serviceWithZIO`.

Example:

```scala
trait UserRepo {
  def find(id: UserId): IO[UserError, User]
  def save(user: User): IO[UserError, Unit]
}

final class UserRepoLive(db: Database) extends UserRepo {
  def find(id: UserId): IO[UserError, User] = ???
  def save(user: User): IO[UserError, Unit] = ???
}

object UserRepoLive {
  val layer: ZLayer[Database, Nothing, UserRepo] =
    ZLayer.fromFunction(UserRepoLive(_))
}
```

Usage:

```scala
def findUser(id: UserId): ZIO[UserRepo, UserError, User] =
  ZIO.serviceWithZIO[UserRepo](_.find(id))
```

## ZLayer Mental Model

`ZLayer[-RIn, +E, +ROut]` describes how to build output services `ROut` from required input services `RIn`, possibly failing with `E`.

Think of it as an async resource-safe constructor:

```scala
RIn => ZIO[Scope, E, ROut]
```

Layer aliases:

- `ULayer[A]` — no deps, cannot fail.
- `URLayer[R, A]` — requires `R`, cannot fail.
- `TaskLayer[A]` — no deps, may fail with `Throwable`.
- `RLayer[R, A]` — requires `R`, may fail with `Throwable`.

## Dependency Graph Patterns

Compose horizontally for independent services:

```scala
val infra = DbLive.layer ++ HttpClientLive.layer
```

Compose vertically when one layer needs another:

```scala
val appLayer = DbLive.layer >>> UserRepoLive.layer
```

Provide all dependencies at application/server boundary, not deep inside functions, unless deliberately overriding for a local scope/test.

## Layer Resource Pattern

For resources with acquisition/release:

```scala
val layer: ZLayer[Config, Throwable, Db] =
  ZLayer.scoped {
    for {
      cfg <- ZIO.service[Config]
      db  <- ZIO.acquireRelease(openDb(cfg))(db => db.close.orDie)
    } yield db
  }
```

## Design Rules

- Traits should express capabilities, not implementation details.
- Keep service methods precise in error types.
- Do not put every helper function into a service; use pure functions/modules for stateless logic.
- Layers are recipes and are shared/memoized by default when composed in a dependency graph.
- Prefer constructor injection inside implementations; prefer environment requirements at API boundaries.

## Anti-patterns

- Passing services manually everywhere when environment/layers are clearer.
- Creating resources per request when they should be app-scoped layers.
- Overusing accessor methods in modern ZIO code when direct `ZIO.serviceWithZIO` is simpler.
- Hiding side effects in constructors instead of layers/scopes.
