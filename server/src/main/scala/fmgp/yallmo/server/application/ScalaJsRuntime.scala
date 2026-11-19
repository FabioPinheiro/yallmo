package fmgp.yallmo.server.application

import fmgp.yallmo.server.protocol.YallmoProtocol.ScalamodeCapabilities
import fmgp.yallmo.tool.scalamode.*
import java.nio.file.Path
import zio.*

/** Startup-only Scala.js runtime; never replaces the application's JVM executor. */
final class ScalaJsRuntime private (
    val executor: Option[ScalaCodeExecutor],
    val capabilities: ScalamodeCapabilities.Result
)
object ScalaJsRuntime {
  val disabled = new ScalaJsRuntime(
    None,
    ScalamodeCapabilities.Result(false, "JS / Node backend is not configured on this server")
  )

  private val unavailable = new ScalaJsRuntime(
    None,
    ScalamodeCapabilities
      .Result(false, "JS / Node backend unavailable: check server Node, Scala CLI and reviewed artifacts")
  )

  /** Startup-only staging; keep the caller's scope open until application-owned execution has shut down. */
  def provisionBundled: URIO[Scope, ScalaJsRuntime] = provisionBundled(BundledJsArtifacts.stage())

  private[server] def provisionBundled(stagedRoot: ZIO[Scope, Throwable, Path]): URIO[Scope, ScalaJsRuntime] =
    stagedRoot
      .flatMap(provision)
      .catchSomeCause {
        // A staging failure can be combined with a cleanup defect; never turn that defect or interruption into success.
        case cause if cause.failures.nonEmpty && cause.defects.isEmpty && !cause.isInterrupted =>
          ZIO.logErrorCause("Bundled Scala.js provisioning failed", cause).as(unavailable)
      }
      .orDie

  /** Trusted embedding seam; the caller owns the reviewed root. No RPC accepts paths. */
  def provision(root: Path): URIO[Scope, ScalaJsRuntime] = {
    val artifacts = BundledJsArtifacts.artifactNames.map(root.resolve)
    ScalaCodeExecutors
      .node(artifacts)
      .flatMap(checked)
      .catchSomeCause {
        case cause if cause.failures.nonEmpty && cause.defects.isEmpty && !cause.isInterrupted =>
          ZIO.logErrorCause("Scala.js linker provisioning failed", cause).as(unavailable)
      }
      .orDie
  }

  /** Trusted embedding seam. Probe once; execution still fails closed if provisioning later disappears. */
  def checked(executor: ScalaCodeExecutor): UIO[ScalaJsRuntime] = executor.isAvailable.map { available =>
    if !available then unavailable
    else
      new ScalaJsRuntime(
        Some(new ScalaCodeExecutor with ScalaCodeExecutorServices {
          def executeWithServices[R <: ScalamodeService](
              code: String,
              services: R,
              timeout: Duration,
              maxOutputBytes: Int,
              dependencies: Seq[String]
          ) =
            report(executor.executeWithServices(code, services, timeout, maxOutputBytes, dependencies))

          private def report[A](effect: Task[A]): Task[A] = effect
            .tapError(error => ZIO.logErrorCause("Scala.js / Node runtime failed", Cause.fail(error)))
            .mapError(_ =>
              new IllegalStateException(
                "Scala.js / Node runtime failed; check server provisioning and logs (no JVM fallback)"
              )
            )

          def isAvailable = executor.isAvailable
          def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) =
            report(executor.execute(code, timeout, maxOutputBytes, dependencies))
        }),
        ScalamodeCapabilities.Result(
          true,
          "Scala.js / Node: retained server linker (cold first link), fresh Node per Run; selected-agent services and permissions preserved"
        )
      )
  }
}
