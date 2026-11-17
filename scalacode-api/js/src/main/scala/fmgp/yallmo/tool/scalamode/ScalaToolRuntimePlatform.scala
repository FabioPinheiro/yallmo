package fmgp.yallmo.tool.scalamode

import fmgp.rpc.Rpc
import zio.*

/** Explicit failures for runner infrastructure that requires JVM transport and filesystem support. */
private[scalamode] trait ScalaToolRuntimePlatform {
  protected def markExecutionStarted: UIO[Unit] = ZIO.unit

  @scala.annotation.nowarn("msg=unused explicit parameter")
  def runRpc[API, E, A](contract: Rpc[API])(evaluate: API => ZIO[Any, E, A]): ZIO[Any, E | Throwable, Unit] =
    ZIO.fail(new UnsupportedOperationException("Scalamode RPC runner is not implemented on Scala.js"))

  val bootstrap: ZLayer[ZIOAppArgs, Throwable, Unit] =
    ZLayer.fail(new UnsupportedOperationException("Scalamode runtime file logging is not implemented on Scala.js"))
}
