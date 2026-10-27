package zio.http.netty.client

import io.netty.channel.{Channel, ChannelFactory, ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.ssl.SslHandler
import javax.net.ssl.SSLHandshakeException
import scala.util.control.NonFatal

/** Configure TLS before channel activation: enforce HTTPS hostname checks and let the scoped connect effect own the
  * handshake deadline, rather than Netty's independent ten-second timeout. Certificate trust uses ZIO HTTP's public
  * ClientSSLConfig. ZIO HTTP does not expose these pre-activation settings; this package is needed for the driver's
  * private[netty] copy. Pool, event-loop and fiber ownership stay with ZIO HTTP. Callbacks execute no ZIO effects.
  */
object YallmoWebSocketClientDriver {
  def apply(driver: NettyClientDriver): NettyClientDriver =
    driver.copy(channelFactory = new ChannelFactory[Channel] {
      override def newChannel(): Channel = {
        val channel = driver.channelFactory.newChannel()
        channel
          .pipeline()
          .addLast(new ChannelInboundHandlerAdapter {
            private var configured: SslHandler = null

            override def channelRegistered(ctx: ChannelHandlerContext): Unit =
              try {
                val ssl = ctx.pipeline().get(classOf[SslHandler])
                // Bootstrap's initializer installs SSL and re-fires registration from the pipeline head. The first pass
                // can precede that initializer; the second finds SSL, still before channelActive starts its handshake.
                if ssl != null && configured == null then {
                  val parameters = ssl.engine().getSSLParameters
                  parameters.setEndpointIdentificationAlgorithm("HTTPS")
                  ssl.engine().setSSLParameters(parameters)
                  ssl.setHandshakeTimeoutMillis(0L)
                  configured = ssl
                }
                ctx.fireChannelRegistered()
                ()
              } catch { case NonFatal(error) => failClosed(ctx, error) }

            override def channelActive(ctx: ChannelHandlerContext): Unit = {
              if ctx.pipeline().get(classOf[SslHandler]) ne configured then
                failClosed(ctx, new SSLHandshakeException("Unconfigured WebSocket TLS pipeline"))
              else {
                ctx.pipeline().remove(this)
                ctx.fireChannelActive()
                ()
              }
            }

            private def failClosed(ctx: ChannelHandlerContext, error: Throwable): Unit = {
              ctx.fireExceptionCaught(error)
              ctx.close()
              ()
            }
          })
        channel
      }
    })
}
