package fmgp.yallmo.webharness

import fmgp.yallmo.tool.{BashToolDefinition, FunctionKey}
import zio.json.*
import zio.json.ast.Json

/** Decode known code/command-only contracts; unknown or malformed calls keep their JSON display. */
private[webharness] object ToolCallContent {
  def scalaSource(role: String, toolName: Option[FunctionKey], arguments: String): Option[String] =
    singleStringArgument(role, toolName, arguments, FunctionKey.scalamode, "code")

  def bashCommand(role: String, toolName: Option[FunctionKey], arguments: String): Option[String] =
    singleStringArgument(role, toolName, arguments, BashToolDefinition.Execute, "command")

  private def singleStringArgument(
      role: String,
      toolName: Option[FunctionKey],
      arguments: String,
      expectedTool: FunctionKey,
      field: String
  ): Option[String] =
    Option.when(role == "tool_call" && toolName.contains(expectedTool))(arguments).flatMap { text =>
      text
        .fromJson[Json]
        .toOption
        .collect {
          case Json.Obj(fields) if fields.size == 1 && fields.head._1 == field => fields.head._2
        }
        .collect { case Json.Str(source) => source }
    }
}
