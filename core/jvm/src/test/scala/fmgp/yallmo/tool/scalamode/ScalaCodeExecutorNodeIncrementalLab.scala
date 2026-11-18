package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.orchestration.ExternalFunctionResult
import fmgp.yallmo.tool.CommandOutput
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}
import zio.*

/** Benchmark-only fixed-configuration workspace. Not installed in ScalaCodeExecutorJVM and not a general cache.
  * Artifact/compiler/options identity is immutable for the scope; each case gets its own directory and worker. Calls
  * are serialized. Source, diagnostics and build/link state persist until replacement or scope closure; evaluated
  * runtime state does not persist, because every evaluation runs in a fresh child.
  */
private[scalamode] object ScalaCodeExecutorNodeIncrementalLab {
  enum Mode { case CLI, Linker, JVM }

  def open(mode: Mode): ZIO[Scope, Throwable, Session] = for {
    directory <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("yallmo-incremental-lab-")))(delete)
    _ <- ZIO.attemptBlocking(Files.createDirectories(directory.resolve("linked")))
    classpath <- ZIO.attempt {
      if mode == Mode.JVM then ScalaCodeExecutorJVM.apiClasspath
      else
        ScalaCodeExecutorJVM.nodeClasspath(
          ScalaCodeExecutorNodeFixtures.artifacts(Path.of(sys.env("YALLMO_TEST_NODE_ARTIFACT_ROOT"))),
          ScalaCodeExecutor.ScalaVersion
        )
    }
    gate <- Semaphore.make(1)
    worker <-
      if mode != Mode.Linker then ZIO.none
      else
        ZIO
          .acquireRelease(ZIO.attemptBlocking {
            val builder = new ProcessBuilder(
              Path.of(java.lang.System.getProperty("java.home"), "bin", "java").toString,
              "-Xmx512m",
              "-cp",
              workerClasspath,
              "fmgp.yallmo.tool.scalamode.ScalaCodeExecutorNodeLinkerWorker",
              directory.toString
            )
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
            Seq("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "NODE_OPTIONS", "NODE_PATH")
              .foreach(builder.environment().remove)
            builder.start()
          })(stopWorker)
          .map(Some(_))
    session = new Session(directory, mode, classpath, worker, gate)
    _ <- ZIO.foreachDiscard(worker) { process =>
      ZIO.attemptBlockingInterrupt {
        val deadline = java.lang.System.nanoTime() + 30.seconds.toNanos
        while process.isAlive && !Files.exists(directory.resolve("ready")) && java.lang.System.nanoTime() < deadline do
          Thread.sleep(5)
        require(process.isAlive && Files.exists(directory.resolve("ready")), "Linker worker did not initialize")
      }
    }
  } yield session

  // sbt's forked runner can load tests through a URL loader rather than java.class.path. This is only the trusted
  // worker tooling classpath, NEVER the restricted submitted program's compilation/link input.
  private def workerClasspath: String = {
    val urls = Iterator
      .iterate(getClass.getClassLoader)(_.getParent)
      .takeWhile(_ != null)
      .flatMap {
        case loader: URLClassLoader => loader.getURLs.toSeq.map(url => Path.of(url.toURI).toString)
        case _                      => Seq.empty
      }
      .toSeq
    (urls ++ java.lang.System.getProperty("java.class.path").split(java.io.File.pathSeparator)).distinct
      .mkString(java.io.File.pathSeparator)
  }

  private def stopWorker(process: Process): UIO[Unit] = ZIO.attemptBlocking {
    try {
      if process.isAlive then {
        process.getOutputStream.write("quit\n".getBytes(UTF_8))
        process.getOutputStream.flush()
        process.getOutputStream.close()
        process.waitFor(2, TimeUnit.SECONDS)
      }
    } finally ScalaCodeExecutorJVM.stop(process, new AtomicBoolean(false))
  }.orDie

  private def delete(path: Path): UIO[Unit] = ZIO.attemptBlocking {
    val paths = Files.walk(path)
    try paths.sorted(java.util.Comparator.reverseOrder()).forEach(p => { Files.deleteIfExists(p); () })
    finally paths.close()
  }.orDie

  final class Session private[ScalaCodeExecutorNodeIncrementalLab] (
      val directory: Path,
      val mode: Mode,
      classpath: String,
      val worker: Option[Process],
      gate: Semaphore
  ) {
    private val cli = sys.env("YALLMO_TEST_SCALA_CLI")
    private val node = sys.env("YALLMO_TEST_NODE")
    private val budget = 64 * 1024

    /** Always calls the retained linker, even for identical IR; no result-artifact cache shortcut. Output eligible for
      * execution is a separate copy published only after this invocation's successful link response.
      */
    def link(classpath: String, deadline: Long, remaining: AtomicInteger): Task[CommandOutput] = {
      val process = worker.getOrElse(throw new IllegalStateException("No linker worker"))
      (for {
        _ <- ZIO.attemptBlocking {
          Files.deleteIfExists(directory.resolve("program.cjs"))
          Files.deleteIfExists(directory.resolve("response"))
          require(process.isAlive, "Linker scope is no longer usable")
          Files.writeString(directory.resolve("classpath"), classpath, UTF_8)
          process.getOutputStream.write("link\n".getBytes(UTF_8))
          process.getOutputStream.flush()
        }
        completed <- ZIO.attemptBlockingInterrupt {
          while process.isAlive && !Files.exists(directory.resolve("response")) && java.lang.System
              .nanoTime() < deadline
          do Thread.sleep(5)
          Files.exists(directory.resolve("response"))
        }
        result <-
          if !completed then
            ZIO
              .attemptBlocking(ScalaCodeExecutorJVM.stop(process, new AtomicBoolean(false)))
              .as(
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
              val response = Files.readString(directory.resolve("response"), UTF_8)
              val exit = response.takeWhile(_ != '\n').toInt
              val bytes = response.dropWhile(_ != '\n').drop(1).getBytes(UTF_8)
              val count = math.min(bytes.length, remaining.getAndUpdate(left => math.max(0, left - bytes.length)))
              if exit == 0 then
                Files.copy(
                  directory.resolve("linked/program.cjs"),
                  directory.resolve("program.cjs"),
                  StandardCopyOption.REPLACE_EXISTING
                )
              CommandOutput(
                exit,
                false,
                "",
                count < bytes.length,
                new String(bytes, 0, count, UTF_8).linesIterator.toVector
              )
            }
      } yield result)
        .onInterrupt(ZIO.attemptBlocking(ScalaCodeExecutorJVM.stop(process, new AtomicBoolean(false))).orDie)
    }

    def execute(code: String, timeout: Duration = 120.seconds): Task[ExternalFunctionResult.ScalaMode] =
      gate.withPermit {
        (for {
          _ <- ZIO.fromEither(ScalaTool.validate(code).left.map(new IllegalArgumentException(_)))
          _ <- ZIO.attemptBlocking {
            // Replace the whole submission. Nothing from a previous source body is appended or imported.
            Files.writeString(directory.resolve("Snippet.scala"), ScalaCodeExecutorJVM.snippet(code), UTF_8)
            Files.writeString(directory.resolve("Main.scala"), ScalaCodeExecutorJVM.main, UTF_8)
            Seq("program.cjs", ScalaToolRuntime.LogFile, ScalaToolRuntime.ExecutionStartedFile).foreach(name =>
              Files.deleteIfExists(directory.resolve(name))
            )
          }
          deadline = java.lang.System.nanoTime() + timeout.toNanos
          remaining = new AtomicInteger(budget)
          setup = new AtomicLong(-1L)
          compile =
            if mode == Mode.JVM then ScalaCodeExecutorJVM.compileArguments(cli, classpath = classpath)
            else ScalaCodeExecutorJVM.nodeCompileArguments(cli, true, classpath) ++ Seq("--force")
          irCompile = Seq(cli, "compile") ++ compile
            .drop(2)
            .filterNot(Set("--main-class", "ScalaToolMain", "-o", "program.cjs", "--force")) ++ Seq(
            "--print-class-path"
          )
          (compileTime, compilation) <- (for {
            command <- ScalaCodeExecutorJVM.runProcess(
              if mode == Mode.Linker then irCompile else compile,
              directory,
              deadline,
              remaining,
              None,
              nodeBackend = mode != Mode.JVM
            )
            linked <-
              if mode != Mode.Linker || command.exitCode != 0 || command.timedOut then ZIO.succeed(command)
              else
                link(command.output.trim, deadline, remaining).map(linked =>
                  linked.copy(logs = command.logs ++ linked.logs, truncated = command.truncated || linked.truncated)
                )
          } yield linked).timed
          compiled = compilation.exitCode == 0 && !compilation.timedOut
          (executeTime, output) <-
            if !compiled then ZIO.succeed((Duration.Zero, compilation))
            else
              (for {
                runtime <- ScalaCodeExecutorJVM.runProcess(
                  if mode == Mode.JVM then
                    ScalaCodeExecutorJVM.arguments(cli, classpath = classpath, maxOutputBytes = budget)
                  else Seq(node, "--max-old-space-size=128", "program.cjs", "--scalamode-node", budget.toString),
                  directory,
                  deadline,
                  remaining,
                  None,
                  Some(setup),
                  nodeBackend = mode != Mode.JVM
                )
                logs <- ScalaCodeExecutorJVM.readRuntimeLogs(directory, remaining)
              } yield runtime.copy(
                logs = compilation.logs ++ compilation.output.linesIterator.toVector ++ runtime.logs ++ logs._1,
                truncated = compilation.truncated || runtime.truncated || logs._2
              )).timed
        } yield ScalaCodeExecutorJVM
          .result(ScalaCodeExecutorJVM.displayDiagnostics(output, code, compiled, budget), compiled)
          .copy(
            compilationTime = compileTime.toMillis,
            executionTime = executeTime.toMillis,
            executionSetupTime = Option.when(setup.get() >= 0L)(setup.get())
          )).timed.map { case (elapsed, result) =>
          result.copy(totalTime = elapsed.toMillis)
        }
      }
  }
}
