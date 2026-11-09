# ZIO and ZIO HTTP Design Patterns

Sources: ZIO architecture/design references plus ZIO HTTP routing/endpoint/client/server references.

## Layered Hexagonal Pattern

Recommended large-app structure:

```text
core/domain        pure domain models, ADTs, validation
application        use cases as ZIO workflows requiring service traits
infrastructure     live service implementations, DB/HTTP/files
http               routes/endpoints, DTOs, HTTP error mapping
main               layer wiring and server startup
```

Dependencies point inward. HTTP and infrastructure adapt external protocols to application/domain services.

## Command/Query Service Pattern

Represent use cases as methods on application services:

```scala
trait UserApp {
  def register(cmd: RegisterUser): IO[UserError, UserId]
  def get(query: GetUser): IO[UserError, User]
}
```

HTTP decodes requests into commands/queries, calls app service, maps result to response.

## DTO Boundary Pattern

Separate external API DTOs from domain models when public contract stability matters.

```scala
final case class CreateUserJson(email: String, name: String)
final case class RegisterUser(email: Email, name: NonEmptyString)
```

## Error Mapping Pattern

Maintain explicit mapping:

```scala
DomainError => HTTP Status + public error body
Throwable/defect => generic 500 + private logs/traces
```

Never leak stack traces/secrets to clients.

## Resource Ownership Pattern

- App-scoped resources: DB pools, clients, configs -> `ZLayer.scoped`.
- Request-scoped data: auth principal/request id -> middleware/context/environment.
- Short-lived files/streams -> local `ZIO.scoped`.

## Concurrency Pattern

- High-level combinators first.
- Bounded parallelism for remote calls.
- Queues for producer/consumer workers.
- Hubs for fanout.
- STM when updates to multiple refs must be atomic.

## Endpoint vs Routes Decision

Use Endpoint API when:

- you need OpenAPI/client generation,
- contract-first development,
- strongly documented inputs/outputs.

Use direct Routes/Handlers when:

- the route is simple,
- you need low-level control,
- the API is internal or experimental.

## Production Readiness Pattern

Every service/HTTP module should consider:

- config via typed config/layers,
- timeouts and retries for external IO,
- back-pressure/bounded concurrency,
- structured errors,
- metrics/logging/tracing,
- resource finalization,
- tests with live and fake layers.

## Common Anti-patterns

- `Task` everywhere.
- Database calls directly in route handlers.
- Effect execution inside constructors.
- Manual fibers without lifecycle management.
- Hidden global dependencies.
- Unbounded queues/parallelism.
- Ad-hoc JSON/string errors with inconsistent status codes.
- Mixing public DTOs and internal persistence/domain models accidentally.
