package fmgp.rpc

import munit.FunSuite
import zio.*
import zio.json.ast.Json
import zio.http.{ChannelEvent, Client, Handler, Method, Routes, Server, WebSocketConfig, WebSocketFrame, handler}
import java.net.URI

object WebSocketFixtures {
  trait Calls {
    def waitFor(id: Int): Task[Int]
    def fail(): Task[Unit]
    def defect(): Task[Unit]
    def echo(value: String): Task[String]
  }
  trait Controls {
    def release(id: Int): Task[Unit]
  }
  val calls: Rpc[Calls] = Rpc.derived[Calls]("test.calls", 1)
  val controls: Rpc[Controls] = Rpc.derived[Controls]("test.controls", 1)
}

class RpcWebSocketSuite extends FunSuite {
  import WebSocketFixtures.*
  override val munitTimeout = scala.concurrent.duration.Duration(40, "seconds")
  private def run[A](effect: ZIO[Scope, Throwable, A]): A = Unsafe.unsafe { _ ?=>
    Runtime.default.unsafe
      .run(ZIO.scoped(effect).timeoutFail(new Exception("test deadline"))(25.seconds))
      .getOrThrowFiberFailure()
  }
  private def service(awaitId: Int => Task[Int], echoValue: String => String = identity): Calls = new Calls {
    def waitFor(id: Int): Task[Int] = awaitId(id)
    def fail(): Task[Unit] = ZIO.fail(new Exception("private service secret"))
    def defect(): Task[Unit] = ZIO.dieMessage("private defect secret")
    def echo(value: String): Task[String] = ZIO.succeed(echoValue(value))
  }
  private def code[A](result: Either[Throwable, A]): Option[RpcError.Code] = result.left.toOption.collect {
    case error: RpcError => error.code
  }

  private case class Fixture(server: RpcTestServer, dispatcher: RpcDispatcher)
  private def serveFixture(dispatcher: RpcDispatcher, limits: RpcWebSocket.Limits = RpcWebSocket.Limits()) =
    RpcTestServer.start(limits = limits).map(Fixture(_, dispatcher))
  private def connectFixture(fixture: Fixture, limits: RpcWebSocket.Limits = RpcWebSocket.Limits()) =
    fixture.server.rpc.register(fixture.dispatcher).flatMap(RpcWebSocket.connect(fixture.server.endpoint, _, limits))

  private def rawClose(port: Fixture, frames: List[WebSocketFrame]): ZIO[Scope, Throwable, Int] =
    for {
      token <- port.server.rpc.register(port.dispatcher)
      closed <- Promise.make[Throwable, Int]
      http <- Client.default.build.map(_.get[Client])
      app = Handler
        .webSocket { channel =>
          channel
            .receiveAll {
              case ChannelEvent.UserEventTriggered(ChannelEvent.UserEvent.HandshakeComplete) =>
                ZIO.foreachDiscard(frames)(frame => channel.send(ChannelEvent.Read(frame)))
              case ChannelEvent.Read(close: WebSocketFrame.Close) =>
                closed.succeed(close.status).unit *> channel.shutdown
              case ChannelEvent.ExceptionCaught(error) => closed.fail(error).unit *> channel.shutdown
              case _                                   => ZIO.unit
            }
            .ensuring(channel.shutdown)
        }
        .withConfig(WebSocketConfig.default.forwardCloseFrames(true))
      _ <- http
        .uri(URI.create(port.server.endpoint))
        .addHeader("Origin", "null")
        .addHeader("Authorization", s"Bearer ${token.exportSecret}")
        .socket(app)
      status <- closed.await
    } yield status

  test("malformed envelopes, binary/fragmented messages and duplicate active IDs close with protocol error") {
    val results = run {
      for {
        port <- serveFixture(calls.bind(service(_ => ZIO.never)))
        malformed <- rawClose(port, List(WebSocketFrame.Text("not json")))
        duplicateKey <- rawClose(
          port,
          List(
            WebSocketFrame.Text(
              """{"id":"1","id":"2","service":"test.calls","version":1,"method":"echo","arguments":["ok"]}"""
            )
          )
        )
        binary <- rawClose(port, List(WebSocketFrame.Binary(Chunk[Byte](1, 2))))
        fragmented <- rawClose(port, List(WebSocketFrame.Text("{", isFinal = false)))
        request = WebSocketFrame.Text(
          """{"id":"1","service":"test.calls","version":1,"method":"waitFor","arguments":[1]}"""
        )
        duplicateId <- rawClose(port, List(request, request))
      } yield List(malformed, duplicateKey, binary, fragmented, duplicateId)
    }
    assertEquals(results, List.fill(5)(1002))
  }

