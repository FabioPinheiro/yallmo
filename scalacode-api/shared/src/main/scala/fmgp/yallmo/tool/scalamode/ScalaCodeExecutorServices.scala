package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.orchestration.ExternalFunctionResult
import zio.*

/** API-only opt-in for executors accepting explicit host-authored service contexts. */
trait ScalaCodeExecutorServices { self: ScalaCodeExecutor =>
  def executeWithServices[R <: ScalamodeService](
      code: String,
      services: R,
      timeout: Duration,
      maxOutputBytes: Int
  ): Task[ExternalFunctionResult.ScalaMode]
}

/** Base-only custom executors fail closed without invoking pure execution or service transport. */
extension (executor: ScalaCodeExecutor)
  def executeWithServices[R <: ScalamodeService](
      code: String,
      services: R,
      timeout: Duration,
      maxOutputBytes: Int
  ): Task[ExternalFunctionResult.ScalaMode] =
    executor match {
      case capable: ScalaCodeExecutorServices =>
        capable.executeWithServices(code, services, timeout, maxOutputBytes)
      case _ =>
        ZIO.fail(new UnsupportedOperationException("Service Scala execution is not enabled"))
    }
