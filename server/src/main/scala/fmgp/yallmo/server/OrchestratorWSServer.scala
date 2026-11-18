package fmgp.yallmo.server

import fmgp.yallmo.model.{User, UserId}
import fmgp.yallmo.server.application.ServerExecutionService
import fmgp.yallmo.server.website.{SdlcHttpRoutes, WebAssets}
import fmgp.yallmo.tool.sdlc.SdlcService
import zio.*
import zio.http.*

/** Authenticated JSON-RPC/HTTP host. Construction starts nothing; only SPA assets and login are public. */
final class OrchestratorWSServer(
    configured: ServerExecutionService,
    sdlc: Option[SdlcService] = None,
    users: Option[Map[UserId, User]] = None
) {
  private val rpc = new OrchestratorRpc(configured)
  private val auth = new WebAuth(users.getOrElse(configured.application.webUsers))
  private val protectedRoutes = sdlc.fold(Routes.empty)(service => new SdlcHttpRoutes(service).routes)

  val routes: Routes[Any, Nothing] = Routes(
    RoutePattern.any -> handler { (_: Path, request: Request) =>
      val path = request.path.encode
      val response =
        if path.startsWith("/auth/") then auth.endpoint(request)
        else if request.method == Method.GET && (path == "/" || path == "/index.html" || path.startsWith("/assets/"))
        then WebAssets.response(request)
        else if !WebAuth.sameOrigin(request, required = path == "/rpc" || request.method != Method.GET) then
          ZIO.succeed(Response.status(Status.Forbidden))
        else
          auth.session(request).flatMap {
            case None => ZIO.succeed(Response.status(Status.Unauthorized))
            case Some(session) if path == "/rpc" && request.method == Method.GET => socket(request, session).toResponse
            case Some(_) if request.method != Method.GET                         =>
              ZIO.succeed(Response.status(Status.MethodNotAllowed).addHeader("Allow", "GET"))
            case Some(_) => ZIO.scoped(protectedRoutes.runZIO(request))
          }
      response.map(WebAssets.secure(request, _))
    }
  )

  def start(port: Int = 8080): ZIO[Scope, Throwable, Int] =
    for {
      _ <- ZIO.fail(new IllegalArgumentException("Port must be between 0 and 65535")).unless(port >= 0 && port <= 65535)
      server <- Server
        .defaultWith(
          _.binding("0.0.0.0", port)
            .disableRequestStreaming(8192)
            .maxHeaderSize(8192)
            .gracefulShutdownTimeout(1.second)
        )
        .build
      bound <- Server.install(routes).provideEnvironment(server)
    } yield bound

  private def socket(request: Request, session: WebAuth.Session): WebSocketApp[Any] = Handler
    .webSocket { channel =>
      ZIO.scoped {
        rpc
          .openAuthenticatedConnection(session.user)
          .flatMap { connection =>
            ZIO.logAnnotate("connectionId", connection.id.toString) {
              for {
                connected <- Ref.make(false)
                text <- WebSocketText.make
                writes <- Semaphore.make(1)
                workers <- Ref.make(0)
                workerScope <- ZIO.service[Scope]
                send = (frame: WebSocketFrame) => writes.withPermit(channel.send(ChannelEvent.Read(frame)))
                _ <- (auth.untilRevoked(session) *>
                  send(WebSocketFrame.Close(1008, Some("Session expired"))).timeout(1.second).ignore *>
                  rpc.disconnect(connection) *> channel.shutdown).forkScoped
                // One scoped sender, not a long-lived request worker. A stalled socket cannot hold a mutation open.
                _ <- (rpc
                  .nextNotification(connection)
                  .flatMap { output =>
                    if RpcWire.size(output) > RpcWire.MaxMessageBytes then
                      ZIO.fail("Event event too large; reconnect and resubscribe")
                    else
                      send(WebSocketFrame.Text(output))
                        .mapError(_ => "Event transport failed; reconnect")
                        .timeoutFail("Event sender stalled; reconnect and resubscribe")(
                          OrchestratorWSServer.EventSendTimeout
                        )
                  })
                  .forever
                  .catchAll { reason =>
                    send(WebSocketFrame.Close(1013, Some(reason))).timeout(1.second).ignore *> channel.shutdown
                  }
                  .forkScoped
                _ <- channel
                  .receiveAll {
                    case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
                      connected.set(true) *> ZIO.logInfo(
                        s"WebSocket connected peer=${request.remoteAddress.fold("unknown")(_.getHostAddress)}"
                      )
                    case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeTimeout) =>
                      ZIO.logWarning("WebSocket handshake timed out") *> channel.shutdown
                    case ChannelEvent.Read(WebSocketFrame.Ping)         => send(WebSocketFrame.Pong)
                    case ChannelEvent.Read(WebSocketFrame.Pong)         => ZIO.unit
                    case ChannelEvent.Read(close: WebSocketFrame.Close) =>
                      ZIO.logDebug(s"WebSocket peer requested close status=${close.status}") *> channel.shutdown
                    case ChannelEvent.Read(frame) =>
                      text
                        .accept(frame)
                        .foldZIO(
                          failure =>
                            ZIO.logWarning(s"WebSocket frame rejected code=${failure.code} reason=${failure.reason}") *>
                              send(WebSocketFrame.Close(failure.code, Some(failure.reason))) *>
                              channel.shutdown,
                          message =>
                            ZIO.foreachDiscard(message) { input =>
                              val work = for {
                                valid <- auth.valid(session)
                                _ <- ZIO.interrupt.unless(valid)
                                _ <- ZIO.logDebug(s"RPC incoming bytes=${RpcWire.size(input)}")
                                // Encode as a JSON string to escape control characters/newlines in untrusted input.
                                _ <- ZIO.logTrace(s"RPC incoming payload=${RpcWire.tracePayload(input)}")
                                response <- rpc.handle(input, connection)
                                _ <- ZIO.foreachDiscard(response) { output =>
                                  if RpcWire.size(output) > RpcWire.MaxMessageBytes then
                                    ZIO.logWarning("WebSocket response exceeds message limit; closing with 1009") *>
                                      send(WebSocketFrame.Close(1009, Some("Response too large"))) *>
                                      channel.shutdown
                                  else
                                    for {
                                      _ <- send(WebSocketFrame.Text(output))
                                      _ <- ZIO.logDebug(s"RPC outgoing bytes=${RpcWire.size(output)}")
                                      _ <- ZIO.logTrace(s"RPC outgoing payload=${RpcWire.tracePayload(output)}")
                                    } yield ()
                                }
                              } yield ()
                              ZIO.uninterruptibleMask { _ =>
                                workers
                                  .modify(count =>
                                    (
                                      count < OrchestratorWSServer.MaxConnectionWorkers,
                                      if count < OrchestratorWSServer.MaxConnectionWorkers then count + 1 else count
                                    )
                                  ) // TODO REVIEW DO we really need this?
                                  .flatMap {
                                    case true =>
                                      work.ensuring(workers.update(_ - 1)).interruptible.forkIn(workerScope).unit
                                    case false =>
                                      send(WebSocketFrame.Close(1013, Some("Too many requests"))) *> channel.shutdown
                                  }
                              }
                            }
                        )
                    case ChannelEvent.ExceptionCaught(error) =>
                      ZIO.logErrorCause("WebSocket transport failed", Cause.fail(error)) *> channel.shutdown
                    case _ => ZIO.unit
                  }
                  .ensuring(
                    rpc.disconnect(connection) *> channel.shutdown *> connected.get
                      .flatMap(established => ZIO.logInfo("WebSocket disconnected").when(established))
                      .unit
                  )
              } yield ()
            }
          }
      }
    }
    .withConfig(RpcWire.webSocketConfig)
}

object OrchestratorWSServer {
  private[server] val MaxConnectionWorkers = 8
  private[server] val EventSendTimeout = 5.seconds
}
