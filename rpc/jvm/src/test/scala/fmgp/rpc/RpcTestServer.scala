package fmgp.rpc

import zio.*
import zio.http.Server

/** Test-only host: listener configuration stays outside the RPC library. */
private[rpc] final case class RpcTestServer(port: Int, rpc: RpcWebSocket.RpcRoute) {
  def endpoint: String = s"ws://127.0.0.1:$port/rpc"
}

private[rpc] object RpcTestServer {
  def start(
      limits: RpcWebSocket.Limits = RpcWebSocket.Limits(),
      auth: RpcWebSocket.AuthConfig = RpcWebSocket.AuthConfig()
  ): ZIO[Scope, Throwable, RpcTestServer] =
    for {
      rpc <- RpcWebSocket.route(limits, auth)
      http <- Server.defaultWith(_.binding("127.0.0.1", 0).gracefulShutdownTimeout(1.second)).build
      port <- Server.install(rpc.routes).provideEnvironment(http)
    } yield RpcTestServer(port, rpc)
}
