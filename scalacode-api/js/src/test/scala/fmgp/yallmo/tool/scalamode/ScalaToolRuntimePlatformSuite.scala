package fmgp.yallmo.tool.scalamode

import munit.FunSuite
import scala.concurrent.Future
import zio.*

final class ScalaToolRuntimePlatformSuite extends FunSuite {
  private def run[A](effect: Task[A]): Future[A] =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.runToFuture(effect) }

  test("RPC runner fails explicitly without evaluating the callback") {
    var evaluated = false
    val effect = ScalaToolRuntime.runRpc(NativeReadToolsRuntime.contract) { _ =>
      evaluated = true
      ZIO.succeed(42)
    }
    run(effect.either.map { result =>
      val error = result.swap.toOption.get
      assert(error.isInstanceOf[UnsupportedOperationException])
      assert(error.getMessage.contains("not implemented on Scala.js"))
      assert(!evaluated)
    })
  }

  test("file logger bootstrap fails explicitly on Scala.js") {
    run(
      ZIO
        .scoped(ScalaToolRuntime.bootstrap.build)
        .provideLayer(ZLayer.succeed(ZIOAppArgs(Chunk("1024"))))
        .either
        .map { result =>
          val error = result.swap.toOption.get
          assert(error.isInstanceOf[UnsupportedOperationException])
          assert(error.getMessage.contains("not implemented on Scala.js"))
        }
    )
  }
}
