package fmgp.yallmo.server

import fmgp.yallmo.orchestration.OrchestratorLive
import java.util.UUID
import zio.*
import zio.http.*
import zio.json.*

/** JSON-RPC WebSocket adapter and SPA host over the supplied master. Construction starts nothing. start binds loopback;
  * routes may also be installed in an existing host. No second storage/runtime is created. This initial viewer boundary
  * has Host/Origin checks, not execution authorization or remote-hosting support.
  */
final class OrchestratorWSServer(master: OrchestratorLive[?]) {
  private val rpc = new OrchestratorRpc(master)

  val routes: Routes[Any, Nothing] = Routes(
    RoutePattern.any -> handler { (_: Path, request: Request) =>
      val response =
        if !trustedSite(request) then ZIO.succeed(Response.status(Status.Forbidden))
        else if request.method != Method.GET then
          ZIO.succeed(Response.status(Status.MethodNotAllowed).addHeader("Allow", "GET"))
        else if request.path.encode == "/rpc" then socket(request).toResponse
        else WebAssets.response(request)
      response.map(WebAssets.secure(request, _))
    }
  )

  def start(port: Int = 8080): ZIO[Scope, Throwable, Int] =
    for {
      _ <- ZIO.fail(new IllegalArgumentException("Port must be between 0 and 65535")).unless(port >= 0 && port <= 65535)
      server <- Server
        .defaultWith(
          _.binding("127.0.0.1", port)
            .disableRequestStreaming(1024)
            .maxHeaderSize(8192)
            .gracefulShutdownTimeout(1.second)
        )
        .build
      bound <- Server.install(routes).provideEnvironment(server)
    } yield bound

  private def socket(request: Request): WebSocketApp[Any] = Handler
    .webSocket { channel =>
      ZIO.succeed(UUID.randomUUID().toString).flatMap { connectionId =>
        ZIO.logAnnotate("connectionId", connectionId) {
          for {
            connected <- Ref.make(false)
            initialized <- Ref.make(false)
            text <- WebSocketText.make
            _ <- channel
              .receiveAll {
                case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
                  connected.set(true) *> ZIO.logInfo(
                    s"WebSocket connected peer=${request.remoteAddress.fold("unknown")(_.getHostAddress)}"
                  )
                case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeTimeout) =>
                  ZIO.logWarning("WebSocket handshake timed out") *> channel.shutdown
                case ChannelEvent.Read(WebSocketFrame.Ping) => channel.send(ChannelEvent.Read(WebSocketFrame.Pong))
                case ChannelEvent.Read(WebSocketFrame.Pong) => ZIO.unit
                case ChannelEvent.Read(close: WebSocketFrame.Close) =>
                  ZIO.logDebug(s"WebSocket peer requested close status=${close.status}") *> channel.shutdown
                case ChannelEvent.Read(frame) =>
                  text
                    .accept(frame)
                    .foldZIO(
                      failure =>
                        ZIO.logWarning(s"WebSocket frame rejected code=${failure.code} reason=${failure.reason}") *>
                          channel.send(ChannelEvent.Read(WebSocketFrame.Close(failure.code, Some(failure.reason)))) *>
                          channel.shutdown,
                      message =>
                        ZIO.foreachDiscard(message) { input =>
                          for {
                            _ <- ZIO.logDebug(s"RPC incoming bytes=${RpcWire.size(input)}")
                            // Encode as a JSON string to escape control characters/newlines in untrusted input.
                            _ <- ZIO.logTrace(s"RPC incoming payload=${input.toJson}")
                            response <- rpc.handle(input, initialized)
                            _ <- ZIO.foreachDiscard(response) { output =>
                              if RpcWire.size(output) > RpcWire.MaxMessageBytes then
                                ZIO.logWarning("WebSocket response exceeds message limit; closing with 1009") *>
                                  channel
                                    .send(ChannelEvent.Read(WebSocketFrame.Close(1009, Some("Response too large")))) *>
                                  channel.shutdown
                              else
                                for {
                                  _ <- channel.send(ChannelEvent.Read(WebSocketFrame.Text(output)))
                                  _ <- ZIO.logDebug(s"RPC outgoing bytes=${RpcWire.size(output)}")
                                  _ <- ZIO.logTrace(s"RPC outgoing payload=${output.toJson}")
                                } yield ()
                            }
                          } yield ()
                        }
                    )
                case ChannelEvent.ExceptionCaught(error) =>
                  ZIO.logErrorCause("WebSocket transport failed", Cause.fail(error))
                case _ => ZIO.unit
              }
              .ensuring(
                channel.shutdown *> connected.get
                  .flatMap(established => ZIO.logInfo("WebSocket disconnected").when(established))
                  .unit
              )
          } yield ()
        }
      }
    }
    .withConfig(RpcWire.webSocketConfig)

  private def trustedSite(request: Request): Boolean = {
    def values(name: String): List[String] =
      request.headers.iterator.filter(_.headerName.equalsIgnoreCase(name)).map(_.renderedValue).toList
    values("Host") match {
      case List(host) if host.matches("(localhost|127\\.0\\.0\\.1):[0-9]{1,5}") =>
        values("Origin") match {
          case Nil          => true
          case List(origin) =>
            origin == s"http://$host" || origin == "http://localhost:5173" || origin == "http://127.0.0.1:5173"
          case _ => false
        }
      case _ => false
    }
  }
}
