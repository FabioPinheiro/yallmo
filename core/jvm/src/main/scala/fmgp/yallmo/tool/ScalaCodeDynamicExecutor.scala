package fmgp.yallmo.tool

import fmgp.yallmo.scalacode.ScalaScript
import zio.*

import java.io.{ByteArrayOutputStream, File, IOException}
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

/** Compiles scripts using trusted configuration; services are taken from the caller on each execution. */
@deprecated("UNSAFE with no sandbox", "0.2.0")
trait ScalaCodeDynamicExecutor {
  def isAvailable: UIO[Boolean]

  /** Runs with only the selected `R` subset of the calling agent's environment.
    *
    * `R` must include the services used by the snippet. The timeout covers compilation and execution; returned compiler
    * diagnostics and values retain at most `maxOutputBytes` UTF-8 bytes. Runtime cancellation is cooperative.
    */
  def execute[R: EnvironmentTag](code: String, timeout: Duration, maxOutputBytes: Int): RIO[R, CommandOutput]
}

@deprecated("UNSAFE with no sandbox", "0.2.0")
object ScalaCodeDynamicExecutor {

  /** Trusted compiler configuration, independent of the environment selected at execution time. The extra compiler
    * classpath defaults to scalacode-api only. Scala CLI resolves Scala/ZIO and their dependencies; add any custom
    * service API artifacts explicitly. Versions must match the host's libraries.
    */
  final case class Config(
      executable: String = "scala-cli",
      scalaVersion: String = "3.10.0",
      classpath: String = apiClasspath,
      offline: Boolean = false,
      apiDescription: String = "",
      zioVersion: String = "2.1.27"
  )

  def live: ULayer[ScalaCodeDynamicExecutor] = layer(Config())

  private def apiClasspath: String =
    Path.of(classOf[ScalaScript].getProtectionDomain.getCodeSource.getLocation.toURI).toString

  /** Creates a reusable compiler/executor without capturing any agent services. */
  def layer(config: Config): ULayer[ScalaCodeDynamicExecutor] =
    ZLayer.succeed[ScalaCodeDynamicExecutor](new ScalaCodeDynamicExecutorImp(config))

  // Service requirements in a compiled snippet cannot be proven to match the host's method-level R.
  // Typecheck the effect and Throwable error channel before the single trusted environment adaptation.
  // FIXME REVIEW https://zio.dev/reference/core/zioapp
  private[tool] def source(code: String): String =
    """package yallmo.dynamic
      |import zio.*
      |final class GeneratedScript extends fmgp.yallmo.scalacode.ScalaScript {
      |  def program[R]: ZIO[R, Throwable, Any] = {
      |""".stripMargin + code + "\n}\n}\n"

  private[tool] def arguments(config: Config): Seq[String] = Seq(
    config.executable,
    "--power",
    "package",
    "Snippet.scala",
    "--library",
    "--output",
    "script.jar",
    "--scala",
    config.scalaVersion,
    "--dependency",
    s"dev.zio::zio:${config.zioVersion}",
    "--jvm",
    "system",
    "--server=false",
    "--quiet",
    "--suppress-outdated-dependency-warning",
    "--classpath",
    config.classpath
      .split(File.pathSeparator)
      .map(path => Path.of(path).toAbsolutePath.toString)
      .mkString(File.pathSeparator),
    "--scalac-option=-color:never"
  ) ++ Option.when(config.offline)("--offline")
}

/** Compiles trusted ZIO snippets externally, then executes them with live services inside the host JVM.
  *
  * This is not a sandbox. Runtime cancellation is cooperative; arbitrary code cannot be forcibly stopped. Only the
  * returned value and compiler diagnostics are captured, not global stdout/stderr or ZIO logs.
  */
