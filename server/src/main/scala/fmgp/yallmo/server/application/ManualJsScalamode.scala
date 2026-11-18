package fmgp.yallmo.server.application

import fmgp.yallmo.server.protocol.YallmoProtocol.ScalamodeCapabilities
import fmgp.yallmo.tool.scalamode.{ScalaCodeExecutor, ScalaCodeExecutorNodeIncremental}
import java.nio.file.Path
import zio.*

/** Startup-only pure playground runtime; never replaces the application's JVM executor. */
final class ManualJsScalamode private (
    val executor: Option[ScalaCodeExecutor],
    val capabilities: ScalamodeCapabilities.Result
)
object ManualJsScalamode {
  val disabled = new ManualJsScalamode(
    None,
    ScalamodeCapabilities.Result(false, "JS / Node playground is not configured on this server")
  )

  private val unavailable = new ManualJsScalamode(
    None,
    ScalamodeCapabilities
      .Result(false, "JS / Node playground unavailable: check server Node, Scala CLI and reviewed artifacts")
  )

  /** Startup-only staging; keep the caller's scope open until application-owned execution has shut down. */
  def provisionBundled: URIO[Scope, ManualJsScalamode] = provisionBundled(BundledJsArtifacts.stage())

  private[server] def provisionBundled(stagedRoot: ZIO[Scope, Throwable, Path]): URIO[Scope, ManualJsScalamode] =
    stagedRoot
      .flatMap(provision)
      .catchSomeCause {
        // A staging failure can be combined with a cleanup defect; never turn that defect or interruption into success.
        case cause if cause.failures.nonEmpty && cause.defects.isEmpty && !cause.isInterrupted =>
          ZIO.logErrorCause("Bundled manual JS provisioning failed", cause).as(unavailable)
      }
      .orDie

  /** Trusted embedding seam; the caller owns the reviewed root. No RPC accepts paths. */
  def provision(root: Path): URIO[Scope, ManualJsScalamode] = {
    val artifacts = BundledJsArtifacts.artifactNames.map(root.resolve)
    ScalaCodeExecutorNodeIncremental
      .make(artifacts)
      .flatMap(checked)
      .catchSomeCause {
        case cause if cause.failures.nonEmpty && cause.defects.isEmpty && !cause.isInterrupted =>
          ZIO.logErrorCause("Manual JS linker provisioning failed", cause).as(unavailable)
      }
      .orDie
  }

  /** Trusted embedding seam. Probe once; execution still fails closed if provisioning later disappears. */
  def checked(executor: ScalaCodeExecutor): UIO[ManualJsScalamode] = executor.isAvailable.map { available =>
    if !available then unavailable
    else
      new ManualJsScalamode(
        Some(new ScalaCodeExecutor {
          def isAvailable = executor.isAvailable
          def execute(code: String, timeout: Duration, maxOutputBytes: Int, dependencies: Seq[String]) =
            executor
              .execute(code, timeout, maxOutputBytes, dependencies)
              .tapError(error => ZIO.logErrorCause("Manual JS / Node runtime failed", Cause.fail(error)))
              .mapError(_ =>
                new IllegalStateException(
                  "JS / Node playground runtime failed; check server provisioning and logs (no JVM fallback)"
                )
              )
        }),
        ScalamodeCapabilities.Result(
          true,
          "JS / Node (pure-only): retained server linker (cold first link), fresh Node per Run; no selected-agent services; permissions checked on every Run"
        )
      )
  }
}