  test("real loopback WebSocket merges two services over one connection and correlates reversed concurrent replies") {
    val values = run {
      for {
        entered <- Queue.bounded[Int](8)
        gates <- ZIO.foreach(1 to 8)(id => Promise.make[Nothing, Unit].map(id -> _)).map(_.toMap)
        host = service(id => entered.offer(id) *> gates(id).await.as(id * 11))
        control = new Controls {
          def release(id: Int): Task[Unit] = gates(id).succeed(()).unit
        }
        dispatcher <- ZIO.fromEither(calls.bind(host).merge(controls.bind(control)))
        port <- serveFixture(dispatcher)
        connection <- connectFixture(port)
        client = calls.client(connection)
        controller = controls.client(connection)
        fibers <- ZIO.foreach(1 to 8)(client.waitFor(_).fork)
        _ <- ZIO.foreachDiscard(1 to 8)(_ => entered.take)
        // Joining each reverse-order call ensures replies really arrive out of request order.
        reversed <- ZIO.foreach((1 to 8).reverse)(id => controller.release(id) *> fibers(id - 1).join)
      } yield reversed.toList
    }
    assertEquals(values, (1 to 8).reverse.map(_ * 11).toList)
  }

  test("authenticated merged typed and Task services correlate concurrent domain, success and infrastructure replies") {
    import RpcTypedErrorFixtures.{files, fileService, FsError}
    val results = run {
      for {
        dispatcher <- ZIO.fromEither(calls.bind(service(ZIO.succeed(_))).merge(files.bind(fileService)))
        port <- serveFixture(dispatcher)
        connection <- connectFixture(port)
        typed = files.client(connection)
        task = calls.client(connection)
        wrongVersion = files.client(
          RpcTypedErrorFixtures.connection((id, _, method, args) => connection.call(id, 1, method, args))
        )
        results <- ZIO
          .collectAllPar(
            List(
              typed.read("missing").either,
              typed.read("exists").either,
              wrongVersion.read("exists").either
            )
          )
          .zipPar(task.echo("task survives"))
        after <- typed.read("exists").either
        _ <- connection.close
        disconnected <- typed.read("exists").either
      } yield (results, after, disconnected)
    }
    assertEquals(
      results._1._1,
      List(
        Left(FsError.NotFound("missing")),
        Right("contents"),
        Left(FsError.Infrastructure(RpcError.Code.VersionMismatch))
      )
    )
    assertEquals(results._1._2, "task survives")
    assertEquals(results._2, Right("contents"))
    assertEquals(results._3, Left(FsError.Infrastructure(RpcError.Code.Disconnected)))
  }

  test("typed timeout and interruption clean pending slots and leave merged Task calls usable") {
    import RpcTypedErrorFixtures.{files, fileService, Files, FsError}
    val result = run {
      for {
        entered <- Queue.bounded[Unit](2)
        host = new Files {
          def read(path: String): IO[FsError, String] = fileService.read(path)
          def broken(): IO[FsError, String] = fileService.broken()
          def thrown(): IO[FsError, String] = fileService.thrown()
          def waitFor(): IO[FsError, String] = entered.offer(()) *> ZIO.never
        }
        dispatcher <- ZIO.fromEither(files.bind(host).merge(calls.bind(service(ZIO.succeed(_)))))
        port <- serveFixture(dispatcher)
        connection <- connectFixture(port, RpcWebSocket.Limits(maxPending = 1, timeout = 1.second))
        typed = files.client(connection)
        timed <- typed.waitFor().either
        _ <- entered.take
        pending <- typed.waitFor().fork
        _ <- entered.take
        _ <- pending.interrupt
        healthy <- calls.client(connection).echo("healthy")
        domain <- typed.read("missing").either
      } yield (timed, healthy, domain)
    }
    assertEquals(
      result,
      (Left(FsError.Infrastructure(RpcError.Code.TimedOut)), "healthy", Left(FsError.NotFound("missing")))
    )
  }

