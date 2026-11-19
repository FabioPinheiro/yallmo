package fmgp.yallmo.tool.scalamode

import munit.ZSuite
import zio.*

final class ScalaToolRuntimePlatformSuite extends ZSuite {
  testZ("RPC runner requires Node opt-in without evaluating the callback")(ZIO.suspendSucceed {
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
      assert(error.getMessage.contains("Node opt-in"))
      assert(!evaluated)
    }
  })

  test("Node RPC stdin parsing is bounded, consumes only bootstrap lines and never renders rejected input") {
    val endpoint = "ws://127.0.0.1:1234/rpc"
    val secret = "A" * 43
    val input = (endpoint + "\n" + secret + "\nremaining").iterator
    val result = NodeRpcBootstrap.read(() => if input.hasNext then input.next().toInt else -1)
    assertEquals(result, (endpoint, secret))
    assertEquals(input.mkString, "remaining")
    List("x" * 4097, endpoint + "\n" + "A" * 44, endpoint + "\n" + secret, "ws://invalid\r\n").foreach { text =>
      val bytes = text.iterator
      var consumed = 0
      val error = intercept[IllegalArgumentException] {
        NodeRpcBootstrap.read(() => { consumed += 1; if bytes.hasNext then bytes.next().toInt else -1 })
      }
      assert(consumed <= 4097)
      assert(!error.getMessage.contains(secret))
      assert(!error.getMessage.contains(endpoint))
    }
    var consumed = 0
    intercept[IllegalArgumentException] {
      NodeRpcBootstrap.read(() => { consumed += 1; 'x'.toInt })
    }
    assertEquals(consumed, 4097)
  }

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
