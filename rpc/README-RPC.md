# Standalone typed RPC PoC

Independent of `core`, `server`, `model`, and AgentRunner. Scala 3 quotes generate both a
host dispatcher and a real implementation of the service trait for the client; no JVM
reflection or dynamic proxy is used. Contracts/codecs/macros are shared JVM/Scala.js;
only the WebSocket transport is JVM-implemented. **There is no JS WebSocket transport yet.**

## Two services, one connection

Derive once at the concrete trait, keep the resulting `Rpc[A]` in its companion or another
stable `val`, and share the contract source/codecs between host and caller:

```scala
import fmgp.rpc.*
import zio.*
import zio.json.*
import zio.http.Server

object Contracts {
  final case class Greeting(text: String) derives JsonCodec
  trait Math {
    def add(left: Int, right: Int): Task[Int]
    def reset(): Task[Unit]
  }
  trait Greeter {
    def hello(name: String): Task[Greeting]
  }
  val math: Rpc[Math] = Rpc.derived[Math]("demo.math", 1)
  val greeter: Rpc[Greeter] = Rpc.derived[Greeter]("demo.greeter", 1)
}

object Demo extends ZIOAppDefault {
  import Contracts.*
  val mathHost: Math = new Math {
    def add(left: Int, right: Int): Task[Int] = ZIO.succeed(left + right)
    def reset(): Task[Unit] = ZIO.unit
  }
  val greeterHost: Greeter = new Greeter {
    def hello(name: String): Task[Greeting] = ZIO.succeed(Greeting(s"Hello, $name"))
  }
  def run = ZIO.scoped {
    for {
      rpc <- RpcWebSocket.route() // starts with an empty, instance-owned ticket registry
      http <- Server.defaultWith(_.binding("127.0.0.1", 0)).build
      port <- Server.install(rpc.routes).provideEnvironment(http)
      // At any later time, register an immutable service set for exactly one connection.
      dispatcher <- ZIO.fromEither(math.bind(mathHost).merge(greeter.bind(greeterHost)))
      ticket <- rpc.register(dispatcher, ttl = 60.seconds)
      connection <- RpcWebSocket.connect(port, ticket) // exactly one receiver
      mathClient = math.client(connection)
      greeterClient = greeter.client(connection)
      answer <- mathClient.add(20, 22)
      greeting <- greeterClient.hello("Scala")
      _ <- Console.printLine(s"$answer: ${greeting.text}")
      _ <- mathClient.reset()
    } yield ()
  }
}
```

The example can be placed in `rpc/jvm/src/test/scala/Demo.scala` and run using
`sbt 'rpc/Test/runMain Demo'` (test compilation is separate from macro compilation).

## Bring your own HTTP server

`RpcWebSocket.route()` creates a scoped `RpcRoute`: its `routes` value is ordinary
`Routes[Any, Nothing]`, and `register`/`revoke` use that instance's private ticket registry.
It does not bind a port, construct a default server, or use mutable global state.
HTTP listener setup belongs to your application; `DemoServer` shows a complete example.

```scala
import fmgp.rpc.*
import zio.*
import zio.http.*

// Given a bound or merged dispatcher: RpcDispatcher
ZIO.scoped {
  for {
    rpc <- RpcWebSocket.route()
    health = Routes(Method.GET / "health" -> Handler.text("ok"))
    http <- Server.defaultWith(_.binding("127.0.0.1", 0)).build
    port <- Server.install(rpc.routes ++ health).provideEnvironment(http)
    ticket <- rpc.register(dispatcher) // May be called any time after route creation.
    connection <- RpcWebSocket.connect(port, ticket)
    // Create generated clients from connection and perform calls here.
    _ <- connection.close
  } yield ()
}
```

Keep the route scope alive while serving it. Closing that scope invalidates pending tickets
and rejects new registrations/upgrades; it does **not** stop the caller-owned HTTP server
or existing sockets. Their lifetime belongs to your server/connection scopes. Different
`route()` invocations have independent registries: a ticket from one cannot be redeemed
or revoked through another. Install each instance's routes only where you intend its
tickets to be accepted. Authentication and Origin checks apply unchanged on custom hosts.
The `route` method's Scaladoc includes a full custom-server example that stays running.

## Runnable calculator demo: two JVMs