  test(
    "wire calls reject unknown routes, versions, wrong arity and malformed arguments; sanitize failure and defects"
  ) {
    val results = run {
      for {
        port <- serveFixture(calls.bind(service(ZIO.succeed(_))))
        connection <- connectFixture(port)
        client = calls.client(connection)
        unknownService <- connection.call("absent", 1, "echo", Chunk(Json.Str("x"))).either
        unknownMethod <- connection.call("test.calls", 1, "absent", Chunk.empty).either
        version <- connection.call("test.calls", 2, "echo", Chunk(Json.Str("x"))).either
        malformed <- connection.call("test.calls", 1, "waitFor", Chunk(Json.Str("not an int"))).either
        arity <- connection.call("test.calls", 1, "echo", Chunk.empty).either
        failed <- client.fail().either
        defect <- client.defect().either
        healthy <- client.echo("still connected")
      } yield (
        List(unknownService, unknownMethod, version, malformed, arity).map(code),
        List(failed, defect).map(code),
        healthy,
        failed.left.toOption.map(_.getMessage)
      )
    }
    assertEquals(
      results._1,
      List(
        RpcError.Code.UnknownService,
        RpcError.Code.UnknownMethod,
        RpcError.Code.VersionMismatch,
        RpcError.Code.InvalidArguments,
        RpcError.Code.InvalidArguments
      ).map(Some(_))
    )
    assertEquals(results._2, List.fill(2)(Some(RpcError.Code.ServiceFailure)))
    assertEquals(results._3, "still connected")
    assertEquals(results._4, Some("ServiceFailure"))
  }

  test("timeout and interruption release pending slots; pending bound rejects rather than queues") {
    val results = run {
      for {
        entered <- Queue.bounded[Int](8)
        port <- serveFixture(calls.bind(service(id => entered.offer(id) *> ZIO.never)))
        connection <- connectFixture(
          port,
          RpcWebSocket.Limits(maxPending = 1, timeout = 1.second)
        )
        client = calls.client(connection)
        pending <- client.waitFor(1).fork
        _ <- entered.take
        limited <- client.echo("blocked").either
        _ <- pending.interrupt
        afterInterrupt <- client.echo("after interrupt")
        timedOut <- client.waitFor(2).either
        afterTimeout <- client.echo("after timeout")
      } yield (code(limited), code(timedOut), afterInterrupt, afterTimeout)
    }
    assertEquals(
      results,
      (Some(RpcError.Code.LimitExceeded), Some(RpcError.Code.TimedOut), "after interrupt", "after timeout")
    )
  }

  test("late replies after a timeout cannot satisfy or poison a newer request") {
    val result = run {
      for {
        gate <- Promise.make[Nothing, Unit]
        port <- serveFixture(calls.bind(service(id => gate.await.as(id * 10))))
        connection <- connectFixture(
          port,
          RpcWebSocket.Limits(maxPending = 1, timeout = 1.second)
        )
        client = calls.client(connection)
        first <- client.waitFor(1).either
        _ <- gate.succeed(())
        second <- client.waitFor(2)
      } yield (code(first), second)
    }
    assertEquals(result, (Some(RpcError.Code.TimedOut), 20))
  }

  test("close fails outstanding calls, rejects new calls and interrupts server workers") {
    val results = run {
      for {
        entered <- Promise.make[Nothing, Unit]
        cancelled <- Promise.make[Nothing, Unit]
        port <- serveFixture(
          calls.bind(service(_ => (entered.succeed(()) *> ZIO.never).ensuring(cancelled.succeed(()))))
        )
        connection <- connectFixture(port)
        client = calls.client(connection)
        pending <- client.waitFor(1).either.fork
        _ <- entered.await
        _ <- connection.close
        failed <- pending.join
        later <- client.echo("closed").either
        _ <- cancelled.await
        _ <- connection.close
      } yield (code(failed), code(later))
    }
    assertEquals(results, (Some(RpcError.Code.Disconnected), Some(RpcError.Code.Disconnected)))
  }

  test("explicit close finalizes each owned HTTP client before the enclosing scope exits") {
    val (counts, finalized) = run {
      for {
        finalized <- Ref.make(0)
        outerClosed <- Ref.make(false)
        _ <- ZIO.addFinalizer(outerClosed.set(true))
        clientLayer = Client.default >>> ZLayer.scoped {
          ZIO.service[Client].tap(_ => ZIO.addFinalizer(finalized.update(_ + 1)))
        }
        port <- serveFixture(calls.bind(service(ZIO.succeed(_))))
        counts <- ZIO.foreach(1 to 3) { cycle =>
          for {
            token <- port.server.rpc.register(port.dispatcher)
            connection <- RpcWebSocket.connectWithClient(
              port.server.endpoint,
              token,
              RpcWebSocket.Limits(),
              clientLayer
            )
            answer <- calls.client(connection).echo(s"cycle $cycle")
            _ <- connection.close
            _ <- connection.close
            count <- finalized.get
            closed <- outerClosed.get
          } yield (answer, count, closed)
        }
      } yield (counts.toList, finalized)
    }
    assertEquals(counts, (1 to 3).map(cycle => (s"cycle $cycle", cycle, false)).toList)
    assertEquals(run(finalized.get), 3) // Outer-scope cleanup must not finalize an already-closed client again.
  }

