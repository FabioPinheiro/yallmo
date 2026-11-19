package fmgp.rpc

import munit.ZSuite
import zio.*
import zio.json.ast.Json

class RpcClientProtocolSuite extends ZSuite {
  private val secret = "A" * 43
  test("portable token parsing is canonical and redacted; protocol credentials are exact and unambiguous") {
    val token = RpcToken.parse(secret).toOption.get
    assertEquals(token.toString, "RpcToken(<redacted>)")
    assertEquals(RpcHandshake.parse(List(RpcHandshake.offer(token))).map(_.exportSecret), Right(secret))
    assertEquals(RpcHandshake.parse(List(s"yallmo.ticket.$secret, yallmo.rpc.v2")).map(_.exportSecret), Right(secret))
    val invalid = List(null, "", "A" * 42, "A" * 44, "_" * 43, "A" * 42 + "B", secret + "=")
    invalid.foreach(value => assertEquals(RpcToken.parse(value).left.map(_.getMessage), Left("Unauthorized")))
    val offers = List(
      Nil,
      List("yallmo.rpc.v2"),
      List(s"yallmo.ticket.$secret"),
      List(s"yallmo.rpc.v2, yallmo.ticket.$secret", s"yallmo.rpc.v2, yallmo.ticket.$secret"),
      List(s"yallmo.rpc.v2, yallmo.rpc.v2, yallmo.ticket.$secret"),
      List(s"yallmo.rpc.v2, yallmo.ticket.$secret, yallmo.ticket.$secret"),
      List(s"yallmo.rpc.v2, yallmo.ticket.$secret, unknown"),
      List(s"yallmo.rpc.v2, yallmo.ticket.$secret,"),
      List("yallmo.rpc.v2, yallmo.ticket.malformed")
    )
    offers.foreach(value => assertEquals(RpcHandshake.parse(value).left.map(_.getMessage), Left("Unauthorized")))
  }

  testZ("shared correlation handles out-of-order replies, pending limits, and disconnect without a transport") {
    ZIO.scoped {
      for {
        outgoing <- Queue.bounded[String](8)
        incoming <- Queue.bounded[RpcSocket.Message](8)
        ended <- Promise.make[RpcError, Nothing]
        socket = new RpcSocket {
          def sendText(text: String): IO[RpcError, Unit] = outgoing.offer(text).unit
          def receive: IO[RpcError, RpcSocket.Message] = incoming.take.raceFirst(ended.await)
          def close: UIO[Unit] = ended.fail(RpcError(RpcError.Code.Disconnected)).unit
        }
        connection <- RpcWebSocketClient.connectUsing("ws://localhost/rpc", RpcWebSocketClient.Limits(maxPending = 2))(
          ZIO.succeed(socket)
        )
        a <- connection.call("service", 1, "a", Chunk.empty).fork
        requestA <- outgoing.take.flatMap(text => ZIO.fromEither(RpcWire.request(text)))
        b <- connection.call("service", 1, "b", Chunk.empty).fork
        requestB <- outgoing.take.flatMap(text => ZIO.fromEither(RpcWire.request(text)))
        excess <- connection.call("service", 1, "excess", Chunk.empty).either
        _ <- incoming.offer(RpcSocket.Message.Text(RpcWire.success(requestB.id, RpcResult.Success(Json.Str("b")))))
        replyB <- b.join
        _ <- incoming.offer(RpcSocket.Message.Text(RpcWire.success(requestA.id, RpcResult.Success(Json.Str("a")))))
        replyA <- a.join
        pending <- connection.call("service", 1, "pending", Chunk.empty).either.fork
        _ <- outgoing.take
        _ <- ended.fail(RpcError(RpcError.Code.Disconnected))
        failure <- pending.join
        _ <- connection.close
        after <- connection.call("service", 1, "after", Chunk.empty).either
      } yield {
        assertEquals(requestA.id, "1")
        assertEquals(requestB.id, "2")
        assertEquals(replyA, RpcResult.Success(Json.Str("a")))
        assertEquals(replyB, RpcResult.Success(Json.Str("b")))
        assertEquals(excess, Left(RpcError(RpcError.Code.LimitExceeded)))
        assertEquals(failure, Left(RpcError(RpcError.Code.Disconnected)))
        assertEquals(after, Left(RpcError(RpcError.Code.Disconnected)))
      }
    }
  }

  testZ("shared deadlines remove local unary waiters and allow later calls; invalid endpoints never open sockets") {
    ZIO.scoped {
      for {
        opened <- Ref.make(false)
        invalid <- RpcWebSocketClient
          .connectUsing("ws://localhost/rpc?ticket=secret", RpcWebSocketClient.Limits())(
            opened.set(true) *> ZIO.fail(RpcError(RpcError.Code.ServiceFailure))
          )
          .either
        didOpen <- opened.get
        socket = new RpcSocket {
          def sendText(text: String): IO[RpcError, Unit] = ZIO.unit
          def receive: IO[RpcError, RpcSocket.Message] = ZIO.never
          def close: UIO[Unit] = ZIO.unit
        }
        connection <- RpcWebSocketClient.connectUsing(
          "ws://localhost/rpc",
          RpcWebSocketClient.Limits(maxPending = 1, timeout = 20.millis)
        )(ZIO.succeed(socket))
        first <- connection.call("service", 1, "first", Chunk.empty).either
        second <- connection.call("service", 1, "second", Chunk.empty).either
      } yield {
        assertEquals(invalid.left.map(_.code), Left(RpcError.Code.ProtocolError))
        assert(!didOpen)
        assertEquals(first, Left(RpcError(RpcError.Code.TimedOut)))
        assertEquals(second, Left(RpcError(RpcError.Code.TimedOut)))
      }
    }
  }
}