`rpc/jvm/src/test/scala/Demo.scala` includes a shared calculator contract and three entry
points: `Demo` (both ends in one JVM), `DemoServer`, and `DemoClient`.

Use a private bootstrap file, not a command-line token or log output. On a POSIX host,
choose an **absolute, not-yet-existing path** in a private directory and set the same
`RPC_TICKET_FILE` environment variable in both terminals before starting sbt:

```sh
export RPC_TICKET_FILE=/absolute/private/directory/calculator.ticket
sbt
```

In terminal 1's interactive sbt shell run `rpc/Test/bgRunMain DemoServer`. The server
creates the file with mode 0600 and reports only the endpoint. In terminal 2 run
`sbt 'rpc/Test/runMain DemoClient'` within 60 seconds. The client reads/parses the ticket
and uses one connection for multiple calls. A second client cannot reuse that ticket.
Delete the bootstrap file afterward; restart the demo server with a new file for another
client (applications can instead call `register` again on the existing route handle).
Do not use a shared/untrusted directory or add this file to source control.

`Test / fork := true` makes these separate application JVMs. Backgrounding the server
avoids blocking a shared sbt server. Use `bgList` and `bgStop <job-id>` to stop it.

### Concrete interfaces

- `Rpc.derived[A]` returns `Rpc[A]`; `descriptor` lists the service ID, version and methods.
  `bind(actualService)` creates a dispatcher; `client(connection)` creates an `A`.
- `RpcDispatcher.merge(other): Either[RpcError, RpcDispatcher]` rejects duplicate service
  IDs, **including identical IDs with different versions**. It never silently overwrites.
  `dispatch(serviceId, version, method, Chunk[Json]): IO[RpcError, Json]` validates routing
  and decoded arguments before invoking host code.
- `RpcConnection.call(serviceId, version, method, arguments): Task[Json]` is the transport
  boundary, independent of the macro. `close: UIO[Unit]` is idempotent. Typed clients
  share the connection; constructing a client never opens a socket or receive loop.
- `RpcWebSocket.route(limits = Limits(), auth = AuthConfig()): URIO[Scope, RpcRoute]`
  returns authenticated `GET /rpc` routes plus an instance-local ticket registry. Install
  `rpc.routes` on your own HTTP server; the library does not construct or own a listener.
  `rpc.register(dispatcher, ttl = 60.seconds): IO[RpcError, RpcToken]` may be called at
  any later time; each ticket binds exactly that dispatcher, with no global route table
  or fallback. Distinct tickets may bind different host instances with identical service IDs.
- `rpc.revoke(ticket): UIO[Boolean]` removes an unclaimed, unexpired ticket only.
  `RpcWebSocket.connect(endpoint, ticket, limits)` or `connect(port, ticket)` returns a
  scoped `RpcConnection`. Route-scope exit clears pending tickets; your HTTP-server and
  connection scopes own sockets and their workers. Explicit `connection.close` releases
  its socket **and owned HTTP-client resources** immediately without closing the caller
  scope; repeated close is safe.

## Tickets and security semantics

- Tickets contain 256 cryptographically random bits, encoded as canonical unpadded URL-safe
  Base64. `RpcToken` is not a case class; default rendering is redacted.
  `ticket.exportSecret` explicitly exports the bearer and `RpcToken.parse(secret)` imports
  it for a separate JVM. Neither operation authenticates a person or process: **possession
  of the bearer is the only authority**. Deliver it via a trusted bootstrap channel and
  keep it out of logs, exceptions, shell history, generated code and RPC arguments/results.
- The client sends `Authorization: Bearer ...` only during the WebSocket handshake, never
  in RPC payloads or URLs. No query-token or cookie fallback exists; query-bearing endpoint
  URLs are rejected. The listener stores only SHA-256 ticket digests plus bound dispatchers.
- Each ticket is atomically removed by one accepted upgrade attempt. Concurrent claims
  have at most one winner. Failed/interrupted handshakes **after redemption consume the
  ticket too**: do not retry/reconnect automatically; request a new ticket through the
  trusted bootstrap channel. Non-upgrade GETs, invalid basic upgrade headers, duplicate
  credentials and rejected Origin headers are checked before redemption and cannot burn it.
