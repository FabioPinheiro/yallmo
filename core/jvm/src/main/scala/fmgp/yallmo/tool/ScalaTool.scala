package fmgp.yallmo.tool

import fmgp.yallmo.model.ToolKey
import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.llm.JsonSchema
import fmgp.yallmo.orchestration.{AgentTool, AgentToolDefinition, ExternalToolCall, ExternalToolResult}
import zio.*
import fmgp.rpc.RpcWebSocket
import fmgp.yallmo.fs.FsRead

/** One-shot restricted Scala evaluation through isolated Scala CLI compilation and a ZIOApp runner.
  *
  * @param dependencies
  *   Trusted host-supplied Scala CLI dependency coordinates. Replaces the defaults; an empty sequence keeps only the
  *   mandatory ZIO/ZIO JSON runner dependencies.
  */
final class ScalaTool(
    timeout: Duration = 45.seconds,
    maxOutputBytes: Int = 64 * 1024,
    dependencies: Seq[String] = ScalaTool.DefaultDependencies
) extends AgentTool[ScalaCodeExecutor] {
  require(timeout > Duration.Zero, "timeout must be positive")
  require(maxOutputBytes > 0, "maxOutputBytes must be positive")

  override val definition: AgentToolDefinition = ScalaTool.definition

  override def execute(call: ExternalToolCall): ZIO[ScalaCodeExecutor, YallmoError, ExternalToolResult] =
    ScalaTool
      .executeWith(call, definition.key) { code =>
        ZIO.serviceWithZIO[ScalaCodeExecutor](_.execute(code, timeout, maxOutputBytes, dependencies))
      }
}

object ScalaTool {

  /** Caller-owned route and selected read capability are required only by the explicitly enabled variant. */
  def filesystem(
      endpoint: String,
      timeout: Duration = 45.seconds,
      maxOutputBytes: Int = 64 * 1024
  ): AgentTool[ScalaCodeExecutor & RpcWebSocket.RpcRoute & FsRead] = {
    require(timeout > Duration.Zero, "timeout must be positive")
    require(maxOutputBytes > 0, "maxOutputBytes must be positive")
    new AgentTool[ScalaCodeExecutor & RpcWebSocket.RpcRoute & FsRead] {
      override val definition = filesystemDefinition
      override def execute(
          call: ExternalToolCall
      ): ZIO[ScalaCodeExecutor & RpcWebSocket.RpcRoute & FsRead, YallmoError, ExternalToolResult] =
        for {
          parsed <- ZIO
            .attempt {
              val args = ujson.read(call.argumentsJson).obj
              require(
                args.keySet.subsetOf(Set("code", "mode")) && args.contains("code"),
                "Expected code and optional mode"
              )
              val mode = args.get("mode").map(_.str).getOrElse("pure")
              require(mode == "pure" || mode == "filesystem", "Expected pure or filesystem mode")
              (args("code").str, mode)
            }
            .mapError(error => YallmoError.InvalidToolArguments(call.toolKey, error.getMessage))
          result <- executeWith(
            call.copy(argumentsJson = ujson.write(ujson.Obj("code" -> parsed._1))),
            definition.key
          ) { code =>
            ZIO.serviceWithZIO[ScalaCodeExecutor] { executor =>
              if parsed._2 == "filesystem" then executor.executeFilesystem(code, endpoint, timeout, maxOutputBytes)
              else executor.execute(code, timeout, maxOutputBytes)
            }
          }
        } yield result
    }
  }

  val DefaultDependencies: Seq[String] = Seq("dev.zio::zio:2.1.27")

  /** Shared argument/error boundary for the two Scala tools, independent of their execution backend. */
  private[tool] def executeWith[R, A](call: ExternalToolCall, toolKey: ToolKey)(
      execute: String => RIO[R, A]
  ): ZIO[R, YallmoError, A] =
    for {
      code <- ZIO
        .fromEither(
          scala.util
            .Try {
              val arguments = ujson.read(call.argumentsJson).obj
              require(arguments.keySet == Set("code"), "Expected only a code string")
              arguments("code").str
            }
            .toEither
            .left
            .map(_.getMessage)
        )
        .flatMap(value => ZIO.fromEither(validate(value)).as(value))
        .mapError(YallmoError.InvalidToolArguments(call.toolKey, _))
      result <- execute(code)
        .mapError(error => YallmoError.ToolExecutionFailed(toolKey, error.getMessage, error))
    } yield result

  val definition: AgentToolDefinition = AgentToolDefinition(
    key = ToolKey.Scalamode,
    description = "Evaluate a Scala block with Scala 3.10.0, offline. The last expression is returned. " +
      "Returned ZIO effects are executed by the host. Automatically imported restricted ZIO constructors: " +
      "ZIO.succeed, fail, attempt, fromEither, foreach and unit (not the full zio.ZIO companion). " +
      "Example: ZIO.succeed(1 + 1) returns 2. Trace is supplied; do not import zio.ZIO. " +
      "Trusted API, automatically imported: def api: Unit — prints Tool API available. Also available as tool.api. " +
      "No direct file, network, process, environment access or println. " +
      "No user-supplied dependencies, directives or annotations. " +
      "Returns only the value on success, or actionable compiler/runtime diagnostics on failure. " +
      "The host retains typed results, exitCode, timedOut, truncated and separate execution logs.",
    inputSchema = JsonSchema.ObjectSchema(
      Map("code" -> JsonSchema.StringSchema(Some("Scala block body; its last expression is the result"))),
      required = Set("code"),
      additionalProperties = false
    )
  )

  val filesystemDefinition: AgentToolDefinition = definition.copy(
    description = definition.description +
      " Optional mode=filesystem: code must return ZIO[Any, FsError, A], with selected read-only fs: FsRead injected. " +
      "Example: fs.readUtf8(\"notes.txt\").map(_.length). " +
      "Supports fs.list and fs.readBytes; use fold for error recovery. Trace and CanFail are supplied. " +
      "No runtime execution in code; trusted Main connects once and runs the effect. Default mode=pure is unchanged.",
    inputSchema = JsonSchema.ObjectSchema(
      Map(
        "code" -> JsonSchema.StringSchema(
          Some("Pure block, or filesystem-mode effect body returning ZIO[Any, FsError, A]")
        ),
        "mode" -> JsonSchema.StringSchema(Some("Explicit result mode; defaults to pure"), Chunk("pure", "filesystem"))
      ),
      required = Set("code"),
      additionalProperties = false
    )
  )

  // Deliberately conservative, including inside literals/comments. These checks protect compiler configuration.
  // Restricted execution relies on compiler safe mode, not an API blacklist.
  private[tool] def validate(code: String): Either[String, Unit] =
    if code.trim.isEmpty then Left("code must not be empty")
    else if code.length > 64 * 1024 then Left("code exceeds 65,536 characters")
    else if code.contains("//>") || code.contains("\\u") || code.contains('@') || code.contains('`') ||
      "\\b(language|unsafe|assumeSafe)\\b|import\\s+\\$".r.findFirstIn(code).nonEmpty
    then Left("Directives, annotations, Unicode escapes, backticks and compiler safety overrides are not supported")
    else Right(())
}
