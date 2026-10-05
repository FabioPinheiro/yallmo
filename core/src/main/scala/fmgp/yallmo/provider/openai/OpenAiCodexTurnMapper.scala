package fmgp.yallmo.provider.openai

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.*
import zio.Chunk

/** Maps the canonical turn protocol to the OpenAI Codex Responses request and response item formats. */
private[openai] object OpenAiCodexTurnMapper {
  val providerId: ProviderId = ProviderId.unsafe("openai-codex-responses")

  def input(items: Chunk[MessagePayload]): ujson.Arr =
    ujson.Arr.from(items.flatMap(toInputItem))

  def tool(definition: FunctionToolDefinition): ujson.Obj =
    ujson.Obj(
      "type" -> "function",
      "name" -> definition.name,
      "description" -> definition.description,
      "parameters" -> schema(definition.inputSchema),
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

  private def toInputItem(item: MessagePayload): Chunk[ujson.Value] =
    item match
      case MessagePayload.User(text)      => Chunk(userMessage(text))
      case MessagePayload.Assistant(text) =>
        Chunk(
          ujson.Obj(
            "type" -> "message",
            "role" -> "assistant",
            "content" -> ujson.Arr(ujson.Obj("type" -> "output_text", "text" -> text, "annotations" -> ujson.Arr())),
            "status" -> "completed"
          )
        )
      case MessagePayload.Context(label, text) => Chunk(userMessage(s"Context [$label]:\n$text"))
      case item: MessagePayload.RagResult      => Chunk(userMessage(s"Context:\n${item.contentStr}"))
      case item: MessagePayload.ToolResult     => Chunk(userMessage(s"Context:\n${item.contentStr}"))
      case MessagePayload.Instructions(text)   =>
        Chunk(
          ujson.Obj(
            "type" -> "message",
            "role" -> "system",
            "content" -> ujson.Arr(ujson.Obj("type" -> "input_text", "text" -> text))
          )
        )
      case MessagePayload.Reasoning(_, continuation) if continuation.provider == providerId =>
        Chunk(
          ujson.Obj.from(
            Chunk(
              Some("type" -> ujson.Str("reasoning")),
              continuation.itemId.map("id" -> ujson.Str(_)),
              Some("content" -> ujson.Arr()),
              Some("encrypted_content" -> ujson.Str(continuation.encryptedContent))
            ).flatten
          )
        )
      case MessagePayload.Reasoning(_, _)           => Chunk.empty
      case MessagePayload.FunctionCallRequest(call) =>
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
      case MessagePayload.FunctionCallOutput(output) =>
        Chunk(
          ujson.Obj(
            "type" -> "function_call_output",
            "call_id" -> output.callId.value,
            "output" -> output.output
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

  private def schema(value: JsonSchema): ujson.Obj =
    value match
      case JsonSchema.StringSchema(description, allowedValues) =>
        withDescription(
          ujson.Obj("type" -> "string"),
          description,
          Option.when(allowedValues.nonEmpty)("enum" -> ujson.Arr.from(allowedValues))
        )
      case JsonSchema.NumberSchema(description)       => withDescription(ujson.Obj("type" -> "number"), description)
      case JsonSchema.IntegerSchema(description)      => withDescription(ujson.Obj("type" -> "integer"), description)
      case JsonSchema.BooleanSchema(description)      => withDescription(ujson.Obj("type" -> "boolean"), description)
      case JsonSchema.ArraySchema(items, description) =>
        withDescription(ujson.Obj("type" -> "array", "items" -> schema(items)), description)
      case JsonSchema.ObjectSchema(properties, required, additionalProperties, description) =>
        val result = withDescription(
          ujson.Obj(
            "type" -> "object",
            "properties" -> ujson.Obj.from(properties.view.mapValues(schema).toMap),
            "additionalProperties" -> additionalProperties
          ),
          description
        )
        if required.nonEmpty then result("required") = ujson.Arr.from(required.toSeq.sorted)
        result

  private def withDescription(
      value: ujson.Obj,
      description: Option[String],
      enumOption: Option[(String, ujson.Value)] = None
  ): ujson.Obj =
    description.foreach(value("description") = _)
    enumOption.foreach { case (key, enumValue) => value(key) = enumValue }
    value
}
