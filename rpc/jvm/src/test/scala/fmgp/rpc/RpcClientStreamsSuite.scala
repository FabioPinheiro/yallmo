package fmgp.rpc

import munit.FunSuite
import zio.*

class RpcClientStreamsSuite extends FunSuite {
  private def run[A](effect: ZIO[Scope, RpcError, A]): A = Unsafe.unsafe { _ ?=>
    Runtime.default.unsafe
      .run(ZIO.scoped(effect).timeoutFail(RpcError(RpcError.Code.TimedOut))(10.seconds))
      .getOrThrowFiberFailure()
  }
  test("client stream slots, cancellation acknowledgement and late batches cannot resurrect or cross IDs") {
    val result = run {
      for {
        next <- Ref.make(0L)
        sent <- Queue.bounded[RpcWire.Control](8)
        writes <- Semaphore.make(1)
        streams <- RpcClientStreams.make(
          RpcWebSocket.Limits(maxStreams = 1),
          next.updateAndGet(_ + 1).map(_.toString),
          writes,
          text => ZIO.fromEither(RpcWire.control(text)).flatMap(sent.offer).unit,
          _ => ZIO.unit
        )
        stream = streams.stream("test", 1, "bytes", zio.Chunk.empty, true)
        initial <- sent.size
        first <- stream.take(1).runCollect.either.fork
        open1 <- sent.take
        _ <- streams.receive(open1.id, RpcWire.StreamReply.Opened)
        pull1 <- sent.take
        _ <- streams.receiveBytes(pull1.id, Chunk[Byte](10))
        firstResult <- first.join
        cancel1 <- sent.take
        limited <- stream.runDrain.either
        _ <- streams.receiveBytes(cancel1.id, Chunk[Byte](99))
        _ <- streams.receive(cancel1.id, RpcWire.StreamReply.Cancelled)
        second <- stream.take(1).runCollect.either.fork
        open2 <- sent.take
        _ <- streams.receive(open2.id, RpcWire.StreamReply.Opened)
        pull2 <- sent.take
        _ <- streams.receive(cancel1.id, RpcWire.StreamReply.End)
        _ <- streams.receiveBytes(pull2.id, Chunk[Byte](20))
        secondResult <- second.join
        cancel2 <- sent.take
        _ <- streams.receive(cancel2.id, RpcWire.StreamReply.Cancelled)
        invalid <- streams.receiveBytes(cancel1.id, Chunk[Byte](77)).either
      } yield (initial, firstResult, limited, open1.id, open2.id, secondResult, invalid)
    }
    assertEquals(result._1, 0)
    assertEquals(result._2, Right(Chunk(RpcStreamChunk.Bytes(Chunk[Byte](10)))))
    assertEquals(result._3, Left(RpcStreamError.Infrastructure(RpcError(RpcError.Code.LimitExceeded))))
    assertEquals(result._4, "1")
    assertEquals(result._5, "2")
    assertEquals(result._6, Right(Chunk(RpcStreamChunk.Bytes(Chunk[Byte](20)))))
    assertEquals(result._7, Left(RpcError(RpcError.Code.ProtocolError)))
  }
  test("open deadline interrupts a stalled send and still sends cancellation") {
    val result = run {
      for {
        next <- Ref.make(0L)
        sent <- Queue.bounded[RpcWire.Control](4)
        writes <- Semaphore.make(1)
        streams <- RpcClientStreams.make(
          RpcWebSocket.Limits(timeout = 100.millis),
          next.updateAndGet(_ + 1).map(_.toString),
          writes,
          text =>
            ZIO.fromEither(RpcWire.control(text)).flatMap { control =>
              sent.offer(control) *> (control match {
                case _: RpcWire.Control.Open => ZIO.never
                case _                       => ZIO.unit
              })
            },
          _ => ZIO.unit
        )
        consumer <- streams.stream("test", 1, "bytes", Chunk.empty, true).runDrain.either.fork
        opened <- sent.take
        cancelled <- sent.take
        result <- consumer.join
        _ <- streams.receive(cancelled.id, RpcWire.StreamReply.Cancelled)
      } yield (opened.id, cancelled, result)
    }
    assertEquals(
      result,
      ("1", RpcWire.Control.Cancel("1"), Left(RpcStreamError.Infrastructure(RpcError(RpcError.Code.TimedOut))))
    )
  }

  test("stream admission rejects excess materializations before waiting for a stalled write") {
    val result = run {
      for {
        next <- Ref.make(0L)
        started <- Promise.make[Nothing, Unit]
        sent <- Queue.bounded[RpcWire.Control](4)
        writes <- Semaphore.make(1)
        streams <- RpcClientStreams.make(
          RpcWebSocket.Limits(maxStreams = 1, timeout = 5.seconds),
          next.updateAndGet(_ + 1).map(_.toString),
          writes,
          text =>
            ZIO.fromEither(RpcWire.control(text)).flatMap { control =>
              sent.offer(control) *> (control match {
                case _: RpcWire.Control.Open => started.succeed(()) *> ZIO.never
                case _                       => ZIO.unit
              })
            },
          _ => ZIO.unit
        )
        first <- streams.stream("test", 1, "bytes", Chunk.empty, true).runDrain.either.fork
        _ <- started.await
        excess <- ZIO
          .foreachPar(1 to 32)(_ => streams.stream("test", 1, "bytes", Chunk.empty, true).runDrain.either)
          .timeoutFail(RpcError(RpcError.Code.TimedOut))(1.second)
        allocated <- next.get
        opened <- sent.take
        _ <- first.interrupt
        cancelled <- sent.take
        _ <- streams.receive(cancelled.id, RpcWire.StreamReply.Cancelled)
      } yield (excess, allocated, opened.id, cancelled)
    }
    assertEquals(
      result._1.toList,
      List.fill(32)(Left(RpcStreamError.Infrastructure(RpcError(RpcError.Code.LimitExceeded))))
    )
    assertEquals(result._2, 1L)
    assertEquals(result._3, "1")
    assertEquals(result._4, RpcWire.Control.Cancel("1"))
  }
  test("disconnect during allocation cannot resurrect an entry or send any control") {
    val result = run {
      for {
        allocating <- Promise.make[Nothing, Unit]
        resume <- Promise.make[Nothing, Unit]
        sent <- Queue.bounded[RpcWire.Control](4)
        writes <- Semaphore.make(1)
        streams <- RpcClientStreams.make(
          RpcWebSocket.Limits(),
          allocating.succeed(()) *> resume.await.as("1"),
          writes,
          text => ZIO.fromEither(RpcWire.control(text)).flatMap(sent.offer).unit,
          _ => ZIO.unit
        )
        consumer <- streams.stream("test", 1, "bytes", Chunk.empty, true).runDrain.either.fork
        _ <- allocating.await
        _ <- streams.disconnect(RpcError(RpcError.Code.Disconnected))
        _ <- resume.succeed(())
        outcome <- consumer.join
        present <- streams.contains("1")
        controls <- sent.size
        after <- streams.stream("test", 1, "bytes", Chunk.empty, true).runDrain.either
      } yield (outcome, present, controls, after)
    }
    val disconnected = Left(RpcStreamError.Infrastructure(RpcError(RpcError.Code.Disconnected)))
    assertEquals(result, (disconnected, false, 0, disconnected))
  }

}
