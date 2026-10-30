package fmgp.rpc

import io.netty.handler.codec.TooLongFrameException
import io.netty.handler.codec.http.websocketx.CorruptedWebSocketFrameException
import java.net.URI
import java.nio.charset.StandardCharsets
import zio.*
import zio.stream.*
import zio.http.*
import zio.json.ast.Json
import zio.json.*

/** JVM-only authenticated RPC routes and client transport; HTTP hosting belongs to the caller. */
object RpcWebSocket {

  /** Per-connection bounds. maxPending is unary-only; maxStreams includes cancelling streams until cleanup.
    * maxChunkElements also bounds byte payload elements, in addition to the binary frame's 12-byte header. timeout is a
    * client connect/unary/open/per-pull deadline, not a total stream lifetime or idle TTL.
    */
  final case class Limits(
      maxFrameBytes: Int = 65536,
      maxPending: Int = 32,
      timeout: Duration = 10.seconds,
      maxStreams: Int = 16,
      maxChunkElements: Int = 65524
  ) {
    require(maxFrameBytes > 0 && maxFrameBytes <= 1024 * 1024, "maxFrameBytes must be 1..1048576")
    require(maxStreams > 0 && maxStreams <= 1024, "maxStreams must be 1..1024")
    require(maxChunkElements > 0 && maxChunkElements <= 65536, "maxChunkElements must be 1..65536")
    require(maxPending > 0 && maxPending <= 1024, "maxPending must be 1..1024")
    require(timeout > Duration.Zero && timeout < Duration.Infinity, "timeout must be finite and positive")
  }
  final case class AuthConfig(maxPendingTickets: Int = 128, maxTtl: Duration = 5.minutes) {
    require(maxPendingTickets > 0 && maxPendingTickets <= 65536, "maxPendingTickets must be 1..65536")
    require(maxTtl > Duration.Zero && maxTtl <= 1.day, "maxTtl must be positive and at most one day")
  }

  /** Instance-owned authenticated routes and ticket registry, independent of any HTTP listener. */
  final class RpcRoute private[RpcWebSocket] (val routes: Routes[Any, Nothing], tickets: RpcTickets) {

    /** Issues a single-use connection ticket for this route instance only. */
    def register(dispatcher: RpcDispatcher, ttl: Duration = 60.seconds): IO[RpcError, RpcToken] =
      tickets.register(dispatcher, ttl)

    /** Issues a single-use ticket whose authority ends with the caller's scope. Scope exit revokes an unclaimed ticket,
      * closes a redeemed socket and interrupts its unary/stream work, including during handshake. Finalization awaits
      * cooperative interruption; uninterruptible service work can delay scope closure.
      */
    def registerScoped(dispatcher: RpcDispatcher, ttl: Duration = 60.seconds): ZIO[Scope, RpcError, RpcToken] =
      ZIO.serviceWithZIO[Scope] { scope =>
        ZIO.acquireRelease(tickets.register(dispatcher, ttl, Some(scope)))(token => tickets.revoke(token).unit)
      }

    /** Revokes an unclaimed, unexpired ticket; established connections are unaffected. */
    def revoke(token: RpcToken): UIO[Boolean] = tickets.revoke(token)

  }

  private def config(limits: Limits): WebSocketConfig = WebSocketConfig.default
    .decoderConfig(SocketDecoder.default.maxFramePayloadLength(limits.maxFrameBytes))
    .forwardCloseFrames(true)
    .forceCloseTimeout(1.second)
  private def size(text: String): Int = text.getBytes(StandardCharsets.UTF_8).length

  /** TRACE deliberately includes RPC data; JSON-string encoding prevents multiline/log-injection payloads. */
  private def traceMessage(side: String, event: String, connectionId: java.util.UUID, payload: => String): UIO[Unit] =
    ZIO.logTrace(s"RpcWebSocket $side RPC $event connectionId=$connectionId payload=${payload.toJson}")

