# ZIO HTTP Routing and Handlers

Sources: ZIO HTTP introduction, overview, routes, route pattern, handler, request, response, body docs.

## Core Model

ZIO HTTP applications are built from routes. A route combines:

- a `RoutePattern`: method + path matching,
- a `Handler`: effectful request processing.

`Routes` models a collection/routing table of routes.

Simple shape from docs:

```scala
Routes(
  Method.GET / "hello"        -> Handler.text("hello"),
  Method.GET / "health-check" -> Handler.ok,
)
```

## Handler Model

`Handler[-R, +Err, -In, +Out]` is essentially:

```scala
In => ZIO[R, Err, Out]
```

For request handlers, input is usually `Request`, output is `Response`.

Use handlers to translate HTTP inputs into application service calls and translate domain results into HTTP responses.

## Boundary Pattern

Keep HTTP-specific logic at the edge:

```scala
val routes: Routes[UserService, Response] =
  Routes(
    Method.GET / "users" / string("id") -> handler { (id: String, req: Request) =>
      UserId.fromString(id) match {
        case Left(err) => ZIO.succeed(Response.badRequest(err))
        case Right(uid) =>
          ZIO.serviceWithZIO[UserService](_.find(uid))
            .map(user => Response.json(user.toJson))
            .catchAll {
              case UserError.NotFound(_) => ZIO.succeed(Response.status(Status.NotFound))
              case e                     => ZIO.succeed(Response.text(e.toString).status(Status.InternalServerError))
            }
      }
    }
  )
```

Adapt exact syntax to the project's ZIO HTTP version.

## Request/Response/Body

- `Request` represents decoded HTTP request data: method, URL/path/query, headers, body.
- `Response` represents HTTP response: status, headers, body.
- `Body` can represent fixed bytes, streams, forms, etc.
- Decode request bodies at the route boundary; validate into domain types before calling services.

## Route Organization Patterns

- Group routes by bounded context/module.
- Expose each module as `Routes[ModuleDeps, ResponseError]` or project-standard error type.
- Combine routes in the server module.
- Keep middleware/aspects separate from route business logic.

Example structure:

```scala
object UserRoutes {
  val routes: Routes[UserService, Response] = ???
}

object AdminRoutes {
  val routes: Routes[AdminService, Response] = ???
}

val appRoutes = UserRoutes.routes ++ AdminRoutes.routes
```

## Error-to-HTTP Pattern

Define one mapper near HTTP edge:

```scala
def toResponse(error: DomainError): Response = error match {
  case DomainError.NotFound     => Response.status(Status.NotFound)
  case DomainError.Unauthorized => Response.status(Status.Unauthorized)
  case DomainError.Invalid(msg) => Response.text(msg).status(Status.BadRequest)
}
```

## Anti-patterns

- Putting database logic directly in handlers.
- Throwing exceptions in handlers for expected HTTP/domain failures.
- Returning `200 OK` with error payloads for real failures.
- Parsing strings repeatedly instead of decoding once into domain types.
- Mixing route definitions, service implementations, and layer wiring in one file for large apps.