  test("failed connection acquisition finalizes the owned HTTP client before the enclosing scope exits") {
    val result = run {
      for {
        finalized <- Ref.make(0)
        clientLayer = Client.default >>> ZLayer.scoped {
          ZIO.service[Client].tap(_ => ZIO.addFinalizer(finalized.update(_ + 1)))
        }
        port <- serveFixture(calls.bind(service(ZIO.succeed(_))))
        token <- port.server.rpc.register(port.dispatcher)
        failed <- RpcWebSocket
          .connectWithClient(
            s"ws://127.0.0.1:${port.server.port}/not-rpc",
            token,
            RpcWebSocket.Limits(),
            clientLayer
          )
          .either
        count <- finalized.get
      } yield (failed.isLeft, count)
    }
    assertEquals(result, (true, 1))
  }

  test("malformed peer responses fail every pending call with a protocol error and close the connection") {
    val result = run {
      for {
        received <- Ref.make(0)
        app = Handler
          .webSocket { channel =>
            channel
              .receiveAll {
                case ChannelEvent.Read(_: WebSocketFrame.Text) =>
                  received.updateAndGet(_ + 1).flatMap { count =>
                    if count == 2 then
                      channel.send(
                        ChannelEvent.Read(
                          WebSocketFrame.Text(
                            """{"id":"1","result":123,"error":"ServiceFailure"}"""
                          )
                        )
                      )
                    else ZIO.unit
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
        client = calls.client(connection)
        first <- client.waitFor(1).either.fork
        second <- RpcTypedErrorFixtures.files.client(connection).waitFor().either.fork
        firstResult <- first.join
        secondResult <- second.join
        later <- client.echo("closed").either
      } yield (code(firstResult), secondResult, code(later))
    }
    assertEquals(
      result,
      (
        Some(RpcError.Code.ProtocolError),
        Left(RpcTypedErrorFixtures.FsError.Infrastructure(RpcError.Code.ProtocolError)),
        Some(RpcError.Code.Disconnected)
      )
    )
  }

  test("server disconnect fails outstanding client calls") {
    val result = run {
      for {
        serverScope <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        entered <- Promise.make[Nothing, Unit]
        port <- serveFixture(calls.bind(service(_ => entered.succeed(()) *> ZIO.never)))
          .provideEnvironment(ZEnvironment(serverScope))
        connection <- connectFixture(port)
        pending <- calls.client(connection).waitFor(1).either.fork
        _ <- entered.await
        _ <- serverScope.close(Exit.unit)
        failed <- pending.join
      } yield code(failed)
    }
    assertEquals(result, Some(RpcError.Code.Disconnected))
  }

  test("outgoing client size limit is local and does not poison connection") {
    val results = run {
      for {
        port <- serveFixture(calls.bind(service(ZIO.succeed(_))))
        connection <- connectFixture(port, RpcWebSocket.Limits(maxFrameBytes = 256))
        client = calls.client(connection)
        large <- client.echo("x" * 1024).either
        small <- client.echo("ok")
      } yield (code(large), small)
    }
    assertEquals(results, (Some(RpcError.Code.LimitExceeded), "ok"))
  }

  test("client rejects incoming oversized responses and closes all pending calls") {
    val result = run {
      for {
        port <- serveFixture(calls.bind(service(ZIO.succeed(_), _ => "x" * 1024)))
        connection <- connectFixture(port, RpcWebSocket.Limits(maxFrameBytes = 256))
        oversized <- calls.client(connection).echo("large").either
        later <- calls.client(connection).waitFor(1).either
      } yield (code(oversized), code(later))
    }
    assertEquals(result, (Some(RpcError.Code.LimitExceeded), Some(RpcError.Code.Disconnected)))
  }

  test("server response size and active request limits close connections") {
    val results = run {
      for {
        port <- serveFixture(
          calls.bind(service(ZIO.succeed(_), _ => "x" * 512)),
          limits = RpcWebSocket.Limits(maxFrameBytes = 256)
        )
        connection <- connectFixture(port)
        oversized <- calls.client(connection).echo("large").either
        entered <- Promise.make[Nothing, Unit]
        boundedPort <- serveFixture(
          calls.bind(service(_ => entered.succeed(()) *> ZIO.never)),
          limits = RpcWebSocket.Limits(maxPending = 1)
        )
        bounded <- connectFixture(boundedPort)
        pending <- calls.client(bounded).waitFor(1).either.fork
        _ <- entered.await
        excess <- calls.client(bounded).echo("excess").either
        first <- pending.join
      } yield (code(oversized), code(excess), code(first))
    }
    assertEquals(
      results,
      (Some(RpcError.Code.LimitExceeded), Some(RpcError.Code.LimitExceeded), Some(RpcError.Code.LimitExceeded))
    )
  }
}
