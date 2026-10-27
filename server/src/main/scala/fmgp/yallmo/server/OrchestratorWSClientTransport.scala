package fmgp.yallmo.server

import fmgp.yallmo.server.OrchestratorClient.Error
import io.netty.channel.{Channel, ChannelDuplexHandler, ChannelHandlerContext, ChannelPromise}
import io.netty.handler.codec.TooLongFrameException
import io.netty.handler.codec.http.{HttpHeaderNames, HttpRequest, HttpResponse}
import io.netty.handler.codec.http.websocketx.{
  BinaryWebSocketFrame,
  CloseWebSocketFrame,
  CorruptedWebSocketFrameException,
  WebSocketClientProtocolHandler,
  WebSocketFrame as NettyFrame,
  WebSocketFrameAggregator
}
import zio.*
import zio.http.*
import zio.http.internal.ChannelState
import zio.http.netty.NettyConfig
import zio.http.netty.client.{NettyClientDriver, YallmoWebSocketClientDriver}

/** ZIO HTTP client configuration and WebSocket message boundary. Channels and receive fibers belong to the client
  * scope. The synchronous Netty pipeline adjustments below run no ZIO effects.
  *
  * Aggregate bounded raw bytes before zio-http converts the first Text frame to String (which otherwise loses a code
  * point split across the initial frame boundary). Netty still validates UTF-8 and handles ping/pong. Its generated
  * Origin is removed to retain the JVM client's non-browser handshake; server policy is unchanged. ZIO HTTP's inbound
  * event queue is unbounded, even though frames and assembled messages are bounded here.
  */
private[server] object OrchestratorWSClientTransport {
  val layer: ZLayer[Any, Throwable, Client] = withTLS(ClientSSLConfig.FromSystemTrustStore)

  private[server] def withTLS(tls: ClientSSLConfig): ZLayer[Any, Throwable, Client] = {
    val driver = NettyClientDriver.live >>> ZLayer.fromZIO {
      ZIO.service[ClientDriver].flatMap {
        case netty: NettyClientDriver =>
          val secure = YallmoWebSocketClientDriver(netty)
          ZIO.succeed(new CompatibleDriver(secure))
        case _ => ZIO.fail(new IllegalStateException("Expected the zio-http Netty client driver"))
      }
    }
    (
      ZLayer.succeed(
        ZClient.Config.default.disabledConnectionPool.noIdleTimeout.ssl(tls).webSocketConfig(RpcWire.webSocketConfig)
      ) ++
        (ZLayer.succeed(NettyConfig.defaultWithFastShutdown) >>> driver) ++ DnsResolver.default
    ) >>> Client.customized
  }

  def error(cause: Throwable): Error = cause match {
    case _: TooLongFrameException | _: CorruptedWebSocketFrameException => Error.InvalidResponse
    case _                                                              => Error.Transport(cause)
  }

  private final class CompatibleDriver(underlying: NettyClientDriver) extends ClientDriver {
    type Connection = Channel

    override def createConnectionPool(dnsResolver: DnsResolver, config: ConnectionPoolConfig)(using
        Trace
    ): ZIO[Scope, Nothing, ConnectionPool[Channel]] = underlying.createConnectionPool(dnsResolver, config)

    override def requestOnChannel(
        channel: Channel,
        location: URL.Location.Absolute,
        request: Request,
        onResponse: Promise[Throwable, Response],
        onComplete: Promise[Throwable, ChannelState],
        enableKeepAlive: Boolean,
        createSocketApp: () => WebSocketApp[Any],
        webSocketConfig: WebSocketConfig,
        bodyReadTimeoutMillis: Option[Long]
    )(using Trace): ZIO[Scope, Throwable, ClientDriver.ChannelInterface] =
      ZIO.attempt {
        // Installed before the delegate starts the handshake, after its HTTP codec. At the first HTTP response all
        // WebSocket handlers exist; adjust the pipeline on its event loop before any WebSocket bytes are decoded.
        channel
          .pipeline()
          .addLast(new ChannelDuplexHandler {
            override def write(ctx: ChannelHandlerContext, message: Any, promise: ChannelPromise): Unit = {
              message.asInstanceOf[Matchable] match {
                case request: HttpRequest => request.headers().remove(HttpHeaderNames.ORIGIN)
                case _                    => ()
              }
              ctx.write(message, promise)
              ()
            }

            override def channelRead(ctx: ChannelHandlerContext, message: Any): Unit = {
              message.asInstanceOf[Matchable] match {
                case _: HttpResponse =>
                  val protocol = ctx.pipeline().context(classOf[WebSocketClientProtocolHandler])
                  ctx
                    .pipeline()
                    .addAfter(
                      protocol.name(),
                      "yallmo-message-aggregator",
                      new WebSocketFrameAggregator(RpcWire.MaxMessageBytes) {
                        // Binary is unsupported, including a non-final initial frame: do not wait for its continuations.
                        override def acceptInboundMessage(message: Any): Boolean =
                          !message.isInstanceOf[BinaryWebSocketFrame] && super.acceptInboundMessage(message)

                        override def handleOversizedMessage(ctx: ChannelHandlerContext, message: NettyFrame): Unit = {
                          ctx.writeAndFlush(new CloseWebSocketFrame(1009, "Message too large"))
                          ctx.fireExceptionCaught(new TooLongFrameException("WebSocket message too large"))
                          ()
                        }
                      }
                    )
                case _ => ()
              }
              ctx.fireChannelRead(message)
              ()
            }
          })
        ()
      } *> underlying.requestOnChannel(
        channel,
        location,
        request,
        onResponse,
        onComplete,
        enableKeepAlive,
        createSocketApp,
        webSocketConfig,
        bodyReadTimeoutMillis
      )
  }
}
