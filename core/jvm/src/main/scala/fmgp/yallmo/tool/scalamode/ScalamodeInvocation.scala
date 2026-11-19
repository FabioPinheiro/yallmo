package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.tool.CommandOutput
import fmgp.rpc.{RpcToken, RpcWebSocket}
import zio.*
import zio.http.Server

/** Host authority is scoped to the invocation, never to a retained compiler workspace. */
private[scalamode] object ScalamodeInvocation {
  type Listener = ZIO[Scope, Throwable, (RpcWebSocket.RpcRoute, String)]

  def localListener: Listener = for {
    route <- RpcWebSocket.route()
    server <- Server.defaultWith(_.binding("127.0.0.1", 0)).build
    port <- Server.install(route.routes).provideEnvironment(server)
  } yield (route, s"ws://127.0.0.1:$port/rpc")

  def prepare(
      service: ScalamodeService,
      listener: Listener,
      deadline: Long
  ): ZIO[Scope, Throwable, Option[(String, RpcToken)]] = (for {
    _ <- ZIO.attempt(
      require(service.rpcBinding.isEmpty || service.localRpcBinding.isEmpty, "Choose one Scalamode RPC transport")
    )
    transport <- service.rpcBinding match {
      case Some(binding) => ZIO.some(binding)
      case None          =>
        ZIO.foreach(service.localRpcBinding) { dispatcher =>
          listener.map { case (route, endpoint) => ScalamodeService.RpcBinding(endpoint, route, dispatcher) }
        }
    }
    bootstrap <- ZIO.foreach(transport) { binding =>
      ZIO.attempt(ScalamodeProgram.validateRpcEndpoint(binding.endpoint)) *>
        binding.route.registerScoped(binding.dispatcher, ttl = remaining(deadline)).map(binding.endpoint -> _)
    }
  } yield bootstrap).timeoutFail(
    new java.util.concurrent.TimeoutException("Scalamode service setup deadline exceeded")
  )(remaining(deadline))

  def validate(code: String, timeout: Duration, budget: Int): Task[Unit] =
    ZIO.attempt {
      require(timeout > Duration.Zero, "timeout must be positive")
      require(budget > 0, "maxOutputBytes must be positive")
    } *> ZIO.fromEither(ScalaTool.validate(code).left.map(new IllegalArgumentException(_)))

  def timedOut(code: String, budget: Int) =
    ScalamodeProgram(code, None).finish(
      CommandOutput(-1, true, "Scalamode service setup deadline exceeded", false),
      false,
      budget,
      None
    )

  def remaining(deadline: Long): Duration = Duration.fromNanos(math.max(1L, deadline - java.lang.System.nanoTime()))
}
