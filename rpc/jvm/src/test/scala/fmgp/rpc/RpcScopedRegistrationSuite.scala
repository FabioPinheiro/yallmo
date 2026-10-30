package fmgp.rpc

import munit.FunSuite
import zio.*
import zio.http.{Method, Routes, Server, handler}
import zio.stream.ZStream

class RpcScopedRegistrationSuite extends FunSuite {
  import WebSocketFixtures.*
  override val munitTimeout = scala.concurrent.duration.Duration(40, "seconds")
  private def run[A](effect: ZIO[Scope, Throwable, A]): A = Unsafe.unsafe { _ ?=>
    Runtime.default.unsafe
      .run(ZIO.scoped(effect).timeoutFail(new Exception("test deadline"))(25.seconds))
      .getOrThrowFiberFailure()
  }
  private def scope: URIO[Scope, Scope.Closeable] = ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
  private def host: Calls = new Calls {
    def waitFor(id: Int): Task[Int] = ZIO.succeed(id)
    def fail(): Task[Unit] = ZIO.unit
    def defect(): Task[Unit] = ZIO.unit
    def echo(value: String): Task[String] = ZIO.succeed(value)
  }
  private def code[A](result: Either[Throwable, A]): Option[RpcError.Code] =
    result.left.toOption.collect { case error: RpcError => error.code }

  test("registerScoped invalidates an unclaimed ticket on caller scope exit without closing the route") {
    val result = run {
      for {
        server <- RpcTestServer.start()
        authority <- scope
        token <- server.rpc.registerScoped(calls.bind(host)).provideEnvironment(ZEnvironment(authority))
        _ <- authority.close(Exit.unit)
        revoked <- server.rpc.revoke(token)
        denied <- RpcWebSocket.connect(server.port, token).either
        fresh <- server.rpc.register(calls.bind(host))
        connection <- RpcWebSocket.connect(server.port, fresh)
        reply <- calls.client(connection).echo("route remains open")
      } yield (revoked, code(denied), reply)
    }
    assertEquals(result, (false, Some(RpcError.Code.Disconnected), "route remains open"))
  }

  test("scoped socket closure cancels and awaits unary finalization, isolating other scoped and legacy connections") {
    val result = run {
      for {
        server <- RpcTestServer.start()
        authority <- scope
        otherAuthority <- scope
        started <- Promise.make[Nothing, Unit]
        finalizing <- Promise.make[Nothing, Unit]
        releaseFinalizer <- Promise.make[Nothing, Unit]
        finalized <- Promise.make[Nothing, Unit]
        service = new Calls {
          def waitFor(id: Int): Task[Int] =
            (started.succeed(()) *> ZIO.never).ensuring(
              finalizing.succeed(()) *> releaseFinalizer.await *> finalized.succeed(()).unit
            )
          def fail(): Task[Unit] = ZIO.unit
          def defect(): Task[Unit] = ZIO.unit
          def echo(value: String): Task[String] = ZIO.succeed(value)
        }
        token <- server.rpc.registerScoped(calls.bind(service)).provideEnvironment(ZEnvironment(authority))
        other <- server.rpc.registerScoped(calls.bind(host)).provideEnvironment(ZEnvironment(otherAuthority))
        legacy <- server.rpc.register(calls.bind(host))
        connection <- RpcWebSocket.connect(server.port, token)
        otherConnection <- RpcWebSocket.connect(server.port, other)
        legacyConnection <- RpcWebSocket.connect(server.port, legacy)
        pending <- calls.client(connection).waitFor(1).either.fork
        _ <- started.await
        closing <- authority.close(Exit.unit).fork
        _ <- finalizing.await
        closingState <- closing.poll
        _ <- releaseFinalizer.succeed(())
        _ <- closing.join
        finished <- finalized.isDone
        answer <- pending.join
        closed <- calls.client(connection).echo("closed").either
        otherReply <- calls.client(otherConnection).echo("other scoped")
        legacyReply <- calls.client(legacyConnection).echo("legacy")
        revoked <- server.rpc.revoke(other)
        stillOpen <- calls.client(otherConnection).echo("revoke preserves established socket")
      } yield (closingState.isEmpty, finished, code(answer), code(closed), otherReply, legacyReply, revoked, stillOpen)
    }
    assertEquals(
      result,
      (
        true,
        true,
        Some(RpcError.Code.Disconnected),
        Some(RpcError.Code.Disconnected),
        "other scoped",
        "legacy",
        false,
        "revoke preserves established socket"
      )
    )
  }