- Missing/malformed/unknown/expired/used/revoked credentials all receive the same empty
  HTTP401 before upgrade. `RpcError.Code.Unauthorized` represents local parse failures and
  typed HTTP401 handshake failures where available. **zio-http 3.11.6 discards the response
  status into an untyped handshake exception, so its JVM client currently reports sanitized
  `Disconnected` for a denied handshake.** It does not parse exception messages as status.
- Origins must be absent or exactly one literal `null`; all other or duplicate Origin
  headers are rejected before redemption. The JVM client explicitly sends `Origin: null`
  because Netty otherwise generates a concrete Origin automatically. This is a compatibility
  policy, **not evidence of a JVM or trusted caller** (sandboxed browsers can also have null
  origins). Browser WebSocket APIs cannot attach this bearer header; there is no JS transport.
- TTL must be finite, positive and at most `auth.maxTtl` (default 5 minutes; hard maximum
  one day). Pending capacity defaults to 128 tickets (configurable 1..65536). Invalid TTL
  fails with `InvalidArguments`; full capacity with `LimitExceeded`. Expiry uses monotonic
  time and is lazily pruned on registration/redemption/revocation, so expired entries can
  remain in memory until the next operation but never exceed the configured capacity.
  Shutdown clears tickets and future registration fails with `Disconnected`.
- TTL and revocation affect **unclaimed tickets only**, not established connections.
  Tickets authorize the bound service set, not individual method permissions. Service
  implementations remain responsible for application authorization and safe behavior.
- `ws` is plaintext and loopback is **not a sandbox**: untrusted local software may steal
  bearers or attack the listener. Do not bind privileged unsafe services for hostile code.
  Use a properly secured `wss` deployment for remote access. Applications must configure
  their own binding, TLS and security controls; the demos and tests bind loopback only.
  TLS/proxy deployment is not implemented or tested. No AgentRunner integration.

## Contract restrictions and wire identity

Supported: non-generic traits with public **abstract** methods returning exactly
`Task[A]` (`ZIO[Any, Throwable, A]`), zero arguments (`def x` or `def x()`), or a single
ordinary parameter list with multiple arguments. Every parameter and result needs both
zio-json `JsonEncoder` and `JsonDecoder` in derivation scope. DTOs can `derive JsonCodec`.
`Unit` is encoded as JSON `null`, including when it is an argument.

Compile-time errors reject polymorphic/overloaded methods, vals/vars, concrete service
methods, type members, callbacks, contextual/by-name/repeated/default parameters, multiple
parameter lists, non-Task results, environment requirements and typed errors. This is a
small proof of concept, not a universal Scala interface serializer.

Prefer explicit literal IDs and positive integer versions as above. The no-argument
`Rpc.derived[A]` defaults to the compiler's fully qualified trait symbol name (including
`$` for object owners), version 1. Renaming/moving a trait changes that default identity.
Explicit IDs are 1–160 letters/digits/`._$-`. IDs must belong to distinct services; merge
checks uniqueness, method overloads are rejected, and dispatch validates service, version,
method and argument arity/types. **Bump the version for any incompatible signature or
codec change.** There is no schema negotiation/fingerprint: reusing an ID/version for a
different schema is a contract violation, not something the PoC can detect automatically.

The private wire protocol is not JSON-RPC 2.0: one text message per request/response.
Requests contain string `id`, `service`, integer `version`, `method`, and positional JSON
`arguments`; replies contain the same `id` and exactly one `result` or sanitized `error`.
Unknown envelope fields, duplicate JSON object keys, nesting over 32, invalid IDs and
ambiguous envelopes are rejected. Binary/fragmented messages are deliberately rejected;
senders must emit a complete text frame. This keeps aggregation bounded and avoids
partial UTF-8 handling in the PoC.

## Failure, concurrency and limits

- ZIO INFO logs record established WebSocket connections and disconnections on both server
  and client. Each side assigns its own `connectionId`, retained in its matching disconnect
  log. Repeated close does not duplicate logs; unsuccessful handshakes are not logged as
  established connections. Endpoint URLs are not logged.
