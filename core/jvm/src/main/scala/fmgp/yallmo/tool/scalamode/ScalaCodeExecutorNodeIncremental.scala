package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.orchestration.ExternalFunctionResult
import fmgp.yallmo.tool.CommandOutput
import java.io.{FileNotFoundException, IOException, UncheckedIOException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import zio.*

/** One server-owned, fixed-configuration compiler/linker workspace, NEVER an evaluation JVM. Sources, compiler products
  * and diagnostics remain in private temporary storage until replacement/rotation/scope close. Whole-source replacement
  * and fresh Node children prevent definitions/results/runtime state crossing calls. This is not a sandbox.
  */
object ScalaCodeExecutorNodeIncremental {
  private val MaxCalls = 64
  private val MaxWorkspaceBytes = 256L * 1024 * 1024

  def make(artifacts: Seq[Path]): ZIO[Scope, Throwable, ScalaCodeExecutor] =
    makeWithToolingResources(artifacts, getClass.getClassLoader.getResourceAsStream)

  private[yallmo] def makeWithToolingResources(
      artifacts: Seq[Path],
      openResource: String => java.io.InputStream
  ): ZIO[Scope, Throwable, ScalaCodeExecutor] = ZIO.uninterruptibleMask { restore =>
    for {
      ownership <- ZIO.service[Scope].flatMap(_.fork)
      executor <- restore(open(artifacts, openResource)).provideEnvironment(ZEnvironment[Scope](ownership)).onExit {
        case Exit.Success(_) => ZIO.unit
        case failure         => ownership.close(failure)
      }
    } yield executor
  }

  private def open(
      artifacts: Seq[Path],
      openResource: String => java.io.InputStream
  ): ZIO[Scope, Throwable, ScalaCodeExecutor] = for {
    classpath <- ZIO.attempt(ScalaCodeExecutorJVM.nodeClasspath(artifacts, ScalaCodeExecutor.ScalaVersion))
    root <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("yallmo-js-incremental-")))(remove)
    // Finish copy/close before an interrupted provisioning scope can remove this root.
    tooling <- ZIO.attemptBlocking {
      def resource(name: String) = Option(openResource(s"scalamode-linker/$name"))
        .getOrElse(throw new FileNotFoundException("Missing production linker tooling"))
      val names = Using
        .resource(resource("classpath.txt"))(input => new String(input.readAllBytes(), UTF_8))
        .linesIterator
        .toVector
      require(
        names.nonEmpty && names.forall(_.matches("[0-9]{4}-[A-Za-z0-9._-]+\\.jar")),
        "Invalid linker tooling manifest"
      )
      val target = Files.createDirectory(root.resolve("tooling"))
      names
        .map { name =>
          val path = target.resolve(name)
          Using.resource(resource(name))(Files.copy(_, path))
          path.toString
        }
        .mkString(java.io.File.pathSeparator)
    }.uninterruptible
    gate <- Semaphore.make(1)
    admitted <- Ref.make(0)
    session = new Executor(root, tooling, classpath, artifacts, gate, admitted)
    _ <- ZIO.addFinalizer(session.close)
  } yield session

  private def remove(path: Path): UIO[Unit] = ZIO
    .attemptBlocking {
      if Files.exists(path) then
        Using.resource(Files.walk(path)) { paths =>
          paths.sorted(java.util.Comparator.reverseOrder()).forEach(p => { Files.deleteIfExists(p); () })
        }
    }
    .catchSome {
      case error: IOException =>
        ZIO.logWarningCause("JS workspace cleanup failed; temporary source may remain", Cause.fail(error))
      case error: UncheckedIOException =>
        ZIO.logWarningCause("JS workspace cleanup failed; temporary source may remain", Cause.fail(error))
    }
    .orDie

  private final case class Worker(directory: Path, process: Process, var calls: Int = 0)

  private final class Executor(
      root: Path,
      tooling: String,
      classpath: String,
      artifacts: Seq[Path],
      gate: Semaphore,
      admitted: Ref[Int]
  ) extends ScalaCodeExecutor
      with ScalaCodeExecutorServices {
    private var worker: Option[Worker] = None // Access only under gate, including finalization.

    def isAvailable: UIO[Boolean] =
      ScalaCodeExecutorJVM.make(backend = ScalaCodeExecutorJVM.Backend.Node(artifacts)).isAvailable

    override def executeWithServices[R <: ScalamodeService](
        code: String,
        services: R,
        timeout: Duration,
        maxOutputBytes: Int
    ): Task[ExternalFunctionResult.ScalaMode] =
      ZIO.fail(new UnsupportedOperationException("JS / Node backend does not support service bindings"))

    private def discard: UIO[Unit] = ZIO.suspendSucceed {
      val previous = worker
      worker = None
      ZIO.foreachDiscard(previous) { current =>
        ZIO
          .attemptBlocking {
            try ScalaCodeExecutorJVM.stop(current.process, new AtomicBoolean(false))
            finally {
              current.process.getOutputStream.close()
              current.process.getInputStream.close()
              current.process.getErrorStream.close()
            }
          }
          .catchSome { case error: IOException =>
            ZIO.logWarningCause("JS worker stream cleanup failed", Cause.fail(error))
          }
          .orDie *>
          remove(current.directory) *> ZIO.logInfo("JS incremental worker closed")
      }
    }
    def close: UIO[Unit] = gate.withPermit(discard)

    private def start(deadline: Long): Task[Worker] = ZIO.uninterruptibleMask { restore =>
      for {
        directory <- ZIO.attemptBlocking(Files.createTempDirectory(root, "workspace-"))
        process <- ZIO
          .attemptBlocking {
            Files.createDirectory(directory.resolve("linked"))
            val builder = new ProcessBuilder(
              Path.of(java.lang.System.getProperty("java.home"), "bin", "java").toString,
              "-Xmx512m",
              "-cp",
              tooling,
              "fmgp.yallmo.linker.IncrementalLinkerWorker",
              directory.toString
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            Seq("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "NODE_OPTIONS", "NODE_PATH")
              .foreach(builder.environment().remove)
            builder.start()
          }
          .onError(_ => remove(directory))
        current = Worker(directory, process)
        _ <- ZIO.succeed { worker = Some(current) }
        ready <- restore(await(current, "ready", deadline))
        _ <- ZIO
          .fail(new java.util.concurrent.TimeoutException("Linker startup deadline exceeded"))
          .when(!ready && java.lang.System.nanoTime() >= deadline)
        _ <- ZIO.fail(new IOException("Production linker worker unavailable")).unless(ready)
        _ <- ZIO.logInfo("JS incremental worker started (512MB heap, 64-call/256MB workspace rotation)")
      } yield current
    }

    private def await(current: Worker, name: String, deadline: Long): Task[Boolean] = ZIO.attemptBlockingInterrupt {
      while current.process.isAlive && !Files.exists(current.directory.resolve(name)) && java.lang.System
          .nanoTime() < deadline
      do Thread.sleep(5)
      Files.exists(current.directory.resolve(name)) && current.process.isAlive
    }

    private def link(
        current: Worker,
        irClasspath: String,
        deadline: Long,
        remaining: AtomicInteger
    ): Task[CommandOutput] = for {
      _ <- ZIO.attemptBlocking {
        require(current.process.isAlive, "Linker worker exited")
        Files.deleteIfExists(current.directory.resolve("response"))
        Files.writeString(current.directory.resolve("classpath"), irClasspath, UTF_8)
        current.process.getOutputStream.write("link\n".getBytes(UTF_8))
        current.process.getOutputStream.flush()
      }
      completed <- await(current, "response", deadline)
      output <-
        if !completed then
          discard.as(
            CommandOutput(
              -1,
              java.lang.System.nanoTime() >= deadline,
              "",
              false,
              Vector("Linker worker stopped before response")
            )
          )
        else
          ZIO.attemptBlocking {
            val response = Files.readString(current.directory.resolve("response"), UTF_8)
            val exit = response.takeWhile(_ != '\n').toInt
            val bytes = response.dropWhile(_ != '\n').drop(1).getBytes(UTF_8)
            val count = math.min(bytes.length, remaining.getAndUpdate(left => math.max(0, left - bytes.length)))
            if exit == 0 then {
              val pending = current.directory.resolve("current.tmp")
              Files.copy(current.directory.resolve("linked/program.cjs"), pending, StandardCopyOption.REPLACE_EXISTING)
              Files.move(
                pending,
                current.directory.resolve("program.cjs"),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
              )
            }
            CommandOutput(
              exit,
              false,
              "",
              count < bytes.length,
              new String(bytes, 0, count, UTF_8).linesIterator.toVector
            )
          }
    } yield output

    private def invocation(code: String, deadline: Long, budget: Int): Task[ExternalFunctionResult.ScalaMode] = (for {
      remaining <- ZIO.succeed(new AtomicInteger(budget))
      setup <- ZIO.succeed(new AtomicLong(-1L))
      (compileTime, compiledOutput) <- (for {
        _ <- ZIO.when(worker.exists(w => !w.process.isAlive || w.calls >= MaxCalls))(discard)
        current <- worker.fold(start(deadline))(ZIO.succeed(_))
        _ <- ZIO.attemptBlocking {
          current.calls += 1
          // Remove execution eligibility BEFORE compiling, even when compilation fails or is interrupted.
          Seq("program.cjs", "current.tmp", "response", ScalaToolRuntime.LogFile, ScalaToolRuntime.ExecutionStartedFile)
            .foreach(name => Files.deleteIfExists(current.directory.resolve(name)))
          Files.writeString(current.directory.resolve("Snippet.scala"), ScalaCodeExecutorJVM.snippet(code), UTF_8)
          Files.writeString(current.directory.resolve("Main.scala"), ScalaCodeExecutorJVM.main, UTF_8)
        }
        command = ScalaCodeExecutorJVM.nodeCompileArguments("scala-cli", true, classpath)
        irCompile = Seq("scala-cli", "compile") ++ command
          .drop(2)
          .filterNot(Set("--main-class", "ScalaToolMain", "-o", "program.cjs")) ++ Seq("--print-class-path")
        compilation <- ScalaCodeExecutorJVM.runProcess(
          irCompile,
          current.directory,
          deadline,
          remaining,
          None,
          nodeBackend = true,
          classpathOutput = true
        )
        _ <- ZIO
          .fail(new IOException("Compiler classpath control output exceeded its bound"))
          .when(compilation.output.getBytes(UTF_8).length >= 64 * 1024)
        linked <-
          if compilation.exitCode != 0 || compilation.timedOut then ZIO.succeed(compilation)
          else
            link(current, compilation.output.trim, deadline, remaining).map(output =>
              output.copy(logs = compilation.logs ++ output.logs, truncated = compilation.truncated || output.truncated)
            )
      } yield linked).catchSome { case _: java.util.concurrent.TimeoutException =>
        discard.as(CommandOutput(-1, true, "Linker startup deadline exceeded", false))
      }.timed
      compiled = compiledOutput.exitCode == 0 && !compiledOutput.timedOut
      (executionTime, output) <-
        if !compiled then ZIO.succeed((Duration.Zero, compiledOutput))
        else
          (for {
            current <- ZIO.fromOption(worker).orElseFail(new IOException("Linker worker lost"))
            _ <- ZIO.fail(new IOException("Linker worker exited before evaluation")).unless(current.process.isAlive)
            runtime <- ScalaCodeExecutorJVM.runProcess(
              Seq("node", "--max-old-space-size=128", "program.cjs", "--scalamode-node", budget.toString),
              current.directory,
              deadline,
              remaining,
              None,
              Some(setup),
              nodeBackend = true
            )
            logs <- ScalaCodeExecutorJVM.readRuntimeLogs(current.directory, remaining)
          } yield runtime.copy(
            logs = compiledOutput.logs ++ runtime.logs ++ logs._1,
            truncated = compiledOutput.truncated || runtime.truncated || logs._2
          )).timed
      _ <- if output.timedOut then discard else ZIO.unit
      _ <- ZIO.foreachDiscard(worker) { current =>
        ZIO
          .attemptBlocking {
            Using.resource(Files.walk(current.directory))(
              _.iterator().asScala.filter(Files.isRegularFile(_)).map(Files.size(_)).sum
            )
          }
          .flatMap(bytes => ZIO.when(bytes > MaxWorkspaceBytes)(discard))
      }
    } yield ScalaCodeExecutorJVM
      .result(ScalaCodeExecutorJVM.displayDiagnostics(output, code, compiled, budget), compiled)
      .copy(
        compilationTime = compileTime.toMillis,
        executionTime = executionTime.toMillis,
        executionSetupTime = Option.when(setup.get() >= 0L)(setup.get())
      ))
      .onError(_ => discard)
      .onInterrupt(discard)

    def execute(
        code: String,
        timeout: Duration,
        maxOutputBytes: Int,
        dependencies: Seq[String]
    ): Task[ExternalFunctionResult.ScalaMode] =
      (for {
        _ <- ZIO.attempt {
          require(timeout > Duration.Zero && maxOutputBytes > 0, "Positive timeout/output budget required")
          require(code.getBytes(UTF_8).length <= 256 * 1024, "JS / Node source exceeds 256KB")
          require(
            dependencies.isEmpty || dependencies == ScalaTool.DefaultDependencies,
            "JS / Node does not support extra dependency grants"
          )
        }
        _ <- ZIO.fromEither(ScalaTool.validate(code).left.map(new IllegalArgumentException(_)))
        deadline <- ZIO.succeed(java.lang.System.nanoTime() + timeout.toNanos)
        output <- ZIO.uninterruptibleMask { restore =>
          admitted.modify(count => (count < 2, if count < 2 then count + 1 else count)).flatMap { accepted =>
            if !accepted then
              ZIO.succeed(
                ScalaCodeExecutorJVM.result(
                  ScalaCodeExecutorJVM.displayDiagnostics(
                    CommandOutput(-1, false, "JS / Node linker busy; try again later", false),
                    code,
                    false,
                    maxOutputBytes
                  ),
                  false
                )
              )
            else
              restore(ZIO.scoped {
                gate.withPermitScoped
                  .timeout(Duration.fromNanos(math.max(0L, deadline - java.lang.System.nanoTime())))
                  .flatMap {
                    case Some(_) => invocation(code, deadline, maxOutputBytes)
                    case None    =>
                      ZIO.succeed(
                        ScalaCodeExecutorJVM.result(
                          ScalaCodeExecutorJVM.displayDiagnostics(
                            CommandOutput(-1, true, "JS / Node deadline exceeded (including queue wait)", false),
                            code,
                            false,
                            maxOutputBytes
                          ),
                          false
                        )
                      )
                  }
              }).ensuring(admitted.update(_ - 1))
          }
        }
      } yield output).timed.map { case (elapsed, result) => result.copy(totalTime = elapsed.toMillis) }
  }
}
