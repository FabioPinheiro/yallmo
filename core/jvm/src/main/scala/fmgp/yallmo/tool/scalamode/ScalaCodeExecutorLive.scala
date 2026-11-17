package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.tool.scalamode.ScalaToolApi
import fmgp.yallmo.tool.*
import fmgp.yallmo.orchestration.ExternalFunctionResult
import zio.*
import fmgp.rpc.*
import fmgp.yallmo.fs.FsRead
import zio.http.Server

import java.io.{ByteArrayOutputStream, IOException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}
import scala.jdk.CollectionConverters.*

/** Trusted host boundary for Scala CLI. Compiler errors and runtime failures are ordinary command results.
  *
  * TODO: Match Pi codemode truncation: keep the beginning and end with an omission marker, and save full output to a
  * temporary file.
  *
  * TODO: Add a separate execution-only timeout. The current timeout covers downloads, compilation and execution
  * together.
  */
object ScalaCodeExecutorLive {
  import ScalaCodeExecutor.ScalaVersion
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

  private def localListener: ZIO[Scope, Throwable, (RpcWebSocket.RpcRoute, String)] =
    for {
      route <- RpcWebSocket.route()
      server <- Server.defaultWith(_.binding("127.0.0.1", 0)).build
      port <- Server.install(route.routes).provideEnvironment(server)
    } yield (route, s"ws://127.0.0.1:$port/rpc")