- ZIO TRACE logs include sent/received RPC requests and responses (including sanitized
  error replies), rejected messages/calls, ignored late replies, timeouts and interruptions.
  Wire logs carry the local `connectionId` and JSON envelope, including the request ID.
  Payloads are JSON-string escaped to avoid multiline log injection. **TRACE exposes
  arguments/results and has no application-data redaction; use it only for debugging.**
  INFO does not log RPC payloads.
- Monotonic per-connection IDs correlate concurrent/out-of-order responses. Late replies
  after a timeout/interruption are ignored; IDs are never reused. Duplicate *active*
  request IDs are rejected by the host. No retry, reconnect, replay, batching or streaming.
- Failures are `RpcError` Throwables (typed `Task` APIs stay usable). Unknown service,
  version mismatch, unknown method and invalid arguments have distinct error codes.
  Host typed failures, thrown exceptions and defects become `ServiceFailure`; messages,
  stack traces and internal transport exceptions are not returned to callers.
- A local deadline or fiber interruption removes its pending promise. It **does not undo
  or cancel server execution**; an abandoned operation may still have side effects. Server
  workers are interrupted when their connection ends, subject to normal ZIO cooperative
  interruption. Do not retry a side-effecting call based only on a lost reply.
- Closing or losing the socket atomically fails every outstanding call and rejects future
  calls with `Disconnected`. Malformed peer responses yield `ProtocolError`; protocol
  close codes 1002/1003/1007 map to `ProtocolError`, 1009/1013 to `LimitExceeded`.
  After a peer disconnect, explicitly close the handle (or exit its scope) to release
  the remaining HTTP-client resources; there is no automatic reconnect.
- Defaults: 64 KiB UTF-8 frames/messages, 32 client pending/server active requests per
  connection, 10-second connect/request deadline, up to 64 wire arguments and JSON nesting
  32. Limits are configurable (1 MiB / 1024 requests maximum). Outgoing client overflow is
  local; server request/response overflow closes the connection. No unbounded application
  work queue: overload rejects/closes instead of accumulating waiting fibers.

**Production limitations:** this remains a PoC, not a hostile-code sandbox. There are
no global connection quotas or server operation deadlines. zio-http/Netty's internal
inbound event queue is not bounded by this application; frame/active-request/ticket
limits are not a total connection/memory/CPU budget. TLS deployment/hardening is untested.

## Enabling TRACE

`Demo`, `DemoServer`, and `DemoClient` accept `--log-level` (default: `info`):

```sh
sbt 'rpc/Test/runMain Demo --log-level trace'
sbt 'rpc/Test/runMain DemoServer --log-level trace'
sbt 'rpc/Test/runMain DemoClient --log-level trace'
```

Supported levels: `trace`, `debug`, `info`, `warning` (or `warn`), `error`, `fatal`, `off`.
Values are case-insensitive; `--log-level=trace` also works. Invalid arguments fail before
opening a listener or connection. `off` suppresses logs, not `Console.printLine` output.

When using a shared sbt server, start the server with
`rpc/Test/bgRunMain DemoServer --log-level trace` in an interactive sbt shell so it does
not block the client command. Configure each JVM separately to see both sides. The
library itself does not change the application's logging configuration.

## Validation

```sh
timeout 180s sbt 'rpc/test'
timeout 180s sbt 'rpc/testOnly fmgp.rpc.*; rpcJS/compile; rpcJS/Test/compile'
timeout 180s sbt 'rpc/scalafmt; rpc/Test/scalafmt; rpcJS/scalafmt; rpcJS/Test/scalafmt'
```

MUnit covers positive derivation, DTO/Unit codecs, compile-negative contracts, strict wire
validation, real ephemeral loopback WebSockets, two merged services on one connection,
reversed concurrent responses, route/argument errors, sanitized failures/defects,
malformed frames/envelopes, duplicate active IDs, pending/active/frame limits, timeout and
interruption cleanup, malformed peer responses failing all outstanding calls, explicit
close/remote disconnect cleanup, and owned HTTP-client finalization before the caller
scope exits (including repeated connection churn and failed acquisition).

Authentication coverage includes empty-listener dynamic registration, strict/redacted token
parsing, raw HTTP401 probes, single-use/replay and concurrent claims, isolated host state
for identical service IDs with merged service sets, Origin/upgrade preconditions not burning
tickets, deterministic monotonic expiry/capacity pruning, revocation and shutdown invalidation.
