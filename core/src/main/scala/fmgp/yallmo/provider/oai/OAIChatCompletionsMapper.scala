package fmgp.yallmo.provider.oai

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.*
import zio.Chunk

import java.util.Base64

/** Encodes and decodes the OpenAI-compatible Chat Completions wire dialect. */
private[oai] object OAIChatCompletionsMapper {
  def messages(items: Chunk[MessagePayload]): ujson.Arr =
    ujson.Arr.from(items.foldLeft(Vector.empty[ujson.Value])(append))

  def agentTurnResponse(response: OAIResponse): Either[String, AgentTurnResponse] =
    val text = response.content
    val calls =
      response.functionCalls.map(call => FunctionCall(FunctionCallId.unsafe(call.callId), call.name, call.arguments))
    val output =
      Chunk.fromIterable(text.map(MessagePayload.Assistant.apply)) ++ calls.map(
        MessagePayload.FunctionCallRequest.apply
      )
    Right(
      AgentTurnResponse(
        output = output,
        stopReason = stopReason(response.finishReason, calls.nonEmpty),
        responseId = response.responseId
      )
    )

  private def stopReason(finishReason: Option[String], hasCalls: Boolean): LlmStopReason =
    finishReason.map(_.toLowerCase) match
      case Some("tool_calls") | Some("function_call") => LlmStopReason.ToolUse
      case Some("length") | Some("max_tokens")        => LlmStopReason.Length
      case Some("content_filter")                     => LlmStopReason.ContentFilter
      case Some("stop")                               => LlmStopReason.Stop
      case Some(value)                                => LlmStopReason.Unknown(value)
      case None if hasCalls                           => LlmStopReason.ToolUse
      case None                                       => LlmStopReason.Stop

  def turnTool(definition: FunctionToolDefinition): ujson.Obj =
    ujson.Obj(
      "type" -> "function",
      "function" -> ujson.Obj(
        "name" -> definition.name,
        "description" -> definition.description,
        "parameters" -> schema(definition.inputSchema)
      )
    )

  private def append(messages: Vector[ujson.Value], item: MessagePayload): Vector[ujson.Value] =
    item match
      case MessagePayload.User(text)           => messages :+ ujson.Obj("role" -> "user", "content" -> text)
      case MessagePayload.Context(label, text) =>
        messages :+ ujson.Obj("role" -> "user", "content" -> s"Context [$label]:\n$text")
      case item: MessagePayload.RagResult =>
        messages :+ ujson.Obj("role" -> "user", "content" -> s"Context:\n${item.contentStr}")
      case item: MessagePayload.ToolResult =>
        messages :+ ujson.Obj("role" -> "user", "content" -> s"Context:\n${item.contentStr}")
      case MessagePayload.Assistant(text)           => messages :+ ujson.Obj("role" -> "assistant", "content" -> text)
      case MessagePayload.Reasoning(_, _)           => messages
      case MessagePayload.Instructions(text)        => messages :+ ujson.Obj("role" -> "system", "content" -> text)
      case MessagePayload.FunctionCallRequest(call) =>
        val toolCall = ujson.Obj(
          "id" -> call.id.value,
          "type" -> "function",
          "function" -> ujson.Obj("name" -> call.name, "arguments" -> call.argumentsJson)
        )
        messages.lastOption.flatMap(_.objOpt).filter(_.get("role").flatMap(_.strOpt).contains("assistant")) match
          case Some(assistant) =>
            val existing = assistant.get("tool_calls").flatMap(_.arrOpt).map(_.value).getOrElse(Vector.empty)
            assistant("tool_calls") = ujson.Arr.from(existing :+ toolCall)
            messages
          case None =>
            messages :+ ujson.Obj("role" -> "assistant", "content" -> ujson.Null, "tool_calls" -> ujson.Arr(toolCall))
      case MessagePayload.FunctionCallOutput(output) =>
        messages :+ ujson.Obj("role" -> "tool", "tool_call_id" -> output.callId.value, "content" -> output.output)

  private def schema(value: JsonSchema): ujson.Obj =
    value match
      case JsonSchema.StringSchema(description, allowed) =>
        decorate(
          ujson.Obj("type" -> "string"),
          description,
          Option.when(allowed.nonEmpty)("enum" -> ujson.Arr.from(allowed))
        )
      case JsonSchema.NumberSchema(description)       => decorate(ujson.Obj("type" -> "number"), description)
      case JsonSchema.IntegerSchema(description)      => decorate(ujson.Obj("type" -> "integer"), description)
      case JsonSchema.BooleanSchema(description)      => decorate(ujson.Obj("type" -> "boolean"), description)
      case JsonSchema.ArraySchema(items, description) =>
        decorate(ujson.Obj("type" -> "array", "items" -> schema(items)), description)
      case JsonSchema.ObjectSchema(properties, required, additional, description) =>
        val result = decorate(
          ujson.Obj(
            "type" -> "object",
            "properties" -> ujson.Obj.from(properties.view.mapValues(schema).toMap),
            "additionalProperties" -> additional
          ),
          description
        )
        if required.nonEmpty then result("required") = ujson.Arr.from(required.toSeq.sorted)
        result

  private def decorate(
      value: ujson.Obj,
      description: Option[String],
      enumOption: Option[(String, ujson.Value)] = None
  ): ujson.Obj =
    description.foreach(value("description") = _)
    enumOption.foreach { case (key, option) => value(key) = option }
    value
  private[oai] def initialMessages(prompt: String, options: ProviderGenerateOptions): ujson.Arr = {
    val messages = Vector.newBuilder[ujson.Value]
    options.systemInstruction
      .filter(_.trim.nonEmpty)
      .foreach(instruction => messages += ujson.Obj("role" -> "system", "content" -> instruction))
    messages += ujson.Obj("role" -> "user", "content" -> prompt)
    ujson.Arr.from(messages.result())
  }

  private[oai] def turnRequestBody(model: String, request: AgentTurnRequest): ujson.Obj = {
    val body = ujson.Obj(
      "model" -> model,
      "messages" -> messages(request.input),
      "stream" -> false
    )
    request.options.temperature.foreach(value => body("temperature") = value)
    request.options.topP.foreach(value => body("top_p") = value)
    request.options.maxOutputTokens.foreach(value => body("max_tokens") = value)
    request.options.seed.foreach(value => body("seed") = value)
    if request.options.stopSequences.nonEmpty then body("stop") = ujson.Arr.from(request.options.stopSequences)
    if request.tools.nonEmpty then body("tools") = ujson.Arr.from(request.tools.map(turnTool))
    if request.tools.nonEmpty then body("tool_choice") = toolChoiceJson(request.toolChoice)
    body("parallel_tool_calls") = request.parallelToolCalls
    body
  }

  private[oai] def toolChoiceJson(choice: ToolChoice): ujson.Value =
    choice match
      case ToolChoice.Auto           => ujson.Str("auto")
      case ToolChoice.None           => ujson.Str("none")
      case ToolChoice.Required       => ujson.Str("required")
      case ToolChoice.Function(name) =>
        ujson.Obj("type" -> "function", "function" -> ujson.Obj("name" -> name))

  private[oai] def requestBody[R](
      model: String,
      messages: ujson.Arr,
      tools: Chunk[LlmTool[R]],
      options: ProviderGenerateOptions
  ): ujson.Obj = {
    val body = ujson.Obj("model" -> model, "messages" -> messages, "stream" -> false)
    options.temperature.foreach(value => body("temperature") = value)
    options.topP.foreach(value => body("top_p") = value)
    options.maxOutputTokens.foreach(value => body("max_tokens") = value)
    options.seed.foreach(value => body("seed") = value)
    if options.stopSequences.nonEmpty then body("stop") = ujson.Arr.from(options.stopSequences)
    options.responseMimeType
      .filter(_ == "application/json")
      .foreach(_ => body("response_format") = ujson.Obj("type" -> "json_object"))
    if tools.nonEmpty then body("tools") = ujson.Arr.from(tools.map(tool => toolJson(tool.definition)))
    body
  }

  private[oai] def parseResponse(body: String): Either[String, OAIResponse] =
    scala.util
      .Try {
        val root = ujson.read(body).obj
        val message = root
          .get("choices")
          .collect { case choices: ujson.Arr => choices.value }
          .flatMap(_.headOption)
          .flatMap(_.objOpt)
          .flatMap(_.get("message"))
          .flatMap(_.objOpt)
          .getOrElse(throw IllegalArgumentException("response is missing choices[0].message"))
        val functionCalls = message
          .get("tool_calls")
          .collect { case calls: ujson.Arr => calls.value.toVector }
          .getOrElse(Vector.empty)
          .map(parseFunctionCall)
        val content = message.get("content").flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty)
        val choice = root("choices").arr.value.head.obj
        OAIResponse(
          message,
          functionCalls,
          content,
          choice.get("finish_reason").flatMap(_.strOpt),
          root.get("id").flatMap(_.strOpt)
        )
      }
      .toEither
      .left
      .map(error => Option(error.getMessage).getOrElse("Invalid OpenAI-compatible endpoint response"))

  private def toolJson(definition: LlmToolDefinition): ujson.Obj = {
    val properties = definition.parameters.map { parameter =>
      parameter.name -> ujson.Obj("type" -> schemaType(parameter.schemaType), "description" -> parameter.description)
    }.toMap
    val parameters = ujson.Obj("type" -> "object", "properties" -> ujson.Obj.from(properties))
    val required = definition.parameters.filter(_.required).map(_.name)
    if required.nonEmpty then parameters("required") = ujson.Arr.from(required)
    ujson.Obj(
      "type" -> "function",
      "function" -> ujson.Obj(
        "name" -> definition.name,
        "description" -> definition.description,
        "parameters" -> parameters
      )
    )
  }

  private def parseFunctionCall(value: ujson.Value): OAIFunctionCall = {
    val json = value.obj
    val function =
      json.get("function").flatMap(_.objOpt).getOrElse(throw IllegalArgumentException("tool call is missing function"))
    val arguments = function
      .get("arguments")
      .map {
        case value: ujson.Str => value.value
        case value            => ujson.write(value)
      }
      .getOrElse("{}")
    OAIFunctionCall(
      json.get("id").flatMap(_.strOpt).getOrElse(throw IllegalArgumentException("tool call is missing id")),
      function
        .get("name")
        .flatMap(_.strOpt)
        .getOrElse(throw IllegalArgumentException("tool call is missing function.name")),
      arguments
    )
  }

  private def schemaType(value: ToolSchemaType): String = value.toString.toLowerCase

  def audioRequestBody(model: String, systemPrompt: String, audio: AudioInput): ujson.Obj =
    ujson.Obj(
      "model" -> model,
      "messages" -> ujson.Arr(
        ujson.Obj("role" -> "system", "content" -> systemPrompt),
        ujson.Obj(
          "role" -> "user",
          "content" -> ujson.Arr(
            ujson.Obj(
              "type" -> "input_audio",
              "input_audio" -> ujson.Obj(
                "data" -> Base64.getEncoder.encodeToString(audio.bytes.toArray),
                "format" -> audio.format.wireName
              )
            )
          )
        )
      ),
      "temperature" -> 0
    )
}
