package fmgp.rpc

import java.nio.charset.StandardCharsets
import zio.*
import zio.http.*
import zio.json.*

/** JVM-only authenticated RPC routes and ticket authority; HTTP hosting belongs to the caller. */
object RpcWebSocket {

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

  private def config(limits: RpcWebSocketClient.Limits): WebSocketConfig = WebSocketConfig.default
    .decoderConfig(SocketDecoder.default.maxFramePayloadLength(limits.maxFrameBytes))
    .forwardCloseFrames(true)
    .forceCloseTimeout(1.second)
  private def size(text: String): Int = text.getBytes(StandardCharsets.UTF_8).length

  /** TRACE deliberately includes RPC data; JSON-string encoding prevents multiline/log-injection payloads. */
  private def traceMessage(side: String, event: String, connectionId: java.util.UUID, payload: => String): UIO[Unit] =
    ZIO.logTrace(s"RpcWebSocket $side RPC $event connectionId=$connectionId payload=${payload.toJson}")

  private def text(frame: WebSocketFrame, limits: RpcWebSocketClient.Limits): IO[RpcError, String] = frame match {
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
    *     _ <- RpcWebSocketClient.connect(port, ticket)
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
  def route(
      limits: RpcWebSocketClient.Limits = RpcWebSocketClient.Limits(),
      auth: AuthConfig = AuthConfig()
  ): URIO[Scope, RpcRoute] =
    for {
      tickets <- ZIO.acquireRelease(RpcTickets.make(auth))(_.close)
      routes = Routes(Method.GET / "rpc" -> handler { (request: Request) =>
        val authorization =
          if headerValues(request, "Authorization").nonEmpty then Left(RpcError(RpcError.Code.Unauthorized))
          else RpcHandshake.parse(headerValues(request, "Sec-WebSocket-Protocol"))
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
      limits: RpcWebSocketClient.Limits,
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
      .withConfig(config(limits).subProtocol(Some(RpcHandshake.Protocol)))
}
