package fmgp.yallmo.scalacode

import fmgp.yallmo.orchestration.ExternalFunctionResult
import fmgp.yallmo.tool.ScalaModeResult
import fmgp.yallmo.tool.scalamode.{ScalaCodeExecutor, ScalaCodeExecutorServices, ScalamodeService, ScalaTool}
import fmgp.yallmo.tool.scalamode.executeWithServices
import munit.ZSuite
import zio.*

/** External selective imports must explicitly bring API service dispatch into scope. */
final class ScalaCodeExecutorServicesSuite extends ZSuite {
  private val service = new ScalamodeService {
    val apiDocs = "Unused transport"
    val binding = ScalamodeService.Binding("api", Seq("example.Api"), "example.Runtime.run")
    override def rpcBinding = throw new AssertionError("Dispatch must not inspect transport")
  }

  testZ("base-typed capability preserves descriptor identity and execution limits") {
    val executor: ScalaCodeExecutor = new ScalaCodeExecutor with ScalaCodeExecutorServices {
      def isAvailable = ZIO.succeed(true)
      def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) =
        ZIO.dieMessage("No pure fallback")
      def executeWithServices[R <: ScalamodeService](
          code: String,
          services: R,
          timeout: Duration,
          maxOutputBytes: Int,
          dependencies: Seq[String]
      ) =
        ZIO.succeed {
          assert(services eq service)
          assertEquals(timeout, 2.seconds)
          assertEquals(maxOutputBytes, 123)
          ExternalFunctionResult.ScalaMode(ScalaModeResult.Executed(code))
        }
    }
    executor.executeWithServices("42", service, 2.seconds, 123).map(result => assertEquals(result.text, "42"))
  }

  testZ("base-only executor rejects services without pure fallback or transport access") {
    val executor: ScalaCodeExecutor = new ScalaCodeExecutor {
      def isAvailable = ZIO.succeed(true)
      def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) =
        ZIO.dieMessage("No pure fallback")
    }
    executor.executeWithServices("42", service, 2.seconds, 123).either.map { result =>
      val error = result.left.toOption.get
      assert(error.isInstanceOf[UnsupportedOperationException])
      assertEquals(error.getMessage, "Service Scala execution is not enabled")
      assert(ScalaTool.DefaultDependencies eq ScalaCodeExecutor.DefaultDependencies)
      assertEquals(service.binding.dependencies, Seq.empty[String])
    }
  }
}