  private def errorCode(error: Throwable): RpcError.Code = error match {
    case error: RpcError => error.code
    case _               => RpcError.Code.ServiceFailure
  }
  private def transportError(error: Throwable): RpcError = error match {
    case _: TooLongFrameException                    => RpcError(RpcError.Code.LimitExceeded)
    case corrupted: CorruptedWebSocketFrameException =>
      RpcError(
        if corrupted.closeStatus().code() == 1009 then RpcError.Code.LimitExceeded else RpcError.Code.ProtocolError
      )
    case _ => RpcError(RpcError.Code.Disconnected)
  }
  private def text(frame: WebSocketFrame, limits: Limits): IO[RpcError, String] = frame match {
    case value: WebSocketFrame.Text if value.isFinal =>
      if size(value.text) <= limits.maxFrameBytes then ZIO.succeed(value.text)
      else ZIO.fail(RpcError(RpcError.Code.LimitExceeded))
    case _ => ZIO.fail(RpcError(RpcError.Code.ProtocolError))
  }
  private def reject(channel: WebSocketChannel, writes: Semaphore, status: Int): UIO[Unit] =
    writes
      .withPermit(channel.send(ChannelEvent.Read(WebSocketFrame.Close(status, None))))
      .timeout(1.second)
      .ignore
      .ensuring(channel.shutdown)

  /** Creates authenticated `GET /rpc` routes without binding a port or constructing an HTTP server.
    *
    * Each invocation allocates its own ticket registry: no mutable global state or default `Server` is used. Install
    * the returned `RpcRoute.routes` on your server, optionally composed with other routes, and call `RpcRoute.register`
    * whenever a new dispatcher set is ready. Tickets are valid only on that route instance. Redemption is final even if
    * the accepted handshake subsequently fails.
    *
    * Keep this scope alive while serving the routes. Closing it clears pending tickets and rejects registrations and
    * new upgrades; it does not stop your HTTP server or close established sockets issued by `register`. Independently,
    * `registerScoped` binds its socket and work to its caller's scope. The caller owns the HTTP server and its
    * connection lifetime. Authentication and Origin checks are unchanged; non-loopback/TLS deployments require
    * caller-supplied security configuration.
    *
    * For example, build and install your own server (pass a bound or merged dispatcher to `run`):
    * {{{
    * import fmgp.rpc.*
    * import zio.*
    * import zio.http.*
    *
    * def run(dispatcher: RpcDispatcher): Task[Unit] = ZIO.scoped {
    *   for {
    *     rpc <- RpcWebSocket.route()
    *     health = Routes(Method.GET / "health" -> Handler.text("ok"))
    *     http <- Server.defaultWith(_.binding("127.0.0.1", 0)).build
    *     port <- Server.install(rpc.routes ++ health).provideEnvironment(http)
    *     ticket <- rpc.register(dispatcher, ttl = 60.seconds)
    *     // Or deliver the endpoint and ticket to another JVM over a trusted channel.
    *     _ <- RpcWebSocket.connect(port, ticket)
    *     _ <- ZIO.never // Keep the route and HTTP-server scopes alive.
    *   } yield ()
    * }
    * }}}
    *
    * @param limits
    *   per-connection frame and concurrency limits; timeout is only used by `connect`
    * @param auth
    *   pending-ticket capacity and maximum ticket lifetime for this instance
    * @return
    *   scoped routes together with their instance-local registration and revocation methods
    */
  def route(limits: Limits = Limits(), auth: AuthConfig = AuthConfig()): URIO[Scope, RpcRoute] =
    for {
      tickets <- ZIO.acquireRelease(RpcTickets.make(auth))(_.close)
      routes = Routes(Method.GET / "rpc" -> handler { (request: Request) =>
        val authorization = singleHeader(request, "Authorization")
          .filter(_.startsWith("Bearer "))
          .toRight(RpcError(RpcError.Code.Unauthorized))
          .flatMap(value => RpcToken.parse(value.stripPrefix("Bearer ")))
        val accepted = for {
          _ <- ZIO.fail(RpcError(RpcError.Code.Unauthorized)).unless(validUpgrade(request))
          token <- ZIO.fromEither(authorization)
          ticket <- tickets.redeem(token)
          response <- socket(ticket.dispatcher, limits, ticket.scope).toResponse
        } yield response
        accepted.catchAll(_ => ZIO.succeed(Response.status(Status.Unauthorized)))
      })
    } yield new RpcRoute(routes, tickets)

  private def headerValues(request: Request, name: String): List[String] =
    request.headers.iterator.filter(_.headerName.equalsIgnoreCase(name)).map(_.renderedValue).toList
  private def singleHeader(request: Request, name: String): Option[String] =
    headerValues(request, name) match {
      case List(value) => Some(value)
      case _           => None
    }

