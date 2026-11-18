package fmgp.yallmo.tool.scalamode

import fmgp.yallmo.fs.{FsRead, FsWrite}
import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.tool.*
import zio.*

/** Explicit resolution-time utilities; neither stage installs listeners or consults a global catalog. */
object AgentToolWirings {

  /** Default agent services: hello plus the rooted filesystem when both native reads are permitted. Supplying FsWrite
    * intentionally grants rooted mutations with that read/list-qualified service; trusted wiring owns the derived API,
    * without a separate write allowlist. No listener or filesystem operation runs at resolution.
    */
  def withDefaults(
      executor: ScalaCodeExecutor,
      filesystem: Option[FsRead] = None,
      filesystemWrite: Option[FsWrite] = None
  ): ToolWiring = tools => {
    val permitted = tools.filter(_.name == ToolKey.Filesystem).flatMap(_.functions.map(_.definition.name)).toSet
    filesystem match {
      case Some(read)
          if Set(FilesystemToolDefinition.ReadFile, FilesystemToolDefinition.ListFiles).subsetOf(permitted) =>
        withServiceInternal(FilesystemService.default(read, filesystemWrite), executor, 45.seconds, 64 * 1024, true)(
          tools
        )
      case _ => withHelloWorld(executor)(tools)
    }
  }

  /** Bind the pure greeting inside Scalamode, never as a separate native tool. Missing Scalamode and existing custom
    * service bindings are preserved. Reapplying the wiring is an identity operation.
    */
  def withHelloWorld(executor: ScalaCodeExecutor): ToolWiring =
    withServiceInternal(HelloWorldService, executor, 45.seconds, 64 * 1024, true)

  /** Rebind only explicitly integrated built-in defaults, retaining guards. Opaque/default-looking custom closures are
    * not replaceable. Arbitrary service bindings do not opt into the manual pure playground.
    */
  def withService(
      service: ScalamodeService,
      executor: ScalaCodeExecutor,
      timeout: Duration = 45.seconds,
      maxOutputBytes: Int = 64 * 1024
  ): ToolWiring = withServiceInternal(service, executor, timeout, maxOutputBytes, false)

  private def withServiceInternal(
      service: ScalamodeService,
      executor: ScalaCodeExecutor,
      timeout: Duration,
      maxOutputBytes: Int,
      purePlayground: Boolean
  ): ToolWiring = tools => {
    val bound = ScalaTool.withServices(service, timeout, maxOutputBytes)
    val function = new ScalamodeFunction[Any] {
      val definition = bound.definition
      def execute(call: ExternalFunctionCall) = bound.execute(call).provideEnvironment(ZEnvironment(executor))
      override def manualScalamode =
        if purePlayground then new ScalaTool(timeout, maxOutputBytes).manualScalamode
        else None
    }
    val replacements = tools
      .filter(_.name == ToolKey.Scalamode)
      .flatMap(_.functions)
      .flatMap(existing =>
        ScalamodeFunction.from(existing).flatMap(_.bindDefaultScalamode(function)).map(existing -> _)
      )
      .toMap
    if replacements.isEmpty then ZIO.succeed(tools)
    else
      ZIO.succeed(tools.map { tool =>
        if tool.name != ToolKey.Scalamode then tool
        else {
          val functions = tool.functions.map(existing => replacements.getOrElse(existing, existing))
          AgentTool(ToolDefinition(tool.name, functions.map(_.definition).toSet), functions)
        }
      })
  }

  /** Replace only the selected default Scalamode definition with exact already-permitted native read/list bindings.
    * Missing tools/reads and custom Scalamode bindings are identity operations. Trusted captured code remains
    * authoritative; filtering cannot inspect hidden capabilities in closures. The caller owns the RPC listener.
    */
  def withFilesystem(
      executor: ScalaCodeExecutor,
      route: ScalamodePlatform.RpcRoute,
      endpoint: String,
      timeout: Duration = 45.seconds,
      maxOutputBytes: Int = 64 * 1024
  ): ToolWiring = tools => {
    val reads = tools
      .filter(_.name == ToolKey.Filesystem)
      .flatMap(_.functions)
      .filter(function =>
        Set(FilesystemToolDefinition.ReadFile, FilesystemToolDefinition.ListFiles).contains(function.definition.name)
      )
    if reads.isEmpty then ZIO.succeed(tools)
    else withService(readService(reads, route, endpoint), executor, timeout, maxOutputBytes)(tools)
  }

