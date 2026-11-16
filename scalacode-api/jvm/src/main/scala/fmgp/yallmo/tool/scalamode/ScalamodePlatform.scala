package fmgp.yallmo.tool.scalamode

/** Platform boundary for caller-owned RPC listeners. Shared descriptors never start a listener. */
object ScalamodePlatform {

  /** Preserves the existing JVM route type and its scoped ticket registration API. */
  type RpcRoute = fmgp.rpc.RpcWebSocket.RpcRoute
}
