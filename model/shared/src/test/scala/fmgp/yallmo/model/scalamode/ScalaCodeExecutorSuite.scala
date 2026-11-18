package fmgp.yallmo.model.scalamode

import fmgp.yallmo.tool.scalamode.ScalaCodeExecutor

import fmgp.yallmo.orchestration.ExternalFunctionResult
import fmgp.yallmo.tool.ScalaModeResult
import munit.ZSuite
import zio.*

/** Model-only consumers implement the canonical boundary on JVM and JS without the service API. */
final class ScalaCodeExecutorSuite extends ZSuite {
  testZ("canonical executor preserves the pure dependency default and compiler pin") {
    val executor: ScalaCodeExecutor = new ScalaCodeExecutor {
      def isAvailable = ZIO.succeed(true)
      def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) = ZIO.succeed {
        assertEquals(dependencies, Seq("dev.zio::zio:2.1.27"))
        assertEquals(code, "42")
        assertEquals(timeout, 1.second)
        assertEquals(maxOutputBytes, 100)
        ExternalFunctionResult.ScalaMode(ScalaModeResult.Executed(code))
      }
    }
    for {
      available <- executor.isAvailable
      output <- executor.execute("42", 1.second, 100)
    } yield {
      assert(available)
      assertEquals(output.text, "42")
      assertEquals(ScalaCodeExecutor.ScalaVersion, "3.10.1")
    }
  }
}
