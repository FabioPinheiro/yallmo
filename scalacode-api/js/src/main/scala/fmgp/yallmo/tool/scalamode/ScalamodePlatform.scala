package fmgp.yallmo.tool.scalamode

/** Platform boundary for caller-owned RPC listeners. Shared descriptors never start a listener. */
object ScalamodePlatform {

  /** Placeholder handle: authenticated listener hosting is currently implemented only on JVM. */
  sealed trait RpcRoute
}
