package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.tool.*
import fmgp.yallmo.orchestration.ExternalFunctionResult
import fmgp.rpc.*
import fmgp.yallmo.fs.FsRead
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Binding-specific generated sources and submitted-code positions shared by both compiler engines. */
private[scalamode] final case class ScalamodeProgram(code: String, binding: Option[ScalamodeService.Binding]) {
  import ScalamodeProgram.*
  val preambleLines = binding.map(config => servicePreamble(config).count(_ == '\n'))
  def snippetSource: String = binding.fold(snippet(code))(config => servicePreamble(config) + code + "\n  }\n}\n")
  def mainSource: String = binding.fold(main)(serviceMain)
  def compilationDiagnostics(command: CommandOutput, compiled: Boolean): CommandOutput = {
    // Scala CLI colours its own messages even with scalac's -color:never. Diagnostics are displayed as plain text.
    val plain = command.copy(
      output = stripAnsiStyle(command.output),
      logs = command.logs.map(stripAnsiStyle)
    )
    preambleLines.fold(plain)(lines => hideSetupDiagnostics(plain, code, lines, compiled))
  }
  def runtimeResult(
      compilation: CommandOutput,
      runtime: CommandOutput,
      fileLogs: (Vector[String], Boolean)
  ): CommandOutput = {
    // Successful stdout is a returned value, not trusted generated-source diagnostics.
    val diagnostics = compilationDiagnostics(runtime.copy(output = "", logs = runtime.logs ++ fileLogs._1), true)
    runtime.copy(
      logs = compilation.logs ++ compilation.output.linesIterator.toVector ++ diagnostics.logs,
      truncated = compilation.truncated || runtime.truncated || fileLogs._2
    )
  }
  def finish(
      command: CommandOutput,
      compiled: Boolean,
      budget: Int,
      bootstrap: Option[(String, RpcToken)]
  ): ExternalFunctionResult.ScalaMode =
    result(displayDiagnostics(redactBootstrap(command, bootstrap), code, compiled, budget, preambleLines), compiled)
}

