# ZIO HTTP Client, Server, Config, Middleware

Sources: ZIO HTTP server, client, body, middleware/handler aspect, config docs.

## Server Pattern

A ZIO HTTP server installs routes and runs as part of a ZIO application. Keep server startup at the application boundary.

Typical responsibilities:

- combine routes,
- apply middleware/aspects,
- provide service layers,
- configure port/netty/server options,
- rely on ZIO scopes/layers for resource lifetime.

Avoid building business services inside individual route handlers.

## Client Pattern

ZIO HTTP `Client` / `ZClient` is a ZIO service for making HTTP requests functionally.

Docs show client usage similar to:

```scala
for {
  resp <- Client.batched(Request.post("http://localhost:8080/hello", Body.fromString("John")))
  body <- resp.body.asString
} yield body
```

Provide `Client.default` or project-specific client layer at the boundary.

## Body Handling

Use `Body` constructors for request/response content:

- empty body,
- string/bytes,
- forms/multipart,
- streams for large/chunked data.

Decode body once, validate, and translate to domain command/DTO.

## Middleware / Aspects

Middleware addresses cross-cutting concerns:

- auth,
- CORS,
- logging,
- metrics/tracing,
- compression,
- error handling,
- request IDs/context.

Keep middleware generic and route handlers focused.

## Request-scoped Context

ZIO HTTP supports request-scoped context patterns, including ZIO environment and `RequestStore`/fiber-local style mechanisms.

Use request context for:

- authenticated principal,
- request id/correlation id,
- tenant id,
- locale,
- tracing metadata.

Do not use request context as an untyped dumping ground.

## Config Pattern

Represent config as typed values/layers. For ZIO HTTP server/client/netty config, inspect the project's version and existing config style.

Good pattern:

```scala
final case class HttpServerConfig(port: Int, host: String)
```

Then wire via layer/config provider.

## External API Client Service Pattern

Wrap HTTP clients behind domain-specific services:

```scala
trait PaymentGateway {
  def charge(req: ChargeRequest): IO[PaymentError, ChargeResult]
}

final class PaymentGatewayLive(client: Client, config: PaymentConfig) extends PaymentGateway {
  def charge(req: ChargeRequest): IO[PaymentError, ChargeResult] = ???
}
```

This keeps HTTP mechanics isolated and testable.

## Anti-patterns

- Creating a new low-level client per request.
- Hardcoding URLs/secrets in handlers.
- Reading full body into memory for large uploads when streaming is needed.
- Duplicating auth/error-handling logic in every route.
- Swallowing non-2xx responses without mapping to domain errors.