  test("scoped socket closure interrupts and awaits a stream producer finalizer") {
    import RpcStreamFixtures.*
    val result = run {
      for {
        server <- RpcTestServer.start()
        authority <- scope
        started <- Promise.make[Nothing, Unit]
        finalized <- Promise.make[Nothing, Unit]
        token <- server.rpc
          .registerScoped(rpc.bind(new Service {
            override def readBytes(path: String): ZStream[Any, FsError, Byte] =
              ZStream.fromZIO(started.succeed(()) *> ZIO.never).ensuring(finalized.succeed(()).unit)
          }))
          .provideEnvironment(ZEnvironment(authority))
        connection <- RpcWebSocket.connect(server.port, token)
        pending <- rpc.client(connection).readBytes("file").runDrain.either.fork
        _ <- started.await
        _ <- authority.close(Exit.unit)
        finished <- finalized.isDone
        answer <- pending.join
      } yield (finished, answer)
    }
    assertEquals(result, (true, Left(FsError.Infra(RpcError.Code.Disconnected))))
  }

  test("concurrent scope closure and real route redemption never leave a usable connection") {
    val results = run {
      for {
        server <- RpcTestServer.start()
        results <- ZIO.foreach(1 to 8) { _ =>
          for {
            authority <- scope
            token <- server.rpc.registerScoped(calls.bind(host)).provideEnvironment(ZEnvironment(authority))
            start <- Promise.make[Nothing, Unit]
            connecting <- (start.await *> RpcWebSocket.connect(server.port, token).either).fork
            closing <- (start.await *> authority.close(Exit.unit)).fork
            _ <- start.succeed(())
            _ <- closing.join
            connection <- connecting.join
            answer <- connection match {
              case Left(error)  => ZIO.succeed(Left(error))
              case Right(value) => calls.client(value).echo("closed authority").either
            }
            revoked <- server.rpc.revoke(token)
          } yield (code(answer), revoked)
        }
      } yield results.toList
    }
    assertEquals(results, List.fill(8)((Some(RpcError.Code.Disconnected), false)))
  }

  test("closure after redemption but before upgrade cannot attach a live socket to a closed authority") {
    // Gate the existing redemption/socket seam, not the network scheduler: closing the authority must win even
    // when the accepted HTTP response and socket handler are created only afterwards.
    val result = run {
      for {
        authority <- scope
        tickets <- RpcTickets.make(RpcWebSocket.AuthConfig())
        _ <- ZIO.addFinalizer(tickets.close)
        invoked <- Ref.make(0)
        dispatcher = calls.bind(new Calls {
          def waitFor(id: Int): Task[Int] = invoked.update(_ + 1).as(id)
          def fail(): Task[Unit] = invoked.update(_ + 1)
          def defect(): Task[Unit] = invoked.update(_ + 1)
          def echo(value: String): Task[String] = invoked.update(_ + 1).as(value)
        })
        token <- tickets.register(dispatcher, 60.seconds, Some(authority))
        redeemed <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        routes = Routes(Method.GET / "rpc" -> handler {
          (for {
            ticket <- tickets.redeem(token)
            _ <- redeemed.succeed(())
            _ <- release.await
            response <- RpcWebSocket.socket(ticket.dispatcher, RpcWebSocket.Limits(), ticket.scope).toResponse
          } yield response).orDie
        })
        http <- Server.defaultWith(_.binding("127.0.0.1", 0).gracefulShutdownTimeout(1.second)).build
        port <- Server.install(routes).provideEnvironment(http)
        attempt <- RpcWebSocket.connect(port, token).either.fork
        _ <- redeemed.await
        _ <- authority.close(Exit.unit)
        _ <- release.succeed(())
        connected <- attempt.join
        answer <- connected match {
          case Left(error)       => ZIO.succeed(Left(error))
          case Right(connection) => calls.client(connection).echo("must not execute").either
        }
        count <- invoked.get
      } yield (code(answer), count)
    }
    assertEquals(result, (Some(RpcError.Code.Disconnected), 0))
  }
}
