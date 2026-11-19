package fmgp.rpc

import munit.ZSuite
import zio.*
import zio.json.ast.Json

private[rpc] object RpcReplyBeforeCloseFixture {
  val messages = Chunk(
    RpcSocket.Message.Text(RpcWire.success("1", RpcResult.Success(Json.Str("mutation completed")))),
    RpcSocket.Message.Binary(RpcWire.binary("2", Chunk[Byte](1, 2, 3))),
    RpcSocket.Message.Text(RpcWire.streamResponse("2", RpcWire.StreamReply.End)),
    RpcSocket.Message.Text(RpcWire.streamResponse("3", RpcWire.StreamReply.Failure(Json.Str("domain failure"))))
  )
}

/** Delay receiving to reproduce an occupied shared receiver while native callbacks accept replies and Close. */
abstract class RpcReplyBeforeCloseSuite extends ZSuite {
  protected def closeHost: ZIO[Scope, Throwable, (String, RpcToken)]
  private object Adapter extends RpcWebSocketClientPlatform {
    def socket(endpoint: String, token: RpcToken): ZIO[Scope, RpcError, RpcSocket] =
      open(endpoint, token, RpcWebSocketClient.Limits())
  }

  testZ("actual adapter retains unary reply, binary final chunk, stream end and domain failure before remote close") {
    ZIO
      .scoped {
        for {
          bootstrap <- closeHost
          (endpoint, token) = bootstrap
          socket <- Adapter.socket(endpoint, token)
          _ <- socket.sendText("send replies then close")
          // The peer closes immediately; leave all messages queued rather than consuming as they arrive.
          _ <- ZIO.sleep(500.millis)
          received <- ZIO.foreach(RpcReplyBeforeCloseFixture.messages)(_ => socket.receive)
          terminal <- socket.receive.either
          repeated <- socket.receive.either
        } yield {
          assertEquals(received, RpcReplyBeforeCloseFixture.messages)
          assertEquals(terminal, Left(RpcError(RpcError.Code.Disconnected)))
          assertEquals(repeated, terminal)
        }
      }
      .timeoutFail(new IllegalStateException("Reply-before-close deadline"))(15.seconds)
  }
}
