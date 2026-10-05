package fmgp.yallmo.tool

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.model.llm.JsonSchema
import zio.*

import java.nio.file.Path
import fmgp.yallmo.orchestration.{AgentToolDefinition, ExternalToolCall, ExternalToolResult}

/** Reads a UTF-8 file from a path resolved against one configured workspace directory. */
final class ReadFileTool(workspace: Path = Path.of("").toAbsolutePath.normalize)
    extends AgentTool[WorkspaceFileSystem] {
  override val definition: AgentToolDefinition = ReadFileTool.definition

  override def execute(call: ExternalToolCall): ZIO[WorkspaceFileSystem, YallmoError, ExternalToolResult] =
    FileSystemTools.resolve(workspace, call).flatMap { path =>
      WorkspaceFileSystem
        .readUtf8(path)
        .mapError(error => YallmoError.ToolExecutionFailed(definition.key, error.getMessage, error))
        .map(ExternalToolResult.apply)
    }
}

object ReadFileTool {
  val definition: AgentToolDefinition = AgentToolDefinition(
    key = "readFile",
    description = "Read a UTF-8 file at a path resolved against the workspace; parent traversal is allowed.",
    inputSchema = FileSystemTools.pathSchema
  )
}

/** Lists up to 200 direct entries at a path resolved against one configured workspace directory. */
final class ListFilesTool(workspace: Path = Path.of("").toAbsolutePath.normalize)
    extends AgentTool[WorkspaceFileSystem] {
  override val definition: AgentToolDefinition = ListFilesTool.definition

  override def execute(call: ExternalToolCall): ZIO[WorkspaceFileSystem, YallmoError, ExternalToolResult] =
    FileSystemTools.resolve(workspace, call).flatMap { path =>
      WorkspaceFileSystem
        .list(path)
        .mapError(error => YallmoError.ToolExecutionFailed(definition.key, error.getMessage, error))
        .map(_.mkString("\n"))
        .map(ExternalToolResult.apply)
    }
}

object ListFilesTool {
  val definition: AgentToolDefinition = AgentToolDefinition(
    key = "listFiles",
    description = "List direct entries at a path resolved against the workspace; parent traversal is allowed.",
    inputSchema = FileSystemTools.pathSchema
  )
}

private object FileSystemTools {
  val pathSchema: JsonSchema.ObjectSchema = JsonSchema.ObjectSchema(
    Map("path" -> JsonSchema.StringSchema(Some("Path resolved against the workspace; ../ traversal is allowed"))),
    required = Set("path"),
    additionalProperties = false
  )

  def resolve(workspace: Path, call: ExternalToolCall): IO[YallmoError, Path] =
    ZIO
      .fromEither(
        scala.util.Try(ujson.read(call.argumentsJson)("path").str).toEither.left.map(_.getMessage)
      )
      .mapError(YallmoError.InvalidToolArguments(call.toolKey, _))
      .map(workspace.resolve(_).normalize)
}
