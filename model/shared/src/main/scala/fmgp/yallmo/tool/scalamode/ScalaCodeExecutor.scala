package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.orchestration.ExternalFunctionResult
import zio.*

/** Host-supplied execution boundary; the Scala CLI/process implementation lives only in core. */
trait ScalaCodeExecutor {

  def isAvailable: UIO[Boolean]

  /** Compiles and evaluates code, returning the value separately from compiler/runtime logs.
    *
    * @param timeout
    *   Combined process deadline, including compiler resolution/downloads, compilation and execution.
    * @param maxOutputBytes
    *   Positive shared byte budget for the result and logs. Keeps the first bytes, not the last. If output exceeds this
    *   limit, the remainder is drained and discarded and `truncated` is true; otherwise it is false. Retained bytes are
    *   decoded as UTF-8; this is a byte limit, not a character limit.
    * @param dependencies
    *   Trusted host-supplied Scala CLI dependency coordinates, never model-supplied tool arguments.
    */
  def execute(
      code: String,
      timeout: Duration,
      maxOutputBytes: Int,
      dependencies: Seq[String] = ScalaCodeExecutor.DefaultDependencies
  ): Task[ExternalFunctionResult.ScalaMode]

}

object ScalaCodeExecutor {
  // Pin the compiler to the main build's release; use its local cache without resolving a floating version.
  val ScalaVersion: String = "3.10.1"

  val DefaultDependencies: Seq[String] = Seq("dev.zio::zio:2.1.27")
}
