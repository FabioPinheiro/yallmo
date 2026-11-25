# ZIO JSON Reference

Use this reference when adding, reviewing, or explaining JSON encoding/decoding with `zio-json`.

## Core Types and Syntax

- `JsonEncoder[A]` encodes values of `A` to JSON.
- `JsonDecoder[A]` decodes JSON into `A`, usually returning an error message for malformed or incompatible input.
- `JsonCodec[A]` is both an encoder and decoder for `A`.
- Common syntax imports include `zio.json._` for `toJson`, `toJsonAST`, `fromJson`, derivation helpers, and annotations.

Prefer checking the project's exact dependency version and imports before writing code.

## Derivation

For straightforward wire formats, place codecs in the companion object:

```scala
import zio.json.*

final case class UserId(value: String)
object UserId:
  given JsonCodec[UserId] = DeriveJsonCodec.gen[UserId]

final case class User(id: UserId, name: String)
object User:
  given JsonCodec[User] = DeriveJsonCodec.gen[User]
```

Use `DeriveJsonEncoder.gen[A]` or `DeriveJsonDecoder.gen[A]` when only one direction is needed or when read/write formats differ.

## Wire Models vs Domain Models

Use separate DTO/API models when:

- JSON field names differ from idiomatic Scala names.
- Compatibility requires deprecated, optional, renamed, or defaulted fields.
- The domain model has invariants that should not be bypassed by automatic derivation.
- Encoding and decoding are intentionally asymmetric.

Keep conversion between DTOs and domain models explicit and return typed domain errors for invalid input.

## Configuration and Annotations

Use annotations intentionally for stable public wire formats:

- Rename fields with `@jsonField` when the JSON name must differ from the Scala field name.
- Use member-name/discriminator annotations for ADTs only after confirming the project's convention.
- Be careful with defaults and `Option`: distinguish missing fields, explicit `null`, and empty values when compatibility matters.

Document non-obvious compatibility choices near the codec or API model.

## Error Handling

- Treat decode failures as expected boundary errors, not defects.
- Convert `fromJson` failures into typed errors or consistent HTTP error responses.
- Avoid `.toOption.get`, throwing, or silently defaulting malformed data unless explicitly required.

Example boundary pattern:

```scala
final case class JsonDecodeError(message: String)

json.fromJson[Payload] match
  case Right(value) => ZIO.succeed(value)
  case Left(error) => ZIO.fail(JsonDecodeError(error))
```

## ZIO HTTP Integration

- Prefer the project's existing ZIO HTTP JSON helpers, body codecs, endpoint codecs, and error response style.
- Keep JSON parsing/serialization at HTTP or persistence boundaries.
- Ensure malformed JSON, validation failures, and unsupported media types map to consistent status codes and response bodies.
- With declarative `Endpoint` APIs, align `zio-json` codecs with schemas/codecs used for OpenAPI and generated clients.

## Testing

Add tests for:

- Round-trip encode/decode for stable models.
- Negative decode cases for malformed JSON and invalid values.
- ADT discriminator behavior.
- Renamed fields and backwards-compatible aliases/defaults.
- Golden JSON examples for public APIs where compatibility matters.
