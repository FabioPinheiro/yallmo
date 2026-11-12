package fmgp.yallmo.tool

import fmgp.yallmo.model.YallmoError

import fmgp.yallmo.model.tool.FilesystemToolDefinition

import fmgp.yallmo.orchestration.*
import zio.*

import fmgp.yallmo.fs.{FsError, FsRead}
import fmgp.yallmo.orchestration.{ExternalFunctionCall, ExternalFunctionResult}

/** Reads a UTF-8 file relative to the configured filesystem root. */
final class ReadFileTool extends AgentFunction[FsRead] {
  override val definition: FunctionToolDefinition = ReadFileTool.definition

  override def execute(call: ExternalFunctionCall): ZIO[FsRead, YallmoError, ExternalFunctionResult] =
    for {
      path <- FileSystemTools.path(call)
      contents <- ZIO.serviceWithZIO[FsRead](_.readUtf8(path)).mapError(FileSystemTools.failure(definition.name, _))
    } yield ExternalFunctionResult(contents)
}

object ReadFileTool {
  val definition: FunctionToolDefinition = FilesystemToolDefinition.ReadFileDefinition
}

/** Lists all sorted direct entries relative to the configured filesystem root. */
final class ListFilesTool extends AgentFunction[FsRead] {
  override val definition: FunctionToolDefinition = ListFilesTool.definition

  override def execute(call: ExternalFunctionCall): ZIO[FsRead, YallmoError, ExternalFunctionResult] =
    for {
      path <- FileSystemTools.path(call)
      entries <- ZIO.serviceWithZIO[FsRead](_.list(path)).mapError(FileSystemTools.failure(definition.name, _))
    } yield ExternalFunctionResult(entries.mkString("\n"))
}

object ListFilesTool {
  val definition: FunctionToolDefinition = FilesystemToolDefinition.ListFilesDefinition
}

private object FileSystemTools {
  def failure(key: FunctionKey, error: FsError): YallmoError =
    YallmoError.ToolExecutionFailed(key, error.toString, new java.io.IOException(error.toString))

  def path(call: ExternalFunctionCall): IO[YallmoError, String] =
    ZIO
      .fromEither(
        scala.util.Try(ujson.read(call.argumentsJson)("path").str).toEither.left.map(_.getMessage)
      )
      .mapError(YallmoError.InvalidToolArguments(call.functionKey, _))
}
