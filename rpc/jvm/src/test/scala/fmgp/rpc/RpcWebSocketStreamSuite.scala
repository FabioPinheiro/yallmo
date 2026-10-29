package fmgp.rpc

import java.nio.file.Files
import munit.FunSuite
import zio.*
import zio.stream.*

class RpcWebSocketStreamSuite extends FunSuite {
  import RpcStreamFixtures.*
  override val munitTimeout = scala.concurrent.duration.Duration(40, "seconds")
  private def run[A](effect: ZIO[Scope, Throwable, A]): A = Unsafe.unsafe { _ ?=>
    Runtime.default.unsafe
      .run(ZIO.scoped(effect).timeoutFail(new Exception("test deadline"))(25.seconds))
      .getOrThrowFiberFailure()
  }
  private def connect(
      service: RpcStreamFixtures.Files,
      limits: RpcWebSocket.Limits = RpcWebSocket.Limits(),
      clientLimits: RpcWebSocket.Limits = RpcWebSocket.Limits()
  ) =
    for {
      server <- RpcTestServer.start(limits)
      ticket <- server.rpc.register(rpc.bind(service))
      connection <- RpcWebSocket.connect(server.endpoint, ticket, clientLimits)
    } yield connection
  private def domain[E, A](effect: ZIO[Any, E, A]): Task[A] = effect.mapError(e => new Exception(e.toString))

