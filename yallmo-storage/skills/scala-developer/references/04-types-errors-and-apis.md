# Types, Errors, and APIs

## Domain Types

- Introduce named domain types for important concepts: IDs, names, paths, durations, amounts, permissions, states.
- Avoid passing unvalidated `String`, `Int`, or `Map[String, Any]` through core logic.
- Use opaque types, value classes, or small case classes depending on Scala version and project style.
- Keep constructors private when invariants are required; expose `from`, `unsafe`, or validated constructors with clear names.

## Error Modeling

Choose error representation intentionally:

- `Option[A]`: absence is expected and there is only one reason or the reason is irrelevant.
- `Either[E, A]`: expected domain/application failure with useful error information.
- `Try[A]`: Java/exception interop or small synchronous exception-capturing boundary.
- Exceptions: truly exceptional failures, defects, framework boundaries, or project convention.
- Effect-specific errors (`IO[E, A]`, `Task`, etc.): follow the effect stack in use.

Prefer sealed ADTs/enums for expected domain errors:

```scala
sealed trait CreateUserError extends Product with Serializable
object CreateUserError {
  final case class DuplicateEmail(email: Email) extends CreateUserError
  final case class InvalidName(reason: String) extends CreateUserError
}
```

Document whether errors are recoverable, user-facing, retriable, or logged internally.

## API Signatures

Good signatures expose:

- Required dependencies.
- Expected failure type.
- Whether work is pure, synchronous, asynchronous, streaming, or effectful.
- Ownership and lifecycle of resources.

Avoid signatures that hide contracts:

- `Future[Any]`
- `Map[String, String]` for structured data
- `Boolean` return values where failure reason matters
- `null` as a normal value
- Throwing from methods documented as pure/total

## Validation

- Validate at boundaries: HTTP/API decoding, CLI parsing, DB reads, message consumers.
- Keep validated domain values validated; do not repeatedly revalidate in internal code.
- Accumulate validation errors when users can fix multiple fields at once.
- Fail fast when continued processing would be unsafe or expensive.

## Serialization and Codecs

When changing models with codecs:

- Check JSON field names, defaults, optional fields, enum labels, and migrations.
- Consider backward compatibility for stored data and clients.
- Update tests for codec round trips and old payload compatibility when needed.
