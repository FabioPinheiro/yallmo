package fmgp.rpc

import zio.*
import zio.http.*

private[rpc] object RpcReplyBeforeCloseHost {
  def start(
      protocol: Option[String] = Some(RpcHandshake.Protocol),
      messages: Chunk[RpcSocket.Message] = RpcReplyBeforeCloseFixture.messages
  ): ZIO[Scope, Throwable, (String, RpcToken)] = for {
    app <- ZIO.succeed(
      Handler
        .webSocket { channel =>
          channel
            .receiveAll {
              case ChannelEvent.Read(_: WebSocketFrame.Text) =>
                ZIO.foreachDiscard(messages) { message =>
                  val frame = message match {
                    case RpcSocket.Message.Text(text)   => WebSocketFrame.Text(text)
                    case RpcSocket.Message.Binary(data) => WebSocketFrame.Binary(data)
                  }
                  channel.send(ChannelEvent.Read(frame))
                } *> channel.send(ChannelEvent.Read(WebSocketFrame.Close(1000, None)))
              case ChannelEvent.Read(_: WebSocketFrame.Close) => channel.shutdown
              case _                                          => ZIO.unit
            }
            .ensuring(channel.shutdown)
        }
        .withConfig(WebSocketConfig.default.forwardCloseFrames(true).subProtocol(protocol))
    )
    http <- Server.defaultWith(_.binding("127.0.0.1", 0).gracefulShutdownTimeout(1.second)).build
    port <- Server.install(Routes(Method.GET / "rpc" -> handler(app.toResponse))).provideEnvironment(http)
    token <- ZIO.fromEither(RpcToken.parse("A" * 43))
  } yield (s"ws://127.0.0.1:$port/rpc", token)
}

class RpcReplyBeforeCloseJvmSuite extends RpcReplyBeforeCloseSuite {
  protected def closeHost = RpcReplyBeforeCloseHost.start()
}
