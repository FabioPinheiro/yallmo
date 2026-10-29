package fmgp.yallmo.tool

import fmgp.yallmo.model.ToolKey
import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.model.llm.JsonSchema
import zio.*

import fmgp.yallmo.fs.{FsError, FsRead}
import fmgp.yallmo.orchestration.{AgentToolDefinition, ExternalToolCall, ExternalToolResult}

/** Reads a UTF-8 file relative to the configured filesystem root. */
final class ReadFileTool extends AgentTool[FsRead] {
  override val definition: AgentToolDefinition = ReadFileTool.definition

  override def execute(call: ExternalToolCall): ZIO[FsRead, YallmoError, ExternalToolResult] =
    for {
      path <- FileSystemTools.path(call)
      contents <- ZIO.serviceWithZIO[FsRead](_.readUtf8(path)).mapError(FileSystemTools.failure(definition.key, _))
    } yield ExternalToolResult(contents)
}

object ReadFileTool {
  val definition: AgentToolDefinition = AgentToolDefinition(
    key = ToolKey.ReadFile,
    description =
      "Read a UTF-8 file relative to the workspace; absolute paths, parent traversal and symlinks are forbidden.",
    inputSchema = FileSystemTools.pathSchema
  )
}

/** Lists all sorted direct entries relative to the configured filesystem root. */
final class ListFilesTool extends AgentTool[FsRead] {
  override val definition: AgentToolDefinition = ListFilesTool.definition

  override def execute(call: ExternalToolCall): ZIO[FsRead, YallmoError, ExternalToolResult] =
    for {
      path <- FileSystemTools.path(call)
      entries <- ZIO.serviceWithZIO[FsRead](_.list(path)).mapError(FileSystemTools.failure(definition.key, _))
    } yield ExternalToolResult(entries.mkString("\n"))
}

object ListFilesTool {
  val definition: AgentToolDefinition = AgentToolDefinition(
    key = ToolKey.ListFiles,
    description =
      "List all sorted direct entries relative to the workspace; absolute paths, parent traversal and symlinks are forbidden.",
    inputSchema = FileSystemTools.pathSchema
  )
}

private object FileSystemTools {
  def failure(key: ToolKey, error: FsError): YallmoError =
    YallmoError.ToolExecutionFailed(key, error.toString, new java.io.IOException(error.toString))

  val pathSchema: JsonSchema.ObjectSchema = JsonSchema.ObjectSchema(
    Map(
      "path" -> JsonSchema.StringSchema(
        Some("Workspace-relative path; use . for the root. Absolute paths, .. and symlinks are forbidden")
      )
    ),
    required = Set("path"),
    additionalProperties = false
  )

  def path(call: ExternalToolCall): IO[YallmoError, String] =
    ZIO
      .fromEither(
        scala.util.Try(ujson.read(call.argumentsJson)("path").str).toEither.left.map(_.getMessage)
      )
      .mapError(YallmoError.InvalidToolArguments(call.toolKey, _))
}
