package fmgp.yallmo.model.llm

import fmgp.yallmo.model.{FunctionToolDefinition, MessagePayload, ToolChoice}
import zio.Chunk

/** Provider-neutral stop reason for a completed model turn. */
enum LlmStopReason {
  case Stop
  case ToolUse
  case Length
  case ContentFilter
  case Unknown(value: String)
}

/** One fully assembled model request. Provider adapters only serialize this value; they do not execute tools. */
final case class AgentTurnRequest(
    model: String,
    input: Chunk[MessagePayload],
    tools: Chunk[FunctionToolDefinition] = Chunk.empty,
    toolChoice: ToolChoice = ToolChoice.Auto,
    parallelToolCalls: Boolean = false,
    options: ProviderGenerateOptions = ProviderGenerateOptions()
) {
  def validate: Either[String, AgentTurnRequest] =
    val duplicateToolNames =
      tools.map(_.name).groupBy(identity).collect { case (name, names) if names.size > 1 => name }
    for {
      _ <- Either.cond(model.trim.nonEmpty, (), "model must not be blank")
      _ <- input.lastOption match {
        case Some(MessagePayload.Instructions(text)) if text.trim.nonEmpty => Right(())
        case Some(MessagePayload.Instructions(_))                          => Left("instructions must not be blank")
        case _ => Left("the final input item must be instructions")
      }
      _ <- Either.cond(
        duplicateToolNames.isEmpty,
        (),
        s"duplicate function tools: ${duplicateToolNames.mkString(", ")}"
      )
      _ <- tools.foldLeft[Either[String, Unit]](Right(()))((result, tool) =>
        result.flatMap(_ => tool.validate.map(_ => ()))
      )
      _ <- options.validate
    } yield this
}

/** One parsed provider response. [[output]] preserves the provider's response ordering. */
final case class AgentTurnResponse(
    output: Chunk[MessagePayload],
    stopReason: LlmStopReason,
    responseId: Option[String] = None
)