@deprecated("UNSAFE with no sandbox", "0.2.0")
final class ScalaCodeDynamicExecutorImp private[tool] (
    config: ScalaCodeDynamicExecutor.Config
) extends ScalaCodeDynamicExecutor {
  import ScalaCodeDynamicExecutor.*

  override def isAvailable: UIO[Boolean] = LocalProcess.isAvailable(config.executable)

  override def execute[R: EnvironmentTag](code: String, timeout: Duration, maxOutputBytes: Int): RIO[R, CommandOutput] =
    for {
      _ <- ZIO.fromEither(ScalaTool.validate(code).left.map(new IllegalArgumentException(_)))
      _ <- ZIO.attempt {
        require(timeout > Duration.Zero, "timeout must be positive")
        require(maxOutputBytes > 0, "maxOutputBytes must be positive")
      }
      result <- ZIO.scoped {
        for {
          directory <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("yallmo-scala-dynamic-")))(
            path =>
              ZIO.attemptBlocking {
                val paths = Files.walk(path)
                try paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists(_))
                finally paths.close()
              }.orDie
          )
          _ <- ZIO.attemptBlocking(
            Files.writeString(directory.resolve("Snippet.scala"), source(code), UTF_8)
          )
          started <- Clock.nanoTime
          compiled <- compile(directory, timeout, maxOutputBytes)
          result <-
            if compiled.exitCode != 0 || compiled.timedOut then ZIO.succeed(compiled)
            else {
              for {
                now <- Clock.nanoTime
                remaining = Duration.fromNanos(math.max(0L, timeout.toNanos - (now - started)))
                exit <-
                  if remaining <= Duration.Zero then ZIO.none
                  else runProgram[R](directory.resolve("script.jar")).exit.timeout(remaining)
                rendered <- ZIO.attempt {
                  val (status, timedOut, value) = exit match {
                    case None => (124, true, "ZIO execution deadline exceeded (cooperative cancellation).")
                    case Some(Exit.Success(value)) => (0, false, value)
                    case Some(Exit.Failure(cause)) => (1, false, cause.prettyPrint)
                  }
                  val separator = if compiled.output.nonEmpty && !compiled.output.endsWith("\n") then "\n" else ""
                  val bytes = (compiled.output + separator + value).getBytes(UTF_8)
                  CommandOutput(
                    status,
                    timedOut,
                    new String(bytes.take(maxOutputBytes), UTF_8),
                    compiled.truncated || bytes.length > maxOutputBytes
                  )
                }
              } yield rendered
            }
        } yield result
      }
    } yield result

  private def runProgram[R: EnvironmentTag](jar: Path): RIO[R, String] =
    ZIO.environmentWithZIO[R] { callerEnvironment =>
      // A narrower effect type alone does not remove extra services from ZIO's runtime environment.
      val environment = callerEnvironment.prune[R]
      ZIO.scoped {
        for {
          loader <- ZIO.fromAutoCloseable(ZIO.attemptBlocking {
            new URLClassLoader(Array(jar.toUri.toURL), classOf[ScalaScript].getClassLoader)
          })
          script <- ZIO.attemptBlocking {
            loader
              .loadClass("yallmo.dynamic.GeneratedScript")
              .asSubclass(classOf[ScalaScript])
              .getDeclaredConstructor()
              .newInstance()
          }
          result <- ZIO.suspendSucceed(script.program[R].provideEnvironment(environment))
          rendered <- ZIO.attempt(String.valueOf(result))
        } yield rendered
      }
    }

  private def compile(directory: Path, timeout: Duration, limit: Int): Task[CommandOutput] = ZIO.scoped {
    for {
      stopping <- ZIO.succeed(new AtomicBoolean(false))
      process <- ZIO.acquireRelease(ZIO.attemptBlocking {
        val builder = new ProcessBuilder(arguments(config)*)
          .directory(directory.toFile)
          .redirectErrorStream(true)
        Seq("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH").foreach(builder.environment().remove)
        val process = builder.start()
        process.getOutputStream.close()
        process
      })(process => ZIO.attemptBlocking(stop(process, stopping)).orDie)
      output <- capture(process, limit, stopping).forkScoped
      completed <- ZIO
        .attemptBlockingInterrupt(process.waitFor(timeout.toNanos, TimeUnit.NANOSECONDS))
        .ensuring(ZIO.attemptBlocking(stop(process, stopping)).orDie)
      captured <- output.join
    } yield CommandOutput(if completed then process.exitValue() else 124, !completed, captured._1, captured._2)
  }

  // Retain only the prefix in memory, draining excess without an unbounded temporary log file.
  private def capture(process: Process, limit: Int, stopping: AtomicBoolean): Task[(String, Boolean)] =
    ZIO.attemptBlockingInterrupt {
      val input = process.getInputStream
      val bytes = new ByteArrayOutputStream()
      val buffer = new Array[Byte](8192)
      var truncated = false
      try {
        def read(available: Int): Int = {
          val count = input.read(buffer, 0, math.min(buffer.length, available))
          if count > 0 then {
            val retained = math.min(count, limit - bytes.size())
            bytes.write(buffer, 0, retained)
            truncated ||= retained < count
          }
          count
        }
        while process.isAlive do {
          val available = input.available()
          if available > 0 then read(available)
          else Thread.sleep(10)
        }
        // A descendant may keep stdout open after CLI exit. Do not wait for its EOF or future output.
        var pending = input.available()
        while pending > 0 do {
          val count = read(pending)
          pending = if count > 0 then pending - count else 0
        }
      } catch {
        case _: IOException if stopping.get() || !process.isAlive => ()
      } finally input.close()
      (bytes.toString(UTF_8), truncated)
    }

  private def stop(process: Process, stopping: AtomicBoolean): Unit = {
    stopping.set(true)
    val descendants = process.descendants()
    try descendants.toList.asScala.reverse.foreach(_.destroyForcibly())
    finally descendants.close()
    if process.isAlive then process.destroyForcibly()
    process.waitFor(5, TimeUnit.SECONDS)
    ()
  }
}