  test(
    "authenticated byte stream reads a real multi-frame file, supports repeats, JSON, Unit, null and partial errors"
  ) {
    val expected = Chunk.fromIterable((0 until 32768).map(_.toByte))
    val result = run {
      for {
        file <- ZIO.acquireRelease(ZIO.attempt(Files.createTempFile("rpc-stream", ".bin")))(p =>
          ZIO.attempt(Files.deleteIfExists(p)).orDie
        )
        _ <- ZIO.attempt(Files.write(file, expected.toArray))
        connection <- connect(
          new Service {
            override def readBytes(path: String): ZStream[Any, FsError, Byte] =
              ZStream
                .fromFile(java.nio.file.Path.of(path).toFile, chunkSize = 4096)
                .mapError(_ => FsError.Missing(path))
          },
          RpcWebSocket.Limits(maxFrameBytes = 1024)
        )
        client = rpc.client(connection)
        bytes <- domain(client.readBytes(file.toString).runCollect)
        again <- domain(client.readBytes(file.toString).runCollect)
        entries <- domain(client.entries().runCollect)
        units <- domain(client.units().runCollect)
        nullable <- domain(client.nullable().runCollect)
        first <- client.fail().runCollect.either
        output <- Ref.make(List.empty[Int])
        later <- client.partial().tap(n => output.update(_ :+ n)).runDrain.either
        seen <- output.get
        defect <- client.bad().runDrain.either
        construction <- client.thrown().runDrain.either
        ping <- domain(client.ping("ok"))
      } yield (bytes, again, entries, units, nullable, first, later, seen, defect, construction, ping)
    }
    assertEquals(result._1, expected)
    assertEquals(result._2, expected)
    assertEquals(result._3, Chunk(Entry("hello", 1), Entry("🙂", 2)))
    assertEquals(result._4, Chunk((), ()))
    assertEquals(result._5, Chunk(None, Some("hello")))
    assertEquals(result._6, Left(FsError.Missing("first")))
    assertEquals(result._7, Left(FsError.Missing("later")))
    assertEquals(result._8, List(1, 2))
    assertEquals(result._9.left.toOption.collect { case e: RpcError => e.code }, Some(RpcError.Code.ServiceFailure))
    assertEquals(result._10.left.toOption.collect { case e: RpcError => e.code }, Some(RpcError.Code.ServiceFailure))
    assertEquals(result._11, "ok")
  }
  test("empty streams/chunks, construction laziness and independent materializations") {
    val result = run {
      for {
        invoked <- Ref.make(0)
        connection <- connect(new Service {
          override def readBytes(path: String): ZStream[Any, FsError, Byte] =
            ZStream.fromZIO(invoked.update(_ + 1)).drain ++ ZStream.fromChunks(Chunk.empty, Chunk.empty)
        })
        client = rpc.client(connection)
        stream = client.readBytes("empty")
        before <- invoked.get
        _ <- domain(stream.take(0).runDrain)
        zero <- invoked.get
        a <- domain(stream.runCollect)
        b <- domain(stream.runCollect)
        total <- invoked.get
      } yield (before, zero, a, b, total)
    }
    assertEquals(result, (0, 0, Chunk.empty[Byte], Chunk.empty[Byte], 2))
  }
  test("slow consumer has no read ahead and take cancels resources without affecting unary/another stream") {
    val result = run {
      for {
        reads <- Ref.make(0)
        started <- Promise.make[Nothing, Unit]
        released <- Promise.make[Nothing, Unit]
        consumerReady <- Promise.make[Nothing, Unit]
        consumerGate <- Promise.make[Nothing, Unit]
        connection <- connect(
          new Service {
            override def readBytes(path: String): ZStream[Any, FsError, Byte] =
              ZStream
                .acquireReleaseWith(started.succeed(()).unit)(_ => released.succeed(()).unit)
                .flatMap(_ => ZStream.repeatZIO(reads.updateAndGet(_ + 1).map(_.toByte)))
          },
          RpcWebSocket.Limits(maxStreams = 2)
        )
        client = rpc.client(connection)
        fiber <- domain(
          client.readBytes("slow").tap(_ => consumerReady.succeed(()) *> consumerGate.await).take(1).runCollect
        ).fork
        _ <- started.await *> consumerReady.await
        count <- reads.get
        pong <- domain(client.ping("unblocked"))
        other <- domain(client.entries().runCollect)
        _ <- consumerGate.succeed(())
        bytes <- fiber.join
        _ <- released.await
        reusable <- domain(client.entries().runCollect)
      } yield (count, pong, other.size, bytes, reusable.size)
    }
    assertEquals(result, (1, "unblocked", 2, Chunk[Byte](1), 2))
  }
  test("interruption of a blocked pull interrupts producer and releases its scope") {
    val result = run {
      for {
        started <- Promise.make[Nothing, Unit]
        released <- Promise.make[Nothing, Unit]
        connection <- connect(new Service {
          override def readBytes(path: String): ZStream[Any, FsError, Byte] =
            ZStream
              .acquireReleaseWith(ZIO.unit)(_ => released.succeed(()).unit)
              .flatMap(_ => ZStream.fromZIO(started.succeed(()) *> ZIO.never))
        })
        client = rpc.client(connection)
        fiber <- client.readBytes("blocked").runDrain.fork
        _ <- started.await
        pong <- domain(client.ping("alive"))
        _ <- fiber.interrupt
        _ <- released.await
        next <- domain(client.entries().runCollect)
      } yield (pong, next.size)
    }
    assertEquals(result, ("alive", 2))
  }
  test("consumer failure and local close both release remote resources") {
    run {
      for {
        released <- Queue.bounded[String](2)
        started <- Promise.make[Nothing, Unit]
        connection <- connect(new Service {
          override def readBytes(path: String): ZStream[Any, FsError, Byte] =
            ZStream
              .acquireReleaseWith(ZIO.unit)(_ => released.offer(path).unit)
              .flatMap(_ =>
                if path == "consumer" then ZStream.repeatZIO(ZIO.succeed(1.toByte))
                else ZStream.fromZIO(started.succeed(()) *> ZIO.never)
              )
        })
        client = rpc.client(connection)
        failure <- client.readBytes("consumer").mapZIO(_ => ZIO.fail(FsError.Missing("consumer"))).runDrain.either
        first <- released.take
        fiber <- client.readBytes("close").runDrain.fork
        _ <- started.await
        _ <- connection.close
        second <- released.take
        _ <- fiber.await
      } yield {
        assertEquals(failure, Left(FsError.Missing("consumer")))
        assertEquals(first, "consumer")
        assertEquals(second, "close")
      }
    }
  }
  test("per-pull timeout cancels producer, not the connection or total stream lifetime") {
    run {
      for {
        released <- Promise.make[Nothing, Unit]
        idleReady <- Promise.make[Nothing, Unit]
        idleGate <- Promise.make[Nothing, Unit]
        connection <- connect(
          new Service {
            override def readBytes(path: String): ZStream[Any, FsError, Byte] =
              if path == "idle" then ZStream(1.toByte, 2.toByte)
              else ZStream.acquireReleaseWith(ZIO.unit)(_ => released.succeed(()).unit).flatMap(_ => ZStream.never)
          },
          clientLimits = RpcWebSocket.Limits(timeout = 1.second)
        )
        client = rpc.client(connection)
        idle <- domain(client.readBytes("idle").tap(_ => idleReady.succeed(()) *> idleGate.await).runCollect).fork
        _ <- idleReady.await
        result <- client.readBytes("timeout").runDrain.either
        _ <- released.await
        _ <- idleGate.succeed(())
        bytes <- idle.join
        _ <- ZIO.attempt(assertEquals(bytes, Chunk[Byte](1, 2)))
        pong <- domain(client.ping("alive"))
      } yield {
        assertEquals(result, Left(FsError.Infra(RpcError.Code.TimedOut)))
        assertEquals(pong, "alive")
      }
    }
  }
  test("client/server stream bounds and terminal outcomes free slots for reuse") {
    run {
      for {
        started <- Promise.make[Nothing, Unit]
        gate <- Promise.make[Nothing, Unit]
        connection <- connect(
          new Service {
            override def readBytes(path: String): ZStream[Any, FsError, Byte] =
              ZStream.fromZIO(started.succeed(()) *> gate.await).drain
          },
          RpcWebSocket.Limits(maxStreams = 1),
          RpcWebSocket.Limits(maxStreams = 2)
        )
        client = rpc.client(connection)
        first <- client.readBytes("held").runDrain.fork
        _ <- started.await
        refused <- client.entries().runDrain.either
        pong <- domain(client.ping("alive"))
        _ <- gate.succeed(()) *> first.join.mapError(e => new Exception(e.toString))
        finished <- domain(client.entries().runCollect)
        failed <- client.fail().runDrain.either
        reused <- domain(client.entries().runCollect)
      } yield {
        assertEquals(refused, Left(FsError.Infra(RpcError.Code.LimitExceeded)))
        assertEquals(pong, "alive")
        assertEquals(finished.size, 2)
        assertEquals(failed, Left(FsError.Missing("first")))
        assertEquals(reused.size, 2)
      }
    }
  }
  test("slow cancellation finalizer does not block receiver, unary calls or other streams") {
    run {
      for {
        finalizing <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        connection <- connect(new Service {
          override def readBytes(path: String): ZStream[Any, FsError, Byte] =
            ZStream
              .acquireReleaseWith(ZIO.unit)(_ => finalizing.succeed(()) *> release.await)
              .flatMap(_ => ZStream.repeatZIO(ZIO.succeed(1.toByte)))
        })
        client = rpc.client(connection)
        _ <- domain(client.readBytes("cancel").take(1).runDrain)
        _ <- finalizing.await
        pong <- domain(client.ping("unblocked"))
        other <- domain(client.entries().runCollect)
        _ <- release.succeed(())
      } yield {
        assertEquals(pong, "unblocked")
        assertEquals(other.size, 2)
      }
    }
  }
  test("real transport retains null domain failure and sanitizes malformed stream codecs") {
    run {
      for {
        server <- RpcTestServer.start()
        token <- server.rpc.register(RpcStreamCodecFixtures.rpc.bind(RpcStreamCodecFixtures.service))
        connection <- RpcWebSocket.connect(server.endpoint, token)
        client = RpcStreamCodecFixtures.rpc.client(connection)
        nullable <- client.nullable().runDrain.either
        codec <- client.value("throw").runDrain.either
        failedCodec <- client.failure("left").runDrain.either
        throwable <- client.throwable().runDrain.either
      } yield {
        assertEquals(nullable, Left(None))
        assertEquals(codec, Left(RpcTypedErrorFixtures.CodecValue("ProtocolError")))
        assertEquals(failedCodec, Left(RpcTypedErrorFixtures.CodecValue("ProtocolError")))
        assertEquals(throwable.left.toOption.collect { case e: RpcError => e.code }, Some(RpcError.Code.ServiceFailure))
      }
    }
  }
  test("oversized UTF-8 JSON element fails only its stream and frees capacity") {
    run {
      for {
        connection <- connect(
          new Service {
            override def entries(): ZStream[Any, FsError, Entry] = ZStream(Entry("🙂" * 1000, 1))
          },
          RpcWebSocket.Limits(maxFrameBytes = 256, maxStreams = 1)
        )
        client = rpc.client(connection)
        result <- client.entries().runDrain.either
        pong <- domain(client.ping("alive"))
        units <- domain(client.units().runCollect)
      } yield {
        assertEquals(result, Left(FsError.Infra(RpcError.Code.LimitExceeded)))
        assertEquals(pong, "alive")
        assertEquals(units.size, 2)
      }
    }
  }

  test("outgoing oversized stream open stays local and leaves connection reusable") {
    run {
      for {
        connection <- connect(
          new Service,
          RpcWebSocket.Limits(maxFrameBytes = 256),
          RpcWebSocket.Limits(maxFrameBytes = 256)
        )
        client = rpc.client(connection)
        large <- client.readBytes("x" * 1024).runDrain.either
        small <- domain(client.readBytes("small").runCollect)
        pong <- domain(client.ping("alive"))
      } yield {
        assertEquals(large, Left(FsError.Infra(RpcError.Code.LimitExceeded)))
        assertEquals(small.size, 256)
        assertEquals(pong, "alive")
      }
    }
  }

}
