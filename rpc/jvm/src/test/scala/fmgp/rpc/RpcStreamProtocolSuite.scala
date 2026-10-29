package fmgp.rpc

import java.net.URI
import munit.FunSuite
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.http.{ChannelEvent, Client, Handler, Method, Routes, Server, WebSocketConfig, WebSocketFrame, handler}

class RpcStreamProtocolSuite extends FunSuite {
  import RpcStreamFixtures.*
  override val munitTimeout = scala.concurrent.duration.Duration(40, "seconds")
  private def run[A](effect: ZIO[Scope, Throwable, A]): A = Unsafe.unsafe { _ ?=>
    Runtime.default.unsafe
      .run(ZIO.scoped(effect).timeoutFail(new Exception("test deadline"))(25.seconds))
      .getOrThrowFiberFailure()
  }
  private def open(id: String = "1", binary: Boolean = true): WebSocketFrame = WebSocketFrame.Text(
    RpcWire.streamControl(
      RpcWire.Control.Open(
        RpcWire.Request(
          id,
          "test.stream.files",
          1,
          if binary then "readBytes" else "entries",
          if binary then Chunk(Json.Str("x")) else Chunk.empty
        ),
        binary
      )
    )
  )
  private def pull(id: String = "1"): WebSocketFrame =
    WebSocketFrame.Text(RpcWire.streamControl(RpcWire.Control.Pull(id)))

  private def raw[A](server: RpcTestServer, service: RpcStreamFixtures.Files)(
      receive: (zio.http.WebSocketChannel, Promise[Throwable, A], ChannelEvent[WebSocketFrame]) => Task[Unit]
  ): ZIO[Scope, Throwable, A] = for {
    token <- server.rpc.register(rpc.bind(service))
    result <- Promise.make[Throwable, A]
    http <- Client.default.build.map(_.get[Client])
    app = Handler
      .webSocket { channel =>
        channel.receiveAll(event => receive(channel, result, event)).ensuring(channel.shutdown)
      }
      .withConfig(WebSocketConfig.default.forwardCloseFrames(true))
    _ <- http
      .uri(URI.create(server.endpoint))
      .addHeader("Origin", "null")
      .addHeader("Authorization", s"Bearer ${token.exportSecret}")
      .socket(app)
    answer <- result.await
  } yield answer

