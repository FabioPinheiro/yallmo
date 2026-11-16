package fmgp.yallmo.tool.scalamode

import munit.ZSuite
import zio.*

final class ScalaToolRuntimePlatformSuite extends ZSuite {
  testZ("RPC runner fails explicitly without evaluating the callback")(ZIO.suspendSucceed {
    var evaluated = false
    val effect = ScalaToolRuntime.runRpc(NativeReadToolsRuntime.contract) { _ =>
      evaluated = true
      ZIO.succeed(42)
    }
    for {
      result <- effect.either
    } yield {
      val error = result.swap.toOption.get
      assert(error.isInstanceOf[UnsupportedOperationException])
      assert(error.getMessage.contains("not implemented on Scala.js"))
      assert(!evaluated)
    }
  })

  testZ("file logger bootstrap fails explicitly on Scala.js")(ZIO.suspendSucceed {
    for {
      result <- ZIO
        .scoped(ScalaToolRuntime.bootstrap.build)
        .provideLayer(ZLayer.succeed(ZIOAppArgs(Chunk("1024"))))
        .either
    } yield {
      val error = result.swap.toOption.get
      assert(error.isInstanceOf[UnsupportedOperationException])
      assert(error.getMessage.contains("not implemented on Scala.js"))
    }
  })
}
