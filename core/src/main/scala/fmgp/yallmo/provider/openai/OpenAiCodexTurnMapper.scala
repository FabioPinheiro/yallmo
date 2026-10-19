package fmgp.yallmo.provider.openai

import fmgp.yallmo.model.*
import fmgp.yallmo.provider.JsonSchemaEncoder
import fmgp.yallmo.model.llm.*
import zio.Chunk

/** Maps the canonical turn protocol to the OpenAI Codex Responses request and response item formats. */
private[openai] object OpenAiCodexTurnMapper {
  val providerId: ProviderId = ProviderId.unsafe("openai-codex-responses")

  private[openai] def validateRequest(request: AgentTurnRequest): Either[String, AgentTurnRequest] =
    request.validate.flatMap(validated =>
      Either.cond(
        !validated.input.exists(_.isInstanceOf[AudioInput]),
        validated,
        "OpenAI Codex does not support audio input"
      )
    )

  private[openai] def requestBody(turnRequest: AgentTurnRequest): Either[String, ujson.Obj] =
    validateRequest(turnRequest).flatMap { validated =>
      validated.input.lastOption match {
        // AgentTurnRequest requires the current instructions last, not at the start of history.
        case Some(MessagePayload.Instructions(instructions)) =>
          OpenAiCodexTurnMapper.input(validated.input.dropRight(1)).map { input =>
            val body = ujson.Obj(
              "model" -> validated.model,
              "store" -> false,
              "stream" -> true,
              "include" -> ujson.Arr("reasoning.encrypted_content"),
              "instructions" -> instructions,
              "input" -> input,
              "tool_choice" -> toolChoiceJson(validated.toolChoice),
              "parallel_tool_calls" -> validated.parallelToolCalls
            )
            validated.options.thinkingLevel
              .foreach(level => body("reasoning") = ujson.Obj("effort" -> OpenAiCodexClient.thinkingEffort(level)))
            if validated.tools.nonEmpty then
              body("tools") = ujson.Arr.from(validated.tools.map(OpenAiCodexTurnMapper.tool))
            body
          }
        case _ => Left("the final input item must be instructions")
      }
    }

  private def toolChoiceJson(choice: ToolChoice): ujson.Value =
    choice match
      case ToolChoice.Auto           => ujson.Str("auto")
      case ToolChoice.None           => ujson.Str("none")
      case ToolChoice.Required       => ujson.Str("required")
      case ToolChoice.Function(name) => ujson.Obj("type" -> "function", "name" -> name)

  /** Unsupported modalities are mapping errors, not thrown exceptions or omitted input. */
  def input(items: Chunk[MessagePayload]): Either[String, ujson.Arr] =
    items
      .foldLeft[Either[String, Chunk[ujson.Value]]](Right(Chunk.empty)) { (result, item) =>
        for {
          mapped <- result
          next <- toInputItem(item)
        } yield mapped ++ next
      }
      .map(ujson.Arr.from(_))

  def tool(definition: FunctionToolDefinition): ujson.Obj =
    ujson.Obj(
      "type" -> "function",
      "name" -> definition.name,
      "description" -> definition.description,
      "parameters" -> JsonSchemaEncoder.encode(definition.inputSchema),
      "strict" -> false
    )

  def response(response: OpenAiCodexResponse): Either[String, AgentTurnResponse] =
    response.output
      .foldLeft[Either[String, Chunk[MessagePayload]]](Right(Chunk.empty)) { (result, item) =>
        result.flatMap(items => toOutputItem(item).map(_.fold(items)(items :+ _)))
      }
      .map { output =>
        val hasToolCalls = output.exists(_.isInstanceOf[MessagePayload.FunctionCallRequest])
        AgentTurnResponse(
          output = output,
          stopReason = stopReason(response.status, hasToolCalls),
          responseId = response.responseId
        )
      }

  private def stopReason(status: Option[String], hasCalls: Boolean): LlmStopReason =
    status.map(_.toLowerCase) match
      case Some("completed") if hasCalls => LlmStopReason.ToolUse
      case Some("completed")             => LlmStopReason.Stop
      case Some("incomplete")            => LlmStopReason.Length
      case Some("failed")                => LlmStopReason.Unknown("failed")
      case Some(value)                   => LlmStopReason.Unknown(value)
      case None if hasCalls              => LlmStopReason.ToolUse
      case None                          => LlmStopReason.Stop

  private def toInputItem(item: MessagePayload): Either[String, Chunk[ujson.Value]] =
    item match
      case _: AudioInput                  => Left("OpenAI Codex does not support audio input")
      case MessagePayload.User(text)      => Right(Chunk(userMessage(text)))
      case MessagePayload.Assistant(text) =>
        Right(
          Chunk(
            ujson.Obj(
              "type" -> "message",
              "role" -> "assistant",
              "content" -> ujson.Arr(ujson.Obj("type" -> "output_text", "text" -> text, "annotations" -> ujson.Arr())),
              "status" -> "completed"
            )
          )
        )
      case MessagePayload.Context(label, text) => Right(Chunk(userMessage(s"Context [$label]:\n$text")))
      case item: MessagePayload.RagResult      => Right(Chunk(userMessage(s"Context:\n${item.contentStr}")))
      case item: MessagePayload.ToolResult     => Right(Chunk(userMessage(s"Context:\n${item.contentStr}")))
      case MessagePayload.Instructions(text)   =>
        Right(
          Chunk(
            ujson.Obj(
              "type" -> "message",
              "role" -> "developer",
              "content" -> ujson.Arr(ujson.Obj("type" -> "input_text", "text" -> text))
            )
          )
        )
      case MessagePayload.Reasoning(summary, continuation) if continuation.provider == providerId =>
        Right(
          Chunk(
            ujson.Obj.from(
              Chunk(
                Some("type" -> ujson.Str("reasoning")),
                continuation.itemId.map("id" -> ujson.Str(_)),
                Some(
                  "summary" -> ujson.Arr.from(summary.map(text => ujson.Obj("type" -> "summary_text", "text" -> text)))
                ),
                Some("content" -> ujson.Arr()),
                Some("encrypted_content" -> ujson.Str(continuation.encryptedContent))
              ).flatten
            )
          )
        )
      case MessagePayload.Reasoning(_, _)           => Right(Chunk.empty)
      case MessagePayload.FunctionCallRequest(call) =>
        Right(
          Chunk(
            ujson.Obj.from(
              Chunk(
                Some("type" -> ujson.Str("function_call")),
                Some("call_id" -> ujson.Str(call.id.value)),
                Some("name" -> ujson.Str(call.name)),
                Some("arguments" -> ujson.Str(call.argumentsJson)),
                call.providerItemId.map("id" -> ujson.Str(_))
              ).flatten
            )
          )
        )
      case MessagePayload.FunctionCallOutput(output) =>
        Right(
          Chunk(
            ujson.Obj(
              "type" -> "function_call_output",
              "call_id" -> output.callId.value,
              "output" -> output.output
            )
          )
        )

  private def toOutputItem(value: ujson.Value): Either[String, Option[MessagePayload]] =
    value.objOpt.toRight("OpenAI Codex output item is not an object").flatMap { item =>
      item.get("type").flatMap(_.strOpt).toRight("OpenAI Codex output item has no type").flatMap {
        case "function_call" =>
          for {
            callId <- requiredString(item, "call_id")
            name <- requiredString(item, "name")
            arguments = item.get("arguments").flatMap(_.strOpt).getOrElse("{}")
            id <- FunctionCallId.from(callId)
          } yield Some(
            MessagePayload.FunctionCallRequest(FunctionCall(id, name, arguments, item.get("id").flatMap(_.strOpt)))
          )
        case "reasoning" =>
          item.get("encrypted_content").flatMap(_.strOpt) match
            case None                   => Right(None)
            case Some(encryptedContent) =>
              val summary = item
                .get("summary")
                .flatMap(_.arrOpt)
                .flatMap(
                  _.value
                    .collectFirst {
                      case summaryItem
                          if summaryItem.objOpt.flatMap(_.get("type")).flatMap(_.strOpt).contains("summary_text") =>
                        summaryItem.objOpt.flatMap(_.get("text")).flatMap(_.strOpt)
                    }
                    .flatten
                )
              Right(
                Some(
                  MessagePayload.Reasoning(
                    summary,
                    ProviderContinuation(providerId, item.get("id").flatMap(_.strOpt), encryptedContent)
                  )
                )
              )
        case "message" =>
          val text = item
            .get("content")
            .flatMap(_.arrOpt)
            .toVector
            .flatMap(_.value)
            .flatMap(_.objOpt.toVector)
            .collect {
              case content if content.get("type").flatMap(_.strOpt).contains("output_text") =>
                content.get("text").flatMap(_.strOpt).getOrElse("")
            }
            .mkString
            .trim
          Right(Option.when(text.nonEmpty)(MessagePayload.Assistant(text)))
        case _ => Right(None)
      }
    }

  private def userMessage(text: String): ujson.Obj =
    ujson.Obj("role" -> "user", "content" -> ujson.Arr(ujson.Obj("type" -> "input_text", "text" -> text)))

  private def requiredString(item: scala.collection.Map[String, ujson.Value], field: String): Either[String, String] =
    item.get(field).flatMap(_.strOpt).filter(_.nonEmpty).toRight(s"OpenAI Codex $field is missing")

}