  private def makeExecutor(
      executable: String,
      scalaVersion: String,
      offline: Boolean,
      listener: ZIO[Scope, Throwable, (RpcWebSocket.RpcRoute, String)]
  ): ScalaCodeExecutor = new ScalaCodeExecutor {
    override def isAvailable: UIO[Boolean] = LocalProcess.isAvailable(executable)

    override def execute(
        code: String,
        timeout: Duration,
        maxOutputBytes: Int,
        dependencies: Seq[String]
    ): Task[ExternalFunctionResult.ScalaMode] = run(code, timeout, maxOutputBytes, dependencies, None)

    override def executeWithServices[R <: ScalamodeService](
        code: String,
        services: R,
        timeout: Duration,
        maxOutputBytes: Int
    ): Task[ExternalFunctionResult.ScalaMode] = ZIO.scoped {
      for {
        config <- ZIO.attempt(services.binding)
        _ <- ZIO.attempt(
          require(services.rpcBinding.isEmpty || services.localRpcBinding.isEmpty, "Choose one Scalamode RPC transport")
        )
        transport <- services.rpcBinding match {
          case Some(binding) => ZIO.some(binding)
          case None          =>
            ZIO.foreach(services.localRpcBinding) { dispatcher =>
              listener.map { case (route, endpoint) => ScalamodeService.RpcBinding(endpoint, route, dispatcher) }
            }
        }
        bootstrap <- ZIO.foreach(transport) { binding =>
          ZIO.attempt(validateRpcEndpoint(binding.endpoint)) *>
            binding.route.registerScoped(binding.dispatcher, ttl = timeout).map(token => binding.endpoint -> token)
        }
        result <- run(code, timeout, maxOutputBytes, config.dependencies, bootstrap, Some(config))
      } yield result
    }

    private def run(
        code: String,
        timeout: Duration,
        maxOutputBytes: Int,
        dependencies: Seq[String],
        bootstrap: Option[(String, RpcToken)],
        service: Option[ScalamodeService.Binding] = None
    ): Task[ExternalFunctionResult.ScalaMode] =
      (ZIO.attempt {
        require(timeout > Duration.Zero, "timeout must be positive")
        require(maxOutputBytes > 0, "maxOutputBytes must be positive")
      } *> ZIO
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
                  service.fold(snippet(code))(config => servicePreamble(config) + code + "\n  }\n}\n"),
                  UTF_8
                )
                Files.writeString(
                  directory.resolve("Main.scala"),
                  service.fold(main)(serviceMain),
                  UTF_8
                )
              }
              deadline <- ZIO.succeed(java.lang.System.nanoTime() + timeout.toNanos)
              remaining <- ZIO.succeed(new AtomicInteger(maxOutputBytes))
              setupTime <- ZIO.succeed(new AtomicLong(-1L))
              classpath = service.fold(apiClasspath)(
                serviceClasspath
              )
              _ <- ZIO.logInfo(s"Scala CLI compiling compiler=$scalaVersion offline=$offline timeout=$timeout")
              (compilationDuration, compilation) <- runProcess(
                compileArguments(executable, scalaVersion, offline, dependencies, classpath),
                directory,
                deadline,
                remaining,
                None
              ).timed
              compiled = compilation.exitCode == 0 && !compilation.timedOut
              compilationDiagnostics = service.fold(compilation)(config =>
                hideSetupDiagnostics(compilation, code, servicePreamble(config).count(_ == '\n'), compiled)
              )
              (executionDuration, command) <-
                if !compiled then ZIO.succeed((Duration.Zero, compilationDiagnostics))
                else
                  (for {
                    runtime <- runProcess(
                      arguments(executable, scalaVersion, offline, dependencies, classpath, maxOutputBytes),
                      directory,
                      deadline,
                      remaining,
                      bootstrap,
                      Some(setupTime)
                    )
                    fileLogs <- readRuntimeLogs(directory, remaining)
                    // Runtime stdout is a returned value, never a trusted-source diagnostic channel.
                    runtimeLogs = runtime.copy(output = "", logs = runtime.logs ++ fileLogs._1)
                    runtimeDiagnostics = service.fold(runtimeLogs)(config =>
                      hideSetupDiagnostics(runtimeLogs, code, servicePreamble(config).count(_ == '\n'), compiled)
                    )
                  } yield runtime.copy(
                    logs = compilationDiagnostics.logs ++ compilationDiagnostics.output.linesIterator.toVector ++
                      runtimeDiagnostics.logs,
                    truncated = compilation.truncated || runtime.truncated || fileLogs._2
                  )).timed
            } yield result(
              displayDiagnostics(
                redactBootstrap(command, bootstrap),
                code,
                compiled,
                maxOutputBytes,
                service.map(config => servicePreamble(config).count(_ == '\n'))
              ),
              compiled
            ).copy(
              compilationTime = compilationDuration.toMillis,
              executionTime = executionDuration.toMillis,
              executionSetupTime = Option.when(setupTime.get() >= 0L)(setupTime.get())
            )
          }
          .onInterrupt(ZIO.logInfo("Scala CLI cancelled; process cleanup completed"))).timed
        .map { case (duration, output) => output.copy(totalTime = duration.toMillis) }
  }

  /** Every phase owns its process and stream readers; both phases share the deadline and byte budget. */
  private def runProcess(
      command: Seq[String],
      directory: Path,
      deadline: Long,
      remaining: AtomicInteger,
      bootstrap: Option[(String, RpcToken)],
      setupTime: Option[AtomicLong] = None
  ): Task[CommandOutput] =
    if java.lang.System.nanoTime() >= deadline then ZIO.succeed(CommandOutput(-1, true, "", false))
    else
      ZIO.scoped {
        for {
          stopping <- ZIO.succeed(new AtomicBoolean(false))
          // Only the runtime receives the marker property. Never reuse a file left by compilation.
          _ <- ZIO.when(setupTime.nonEmpty)(
            ZIO.attemptBlocking(Files.deleteIfExists(directory.resolve(ScalaToolRuntime.ExecutionStartedFile)))
          )
          launchedAt <- ZIO.succeed(java.lang.System.nanoTime())
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
          // Observe with the host's monotonic clock: nanoTime values from different JVMs are not comparable.
          setup <- ZIO.foreach(setupTime) { measured =>
            ZIO
              .attemptBlockingInterrupt {
                val marker = directory.resolve(ScalaToolRuntime.ExecutionStartedFile)
                while process.isAlive && !Files.exists(marker) do Thread.sleep(5)
                if Files.exists(marker) then
                  measured.set(TimeUnit.NANOSECONDS.toMillis(java.lang.System.nanoTime() - launchedAt))
              }
              .ignore
              .forkScoped
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
          _ <- ZIO.foreachDiscard(setup)(_.join)
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
      val path = directory.resolve(fmgp.yallmo.tool.scalamode.ScalaToolRuntime.LogFile)
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

  private def validateRpcEndpoint(endpoint: String): Unit = {
    require(
      endpoint.length <= 2048 && endpoint.getBytes(UTF_8).length <= 2048 &&
        !endpoint.exists(Character.isISOControl),
      "RPC endpoint must be a single line of at most 2048 UTF-8 bytes"
    )
    require(
      scala.util
        .Try(new java.net.URI(endpoint))
        .toOption
        .exists(uri =>
          Set("ws", "wss").contains(uri.getScheme) && uri.getHost != null &&
            uri.getRawUserInfo == null && uri.getRawQuery == null && uri.getRawFragment == null
        ),
      "Expected a ws/wss RPC endpoint without credentials, query or fragment"
    )
  }

  // Required by the trusted ZIOApp runner even when the host requests no additional snippet libraries.
  private[tool] val RuntimeDependencies =
    Seq("dev.zio::zio:2.1.27", "dev.zio::zio-json:1.1.1", "dev.zio::zio-streams:2.1.27")

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
        s"--java-opt=-D${ScalaToolRuntime.ExecutionMarkerProperty}=${ScalaToolRuntime.ExecutionStartedFile}",
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
    "--power",
    "--scala",
    scalaVersion,
    "--jvm",
    "system",
    "--classpath",
    classpath, // Only the selected API/capability artifacts, never the application classpath.
    "--scalac-option=-language:experimental.captureChecking",
    "--scalac-option=-language:experimental.modularity",
    "--scalac-option=-color:never"
  ) ++ (dependencies ++ RuntimeDependencies).distinct.flatMap(dependency => Seq("--dependency", dependency)) ++
    Option.when(offline)(Seq("--offline", "--suppress-experimental-warning")).toSeq.flatten

  private val purePreamble =
    "import scala.language.experimental.safe\n" +
      "import fmgp.yallmo.tool.scalamode.{ScalaToolApi as tool}\n" +
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
  private[tool] def remapDiagnostics(diagnostics: String, code: String): String =
    remapDiagnosticsAt(diagnostics, code, purePreamble.count(_ == '\n'))

  private def remapDiagnosticsAt(diagnostics: String, code: String, preambleLines: Int): String = {
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
      compiled: Boolean,
      maxOutputBytes: Int,
      preambleLines: Option[Int] = None
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
    def remap(value: String): String =
      preambleLines.fold(remapDiagnostics(value, code))(lines => remapDiagnosticsAt(value, code, lines))
    val output = retain(if compiled || command.exitCode == 0 then command.output
    else remap(command.output))
    val logs = retain(remap(command.logs.mkString("\n"))).linesIterator.toVector
    command.copy(output = output, logs = logs, truncated = truncated)
  }

  private[scalamode] def servicePreamble(config: ScalamodeService.Binding): String =
    "import scala.language.experimental.safe\n" +
      "import fmgp.yallmo.tool.scalamode.{ScalaToolApi as tool}\nimport tool.*\n" +
      s"import ${config.errorType}\n" +
      "object ScalaToolSnippet {\n" +
      s"  def result(${config.parameter}: ${config.apiTypes.mkString(" & ")})(using zio.Trace, zio.CanFail[${config.errorType}]) = {\n" +
      config.constants.map { constant =>
        s"    val ${constant.parameter}: ${constant.apiTypes.head} = ${constant.apiTypes.head.stripSuffix(".type")}\n"
      }.mkString

  private[scalamode] def serviceMain(config: ScalamodeService.Binding): String =
    s"""import fmgp.yallmo.tool.scalamode.ScalaToolRuntime
       |import zio.*
       |object ScalaToolMain extends ZIOAppDefault {
       |  override val bootstrap = ScalaToolRuntime.bootstrap
       |  def run = ${config.runner} { ${config.parameter} =>
       |    ScalaToolRuntime.evaluate(ScalaToolSnippet.result(${config.parameter})(using Trace.empty, summon[CanFail[${config.errorType}]]))
       |  }
       |}
       |""".stripMargin

  // Explicit reviewed module anchors, plus only the descriptor's narrow compiled artifacts. Never fullClasspath.
  private[scalamode] def serviceClasspath(config: ScalamodeService.Binding): String = reviewedClasspath(
    config.artifacts
  )

  private def reviewedClasspath(artifacts: Seq[Class[?]]): String =
    (Seq(ScalaToolApi.getClass, classOf[FsRead], classOf[RpcConnection], classOf[fmgp.yallmo.model.YallmoError]) ++
      artifacts)
      .map(cls => Path.of(cls.getProtectionDomain.getCodeSource.getLocation.toURI).toAbsolutePath.toString)
      .distinct
      .mkString(java.io.File.pathSeparator)

  private def redactBootstrap(command: CommandOutput, bootstrap: Option[(String, RpcToken)]): CommandOutput =
    bootstrap.fold(command) { case (endpoint, token) =>
      def redact(text: String): String = {
        val secret = token.exportSecret
        val redacted = text.replace(endpoint, "<rpc endpoint>").replace(secret, "<rpc ticket>")
        // Capture can end in the middle of a ticket. Never return that retained prefix either.
        val prefix = if command.truncated then
          (1 until secret.length).reverse.find(size => redacted.endsWith(secret.take(size)))
        else None
        prefix.fold(redacted)(size => redacted.dropRight(size) + "<rpc ticket>")
      }
      command.copy(output = redact(command.output), logs = redact(command.logs.mkString("\n")).linesIterator.toVector)
    }

  // Fail closed for trusted-source diagnostics, including source excerpts and compiler explanations.
  private def hideSetupDiagnostics(
      command: CommandOutput,
      code: String,
      preambleLines: Int,
      compiled: Boolean
  ): CommandOutput = {
    val text = (command.output +: command.logs).mkString("\n")
    val positions = "(?:[A-Za-z0-9_]+)\\.(?:scala|sc|java):(\\d+)".r.findAllMatchIn(text).toList
    val lastBody = preambleLines + code.split("\n", -1).length
    val setupFailure = positions.exists(position =>
      !position.matched.startsWith("Snippet.scala:") ||
        position.group(1).toIntOption.forall(number => number <= preambleLines || number > lastBody + 3)
    )
    val sourceExcerpt = text.linesIterator.exists(line => sourceGutter.findPrefixMatchOf(line).nonEmpty)
    if setupFailure && !compiled then
      command.copy(output = "Trusted Scalamode setup failed; contact the host.", logs = Vector.empty)
    else if setupFailure && sourceExcerpt then
      // Successful compilation can still emit warnings with privileged source excerpts, including during `run`.
      val outputHasSource = command.output.linesIterator.exists(line => sourceGutter.findPrefixMatchOf(line).nonEmpty)
      command.copy(
        output = if outputHasSource then "Trusted Scalamode setup diagnostics were hidden; contact the host."
        else command.output,
        logs = Vector("Trusted Scalamode setup diagnostics were hidden; contact the host.")
      )
    else command
  }

  // Pure runners also need the API's reviewed signature dependencies (not core/server or the application classpath).
  // Classpath presence is not authority: safe-mode checks still reject acquiring services or ambient filesystem access.
  private[tool] def apiClasspath: String = reviewedClasspath(Seq.empty)

  // ZIOApp owns the runtime, failure reporting and logger scope; stdout is only the returned value.
  private val main = """import fmgp.yallmo.tool.scalamode.ScalaToolRuntime
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
