package fmgp.yallmo.tool

import fmgp.yallmo.scalacode.ScalaToolApi
import fmgp.yallmo.model.ScalaModeResult
import fmgp.yallmo.orchestration.ExternalFunctionResult
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
  ): Task[ExternalFunctionResult.ScalaMode]

  /** Explicit effect mode; pure/custom executors need not support a capability listener. */
  @scala.annotation.nowarn("msg=unused explicit parameter")
  def executeFilesystem(
      code: String,
      endpoint: String,
      timeout: Duration,
      maxOutputBytes: Int
  ): ZIO[FsRead & RpcWebSocket.RpcRoute, Throwable, ExternalFunctionResult.ScalaMode] =
    ZIO.fail(new UnsupportedOperationException("Filesystem Scala execution is not enabled"))
}

object ScalaCodeExecutor {
  // Pin the compiler to the main build's release; use its local cache without resolving a floating version.
  val ScalaVersion: String = "3.10.1" // TODO let's make it configurable
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
    ): Task[ExternalFunctionResult.ScalaMode] = run(code, timeout, maxOutputBytes, dependencies, None)

    override def executeFilesystem(
        code: String,
        endpoint: String,
        timeout: Duration,
        maxOutputBytes: Int
    ): ZIO[FsRead & RpcWebSocket.RpcRoute, Throwable, ExternalFunctionResult.ScalaMode] = ZIO.scoped {
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
    ): Task[ExternalFunctionResult.ScalaMode] =
      (ZIO
        .fail(new IllegalArgumentException("Offline mode requires an exact Scala release or full nightly version"))
        .when(
          offline && !scalaVersion.matches("""\d+\.\d+\.\d+(?:-(?:RC\d+|M\d+))?(?:-bin-\d{8}-[0-9a-f]+-NIGHTLY)?""")
        ) *>
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
              deadline <- ZIO.succeed(java.lang.System.nanoTime() + timeout.toNanos)
              remaining <- ZIO.succeed(new AtomicInteger(maxOutputBytes))
              classpath = if bootstrap.isDefined then filesystemClasspath else apiClasspath
              _ <- ZIO.logInfo(s"Scala CLI compiling compiler=$scalaVersion offline=$offline timeout=$timeout")
              (compilationDuration, compilation) <- runProcess(
                compileArguments(executable, scalaVersion, offline, dependencies, classpath),
                directory,
                deadline,
                remaining,
                None
              ).timed
              compiled = compilation.exitCode == 0 && !compilation.timedOut
              (executionDuration, command) <-
                if !compiled then ZIO.succeed((Duration.Zero, compilation))
                else
                  (for {
                    runtime <- runProcess(
                      arguments(executable, scalaVersion, offline, dependencies, classpath, maxOutputBytes),
                      directory,
                      deadline,
                      remaining,
                      bootstrap
                    )
                    fileLogs <- readRuntimeLogs(directory, remaining)
                  } yield runtime.copy(
                    logs = compilation.logs ++ compilation.output.linesIterator.toVector ++ runtime.logs ++ fileLogs._1,
                    truncated = compilation.truncated || runtime.truncated || fileLogs._2
                  )).timed
            } yield result(
              displayDiagnostics(command, code, bootstrap.isDefined, compiled, maxOutputBytes),
              compiled
            ).copy(compilationTime = compilationDuration.toMillis, executionTime = executionDuration.toMillis)
          }
          .onInterrupt(ZIO.logInfo("Scala CLI cancelled; process cleanup completed"))).timed
        .map { case (duration, output) => output.copy(totalTime = duration.toMillis) }
  })

  /** Every phase owns its process and stream readers; both phases share the deadline and byte budget. */
  private def runProcess(
      command: Seq[String],
      directory: Path,
      deadline: Long,
      remaining: AtomicInteger,
      bootstrap: Option[(String, RpcToken)]
  ): Task[CommandOutput] =
    if java.lang.System.nanoTime() >= deadline then ZIO.succeed(CommandOutput(-1, true, "", false))
    else
      ZIO.scoped {
        for {
          stopping <- ZIO.succeed(new AtomicBoolean(false))
          process <- ZIO.acquireRelease(ZIO.attemptBlocking {
            val builder = new ProcessBuilder(command*)
              .directory(directory.toFile)
            // Do not let host JVM/classpath injection change the compilation or evaluated program.
            Seq("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH").foreach(
              builder.environment().remove
            )
            builder.start()
          })(process => ZIO.attemptBlocking(stop(process, stopping)).orDie)
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
            .attemptBlockingInterrupt(
              process.waitFor(math.max(0L, deadline - java.lang.System.nanoTime()), TimeUnit.NANOSECONDS)
            )
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
          status = s"Scala process finished stage=${command.lift(1).getOrElse("unknown")} " +
            s"exitCode=${process.exitValue()} timedOut=${!completed} " +
            s"stdoutBytes=${captured._1.getBytes(UTF_8).length} stderrBytes=${logs._1.getBytes(UTF_8).length} " +
            s"truncated=${captured._2 || logs._2}"
          // Diagnostics can contain submitted source or runtime secrets; return them to the caller, not host logs.
          _ <- if process.exitValue() != 0 || !completed then ZIO.logWarning(status) else ZIO.logInfo(status)
        } yield CommandOutput(
          process.exitValue(),
          !completed,
          captured._1,
          captured._2 || logs._2,
          logs._1.linesIterator.toVector
        )
      }

  private def readRuntimeLogs(directory: Path, remaining: AtomicInteger): Task[(Vector[String], Boolean)] =
    ZIO.attemptBlocking {
      val path = directory.resolve(fmgp.yallmo.scalacode.ScalaToolRuntime.LogFile)
      if !Files.exists(path) then (Vector.empty, false)
      else {
        val input = Files.newInputStream(path)
        val bytes = try input.readNBytes(math.min(remaining.get().toLong + 1L, Int.MaxValue.toLong).toInt)
        finally input.close()
        val retained = math.min(bytes.length, remaining.getAndUpdate(left => math.max(0, left - bytes.length)))
        (new String(bytes, 0, retained, UTF_8).linesIterator.toVector, Files.size(path) > retained)
      }
    }

  private[tool] def result(command: CommandOutput, compiled: Boolean): ExternalFunctionResult.ScalaMode = {
    val failed = command.exitCode != 0 || command.timedOut
    val diagnostics = (Seq(command.output).filter(_.nonEmpty) ++ command.logs).mkString("\n")
    val outcome =
      if command.timedOut then
        ScalaModeResult.TimedOut(
          "Scala CLI exceeded its compilation/execution deadline." + (if diagnostics.isEmpty then ""
                                                                      else "\n" + diagnostics)
        )
      else if command.exitCode != 0 && !compiled then
        ScalaModeResult.CompilationError(
          if diagnostics.nonEmpty then diagnostics
          else
            s"Scala compiler/launch stage failed (exit ${command.exitCode}). No stdout/stderr was captured. " +
              "Check the Scala CLI launcher and, in offline mode, provision the pinned compiler and dependencies " +
              "in the service user's Scala CLI/Coursier cache."
        )
      else if failed then
        ScalaModeResult.Executed(
          if diagnostics.nonEmpty then diagnostics else s"Scala execution failed (exit ${command.exitCode})."
        )
      else ScalaModeResult.Executed(command.output)
    // Failure diagnostics own the captured logs; successful execution logs stay separate from the returned value.
    val logs = if failed then Seq.empty else command.logs
    ExternalFunctionResult.ScalaMode(outcome, logs, command.exitCode, command.truncated)
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

  // Required by the trusted ZIOApp runner even when the host requests no additional snippet libraries.
  private[tool] val RuntimeDependencies = Seq("dev.zio::zio:2.1.27", "dev.zio::zio-json:1.1.1")

  private[tool] def arguments(
      executable: String = "scala-cli",
      scalaVersion: String = ScalaVersion,
      offline: Boolean = true,
      dependencies: Seq[String] = ScalaTool.DefaultDependencies,
      classpath: String = apiClasspath,
      maxOutputBytes: Int = 64 * 1024
  ): Seq[String] =
    Seq(executable, "run", ".", "--main-class", "ScalaToolMain") ++
      compilerArguments(scalaVersion, offline, dependencies, classpath) ++ Seq(
        "--java-opt=-Xmx128m",
        "--java-opt=--enable-native-access=ALL-UNNAMED",
        "--java-opt=--sun-misc-unsafe-memory-access=allow",
        "--",
        maxOutputBytes.toString // Trusted argument consumed by the JSON logger's bootstrap layer.
      )

  private[tool] def compileArguments(
      executable: String = "scala-cli",
      scalaVersion: String = ScalaVersion,
      offline: Boolean = true,
      dependencies: Seq[String] = ScalaTool.DefaultDependencies,
      classpath: String = apiClasspath
  ): Seq[String] =
    Seq(executable, "compile", ".") ++ compilerArguments(scalaVersion, offline, dependencies, classpath)

  private def compilerArguments(
      scalaVersion: String,
      offline: Boolean,
      dependencies: Seq[String],
      classpath: String
  ): Seq[String] = Seq(
    // --quiet also hides dependency-resolution failures (including offline cache misses), not just progress.
    "--scala",
    scalaVersion,
    "--jvm",
    "system",
    "--server=false",
    "--classpath",
    classpath, // Only the selected API/capability artifacts, never the application classpath.
    "--scalac-option=-language:experimental.captureChecking",
    "--scalac-option=-language:experimental.modularity",
    "--scalac-option=-color:never"
  ) ++ (dependencies ++ RuntimeDependencies).distinct.flatMap(dependency => Seq("--dependency", dependency)) ++
    Option.when(offline)(Seq("--power", "--offline", "--suppress-experimental-warning")).toSeq.flatten

  private val purePreamble =
    "import scala.language.experimental.safe\n" +
      "import fmgp.yallmo.scalacode.{ScalaToolApi as tool}\n" +
      "object ScalaToolSnippet {\n  import tool.*\n  def result(using zio.Trace) = {\n"

  private[tool] def snippet(code: String): String = purePreamble + code + "\n  }\n}\n"

  private val snippetPosition =
    "(?<![\\w.])(?:(?:[A-Za-z]:)?[/\\\\](?:[^:\\r\\n()]*[/\\\\])?)?Snippet\\.scala:(\\d+)(?::(\\d+))?(?=[:)\\s]|$)".r
  private val sourcePosition = "\\.(?:scala|sc|java):\\d+".r
  private val sourceGutter = "^([ \\t]*)(\\d+)([ \\t]*\\|)".r
  private val sourceCaret = "^([ \\t]*\\|)[ \\t]*[\\^~]+[ \\t]*$".r

  /** Hide generated source and report submitted-code coordinates. Body columns/carets stay unchanged; errors in the
    * generated closing braces point to the end of the submitted code instead. Setup failures are not blamed on a user
    * line. Successful result values never pass through this formatter.
    */
  private[tool] def remapDiagnostics(diagnostics: String, code: String, filesystem: Boolean): String = {
    val preambleLines = (if filesystem then filesystemPreamble else purePreamble).count(_ == '\n')
    val codeLines = code.split("\n", -1).map(_.stripSuffix("\r"))
    val lastLine = preambleLines + codeLines.length
    val generatedEnd = lastLine + 3 // Two closing braces and the file's final newline.
    def submittedLine(line: String): Option[Int] =
      line.toIntOption
        .filter(number => number > preambleLines && number <= generatedEnd)
        .map(number => math.min(number - preambleLines, codeLines.length))
    def afterBody(line: String): Boolean =
      line.toIntOption.exists(number => number > lastLine && number <= generatedEnd)

    var inSnippetDiagnostic = false
    var endDiagnostic = false
    var hideCaret = false
    var endCaret = false
    diagnostics
      .split("\n", -1)
      .flatMap { line =>
        sourceGutter.findPrefixMatchOf(line) match {
          case Some(gutter) if inSnippetDiagnostic =>
            val mapped = submittedLine(gutter.group(2)).filter(_ => !afterBody(gutter.group(2)) || endDiagnostic)
            hideCaret = mapped.isEmpty
            endCaret = mapped.nonEmpty && afterBody(gutter.group(2))
            mapped.map { value =>
              val number = value.toString
              val prefix = gutter.group(1) + (" " * (gutter.group(2).length - number.length)) + number + gutter.group(3)
              prefix + (if endCaret then codeLines.last else line.substring(gutter.end))
            }
          case Some(_) => Some(line)
          case None    =>
            if sourcePosition.findFirstIn(line).nonEmpty then {
              val position = snippetPosition.findFirstMatchIn(line)
              inSnippetDiagnostic = position.nonEmpty
              endDiagnostic = position.exists(value => afterBody(value.group(1)))
              hideCaret = false
              endCaret = false
            }
            sourceCaret.findFirstMatchIn(line) match {
              case Some(_) if inSnippetDiagnostic && hideCaret    => None
              case Some(caret) if inSnippetDiagnostic && endCaret =>
                Some(caret.group(1) + codeLines.last.map(char => if char == '\t' then '\t' else ' ') + "^")
              case _ =>
                val positioned = snippetPosition.replaceAllIn(
                  line,
                  position =>
                    submittedLine(position.group(1)).fold("<executor setup>") { number =>
                      val column = Option(position.group(2))
                        .map(value => if afterBody(position.group(1)) then codeLines.last.length.toString else value)
                      s"Snippet.scala:$number" + column.fold("")(":" + _)
                    }
                )
                Some(if endDiagnostic then
                  positioned
                    .replace("expected, but '}' found", "expected at end of submitted code")
                    .replace("expected, but eof found", "expected at end of submitted code")
                else positioned)
            }
        }
      }
      .mkString("\n")
  }

  /** Rendering an EOF excerpt can expand the captured text, so apply the shared byte budget again afterwards. */
  private[tool] def displayDiagnostics(
      command: CommandOutput,
      code: String,
      filesystem: Boolean,
      compiled: Boolean,
      maxOutputBytes: Int
  ): CommandOutput = {
    var remaining = maxOutputBytes
    var truncated = command.truncated
    def retain(value: String): String = {
      val bytes = value.getBytes(UTF_8)
      val kept = math.min(remaining, bytes.length)
      truncated ||= kept < bytes.length
      remaining -= kept
      new String(bytes, 0, kept, UTF_8)
    }
    val output = retain(if compiled || command.exitCode == 0 then command.output
    else remapDiagnostics(command.output, code, filesystem))
    val logs = retain(remapDiagnostics(command.logs.mkString("\n"), code, filesystem)).linesIterator.toVector
    command.copy(output = output, logs = logs, truncated = truncated)
  }

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

  private val filesystemPreamble =
    "import scala.language.experimental.safe\n" +
      "import fmgp.yallmo.scalacode.{ScalaToolApi as tool}\n" +
      "import fmgp.yallmo.fs.{FsRead, FsError}\nimport fmgp.yallmo.scalacode.ScalaToolApi.ZIO\n" +
      "object ScalaToolSnippet {\n  import tool.*\n" +
      "  def result(fs: FsRead)(using zio.Trace, zio.CanFail[FsError]): zio.ZIO[Any, FsError, Any] = {\n"

  private[tool] def filesystemSnippet(code: String): String = filesystemPreamble + code + "\n  }\n}\n"

  private val filesystemMain = """import fmgp.rpc.*
    |import fmgp.yallmo.fs.{FsRead, FsError}
    |import fmgp.yallmo.scalacode.ScalaToolRuntime
    |import zio.*
    |object ScalaToolMain extends ZIOAppDefault {
    |  override val bootstrap = ScalaToolRuntime.bootstrap
    |
    |  private given RpcErrorMapper[FsError] = RpcErrorMapper(error =>
    |    FsError.IoFailure("<rpc>", "RPC " + error.code.toString))
    |
    |  def run = ZIO.scoped {
    |    for {
    |      endpoint <- Console.readLine
    |      secret <- Console.readLine
    |      token <- ZIO.fromEither(RpcToken.parse(secret))
    |      connection <- RpcWebSocket.connect(endpoint, token)
    |      fs = Rpc.derived[FsRead].client(connection)
    |      value <- ZIO.suspendSucceed(ScalaToolSnippet.result(fs)(using Trace.empty, summon[CanFail[FsError]]))
    |      _ <- Console.printLine(value)
    |    } yield ()
    |  }
    |}
    |""".stripMargin

  // ZIOApp owns the runtime, failure reporting and logger scope; stdout is only the returned value.
  private val main = """import fmgp.yallmo.scalacode.ScalaToolRuntime
    |import zio.*
    |object ScalaToolMain extends ZIOAppDefault {
    |  override val bootstrap = ScalaToolRuntime.bootstrap
    |  def run = ScalaToolRuntime.evaluate(ScalaToolSnippet.result).flatMap(value => Console.printLine(value))
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