  /** Opt into structured FsRead and, only when explicitly supplied, FsWrite-backed services. Both native read/list
    * functions and default Scalamode must survive filtering. Partial native grants continue to use withFilesystem.
    *
    * Unlike withFilesystem this grants the supplied raw capabilities, not the native functions' captured behavior. The
    * trusted caller must supply the intended agent root and explicitly authorize any write capability.
    */
  def withFilesystemServices(
      read: FsRead,
      write: Option[FsWrite],
      executor: ScalaCodeExecutor,
      route: ScalamodePlatform.RpcRoute,
      endpoint: String,
      timeout: Duration = 45.seconds,
      maxOutputBytes: Int = 64 * 1024
  ): ToolWiring = tools => {
    val permitted = tools.filter(_.name == ToolKey.Filesystem).flatMap(_.functions.map(_.definition.name)).toSet
    if !Set(FilesystemToolDefinition.ReadFile, FilesystemToolDefinition.ListFiles).subsetOf(permitted) then
      ZIO.succeed(tools)
    else {
      val service = write match {
        case None        => FilesystemService.read(read, route, endpoint)
        case Some(value) => FilesystemService.readWrite(read, value, route, endpoint)
      }
      withService(service, executor, timeout, maxOutputBytes)(tools)
    }
  }

  private[scalamode] def readService(
      reads: Seq[AgentFunction[Any]],
      route: ScalamodePlatform.RpcRoute,
      endpoint: String
  ): ScalamodeService = {
    val selected = reads.map(function => function.definition.name -> function).toMap
    val canRead = selected.contains(FilesystemToolDefinition.ReadFile)
    val canList = selected.contains(FilesystemToolDefinition.ListFiles)
    def invoke(key: FunctionKey, path: String): IO[ReadToolError, String] =
      selected.get(key) match {
        case None           => ZIO.fail(ReadToolError("Denied", s"Operation ${key.value} is not permitted"))
        case Some(function) =>
          val args = ujson.write(ujson.Obj("path" -> path))
          (ZIO
            .fromEither(FunctionToolDefinition.validateArgument(args, function.definition.inputSchema))
            .mapError(YallmoError.InvalidToolArguments(key, _)) *>
            function.execute(ExternalFunctionCall(key, args)))
            .map(_.text)
            .mapError(error => ReadToolError(error.getClass.getSimpleName, error.getMessage))
      }
    val api = new NativeReadTools {
      def readFile(path: String) = invoke(FilesystemToolDefinition.ReadFile, path)
      def listFiles(path: String) = invoke(FilesystemToolDefinition.ListFiles, path)
    }
    val (apiView, runnerMethod) = (canRead, canList) match {
      case (true, true)   => "NativeReadTools" -> "run"
      case (true, false)  => "NativeReadFile" -> "runReadFile"
      case (false, true)  => "NativeListFiles" -> "runListFiles"
      case (false, false) => "scala.Unit" -> "runPure"
    }
    new ScalamodeService {
      override val scalaApi: Option[String] = Some(
        (Seq(
          "package fmgp.yallmo.tool.scalamode",
          "",
          "import zio.IO",
          "",
          "final case class ReadToolError(kind: String, message: String)",
          ""
        ) ++
          Option.when(canRead)(
            "trait NativeReadFile {\n  def readFile(path: String): IO[ReadToolError, String]\n}"
          ) ++
          Option.when(canList)(
            "trait NativeListFiles {\n  def listFiles(path: String): IO[ReadToolError, String]\n}"
          ) ++
          Option.when(canRead && canList)("trait NativeReadTools extends NativeReadFile with NativeListFiles") ++
          Seq("", s"// Available in the snippet as: reads: ${if canRead || canList then apiView else "Unit"}"))
          .mkString("\n")
      )
      val apiDocs = (Seq(
        "Plain values and ZIO[Any, E, A] effects are supported; no environment is provided.",
        "ReadToolError(kind, message) preserves native failure kind/message; infrastructure errors use RpcError.",
        "Paths and output follow the exact selected native tools. Results are tool text, not decoded JSON or structured listings."
      ) ++ reads.map(function =>
        s"reads.${function.definition.name.value}(path: String): ZIO[Any, ReadToolError, String]. ${function.definition.description} " +
          (if function.definition.name == FilesystemToolDefinition.ReadFile then "Normally UTF-8 file contents."
           else "Normally newline-separated direct entry names.")
      )).mkString(" ")
      override val rpcBinding =
        Option.when(canRead || canList)(
          ScalamodeService.RpcBinding(endpoint, route, NativeReadToolsRuntime.contract.bind(api))
        )
      val binding = ScalamodeService.Binding(
        parameter = "reads",
        apiTypes = Seq(if canRead || canList then "fmgp.yallmo.tool.scalamode." + apiView else apiView),
        runner = "fmgp.yallmo.tool.scalamode." +
          (if canRead || canList then "NativeReadToolsRuntime." else "ScalaToolRuntime.") + runnerMethod,
        errorType = "fmgp.yallmo.tool.scalamode.ReadToolError"
      )
    }
  }
}
