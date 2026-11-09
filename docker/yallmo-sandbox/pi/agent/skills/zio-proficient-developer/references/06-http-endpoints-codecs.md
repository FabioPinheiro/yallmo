# ZIO HTTP Endpoint API, Codecs, and Schemas

Sources: ZIO HTTP Endpoint, HttpCodec, PathCodec, OpenAPI docs and examples.

## When to Use Endpoint API

Use `Endpoint` when you want a declarative, type-safe description of HTTP APIs with inputs/outputs separate from implementation.

Benefits documented by ZIO HTTP include:

- describing endpoints and their inputs/outputs,
- implementing endpoints separately,
- OpenAPI documentation generation,
- client generation.

Use `Routes`/`Handler` directly when the API is small, highly custom, or when low-level control is clearer.

## Endpoint Pattern

The docs show endpoint definitions like:

```scala
case class Book(title: String, authors: List[String])
object Book {
  implicit val schema = DeriveSchema.gen[Book]
}

val endpoint =
  Endpoint(RoutePattern.GET / "books")
    .query(HttpCodec.query[String]("q"))
    .out[List[Book]]
```

Then implement the endpoint separately using the project/version's supported API.

## Codecs

Use codecs to describe structured parts of HTTP:

- path segments/path params (`PathCodec`),
- query params,
- headers,
- request bodies,
- response bodies,
- status outputs.

`HttpCodec` and related codecs keep HTTP parsing/encoding explicit and typed.

## Schema Pattern

For JSON/domain DTOs, provide schemas where required, often via derivation:

```scala
final case class CreateUserRequest(email: String, name: String)
object CreateUserRequest {
  implicit val schema = DeriveSchema.gen[CreateUserRequest]
}
```

Do not expose internal persistence models as public endpoint DTOs unless intentionally stable.

## Versioning Pattern

- Put public endpoint definitions in a stable module/package.
- Keep implementation in service modules.
- Use DTOs for external contracts.
- Map DTOs to domain commands/queries.

## OpenAPI Pattern

If using OpenAPI generation:

- prefer explicit request/response models,
- document errors/status codes,
- include examples when possible,
- keep endpoint definitions pure/declarative.

## Design Rules

- Choose one style per API surface: direct `Routes` or declarative `Endpoint`, not a confusing mixture.
- Keep validation close to decoding but domain invariants in domain constructors.
- Model auth requirements consistently, commonly via middleware/aspects/context.
- Ensure error responses are typed/documented, not ad-hoc strings everywhere.

## Anti-patterns

- Defining endpoints that return raw strings/maps when a typed DTO is needed.
- Duplicating route paths between endpoint descriptions and implementations.
- Letting generated clients define your domain model.
- Skipping schema/codecs and manually parsing everything in handlers.
