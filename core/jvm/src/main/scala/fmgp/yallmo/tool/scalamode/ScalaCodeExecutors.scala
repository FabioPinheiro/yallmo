package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.orchestration.ExternalFunctionResult
import fmgp.rpc.RpcToken
import fmgp.yallmo.tool.LocalProcess
import zio.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.jdk.CollectionConverters.*

/** Trusted JVM-hosted compiler/runtime factory. Both engines use shared bounded processes, service authority and
  * diagnostic handling; compiler errors and runtime failures remain ordinary tool results.
  */
object ScalaCodeExecutors {
  import ScalaCodeExecutor.ScalaVersion
  import ScalamodeProgram.*
  import ScalamodeProcess.*
  import ScalamodeInvocation.localListener
  def node(
      artifacts: Seq[Path],
      executable: String = "scala-cli",
      node: String = "node"
  ): ZIO[Scope, Throwable, ScalaCodeExecutor] =
    ScalaJsCompiler.make(artifacts, executable, node)

  val live: ULayer[ScalaCodeExecutor] = layer()

  /** One lazily started loopback listener per layer lifetime; tickets and child connections remain invocation-scoped.
    * Executable, compiler version and offline mode are trusted host configuration, never tool arguments.
    */
  def layer(
      executable: String = "scala-cli",
      scalaVersion: String = ScalaVersion,
      offline: Boolean = true
  ): ULayer[ScalaCodeExecutor] = ZLayer.scoped {
    for {
      scope <- ZIO.service[Scope]
      listener <- localListener.provideEnvironment(ZEnvironment(scope)).memoize
    } yield makeExecutor(executable, scalaVersion, offline, listener)
  }

  /** Resource-free compatibility constructor. Prefer `layer` for application-owned listener reuse. Explicit RPC
    * bindings already reuse their caller's listener; local bindings here acquire a listener per invocation.
    */
  def make(
      executable: String = "scala-cli",
      scalaVersion: String = ScalaVersion,
      offline: Boolean = true
  ): ScalaCodeExecutor = makeExecutor(executable, scalaVersion, offline, localListener)

  private def makeExecutor(
      executable: String,
      scalaVersion: String,
      offline: Boolean,
      listener: ScalamodeInvocation.Listener
  ): ScalaCodeExecutor = new ScalaCodeExecutor with ScalaCodeExecutorServices {
    override def isAvailable: UIO[Boolean] = LocalProcess.isAvailable(executable)

    override def execute(
        code: String,
        timeout: Duration,
        maxOutputBytes: Int,
        dependencies: Seq[String]
    ): Task[ExternalFunctionResult.ScalaMode] = ZIO.suspendSucceed {
      run(code, timeout, maxOutputBytes, dependencies, None, deadline = java.lang.System.nanoTime() + timeout.toNanos)
    }

    override def executeWithServices[R <: ScalamodeService](
        code: String,
        services: R,
        timeout: Duration,
        maxOutputBytes: Int,
        dependencies: Seq[String]
    ): Task[ExternalFunctionResult.ScalaMode] = ZIO
      .scoped {
        for {
          deadline <- ZIO.succeed(java.lang.System.nanoTime() + timeout.toNanos)
          _ <- ScalamodeInvocation.validate(code, timeout, maxOutputBytes)
          config <- ZIO.attempt(services.binding)
          bootstrap <- ScalamodeInvocation.prepare(services, listener, deadline)
          result <- run(
            code,
            timeout,
            maxOutputBytes,
            dependencies ++ config.dependencies ++ JvmTransportDependencies,
            bootstrap,
            Some(config),
            deadline
          )
        } yield result
      }
      .catchSome { case _: java.util.concurrent.TimeoutException =>
        ZIO.succeed(ScalamodeInvocation.timedOut(code, maxOutputBytes))
      }
      .timed
      .map { case (duration, result) => result.copy(totalTime = duration.toMillis) }

    private def run(
        code: String,
        timeout: Duration,
        maxOutputBytes: Int,
        dependencies: Seq[String],
        bootstrap: Option[(String, RpcToken)],
        service: Option[ScalamodeService.Binding] = None,
        deadline: Long
    ): Task[ExternalFunctionResult.ScalaMode] =
      (ScalamodeInvocation.validate(code, timeout, maxOutputBytes) *> ZIO
        .fail(new IllegalArgumentException("Offline mode requires an exact Scala release or full nightly version"))
        .when(
          offline && !scalaVersion.matches("""\d+\.\d+\.\d+(?:-(?:RC\d+|M\d+))?(?:-bin-\d{8}-[0-9a-f]+-NIGHTLY)?""")
        ) *>
        ZIO
          .scoped {
            for {
              program <- ZIO.succeed(ScalamodeProgram(code, service))
              directory <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("yallmo-scala-")))(path =>
                ZIO.attemptBlocking {
                  val paths = Files.walk(path)
                  try paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists(_))
                  finally paths.close()
                }.orDie
              )
              _ <- ZIO.attemptBlocking {
                Files.writeString(
                  directory.resolve("Snippet.scala"),
                  program.snippetSource,
                  UTF_8
                )
                Files.writeString(
                  directory.resolve("Main.scala"),
                  program.mainSource,
                  UTF_8
                )
              }
              remaining <- ZIO.succeed(new AtomicInteger(maxOutputBytes))
              setupTime <- ZIO.succeed(new AtomicLong(-1L))
              classpath = service.fold(apiClasspath)(serviceClasspath)
              compileCommand = compileArguments(executable, scalaVersion, offline, dependencies, classpath)
              runCommand = arguments(executable, scalaVersion, offline, dependencies, classpath, maxOutputBytes)
              _ <- ZIO.logInfo(s"Scala CLI compiling compiler=$scalaVersion offline=$offline timeout=$timeout")
              (compilationDuration, compilation) <- runProcess(
                compileCommand,
                directory,
                deadline,
                remaining,
                None,
                nodeBackend = false
              ).timed
              compiled = compilation.exitCode == 0 && !compilation.timedOut
              compilationDiagnostics = program.compilationDiagnostics(compilation, compiled)
              (executionDuration, command) <-
                if !compiled then ZIO.succeed((Duration.Zero, compilationDiagnostics))
                else
                  (for {
                    runtime <- runProcess(
                      runCommand,
                      directory,
                      deadline,
                      remaining,
                      bootstrap,
                      Some(setupTime),
                      nodeBackend = false
                    )
                    fileLogs <- readRuntimeLogs(directory, remaining)
                  } yield program.runtimeResult(compilationDiagnostics, runtime, fileLogs)).timed
            } yield program
              .finish(command, compiled, maxOutputBytes, bootstrap)
              .copy(
                compilationTime = compilationDuration.toMillis,
                executionTime = executionDuration.toMillis,
                executionSetupTime = Option.when(setupTime.get() >= 0L)(setupTime.get())
              )
          }
          .onInterrupt(ZIO.logInfo("Scala CLI cancelled; process cleanup completed"))).timed
        .map { case (duration, output) => output.copy(totalTime = duration.toMillis) }
  }

}
