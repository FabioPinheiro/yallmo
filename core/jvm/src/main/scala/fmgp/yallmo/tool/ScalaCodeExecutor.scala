package fmgp.yallmo.tool

import zio.*

import java.io.{ByteArrayOutputStream, IOException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

/** Trusted host boundary for Scala CLI. Compiler errors and runtime failures are ordinary command results.
  *
  * TODO: Match Pi codemode truncation: keep the beginning and end with an omission marker, and save full output to a
  * temporary file.
  *
  * TODO: Add a separate execution-only timeout. The current timeout covers downloads, compilation and execution
  * together.
  */
trait ScalaCodeExecutor {
  def isAvailable: UIO[Boolean]

  /** Compiles and evaluates code, returning combined compiler/runtime stdout and stderr.
    *
    * @param timeout
    *   Combined process deadline, including compiler resolution/downloads, compilation and execution.
    * @param maxOutputBytes
    *   Positive maximum number of output bytes to retain. Keeps the first bytes, not the last. If output exceeds this
    *   limit, the remainder is drained and discarded and `CommandOutput.truncated` is true; otherwise it is false.
    *   Retained bytes are decoded as UTF-8; this is a byte limit, not a character limit.
    */
  def execute(code: String, timeout: Duration, maxOutputBytes: Int): Task[CommandOutput]
}

object ScalaCodeExecutor {
  // Pin the verified compiler and use its local cache to avoid a nightly metadata lookup on every call.
  val ScalaVersion: String = "3.10.2-RC1-bin-20261021-a98b104-NIGHTLY"
  val live: ULayer[ScalaCodeExecutor] = layer()

  /** Executable, compiler version and offline mode are trusted host configuration, never tool arguments. */
  def layer(
      executable: String = "scala-cli",
      scalaVersion: String = ScalaVersion,
      offline: Boolean = true
  ): ULayer[ScalaCodeExecutor] = ZLayer.succeed(new ScalaCodeExecutor {
    override def isAvailable: UIO[Boolean] = LocalProcess.isAvailable(executable)

    override def execute(code: String, timeout: Duration, maxOutputBytes: Int): Task[CommandOutput] =
      ZIO
        .fail(new IllegalArgumentException("Offline mode requires a full nightly version"))
        .when(offline && !scalaVersion.endsWith("-NIGHTLY")) *>
        ZIO.fromEither(ScalaTool.validate(code).left.map(new IllegalArgumentException(_))) *>
        ZIO
          .scoped {
            for {
              directory <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("yallmo-scala-")))(path =>
                ZIO.attemptBlocking {
                  val paths = Files.walk(path)
                  try paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists(_))
                  finally paths.close()
                }.orDie
              )
              _ <- ZIO.attemptBlocking {
                Files.writeString(directory.resolve("Snippet.scala"), snippet(code), UTF_8)
                Files.writeString(directory.resolve("Main.scala"), main, UTF_8)
              }
              stopping <- ZIO.succeed(new AtomicBoolean(false))
              _ <- ZIO.logInfo(s"Scala CLI starting compiler=$scalaVersion offline=$offline timeout=$timeout")
              process <- ZIO.acquireRelease(ZIO.attemptBlocking {
                val builder = new ProcessBuilder(arguments(executable, scalaVersion, offline)*)
                  .directory(directory.toFile)
                  .redirectErrorStream(true)
                // Do not let host JVM/classpath injection change the compilation or evaluated program.
                Seq("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH").foreach(
                  builder.environment().remove
                )
                val started = builder.start()
                started.getOutputStream.close()
                started
              })(process => ZIO.attemptBlocking(stop(process, stopping)).orDie)
              output <- ZIO.attemptBlockingInterrupt {
                val input = process.getInputStream
                val bytes = new ByteArrayOutputStream()
                val buffer = new Array[Byte](8192)
                var truncated = false
                try {
                  // Do not wait for EOF: a descendant can inherit stdout and keep the pipe open after CLI exit.
                  // Polling available bytes also makes cancellation independent of interrupting a blocking pipe read.
                  def capture(available: Int): Int = {
                    val count = input.read(buffer, 0, math.min(buffer.length, available))
                    if count > 0 then {
                      val retained = math.min(count, maxOutputBytes - bytes.size())
                      bytes.write(buffer, 0, retained)
                      truncated ||= retained < count
                    }
                    count
                  }
                  while process.isAlive do {
                    val available = input.available()
                    if available > 0 then capture(available)
                    else Thread.sleep(10)
                  }
                  // Drain only bytes already pending at exit, not future output from an orphaned descendant.
                  var pending = input.available()
                  while pending > 0 do {
                    val count = capture(pending)
                    pending = if count > 0 then pending - count else 0
                  }
                } catch {
                  // Process.destroyForcibly can close the pipe while it is being polled. Preserve captured diagnostics.
                  case _: IOException if stopping.get() || !process.isAlive => ()
                } finally input.close()
                (bytes.toString(UTF_8), truncated)
              }.forkScoped
              completed <- ZIO
                .attemptBlockingInterrupt(process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS))
                .ensuring(ZIO.attemptBlocking(stop(process, stopping)).orDie)
              captured <- output.join
              _ <- ZIO.logInfo(s"Scala CLI finished exitCode=${process.exitValue()} timedOut=${!completed}")
            } yield CommandOutput(process.exitValue(), timedOut = !completed, captured._1, captured._2)
          }
          .onInterrupt(ZIO.logInfo("Scala CLI cancelled; process cleanup completed"))
  })

  private[tool] def arguments(
      executable: String = "scala-cli",
      scalaVersion: String = ScalaVersion,
      offline: Boolean = true
  ): Seq[String] = Seq(
    executable,
    "run",
    ".", // The isolated working directory is the explicit project root.
    "--scala",
    scalaVersion, // TODO "3.nightly"! remove offline and suppress-experimental-warning? what is --power?
    "--jvm",
    "system",
    "--server=false",
    "--main-class",
    "ScalaToolMain",
    "--scalac-option=-language:experimental.captureChecking",
    "--scalac-option=-language:experimental.modularity",
    "--scalac-option=-color:never",
    "--java-opt=-Xmx128m"
  ) ++ Option.when(offline)(Seq("--power", "--offline", "--suppress-experimental-warning")).toSeq.flatten

  private[tool] def snippet(code: String): String =
    "import scala.language.experimental.safe\nobject ScalaToolSnippet {\n  def result: Any = {\n" +
      code + "\n  }\n}\n"

  // Kept in a separate source file: only the trusted runner may print, including formatting the result.
  private val main = """object ScalaToolMain {
    |  def main(args: Array[String]): Unit = println(ScalaToolSnippet.result)
    |}
    |""".stripMargin

  private def stop(process: Process, stopping: AtomicBoolean): Unit = {
    stopping.set(true)
    val descendants = process.descendants()
    val children = try descendants.toList.asScala.toList
    finally descendants.close()
    children.reverse.foreach(_.destroyForcibly())
    if process.isAlive then process.destroyForcibly()
    process.waitFor(5, TimeUnit.SECONDS)
    ()
  }
}
