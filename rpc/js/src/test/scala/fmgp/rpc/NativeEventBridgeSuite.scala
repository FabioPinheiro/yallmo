package fmgp.rpc

import munit.ZSuite
import zio.*

class NativeEventBridgeSuite extends ZSuite {
  testZ("remote close drains accepted text/binary messages before exposing its terminal error") {
    val bridge = new NativeEventBridge
    val text = RpcSocket.Message.Text("reply")
    val binary = RpcSocket.Message.Binary(Chunk[Byte](1, 2))
    bridge.push(text)
    bridge.push(binary)
    bridge.end(RpcError(RpcError.Code.Disconnected))
    bridge.push(RpcSocket.Message.Text("after close"))
    for {
      first <- bridge.take
      second <- bridge.take
      terminal <- bridge.take.either
      repeated <- bridge.take.either
    } yield {
      assertEquals(first, text)
      assertEquals(second, binary)
      assertEquals(terminal, Left(RpcError(RpcError.Code.Disconnected)))
      assertEquals(repeated, terminal)
      assert(bridge.failed)
    }
  }

  testZ("explicit abort discards messages even after ordered remote termination") {
    val bridge = new NativeEventBridge
    bridge.push(RpcSocket.Message.Text("reply"))
    bridge.end(RpcError(RpcError.Code.Disconnected))
    bridge.fail(RpcError(RpcError.Code.Disconnected))
    bridge.take.either.map(result => assertEquals(result, Left(RpcError(RpcError.Code.Disconnected))))
  }

  testZ("native callbacks preserve message order, bound queued messages and discard them on overflow") {
    val bridge = new NativeEventBridge
    (1 to 32).foreach(value => bridge.push(RpcSocket.Message.Text(value.toString)))
    for {
      first <- bridge.take
      second <- bridge.take
      _ <- ZIO.succeed((33 to 35).foreach(value => bridge.push(RpcSocket.Message.Text(value.toString))))
      rejected <- bridge.take.either
    } yield {
      assertEquals(first, RpcSocket.Message.Text("1"))
      assertEquals(second, RpcSocket.Message.Text("2"))
      assertEquals(rejected, Left(RpcError(RpcError.Code.LimitExceeded)))
      assert(bridge.failed)
    }
  }

  testZ("disconnect wakes handshake/receiver waiters and interruption removes the receiver callback") {
    for {
      bridge <- ZIO.succeed(new NativeEventBridge)
      opening <- bridge.awaitOpen.either.fork
      _ <- ZIO.yieldNow
      _ <- ZIO.succeed(bridge.opened())
      opened <- opening.join
      cancelled <- bridge.take.fork
      _ <- ZIO.yieldNow
      _ <- cancelled.interrupt
      _ <- ZIO.succeed(bridge.push(RpcSocket.Message.Binary(Chunk[Byte](1, 2))))
      message <- bridge.take
      waiting <- bridge.take.either.fork
      _ <- ZIO.yieldNow
      _ <- ZIO.succeed(bridge.fail(RpcError(RpcError.Code.Disconnected)))
      result <- waiting.join
    } yield {
      assertEquals(opened, Right(()))
      assertEquals(message, RpcSocket.Message.Binary(Chunk[Byte](1, 2)))
      assertEquals(result, Left(RpcError(RpcError.Code.Disconnected)))
    }
  }
}