  private def validUpgrade(request: Request): Boolean = {
    def header(name: String): Option[String] = singleHeader(request, name)
    val keyValid = header("Sec-WebSocket-Key").exists { key =>
      try java.util.Base64.getDecoder.decode(key).length == 16
      catch { case _: IllegalArgumentException => false }
    }
    request.method == Method.GET && request.version == Version.Http_1_1 && request.url.queryParams.isEmpty &&
    (headerValues(request, "Origin") match { case Nil | List("null") => true; case _ => false }) &&
    header("Host").exists(_.nonEmpty) &&
    header("Upgrade").exists(_.equalsIgnoreCase("websocket")) &&
    header("Connection").exists(_.split(",").exists(_.trim.equalsIgnoreCase("upgrade"))) &&
    header("Sec-WebSocket-Version").contains("13") && keyValid
  }

  private[rpc] def socket(
      dispatcher: RpcDispatcher,
      limits: Limits,
      authority: Option[Scope] = None
  ): WebSocketApp[Any] =
    Handler
      .webSocket { channel =>
        val session = ZIO.scoped {
          for {
            scope <- ZIO.service[Scope]
            connectionId <- Random.nextUUID
            connected <- Ref.make(false)
            active <- Ref.make(Set.empty[String])
            writes <- Semaphore.make(1)
            send = (output: String) =>
              if size(output) > limits.maxFrameBytes then
                ZIO.logTrace(
                  s"RpcWebSocket server RPC response rejected connectionId=$connectionId code=LimitExceeded"
                ) *> reject(channel, writes, 1009)
              else
                writes.withPermit(
                  channel.send(ChannelEvent.Read(WebSocketFrame.Text(output))) *>
                    traceMessage("server", "response sent", connectionId, output)
                )
            streams <- RpcServerStreams.make(
              dispatcher,
              limits,
              scope,
              active,
              send,
              (id, bytes) =>
                writes.withPermit(
                  channel.send(ChannelEvent.Read(WebSocketFrame.Binary(RpcWire.binary(id, bytes)))) *>
                    ZIO.logTrace(
                      s"RpcWebSocket server RPC binary sent connectionId=$connectionId streamId=$id bytes=${bytes.size}"
                    )
                ),
              channel.shutdown
            )
            _ <- channel
              .receiveAll {
                case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
                  (connected.set(true) *>
                    ZIO.logInfo(s"RpcWebSocket server connected connectionId=$connectionId")).uninterruptible
                case ChannelEvent.Read(WebSocketFrame.Ping | WebSocketFrame.Pong) => ZIO.unit
                case ChannelEvent.Read(_: WebSocketFrame.Close)                   => channel.shutdown
                case ChannelEvent.Read(frame)                                     =>
                  val work = for {
                    input <- text(frame, limits)
                    _ <- traceMessage("server", "request received", connectionId, input)
                    isStream <- ZIO.fromEither(RpcWire.isStream(input))
                    _ <-
                      if isStream then ZIO.fromEither(RpcWire.control(input)).flatMap(streams.control)
                      else
                        for {
                          request <- ZIO.fromEither(RpcWire.request(input))
                          streamCollision <- streams.contains(request.id)
                          _ <- ZIO.fail(RpcError(RpcError.Code.ProtocolError)).when(streamCollision)
                          admitted <- active.modify { ids =>
                            if ids.contains(request.id) then (Left(1002), ids)
                            else if ids.size >= limits.maxPending then (Left(1013), ids)
                            else (Right(()), ids + request.id)
                          }
                          _ <- admitted match {
                            case Left(status) =>
                              ZIO.logTrace(
                                s"RpcWebSocket server RPC request rejected connectionId=$connectionId requestId=${request.id} closeStatus=$status"
                              ) *> reject(channel, writes, status)
                            case Right(()) =>
                              val run = dispatcher
                                .dispatch(request.service, request.version, request.method, request.arguments)
                                .fold(
                                  error => RpcWire.failure(request.id, error),
                                  result => RpcWire.success(request.id, result)
                                )
                                .flatMap(send)
                                .onInterrupt(
                                  ZIO.logTrace(
                                    s"RpcWebSocket server RPC call interrupted connectionId=$connectionId requestId=${request.id}"
                                  )
                                )
                                .catchAllCause(_ => channel.shutdown)
                                .ensuring(active.update(_ - request.id))
                              run.interruptible.forkIn(scope).unit
                          }
                        } yield ()
                  } yield ()
                  work.catchAll(error =>
                    ZIO.logTrace(
                      s"RpcWebSocket server RPC request rejected connectionId=$connectionId code=${error.code}"
                    ) *> reject(channel, writes, if error.code == RpcError.Code.LimitExceeded then 1009 else 1002)
                  )
                case ChannelEvent.ExceptionCaught(_)                                          => channel.shutdown
                case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeTimeout) => channel.shutdown
                case _                                                                        => ZIO.unit
              }
              .ensuring(
                channel.shutdown *> connected.get
                  .flatMap(established =>
                    ZIO.logInfo(s"RpcWebSocket server disconnected connectionId=$connectionId").when(established)
                  )
                  .unit
              )
          } yield ()
        }
        authority match {
          case None        => session
          case Some(scope) =>
            ZIO.uninterruptibleMask { restore =>
              // Scope.addFinalizer also runs immediately on a closed scope. Install socket cleanup before forking:
              // closure between redemption, handler start and forkIn must never leave an upgraded socket alive.
              scope.addFinalizer(channel.shutdown) *>
                restore(session).forkIn(scope).flatMap(fiber => restore(fiber.join))
            }
        }
      }
      .withConfig(config(limits))
  def connect(port: Int, token: RpcToken): ZIO[Scope, RpcError, RpcConnection] =
    connect(s"ws://127.0.0.1:$port/rpc", token)

  /** One receiver and correlation state per connection, not per typed client. Streams are lazy, stop-and-wait,
    * independently materialized and cancel on scope exit. A pending producer/finalizer never blocks the receiver.
    * Remote finalization uses cooperative ZIO interruption; idle materialized streams have no global TTL. Explicit
    * close releases the owned HTTP client scope; the caller's scope remains open. No retry/replay.
    */
  def connect(endpoint: String, token: RpcToken, limits: Limits = Limits()): ZIO[Scope, RpcError, RpcConnection] =
    connectWithClient(endpoint, token, limits, Client.default)

  private[rpc] def connectWithClient(
      endpoint: String,
      token: RpcToken,
      limits: Limits,
      clientLayer: ZLayer[Any, Throwable, Client]
  ): ZIO[Scope, RpcError, RpcConnection] =
    ZIO.acquireRelease(Scope.make)(_.close(Exit.unit)).flatMap { scope =>
      connectInScope(endpoint, token, limits, clientLayer)
        .provideEnvironment(ZEnvironment(scope))
        .onError(_ => scope.close(Exit.unit))
        .map { connection =>
          new RpcConnection {
            override def call(serviceId: String, version: Int, method: String, arguments: Chunk[Json])
                : IO[RpcError, RpcResult] =
              connection.call(serviceId, version, method, arguments)
            override def stream(
                serviceId: String,
                version: Int,
                method: String,
                arguments: Chunk[Json],
                binary: Boolean
            ): ZStream[Any, RpcStreamError, RpcStreamChunk] =
              connection.stream(serviceId, version, method, arguments, binary)
            override def close: UIO[Unit] = scope.close(Exit.unit).uninterruptible
          }
        }
    }

  private final case class State(
      nextId: Long = 1,
      pending: Map[String, Promise[RpcError, RpcResult]] = Map.empty,
      closed: Boolean = false
  )
  private def disconnect(state: Ref[State], error: RpcError): UIO[Unit] =
    state
      .modify(current => (current.pending.values.toList, current.copy(pending = Map.empty, closed = true)))
      .flatMap(pending => ZIO.foreachDiscard(pending)(_.fail(error)))
      .uninterruptible

  private def connectInScope(
      endpoint: String,
      token: RpcToken,
      limits: Limits,
      clientLayer: ZLayer[Any, Throwable, Client]
  ): ZIO[Scope, RpcError, RpcConnection] =
    for {
      uri <- ZIO.attempt(URI.create(endpoint)).mapError(_ => RpcError(RpcError.Code.ProtocolError))
      _ <- ZIO
        .fail(RpcError(RpcError.Code.ProtocolError))
        .unless(
          Set("ws", "wss")
            .contains(
              uri.getScheme
            ) && uri.getHost != null && uri.getUserInfo == null && uri.getFragment == null && uri.getQuery == null
        )
      state <- Ref.make(State())
      connectionId <- Random.nextUUID
      connected <- Ref.make(false)
      writes <- Semaphore.make(1)
      ready <- Promise.make[RpcError, WebSocketChannel]
      streams <- RpcClientStreams.make(
        limits,
        state
          .modify { current =>
            if current.closed then (Left(RpcError(RpcError.Code.Disconnected)), current)
            else if current.nextId == Long.MaxValue then (Left(RpcError(RpcError.Code.LimitExceeded)), current)
            else (Right(current.nextId.toString), current.copy(nextId = current.nextId + 1))
          }
          .flatMap(ZIO.fromEither),
        writes,
        output =>
          for {
            _ <- ZIO.fail(RpcError(RpcError.Code.LimitExceeded)).when(size(output) > limits.maxFrameBytes)
            channel <- ready.await
            _ <- channel
              .send(ChannelEvent.Read(WebSocketFrame.Text(output)))
              .mapError(_ => RpcError(RpcError.Code.Disconnected))
            _ <- traceMessage("client", "stream control sent", connectionId, output)
          } yield (),
        error => disconnect(state, error) *> ready.await.flatMap(_.shutdown).ignore
      )
      disconnectAll = (error: RpcError) => disconnect(state, error) *> streams.disconnect(error)
      _ <- ZIO.addFinalizer(disconnectAll(RpcError(RpcError.Code.Disconnected)))
      http <- clientLayer.build.map(_.get[Client]).mapError(_ => RpcError(RpcError.Code.Disconnected))
      app = Handler
        .webSocket { channel =>
          channel
            .receiveAll {
              case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
                (connected.set(true) *>
                  ZIO.logInfo(s"RpcWebSocket client connected connectionId=$connectionId") *>
                  ready.succeed(channel).unit).uninterruptible
              case ChannelEvent.Read(WebSocketFrame.Ping | WebSocketFrame.Pong) => ZIO.unit
              case ChannelEvent.Read(close: WebSocketFrame.Close)               =>
                val code = close.status match {
                  case 1002 | 1003 | 1007 => RpcError.Code.ProtocolError
                  case 1009 | 1013        => RpcError.Code.LimitExceeded
                  case _                  => RpcError.Code.Disconnected
                }
                disconnectAll(RpcError(code)) *> channel.shutdown
              case ChannelEvent.Read(frame) =>
                (frame match {
                  case value: WebSocketFrame.Binary if value.isFinal && value.bytes.size <= limits.maxFrameBytes =>
                    for {
                      reply <- ZIO.fromEither(RpcWire.binaryReply(value.bytes))
                      _ <- ZIO.logTrace(
                        s"RpcWebSocket client RPC binary received connectionId=$connectionId streamId=${reply._1} bytes=${reply._2.size}"
                      )
                      _ <- streams.receiveBytes(reply._1, reply._2)
                    } yield ()
                  case _ =>
                    for {
                      input <- text(frame, limits)
                      _ <- traceMessage("client", "response received", connectionId, input)
                      isStream <- ZIO.fromEither(RpcWire.isStream(input))
                      _ <-
                        if isStream then
                          ZIO.fromEither(RpcWire.streamReply(input)).flatMap { (id, reply) =>
                            state.get.flatMap(current =>
                              ZIO
                                .fail(RpcError(RpcError.Code.ProtocolError))
                                .when(current.pending.contains(id) || id.toLong >= current.nextId)
                            ) *>
                              streams.receive(id, reply)
                          }
                        else
                          for {
                            response <- ZIO.fromEither(RpcWire.response(input))
                            collision <- streams.contains(response.id)
                            _ <- ZIO.fail(RpcError(RpcError.Code.ProtocolError)).when(collision)
                            promise <- state.modify(current =>
                              (current.pending.get(response.id), current.copy(pending = current.pending - response.id))
                            )
                            _ <- ZIO.foreachDiscard(promise)(_.done(Exit.fromEither(response.outcome)))
                          } yield ()
                    } yield ()
                }).uninterruptible.catchAll(error =>
                  ZIO.logTrace(
                    s"RpcWebSocket client RPC response rejected connectionId=$connectionId code=${error.code}"
                  ) *>
                    disconnectAll(error) *> channel.shutdown
                )
              case ChannelEvent.ExceptionCaught(error) => disconnectAll(transportError(error)) *> channel.shutdown
              case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeTimeout) => channel.shutdown
              case _                                                                        => ZIO.unit
            }
            .catchAllCause(_ => ZIO.unit)
            .ensuring(
              ready.fail(RpcError(RpcError.Code.Disconnected)).unit *>
                disconnectAll(RpcError(RpcError.Code.Disconnected)) *> channel.shutdown *>
                connected.get
                  .flatMap(established =>
                    ZIO.logInfo(s"RpcWebSocket client disconnected connectionId=$connectionId").when(established)
                  )
                  .unit
            )
        }
        .withConfig(config(limits))
      channel <- (for {
        // Override Netty's generated Origin; null is compatibility policy, not caller identity.
        response <- http
          .uri(uri)
          .addHeader("Origin", "null")
          .addHeader("Authorization", s"Bearer ${token.exportSecret}")
          .socket(app)
          .mapError {
            case error: io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException
                if Option(error.response()).exists(_.status().code() == 401) =>
              RpcError(RpcError.Code.Unauthorized)
            case _ => RpcError(RpcError.Code.Disconnected)
          }
        _ <- ZIO.fail(RpcError(RpcError.Code.Unauthorized)).when(response.status == Status.Unauthorized)
        _ <- ZIO.fail(RpcError(RpcError.Code.ProtocolError)).unless(response.status == Status.SwitchingProtocols)
        channel <- ready.await
      } yield channel).timeoutFail(RpcError(RpcError.Code.TimedOut))(limits.timeout)
      connection = new RpcConnection {
        override def stream(
            serviceId: String,
            version: Int,
            method: String,
            arguments: Chunk[Json],
            binary: Boolean
        ): ZStream[Any, RpcStreamError, RpcStreamChunk] = streams.stream(serviceId, version, method, arguments, binary)
        override def close: UIO[Unit] =
          (disconnectAll(RpcError(RpcError.Code.Disconnected)) *> channel.shutdown).uninterruptible
        override def call(
            serviceId: String,
            version: Int,
            method: String,
            arguments: Chunk[Json]
        ): IO[RpcError, RpcResult] =
          ZIO.uninterruptibleMask { restore =>
            for {
              promise <- Promise.make[RpcError, RpcResult]
              id <- state
                .modify { current =>
                  if current.closed then (Left(RpcError(RpcError.Code.Disconnected)), current)
                  else if current.pending.size >= limits.maxPending || current.nextId == Long.MaxValue then
                    (Left(RpcError(RpcError.Code.LimitExceeded)), current)
                  else {
                    val id = current.nextId.toString
                    (
                      Right(id),
                      current.copy(nextId = current.nextId + 1, pending = current.pending.updated(id, promise))
                    )
                  }
                }
                .flatMap(ZIO.fromEither)
                .tapError(error =>
                  ZIO.logTrace(
                    s"RpcWebSocket client RPC call rejected connectionId=$connectionId service=${serviceId.toJson} method=${method.toJson} code=${error.code}"
                  )
                )
              value <- restore {
                (for {
                  output <- ZIO
                    .attempt(RpcWire.Request(id, serviceId, version, method, arguments).toJson)
                    .mapError(_ => RpcError(RpcError.Code.ProtocolError))
                  _ <- ZIO.fail(RpcError(RpcError.Code.LimitExceeded)).when(size(output) > limits.maxFrameBytes)
                  _ <- writes
                    .withPermit(
                      channel.send(ChannelEvent.Read(WebSocketFrame.Text(output))) *>
                        traceMessage("client", "request sent", connectionId, output)
                    )
                    .mapError(_ => RpcError(RpcError.Code.Disconnected))
                    .tapError(error => disconnectAll(error) *> channel.shutdown)
                  result <- promise.await
                } yield result).timeoutFail(RpcError(RpcError.Code.TimedOut))(limits.timeout)
              }
                .tapError(error =>
                  ZIO.logTrace(
                    s"RpcWebSocket client RPC call failed connectionId=$connectionId requestId=$id code=${errorCode(error)}"
                  )
                )
                .onInterrupt(
                  ZIO.logTrace(s"RpcWebSocket client RPC call interrupted connectionId=$connectionId requestId=$id")
                )
                .ensuring(state.update(current => current.copy(pending = current.pending - id)))
            } yield value
          }
      }
      // Finalize only the channel here: the public wrapper closes this scope, so calling it would recurse.
      _ <- ZIO.addFinalizer(connection.close)
    } yield connection
}