  test("Byte transport emits bounded binary batches, not JSON or one frame per byte") {
    val bytes = run {
      for {
        server <- RpcTestServer.start(RpcWebSocket.Limits(maxFrameBytes = 128))
        result <- raw[Chunk[Byte]](server, new Service) { (channel, result, event) =>
          event match {
            case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
              channel.send(ChannelEvent.Read(open()))
            case ChannelEvent.Read(value: WebSocketFrame.Text) =>
              ZIO.fromEither(RpcWire.streamReply(value.text)).flatMap {
                case ("1", RpcWire.StreamReply.Opened) => channel.send(ChannelEvent.Read(pull()))
                case _                                 => result.fail(new Exception("unexpected JSON byte data")).unit
              }
            case ChannelEvent.Read(value: WebSocketFrame.Binary) =>
              ZIO.fromEither(RpcWire.binaryReply(value.bytes)).flatMap { (id, bytes) =>
                ZIO.attempt {
                  assertEquals(id, "1")
                  assert(value.bytes.size <= 128)
                  assert(bytes.size > 1)
                } *> result.succeed(bytes).unit *> channel.shutdown
              }
            case _ => ZIO.unit
          }
        }
      } yield result
    }
    assertEquals(bytes, Chunk.fromIterable((-128 until -12).map(_.toByte)))
  }
  test("duplicate open, pull flood, unknown pull and malformed control reject without waiting for producer") {
    val results = run {
      for {
        server <- RpcTestServer.start()
        statuses <- ZIO.foreach(
          List(
            List(open(), open()),
            List(open(), pull(), pull()),
            List(pull()),
            List(WebSocketFrame.Text("""{"id":"1","stream":2,"op":"pull","extra":null}"""))
          )
        ) { frames =>
          raw[Int](
            server,
            new Service {
              override def readBytes(path: String) = zio.stream.ZStream.never
            }
          ) { (channel, result, event) =>
            event match {
              case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
                ZIO.foreachDiscard(frames)(f => channel.send(ChannelEvent.Read(f)))
              case ChannelEvent.Read(close: WebSocketFrame.Close) =>
                result.succeed(close.status).unit *> channel.shutdown
              case _ => ZIO.unit
            }
          }
        }
      } yield statuses
    }
    assertEquals(results, List.fill(4)(1002))
  }
  test("cancelling an allocated but unsent OPEN preserves an existing unary call and another stream") {
    run {
      for {
        ready <- Promise.make[Throwable, zio.http.WebSocketChannel]
        frames <- Queue.bounded[WebSocketFrame](16)
        unaryStarted <- Promise.make[Nothing, Unit]
        releaseUnary <- Promise.make[Nothing, Unit]
        openAttempted <- Promise.make[Nothing, Unit]
        nextId <- Ref.make(1L)
        writes <- Semaphore.make(1)
        server <- RpcTestServer.start()
        peer <- raw[Unit](
          server,
          new Service {
            override def ping(value: String): IO[FsError, String] =
              unaryStarted.succeed(()) *> releaseUnary.await.as(value)
          }
        ) { (channel, result, event) =>
          event match {
            case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
              ready.succeed(channel).unit
            case ChannelEvent.Read(frame)            => frames.offer(frame).unit
            case ChannelEvent.ExceptionCaught(error) => result.fail(error).unit
            case _                                   => ZIO.unit
          }
        }.forkScoped
        channel <- ready.await
        nextText = frames.take.flatMap {
          case text: WebSocketFrame.Text => ZIO.succeed(text.text)
          case frame                     => ZIO.fail(new Exception(s"unexpected frame: $frame"))
        }
        _ <- channel.send(
          ChannelEvent.Read(
            WebSocketFrame.Text(RpcWire.Request("1", "test.stream.files", 1, "ping", Chunk(Json.Str("pending"))).toJson)
          )
        )
        _ <- unaryStarted.await
        streams <- RpcClientStreams.make(
          RpcWebSocket.Limits(),
          nextId.updateAndGet(_ + 1).map(_.toString),
          writes,
          text =>
            ZIO.fromEither(RpcWire.control(text)).flatMap {
              case _: RpcWire.Control.Open => openAttempted.succeed(()) *> ZIO.never
              case _                       =>
                channel
                  .send(ChannelEvent.Read(WebSocketFrame.Text(text)))
                  .mapError(_ => RpcError(RpcError.Code.Disconnected))
            },
          _ => ZIO.unit
        )
        consumer <- streams
          .stream("test.stream.files", 1, "readBytes", Chunk(Json.Str("never sent")), true)
          .runDrain
          .fork
        _ <- openAttempted.await
        interrupted <- consumer.interrupt
        cancelled <- nextText.flatMap(text => ZIO.fromEither(RpcWire.streamReply(text)))
        _ <- streams.receive("2", RpcWire.StreamReply.Cancelled)
        _ <- releaseUnary.succeed(())
        pending <- nextText.flatMap(text => ZIO.fromEither(RpcWire.response(text)))
        _ <- channel.send(ChannelEvent.Read(open("3", binary = false)))
        opened <- nextText.flatMap(text => ZIO.fromEither(RpcWire.streamReply(text)))
        _ <- channel.send(ChannelEvent.Read(pull("3")))
        chunk <- nextText.flatMap(text => ZIO.fromEither(RpcWire.streamReply(text)))
        _ <- channel.send(ChannelEvent.Read(WebSocketFrame.Text(RpcWire.streamControl(RpcWire.Control.Cancel("3")))))
        otherCancelled <- nextText.flatMap(text => ZIO.fromEither(RpcWire.streamReply(text)))
        _ <- channel.send(
          ChannelEvent.Read(
            WebSocketFrame.Text(
              RpcWire.Request("4", "test.stream.files", 1, "ping", Chunk(Json.Str("still usable"))).toJson
            )
          )
        )
        later <- nextText.flatMap(text => ZIO.fromEither(RpcWire.response(text)))
        _ <- channel.shutdown *> peer.interrupt
      } yield {
        assert(interrupted.isInterrupted)
        assertEquals(cancelled, "2" -> RpcWire.StreamReply.Cancelled)
        assertEquals(pending, RpcWire.Response("1", Right(RpcResult.Success(Json.Str("pending")))))
        assertEquals(opened, "3" -> RpcWire.StreamReply.Opened)
        assertEquals(chunk._1, "3")
        assert(chunk._2 match { case RpcWire.StreamReply.Chunk(values) => values.nonEmpty; case _ => false })
        assertEquals(otherCancelled, "3" -> RpcWire.StreamReply.Cancelled)
        assertEquals(later, RpcWire.Response("4", Right(RpcResult.Success(Json.Str("still usable")))))
      }
    }
  }
  test("an unseen cancellation retires its ID so a delayed OPEN cannot resurrect it") {
    val result = run {
      for {
        acknowledged <- Ref.make(false)
        server <- RpcTestServer.start()
        result <- raw[(Boolean, Int)](server, new Service) { (channel, result, event) =>
          event match {
            case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
              channel.send(ChannelEvent.Read(WebSocketFrame.Text(RpcWire.streamControl(RpcWire.Control.Cancel("1")))))
            case ChannelEvent.Read(value: WebSocketFrame.Text) =>
              ZIO.fromEither(RpcWire.streamReply(value.text)).flatMap {
                case ("1", RpcWire.StreamReply.Cancelled) =>
                  acknowledged.set(true) *> channel.send(ChannelEvent.Read(open()))
                case _ => result.fail(new Exception("unexpected stream reply")).unit
              }
            case ChannelEvent.Read(close: WebSocketFrame.Close) =>
              acknowledged.get.flatMap(ack => result.succeed((ack, close.status))).unit *> channel.shutdown
            case _ => ZIO.unit
          }
        }
      } yield result
    }
    assertEquals(result, (true, 1002))
  }
  test("stream cancellation cannot target an active unary request ID") {
    val status = run {
      for {
        server <- RpcTestServer.start()
        status <- raw[Int](
          server,
          new Service {
            override def ping(value: String): IO[FsError, String] = ZIO.never
          }
        ) { (channel, result, event) =>
          event match {
            case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
              channel.send(
                ChannelEvent.Read(
                  WebSocketFrame.Text(
                    RpcWire.Request("1", "test.stream.files", 1, "ping", Chunk(Json.Str("pending"))).toJson
                  )
                )
              ) *>
                channel.send(ChannelEvent.Read(WebSocketFrame.Text(RpcWire.streamControl(RpcWire.Control.Cancel("1")))))
            case ChannelEvent.Read(close: WebSocketFrame.Close) => result.succeed(close.status).unit *> channel.shutdown
            case _                                              => ZIO.unit
          }
        }
      } yield status
    }
    assertEquals(status, 1002)
  }
  test("peer close interrupts an outstanding server pull and its finalizer") {
    run {
      for {
        scope <- ZIO.service[Scope]
        entered <- Promise.make[Nothing, Unit]
        released <- Promise.make[Nothing, Unit]
        server <- RpcTestServer.start()
        _ <- raw[Unit](
          server,
          new Service {
            override def readBytes(path: String) =
              zio.stream.ZStream.fromZIO((entered.succeed(()) *> ZIO.never).ensuring(released.succeed(())))
          }
        ) { (channel, result, event) =>
          event match {
            case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
              channel.send(ChannelEvent.Read(open()))
            case ChannelEvent.Read(value: WebSocketFrame.Text) =>
              ZIO.fromEither(RpcWire.streamReply(value.text)).flatMap {
                case (_, RpcWire.StreamReply.Opened) =>
                  channel.send(ChannelEvent.Read(pull())) *> (entered.await *> channel.shutdown *> result
                    .succeed(())).forkIn(scope).unit
                case _ => ZIO.unit
              }
            case _ => ZIO.unit
          }
        }
        _ <- released.await
      } yield ()
    }
  }

