package fmgp.yallmo.tool

import fmgp.yallmo.scalacode.ScalaToolApi
import fmgp.yallmo.model.ScalaModeResult
import fmgp.yallmo.orchestration.ExternalToolResult
import zio.*
import fmgp.rpc.*
import fmgp.yallmo.fs.FsRead

import java.io.{ByteArrayOutputStream, IOException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
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
      dependencies: Seq[String] = ScalaTool.DefaultDependencies
  ): Task[ExternalToolResult.ScalaMode]

  /** Explicit effect mode; pure/custom executors need not support a capability listener. */
  @scala.annotation.nowarn("msg=unused explicit parameter")
  def executeFilesystem(
      code: String,
      endpoint: String,
      timeout: Duration,
      maxOutputBytes: Int
  ): ZIO[FsRead & RpcWebSocket.RpcRoute, Throwable, ExternalToolResult.ScalaMode] =
    ZIO.fail(new UnsupportedOperationException("Filesystem Scala execution is not enabled"))
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

    override def execute(
        code: String,
        timeout: Duration,
        maxOutputBytes: Int,
        dependencies: Seq[String]
    ): Task[ExternalToolResult.ScalaMode] = run(code, timeout, maxOutputBytes, dependencies, None)

    override def executeFilesystem(
        code: String,
        endpoint: String,
        timeout: Duration,
        maxOutputBytes: Int
    ): ZIO[FsRead & RpcWebSocket.RpcRoute, Throwable, ExternalToolResult.ScalaMode] = ZIO.scoped {
      for {
        fs <- ZIO.service[FsRead]
        route <- ZIO.service[RpcWebSocket.RpcRoute]
        _ <- ZIO.attempt(validateFilesystemEndpoint(endpoint))
        token <- route.registerScoped(FsReadRpc.contract.bind(fs), ttl = timeout)
        result <- run(code, timeout, maxOutputBytes, filesystemDependencies, Some(endpoint -> token))
      } yield result
    }

    private def run(
        code: String,
        timeout: Duration,
        maxOutputBytes: Int,
        dependencies: Seq[String],
        bootstrap: Option[(String, RpcToken)]
    ): Task[ExternalToolResult.ScalaMode] =
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
                Files.writeString(
                  directory.resolve("Snippet.scala"),
                  if bootstrap.isDefined then filesystemSnippet(code) else snippet(code),
                  UTF_8
                )
                Files.writeString(
                  directory.resolve("Main.scala"),
                  if bootstrap.isDefined then filesystemMain else main,
                  UTF_8
                )
              }
              stopping <- ZIO.succeed(new AtomicBoolean(false))
              _ <- ZIO.logInfo(s"Scala CLI starting compiler=$scalaVersion offline=$offline timeout=$timeout")
              process <- ZIO.acquireRelease(ZIO.attemptBlocking {
                val builder = new ProcessBuilder(
                  arguments(
                    executable,
                    scalaVersion,
                    offline,
                    dependencies,
                    if bootstrap.isDefined then filesystemClasspath else apiClasspath
                  )*
                )
                  .directory(directory.toFile)
                // Do not let host JVM/classpath injection change the compilation or evaluated program.
                Seq("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH").foreach(
                  builder.environment().remove
                )
                builder.start()
              })(process => ZIO.attemptBlocking(stop(process, stopping)).orDie)
              remaining <- ZIO.succeed(new AtomicInteger(maxOutputBytes))
              captureOutput = (input: java.io.InputStream) =>
                ZIO.attemptBlockingInterrupt {
                  val bytes = new ByteArrayOutputStream()
                  val buffer = new Array[Byte](8192)
                  var truncated = false
                  try {
                    // Do not wait for EOF: a descendant can inherit stdout and keep the pipe open after CLI exit.
                    // Polling available bytes also makes cancellation independent of interrupting a blocking pipe read.
                    def capture(available: Int): Int = {
                      val count = input.read(buffer, 0, math.min(buffer.length, available))
                      if count > 0 then {
                        val retained = math.min(count, remaining.getAndUpdate(left => math.max(0, left - count)))
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
                }
              output <- captureOutput(process.getInputStream).forkScoped
              diagnostics <- captureOutput(process.getErrorStream).forkScoped
              // The deadline runs concurrently with stdin delivery. Killing the child unblocks a pipe write;
              // interrupting a Java blocking write alone does not. The canceler also kills before awaiting delivery.
              completed <- ZIO
                .attemptBlockingInterrupt(process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS))
                .ensuring(ZIO.attemptBlocking(stop(process, stopping)).orDie)
                .zipPar(
                  ZIO.attemptBlockingCancelable {
                    try {
                      try
                        bootstrap.foreach { case (endpoint, token) =>
                          process.getOutputStream.write((endpoint + "\n" + token.exportSecret + "\n").getBytes(UTF_8))
                          process.getOutputStream.flush()
                        }
                      finally process.getOutputStream.close()
                    } catch {
                      case _: IOException if stopping.get() || !process.isAlive => ()
                    }
                  }(ZIO.attemptBlocking(stop(process, stopping)).orDie)
                )
              captured <- output.join
              logs <- diagnostics.join
              _ <- ZIO.logInfo(s"Scala CLI finished exitCode=${process.exitValue()} timedOut=${!completed}")
              started <- ZIO.attemptBlocking(Files.exists(directory.resolve(ExecutionStartedFile)))
            } yield result(
              CommandOutput(
                process.exitValue(),
                !completed,
                captured._1,
                captured._2 || logs._2,
                logs._1.linesIterator.toVector
              ),
              started
            )
          }
          .onInterrupt(ZIO.logInfo("Scala CLI cancelled; process cleanup completed"))
  })

  // A private fixed-size filesystem marker is independent of stdout/stderr retention and cannot leak into results.
  // Safe snippets have no filesystem authority; only trusted Main writes it, before evaluating user code.
  private[tool] val ExecutionStartedFile = ".execution-started"

  private[tool] def result(command: CommandOutput, started: Boolean): ExternalToolResult.ScalaMode = {
    val failed = command.exitCode != 0 || command.timedOut
    val diagnostics = (Seq(command.output).filter(_.nonEmpty) ++ command.logs).mkString("\n")
    val outcome =
      if command.timedOut then
        ScalaModeResult.Executed(
          "Scala CLI exceeded its compilation/execution deadline." + (if diagnostics.isEmpty then ""
                                                                      else "\n" + diagnostics)
        )
      else if command.exitCode != 0 && !started then
        ScalaModeResult.CompilationError(
          if diagnostics.nonEmpty then diagnostics
          else s"Scala compiler/launch stage failed (exit ${command.exitCode})."
        )
      else if failed then
        ScalaModeResult.Executed(
          if diagnostics.nonEmpty then diagnostics else s"Scala execution failed (exit ${command.exitCode})."
        )
      else ScalaModeResult.Executed(command.output)
    ExternalToolResult.ScalaMode(outcome, command.logs, command.exitCode, command.timedOut, command.truncated)
  }

  private def validateFilesystemEndpoint(endpoint: String): Unit = {
    require(
      endpoint.length <= 2048 && endpoint.getBytes(UTF_8).length <= 2048 &&
        !endpoint.exists(Character.isISOControl),
      "Filesystem RPC endpoint must be a single line of at most 2048 UTF-8 bytes"
    )
    require(
      scala.util
        .Try(new java.net.URI(endpoint))
        .toOption
        .exists(uri => Set("ws", "wss").contains(uri.getScheme) && uri.getHost != null),
      "Expected a ws/wss filesystem RPC endpoint"
    )
  }

  private[tool] def arguments(
      executable: String = "scala-cli",
      scalaVersion: String = ScalaVersion,
      offline: Boolean = true,
      dependencies: Seq[String] = ScalaTool.DefaultDependencies,
      classpath: String = apiClasspath
  ): Seq[String] = Seq(
    executable,
    "run", // Compile and execute the project.
    ".", // The isolated working directory is the explicit project root.
    "--scala", // Select the pinned compiler version rather than resolving a moving nightly alias.
    scalaVersion,
    "--jvm", // Use the host JVM instead of downloading a managed JVM.
    "system",
    "--server=false", // Compile without the Bloop build server.
    "--main-class", // Run the trusted entry point that prints the snippet result.
    "ScalaToolMain",
    "--classpath", // Expose only explicitly selected capability/API artifacts, never the application classpath.
    classpath,
    "--scalac-option=-language:experimental.captureChecking", // Enable capture checking in the compiler.
    "--scalac-option=-language:experimental.modularity", // Enable experimental modularity support.
    "--scalac-option=-color:never", // Keep compiler diagnostics free of ANSI color escapes.
    "--java-opt=-Xmx128m", // Limit the evaluated program's JVM heap to 128 MiB.
    "--java-opt=--enable-native-access=ALL-UNNAMED", // Allow Netty's native library loading.
    "--java-opt=--sun-misc-unsafe-memory-access=allow" // Scala lazy vals still use Unsafe on recent JDKs.
  ) ++ dependencies.flatMap { dependency =>
    Seq("--dependency", dependency) // Add each trusted dependency and its transitive dependencies.
  } ++ Option
    .when(offline)(
      Seq(
        "--power", // Unlock Scala CLI's restricted options, including offline mode.
        "--offline", // Resolve dependencies from local caches without network access.
        "--suppress-experimental-warning" // Hide Scala CLI's experimental-option warnings.
      )
    )
    .toSeq
    .flatten

  private[tool] def snippet(code: String): String =
    "import scala.language.experimental.safe\n" +
      "import fmgp.yallmo.scalacode.{ScalaToolApi as tool}\n" +
      "object ScalaToolSnippet {\n  import tool.*\n  def result: Any = {\n" +
      code + "\n  }\n}\n"

  // Expose only scalacode-api, not the application classpath or model-supplied dependencies.
  // The stable API object is the location anchor; only its privileged methods are experimental.
  private[tool] def apiClasspath: String =
    Path.of(ScalaToolApi.getClass.getProtectionDomain.getCodeSource.getLocation.toURI).toAbsolutePath.toString

  private[tool] val filesystemDependencies = Seq(
    "dev.zio::zio-streams:2.1.27",
    "dev.zio::zio-json:1.1.1",
    "dev.zio::zio-http:3.11.6"
  )

  private[tool] def filesystemClasspath: String =
    Seq(ScalaToolApi.getClass, classOf[FsRead], classOf[RpcConnection])
      .map(cls => Path.of(cls.getProtectionDomain.getCodeSource.getLocation.toURI).toAbsolutePath.toString)
      .distinct
      .mkString(java.io.File.pathSeparator)

  private[tool] def filesystemSnippet(code: String): String =
    "import scala.language.experimental.safe\n" +
      "import fmgp.yallmo.scalacode.{ScalaToolApi as tool}\n" +
      "import fmgp.yallmo.fs.{FsRead, FsError}\nimport zio.ZIO\n" +
      "object ScalaToolSnippet {\n  import tool.*\n" +
      "  def result(fs: FsRead)(using zio.Trace, zio.CanFail[FsError]): ZIO[Any, FsError, Any] = {\n" +
      code + "\n  }\n}\n"

  private val filesystemMain = """import fmgp.rpc.*
    |import fmgp.yallmo.fs.{FsRead, FsError}
    |import zio.*
    |object ScalaToolMain {
    |  def main(args: Array[String]): Unit = {
    |    java.nio.file.Files.createFile(java.nio.file.Path.of(".execution-started"))
    |    val resultOut = java.lang.System.out
    |    java.lang.System.setOut(java.lang.System.err)
    |    val endpoint = scala.io.StdIn.readLine()
    |    val secret = scala.io.StdIn.readLine()
    |    given RpcErrorMapper[FsError] = RpcErrorMapper(error =>
    |      FsError.IoFailure("<rpc>", "RPC " + error.code.toString))
    |    val effect = ZIO.scoped {
    |      for {
    |        token <- ZIO.fromEither(RpcToken.parse(secret))
    |        connection <- RpcWebSocket.connect(endpoint, token)
    |        fs = Rpc.derived[FsRead].client(connection)
    |        value <- ScalaToolSnippet.result(fs)(using Trace.empty, summon[CanFail[FsError]])
    |      } yield value
    |    }
    |    Unsafe.unsafe { implicit unsafe =>
    |      Runtime.default.unsafe.run(effect).foldCause(
    |        cause => { java.lang.System.err.println("Filesystem execution failed: " + cause.prettyPrint); java.lang.System.exit(1) },
    |        value => resultOut.println(value)
    |      )
    |    }
    |  }
    |}
    |""".stripMargin

  // Kept in a separate source file: the trusted runner prints the result; snippets use the narrow runtime API.
  private val main = """object ScalaToolMain {
    |  def main(args: Array[String]): Unit = {
    |    java.nio.file.Files.createFile(java.nio.file.Path.of(".execution-started"))
    |    val resultOut = java.lang.System.out
    |    java.lang.System.setOut(java.lang.System.err)
    |    resultOut.println(ScalaToolSnippet.result)
    |  }
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
