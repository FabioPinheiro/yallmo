package fmgp.rpc

import java.net.URI
import io.netty.handler.codec.TooLongFrameException
import io.netty.handler.codec.http.websocketx.CorruptedWebSocketFrameException
import zio.*
import zio.http.*

private[rpc] trait RpcWebSocketClientPlatform {
  private[rpc] def connectWithClient(
      endpoint: String,
      token: RpcToken,
      limits: RpcWebSocketClient.Limits,
      clientLayer: ZLayer[Any, Throwable, Client]
  ): ZIO[Scope, RpcError, RpcConnection] =
    RpcWebSocketClient.connectUsing(endpoint, limits)(openWithClient(endpoint, token, limits, clientLayer))

  protected def open(
      endpoint: String,
      token: RpcToken,
      limits: RpcWebSocketClient.Limits
  ): ZIO[Scope, RpcError, RpcSocket] = openWithClient(endpoint, token, limits, Client.default)

  private def openWithClient(
      endpoint: String,
      token: RpcToken,
      limits: RpcWebSocketClient.Limits,
      clientLayer: ZLayer[Any, Throwable, Client]
  ): ZIO[Scope, RpcError, RpcSocket] =
    for {
      // Remote termination shares reply ordering; only local/protocol aborts bypass queued messages.
      messages <- Queue.bounded[Either[RpcError, RpcSocket.Message]](32)
      ended <- Ref.make(false)
      end = (error: RpcError) =>
        ended.getAndSet(true).flatMap(alreadyEnded => ZIO.unless(alreadyEnded)(messages.offer(Left(error))).unit)
      failed <- Promise.make[RpcError, Nothing]
      ready <- Promise.make[RpcError, WebSocketChannel]
      http <- clientLayer.build.map(_.get[Client]).mapError(_ => RpcError(RpcError.Code.Disconnected))
      app = Handler
        .webSocket { channel =>
          channel
            .receiveAll {
              case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
                ready.succeed(channel).unit
              case ChannelEvent.Read(WebSocketFrame.Ping | WebSocketFrame.Pong) => ZIO.unit
              case ChannelEvent.Read(close: WebSocketFrame.Close)               =>
                end(RpcSocket.closeError(close.status)) *> channel.shutdown
              case ChannelEvent.Read(value: WebSocketFrame.Text) if value.isFinal =>
                messages.offer(Right(RpcSocket.Message.Text(value.text))).unit
              case ChannelEvent.Read(value: WebSocketFrame.Binary) if value.isFinal =>
                messages.offer(Right(RpcSocket.Message.Binary(value.bytes))).unit
              case ChannelEvent.Read(_) => failed.fail(RpcError(RpcError.Code.ProtocolError)).unit *> channel.shutdown
              case ChannelEvent.ExceptionCaught(error) =>
                val code = error match {
                  case _: TooLongFrameException                => RpcError.Code.LimitExceeded
                  case value: CorruptedWebSocketFrameException =>
                    if value.closeStatus().code() == 1009 then RpcError.Code.LimitExceeded
                    else RpcError.Code.ProtocolError
                  case _ => RpcError.Code.Disconnected
                }
                failed.fail(RpcError(code)).unit *> channel.shutdown
              case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeTimeout) =>
                failed.fail(RpcError(RpcError.Code.TimedOut)).unit *> channel.shutdown
              case _ => ZIO.unit
            }
            .catchAllCause(_ => ZIO.unit)
            .ensuring(
              ready.fail(RpcError(RpcError.Code.Disconnected)).unit *>
                end(RpcError(RpcError.Code.Disconnected)) *> channel.shutdown
            )
        }
        .withConfig(
          WebSocketConfig.default
            .decoderConfig(SocketDecoder.default.maxFramePayloadLength(limits.maxFrameBytes))
            .forwardCloseFrames(true)
            .forceCloseTimeout(1.second)
            .subProtocol(Some(RpcHandshake.offer(token)))
        )
      response <- http
        .uri(URI.create(endpoint))
        .addHeader("Origin", "null")
        .socket(app)
        .mapError(_ => RpcError(RpcError.Code.Disconnected))
      _ <- ZIO.fail(RpcError(RpcError.Code.Disconnected)).unless(response.status == Status.SwitchingProtocols)
      _ <- ZIO
        .fail(RpcError(RpcError.Code.ProtocolError))
        .unless(
          response.headers.iterator
            .filter(_.headerName.equalsIgnoreCase("Sec-WebSocket-Protocol"))
            .map(_.renderedValue)
            .toList == List(RpcHandshake.Protocol)
        )
      channel <- ready.await
      _ <- ZIO.addFinalizer(channel.shutdown *> messages.shutdown)
    } yield new RpcSocket {
      def sendText(text: String): IO[RpcError, Unit] = channel
        .send(ChannelEvent.Read(WebSocketFrame.Text(text)))
        .mapError(_ => RpcError(RpcError.Code.Disconnected))
      def receive: IO[RpcError, RpcSocket.Message] = messages.take
        .flatMap {
          case Right(message) => ZIO.succeed(message)
          case Left(error)    => failed.fail(error) *> ZIO.fail(error)
        }
        .raceFirst(failed.await)
      def close: UIO[Unit] = failed.fail(RpcError(RpcError.Code.Disconnected)).unit *> channel.shutdown
    }
}