  private def fakePeer(
      frame: String => WebSocketFrame,
      binary: Boolean = true
  ): ZIO[Scope, Throwable, Either[FsError, Unit]] = for {
    app = Handler
      .webSocket { channel =>
        channel
          .receiveAll {
            case ChannelEvent.Read(value: WebSocketFrame.Text) =>
              ZIO.fromEither(RpcWire.control(value.text)).flatMap {
                case RpcWire.Control.Open(request, _) =>
                  channel.send(
                    ChannelEvent
                      .Read(WebSocketFrame.Text(RpcWire.streamResponse(request.id, RpcWire.StreamReply.Opened)))
                  )
                case RpcWire.Control.Pull(id)  => channel.send(ChannelEvent.Read(frame(id)))
                case RpcWire.Control.Cancel(_) => channel.shutdown
              }
            case ChannelEvent.Read(_: WebSocketFrame.Close) => channel.shutdown
            case _                                          => ZIO.unit
          }
          .ensuring(channel.shutdown)
      }
      .withConfig(WebSocketConfig.default.forwardCloseFrames(true))
    server <- Server.defaultWith(_.binding("127.0.0.1", 0).gracefulShutdownTimeout(1.second)).build
    port <- Server.install(Routes(Method.GET / "rpc" -> handler(app.toResponse))).provideEnvironment(server)
    token <- RpcToken.generate
    connection <- RpcWebSocket.connect(port, token)
    client = rpc.client(connection)
    result <- (if binary then client.readBytes("fake").runDrain else client.entries().runDrain).either
  } yield result

  test("client rejects malformed, unknown-ID and wrong-encoding binary/control replies") {
    val results = run {
      ZIO.foreach(
        List[String => WebSocketFrame](
          _ => WebSocketFrame.Binary(Chunk[Byte](1, 2)),
          _ => WebSocketFrame.Binary(RpcWire.binary("900", Chunk[Byte](1))),
          id => WebSocketFrame.Binary(RpcWire.binary(id, Chunk[Byte](1)).updated(2, 1.toByte)),
          id => WebSocketFrame.Text(RpcWire.streamResponse(id, RpcWire.StreamReply.Chunk(Chunk(Json.Num(1))))),
          id => WebSocketFrame.Text(RpcWire.success(id, RpcResult.Success(Json.Num(1)))),
          _ => WebSocketFrame.Text(RpcWire.streamResponse("900", RpcWire.StreamReply.End))
        )
      )(fakePeer(_))
    }
    assertEquals(results, List.fill(6)(Left(FsError.Infra(RpcError.Code.ProtocolError))))
    val wrong = run(fakePeer(id => WebSocketFrame.Binary(RpcWire.binary(id, Chunk[Byte](1))), binary = false))
    assertEquals(wrong, Left(FsError.Infra(RpcError.Code.ProtocolError)))
  }
}
