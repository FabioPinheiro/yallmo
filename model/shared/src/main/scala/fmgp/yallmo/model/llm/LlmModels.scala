package fmgp.yallmo.model.llm

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.tool.FunctionKey
import zio.*

/** Provider-neutral schema for a function tool exposed to a language model. */
enum ToolSchemaType {
  case String
  case Number
  case Integer
  case Boolean
  case Object
  case Array
}

final case class LlmToolParameter(
    name: String,
    description: String,
    schemaType: ToolSchemaType,
    required: Boolean = false
)

final case class LlmToolDefinition(
    name: FunctionKey,
    description: String,
    parameters: Chunk[LlmToolParameter] = Chunk.empty
) {
  def validate: Either[String, Unit] =
    if name.value.trim.isEmpty then Left("Tool name must not be empty")
    else if description.trim.isEmpty then Left(s"Tool '$name' description must not be empty")
    else if parameters.exists(_.name.trim.isEmpty) then Left(s"Tool '$name' contains a parameter with an empty name")
    else Right(())
}

final case class LlmToolCall(
    id: Option[String],
    name: FunctionKey,
    argsJson: String
)

final case class LlmToolResult(
    name: FunctionKey,
    responseJson: String
)

final case class LlmTool[-R](
    definition: LlmToolDefinition,
    run: LlmToolCall => ZIO[R, YallmoError, LlmToolResult]
)