private[scalamode] object ScalamodeProgram {
  import ScalaCodeExecutor.ScalaVersion
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

  private[scalamode] def validateRpcEndpoint(endpoint: String): Unit = {
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
  private[scalamode] val JvmTransportDependencies = Seq(
    "dev.zio::zio-http:3.11.6",
    "org.bouncycastle:bcprov-jdk18on:1.86",
    "org.bouncycastle:bcpkix-jdk18on:1.86",
    "org.bouncycastle:bcutil-jdk18on:1.86"
  )

  private[tool] val RuntimeDependencies =
    Seq("dev.zio::zio:2.1.27", "dev.zio::zio-json:1.1.3", "dev.zio::zio-streams:2.1.27")

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
      classpath: String,
      runtimeDependencies: Seq[String] = RuntimeDependencies
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
  ) ++ (dependencies ++ runtimeDependencies).distinct.flatMap(dependency => Seq("--dependency", dependency)) ++
    Option.when(offline)(Seq("--offline", "--suppress-experimental-warning")).toSeq.flatten

  private[scalamode] def nodeCompileArguments(
      executable: String,
      offline: Boolean,
      classpath: String,
      dependencies: Seq[String] = Seq.empty
  ): Seq[String] =
    Seq(executable, "compile", ".", "--print-class-path") ++
      compilerArguments(
        ScalaVersion,
        offline,
        dependencies,
        classpath,
        Seq("dev.zio::zio::2.1.27", "dev.zio::zio-json::1.1.3", "dev.zio::zio-streams::2.1.27")
      ) ++ Seq(
        "--js",
        "--js-version",
        "1.22.0",
        "--js-cli-version",
        "1.22.0.1",
        "--js-module-kind",
        "commonjs",
        "--js-mode",
        "dev"
      )

  /** Fail closed on missing/wrong platform/compiler products. Paths are a trusted host grant, not user input; IR
    * presence is a provisioning check, not artifact authenticity verification.
    */
  private[scalamode] def nodeClasspath(artifacts: Seq[Path], scalaVersion: String): String = {
    require(scalaVersion == ScalaVersion, s"Node backend requires Scala $ScalaVersion")
    val names = Seq("scalacode-api", "filesystem", "rpc", "model")
      .map(name => s"yallmo-${name}_sjs1_3-2.0.0-SNAPSHOT.jar")
      .toSet
    require(
      artifacts.size == names.size && artifacts.map(_.getFileName.toString).toSet == names,
      "Expected exactly the reviewed scalacode-api/filesystem/rpc/model Scala.js packageBin products"
    )
    artifacts.foreach { path =>
      require(Files.isRegularFile(path), s"Missing reviewed Scala.js artifact: $path")
      val jar = new java.util.jar.JarFile(path.toFile)
      try require(jar.entries().asScala.exists(_.getName.endsWith(".sjsir")), s"Missing Scala.js IR: $path")
      finally jar.close()
      require(
        path.toAbsolutePath.iterator().asScala.exists(_.toString == s"scala-$ScalaVersion"),
        s"Expected Scala $ScalaVersion package path: $path"
      )
    }
    artifacts.map(_.toAbsolutePath.toString).mkString(java.io.File.pathSeparator)
  }

  def nodeDependencies(dependencies: Seq[String], service: Option[ScalamodeService.Binding]): Seq[String] = {
    val builtins = RuntimeDependencies.map(value => value -> value.replaceAll(":([^:]+)$", "::$1")).toMap
    val reviewed = builtins ++ service.toSeq.flatMap(_.products.scalaJsDependencies)
    dependencies.distinct.map(value =>
      reviewed.getOrElse(
        value,
        throw new UnsupportedOperationException(
          s"Unsupported Scala.js dependency grant: $value; provide a reviewed platform mapping"
        )
      )
    )
  }

  def nodeServiceClasspath(base: String, service: Option[ScalamodeService.Binding]): String = {
    val extra = service.toSeq.flatMap { binding =>
      require(
        binding.products.jvm.isEmpty || binding.products.scalaJs.nonEmpty,
        "Custom Scalamode service requires reviewed Scala.js products; JVM Class anchors cannot locate Scala.js IR"
      )
      binding.products.scalaJs.toSeq.flatten.map { value =>
        val path = Path.of(value).toAbsolutePath
        require(Files.isRegularFile(path), s"Missing reviewed Scala.js service artifact: $path")
        require(
          !path.getFileName.toString.matches(".*yallmo-(core|server).*"),
          "Application artifacts are not snippet libraries"
        )
        val jar = new java.util.jar.JarFile(path.toFile)
        try require(jar.entries().asScala.exists(_.getName.endsWith(".sjsir")), s"Missing Scala.js IR: $path")
        finally jar.close()
        path.toString
      }
    }
    (base +: extra).mkString(java.io.File.pathSeparator)
  }

  // Keep trusted declarations at column zero: submitted code starts there too. Indenting the submitted code would
  // change multiline string values and require column/caret remapping; an indented wrapper causes spurious warnings.
  private val purePreamble =
    "import scala.language.experimental.safe\n" +
      "import fmgp.yallmo.tool.scalamode.{ScalaToolApi as tool}\n" +
      "object ScalaToolSnippet {\nimport tool.*\ndef result(using zio.Trace) = {\n"

  private val ansiStyle = "\u001b\\[[0-9;]*m".r
  private def stripAnsiStyle(value: String): String = ansiStyle.replaceAllIn(value, "")

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
      s"def result(${config.parameter}: ${config.apiTypes.mkString(" & ")})(using zio.Trace, zio.CanFail[${config.errorType}]) = {\n" +
      config.constants.map { constant =>
        s"val ${constant.parameter}: ${constant.apiTypes.head} = ${constant.apiTypes.head.stripSuffix(".type")}\n"
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
    config.products.jvm
  )

  private def reviewedClasspath(artifacts: Seq[Class[?]]): String =
    (Seq(ScalaToolApi.getClass, classOf[FsRead], classOf[RpcConnection], classOf[fmgp.yallmo.model.YallmoError]) ++
      artifacts)
      .map(cls => Path.of(cls.getProtectionDomain.getCodeSource.getLocation.toURI).toAbsolutePath.toString)
      .distinct
      .mkString(java.io.File.pathSeparator)

  private[scalamode] def redactBootstrap(command: CommandOutput, bootstrap: Option[(String, RpcToken)]): CommandOutput =
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
  private[scalamode] def hideSetupDiagnostics(
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
  private[scalamode] val main = """import fmgp.yallmo.tool.scalamode.ScalaToolRuntime
    |import zio.*
    |object ScalaToolMain extends ZIOAppDefault {
    |  override val bootstrap = ScalaToolRuntime.bootstrap
    |  def run = ScalaToolRuntime.evaluate(ScalaToolSnippet.result).flatMap(value => Console.printLine(value))
    |}
    |""".stripMargin

}
