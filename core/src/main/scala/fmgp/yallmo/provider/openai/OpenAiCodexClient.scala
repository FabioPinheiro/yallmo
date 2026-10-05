package fmgp.yallmo.provider.openai

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.*
import fmgp.yallmo.model.{Agent, AgentId, MessagePayload, ToolChoice}
import fmgp.yallmo.model.llm.*
import zio.*
import zio.http.*

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Native client for the ChatGPT Codex subscription backend. It uses OAuth credentials held in `.env`, not an API key.
  */
final class OpenAiCodexClient private[openai] (client: Client, auth: OpenAiCodexAuth)
    extends LlmProvider
    with Agent[Any] {
  import OpenAiCodexClient.*

  override val id: AgentId = AgentId("openai-codex")

  override val defaultModel: String = OpenAiCodexConfig.Model

  override def inferenceWithText(
      prompt: String,
      options: ProviderGenerateOptions,
      model: Option[String]
  ): IO[YallmoError, String] =
    generate(prompt, Chunk.empty, options, model).mapError(YallmoError.fromThrowable)

  override def generateWithTools[R](
      prompt: String,
      tools: Chunk[LlmTool[R]],
      options: ProviderGenerateOptions,
      model: Option[String]
  ): ZIO[R, YallmoError, String] =
    generate(prompt, tools, options, model).mapError(YallmoError.fromThrowable)

  /** Performs one Responses API turn; tool execution and looping belong to AgentRunner. */
  override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
    (for {
      _ <- ZIO.fromEither(request.validate.left.map(YallmoError.InvalidConfig.apply))
      _ <- ZIO.fromEither(validateModel(request.model).left.map(YallmoError.InvalidConfig.apply))
      credentials <- auth.validCredentials
      account <- ZIO.fromEither(accountId(credentials.accessToken).left.map(YallmoError.AuthenticationFailure.apply))
      turnId = LlmProviderPayloadDebug.newTurnId()
      response <- callTurn(credentials.accessToken, account, request, turnId)
      turn <- ZIO.fromEither(OpenAiCodexTurnMapper.response(response).left.map(YallmoError.ProtocolFailure.apply))
      _ <- LlmProviderPayloadDebug.captureResponse(turnId, "OpenAI Codex", defaultModel, turn)
    } yield turn)
      .tapBoth(
        error =>
          ZIO.logError(
            s"OpenAI Codex turn failed model=${request.model}: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
          ),
        turn =>
          ZIO.logInfo(
            s"OpenAI Codex turn completed model=${request.model} stopReason=${turn.stopReason} toolCalls=${turn.output.count(_.isInstanceOf[MessagePayload.FunctionCallRequest])}"
          )
      )
      .mapError(YallmoError.fromThrowable)

  private def generate[R](
      prompt: String,
      tools: Chunk[LlmTool[R]],
      options: ProviderGenerateOptions,
      model: Option[String]
  ): ZIO[R, Throwable, String] =
    for
      capture <- LlmDebugCapture.captureCallStarted(
        provider = "OpenAI Codex",
        model = model.getOrElse(defaultModel),
        prompt = prompt,
        options = options,
        toolNames = tools.map(_.definition.name)
      )
      result <- (
        ZIO.fromEither(options.validate.left.map(YallmoError.InvalidConfig.apply)) *>
          ZIO.fromEither(validateModel(model).left.map(YallmoError.InvalidConfig.apply)) *>
          auth.validCredentials.flatMap { credentials =>
            ZIO
              .fromEither(accountId(credentials.accessToken).left.map(YallmoError.AuthenticationFailure.apply))
              .flatMap { accountId =>
                toolLoop(
                  credentials.accessToken,
                  accountId,
                  userInput(prompt),
                  tools,
                  options,
                  remainingIterations = options.maxToolIterations
                )
              }
          }
      ).tapBoth(
        error => LlmDebugCapture.captureCallFailed(capture, error),
        output => LlmDebugCapture.captureCallSucceeded(capture, output)
      ).onInterrupt(
        LlmDebugCapture.captureCallFailed(capture, InterruptedException("OpenAI Codex generation was interrupted"))
      )
    yield result

  private def toolLoop[R](
      accessToken: String,
      accountId: String,
      history: ujson.Arr,
      tools: Chunk[LlmTool[R]],
      options: ProviderGenerateOptions,
      remainingIterations: Int
  ): ZIO[R, Throwable, String] =
    call(accessToken, accountId, history, tools, options).flatMap { response =>
      if response.functionCalls.isEmpty then
        ZIO.fromEither(response.text.toRight(YallmoError.EmptyResponse("OpenAI Codex returned no text response")))
      else if remainingIterations <= 0 then ZIO.fail(YallmoError.ToolLoopLimitExceeded(options.maxToolIterations))
      else
        ZIO.foreach(response.functionCalls)(executeTool(_, tools)).flatMap { results =>
          val nextHistory = ujson.Arr.from(history.value ++ response.output ++ results)
          toolLoop(accessToken, accountId, nextHistory, tools, options, remainingIterations - 1)
        }
    }

  private def executeTool[R](
      call: OpenAiCodexFunctionCall,
      tools: Chunk[LlmTool[R]]
  ): ZIO[R, Throwable, ujson.Obj] =
    tools.find(_.definition.name == call.name) match {
      case None       => ZIO.fail(YallmoError.LlmUnknownTool(call.name))
      case Some(tool) =>
        tool
          .run(LlmToolCall(Some(call.callId), call.name, call.arguments))
          .mapError(error => YallmoError.ToolExecutionFailure(call.name, error))
          .map(result =>
            ujson.Obj("type" -> "function_call_output", "call_id" -> call.callId, "output" -> result.responseJson)
          )
    }

  private def call[R](
      accessToken: String,
      accountId: String,
      history: ujson.Arr,
      tools: Chunk[LlmTool[R]],
      options: ProviderGenerateOptions
  ): Task[OpenAiCodexResponse] = {
    val request = Request
      .post(CodexResponsesUrl, Body.fromString(ujson.write(requestBody(history, tools, options))))
      .addHeader("Authorization", s"Bearer $accessToken")
      .addHeader("chatgpt-account-id", accountId)
      .addHeader("OpenAI-Beta", "responses=experimental")
      .addHeader("originator", "asar")
      .addHeader("User-Agent", "ASAR")
      .addHeader("Content-Type", "application/json")
      .addHeader("Accept", "text/event-stream")

    ZIO.logInfo(
      s"Calling OpenAI Codex model=$defaultModel historyItems=${history.value.size} tools=${tools.map(_.definition.name).mkString("[", ",", "]")}"
    ) *>
      Client
        .batched(request)
        .provideEnvironment(ZEnvironment(client))
        .flatMap { response =>
          response.body.asString.flatMap { body =>
            if response.status.isSuccess then
              ZIO.fromEither(parseSseResponse(body)).mapError(YallmoError.ProtocolFailure.apply)
            else ZIO.fail(YallmoError.HttpFailure(s"OpenAI Codex returned HTTP ${response.status.code}"))
          }
        }
        .timeoutFail(YallmoError.HttpFailure("OpenAI Codex request timed out"))(RequestTimeout)
  }

  private def callTurn(
      accessToken: String,
      accountId: String,
      turnRequest: AgentTurnRequest,
      turnId: String
  ): Task[OpenAiCodexResponse] = {
    val body = turnRequestBody(turnRequest)
    val request = Request
      .post(CodexResponsesUrl, Body.fromString(ujson.write(body)))
      .addHeader("Authorization", s"Bearer $accessToken")
      .addHeader("chatgpt-account-id", accountId)
      .addHeader("OpenAI-Beta", "responses=experimental")
      .addHeader("originator", "asar")
      .addHeader("User-Agent", "ASAR")
      .addHeader("Content-Type", "application/json")
      .addHeader("Accept", "text/event-stream")

    LlmProviderPayloadDebug.captureRequest(turnId, "OpenAI Codex", defaultModel, body) *>
      ZIO.logInfo(
        s"Calling OpenAI Codex model=$defaultModel turnId=$turnId inputItems=${turnRequest.input.size} tools=${turnRequest.tools.map(_.name).mkString("[", ",", "]")}"
      ) *>
      Client
        .batched(request)
        .provideEnvironment(ZEnvironment(client))
        .flatMap { response =>
          response.body.asString.flatMap { body =>
            if response.status.isSuccess then
              ZIO.fromEither(parseSseResponse(body)).mapError(YallmoError.ProtocolFailure.apply)
            else ZIO.fail(YallmoError.HttpFailure(s"OpenAI Codex returned HTTP ${response.status.code}"))
          }
        }
        .timeoutFail(YallmoError.HttpFailure("OpenAI Codex request timed out"))(RequestTimeout)
  }

  private def requestBody[R](
      history: ujson.Arr,
      tools: Chunk[LlmTool[R]],
      options: ProviderGenerateOptions
  ): ujson.Obj = {
    val body = ujson.Obj(
      "model" -> defaultModel,
      "store" -> false,
      "stream" -> true,
      "instructions" -> options.systemInstruction.getOrElse("You are a helpful assistant."),
      "input" -> history,
      "tool_choice" -> "auto",
      "parallel_tool_calls" -> true
    )
    options.thinkingLevel.foreach(level => body("reasoning") = ujson.Obj("effort" -> thinkingEffort(level.toString)))
    if tools.nonEmpty then body("tools") = ujson.Arr.from(tools.map(tool => toolJson(tool.definition)))
    body
  }

  private def turnRequestBody(turnRequest: AgentTurnRequest): ujson.Obj = {
    val body = ujson.Obj(
      "model" -> turnRequest.model,
      "store" -> false,
      "stream" -> true,
      "input" -> OpenAiCodexTurnMapper.input(turnRequest.input),
      "tool_choice" -> toolChoiceJson(turnRequest.toolChoice),
      "parallel_tool_calls" -> turnRequest.parallelToolCalls
    )
    turnRequest.options.thinkingLevel.foreach(level =>
      body("reasoning") = ujson.Obj("effort" -> thinkingEffort(level.toString))
    )
    if turnRequest.tools.nonEmpty then body("tools") = ujson.Arr.from(turnRequest.tools.map(OpenAiCodexTurnMapper.tool))
    body
  }

  private def toolChoiceJson(choice: ToolChoice): ujson.Value =
    choice match
      case ToolChoice.Auto           => ujson.Str("auto")
      case ToolChoice.None           => ujson.Str("none")
      case ToolChoice.Required       => ujson.Str("required")
      case ToolChoice.Function(name) => ujson.Obj("type" -> "function", "name" -> name)

  private def toolJson(definition: LlmToolDefinition): ujson.Obj = {
    val properties = definition.parameters.map { parameter =>
      parameter.name -> ujson.Obj(
        "type" -> schemaType(parameter.schemaType),
        "description" -> parameter.description
      )
    }.toMap
    val required = definition.parameters.filter(_.required).map(_.name)
    val parameters = ujson.Obj("type" -> "object", "properties" -> ujson.Obj.from(properties))
    if required.nonEmpty then parameters("required") = ujson.Arr.from(required)
    ujson.Obj(
      "type" -> "function",
      "name" -> definition.name,
      "description" -> definition.description,
      "parameters" -> parameters,
      "strict" -> false
    )
  }

  private def userInput(prompt: String): ujson.Arr =
    ujson.Arr(ujson.Obj("role" -> "user", "content" -> ujson.Arr(ujson.Obj("type" -> "input_text", "text" -> prompt))))
}

object OpenAiCodexClient {
  val CodexResponsesUrl = "https://chatgpt.com/backend-api/codex/responses"
  val RequestTimeout: Duration = 2.minutes

  def make(client: Client): OpenAiCodexClient = new OpenAiCodexClient(client, OpenAiCodexAuth.live(client))

  val live: ZLayer[Client, Nothing, OpenAiCodexClient] = ZLayer.fromFunction(make)

  private[openai] def validateModel(model: String): Either[String, Unit] =
    Either.cond(
      model == OpenAiCodexConfig.Model,
      (),
      s"OpenAI Codex model is fixed to ${OpenAiCodexConfig.Model}"
    )

  private[openai] def validateModel(model: Option[String]): Either[String, Unit] =
    model.fold[Either[String, Unit]](Right(()))(validateModel)

  private[openai] def accountId(accessToken: String): Either[String, String] =
    scala.util
      .Try {
        val payload =
          accessToken.split("\\.").lift(1).getOrElse(throw IllegalArgumentException("JWT payload is missing"))
        val decoded = new String(Base64.getUrlDecoder.decode(payload), StandardCharsets.UTF_8)
        val json = ujson.read(decoded).obj
        json("https://api.openai.com/auth")("chatgpt_account_id").str
      }
      .toEither
      .left
      .map(_ => "OpenAI Codex access token does not contain a ChatGPT account id")

  /** Reconstructs text and tool calls from the Codex Server-Sent Event stream. */
  private[openai] def parseSseResponse(body: String): Either[String, OpenAiCodexResponse] =
    scala.util
      .Try {
        val events = body
          .replace("\r\n", "\n")
          .split("\\n\\n")
          .iterator
          .flatMap { frame =>
            val payload =
              frame.linesIterator.collect { case line if line.startsWith("data:") => line.drop(5).trim }.mkString("\\n")
            Option.when(payload.nonEmpty && payload != "[DONE]")(payload)
          }
          .map(payload => ujson.read(payload).obj)
          .toVector

        var output = Vector.empty[ujson.Value]
        val streamedText = new StringBuilder
        var terminalResponse: Option[ujson.Value] = None

        events.foreach { event =>
          event.get("type").flatMap(_.strOpt) match {
            case Some("response.output_text.delta") =>
              streamedText.append(event.get("delta").flatMap(_.strOpt).getOrElse(""))
            case Some("response.output_item.done") => event.get("item").foreach(item => output :+= item)
            case Some("response.completed") | Some("response.incomplete") =>
              terminalResponse = event.get("response")
            case Some("response.failed") | Some("error") =>
              throw IllegalArgumentException(
                event.get("message").flatMap(_.strOpt).getOrElse("OpenAI Codex response failed")
              )
            case _ => ()
          }
        }

        if terminalResponse.isEmpty then throw IllegalArgumentException("OpenAI Codex response stream did not complete")
        if output.isEmpty then
          output = terminalResponse
            .flatMap(response => parseResponse(ujson.write(response)).toOption.map(_.output))
            .getOrElse(Vector.empty)

        val text = streamedText.result().trim match {
          case "" =>
            output.flatMap(parseText).mkString.trim match {
              case ""    => None
              case value => Some(value)
            }
          case value => Some(value)
        }
        val metadata = responseMetadata(terminalResponse.get.obj)
        OpenAiCodexResponse(output, output.flatMap(parseFunctionCall), text, metadata._1, metadata._2)
      }
      .toEither
      .left
      .map(error => Option(error.getMessage).getOrElse("Invalid OpenAI Codex response stream"))

  private[openai] def parseResponse(body: String): Either[String, OpenAiCodexResponse] =
    scala.util
      .Try {
        val root = ujson.read(body).obj
        val output =
          root.get("output").collect { case value: ujson.Arr => value.value.toVector }.getOrElse(Vector.empty)
        val calls = output.flatMap(parseFunctionCall)
        val text = output.flatMap(parseText).mkString.trim match {
          case ""    => None
          case value => Some(value)
        }
        val metadata = responseMetadata(root)
        OpenAiCodexResponse(output, calls, text, metadata._1, metadata._2)
      }
      .toEither
      .left
      .map(_ => "Invalid OpenAI Codex response")

  private def responseMetadata(
      root: scala.collection.Map[String, ujson.Value]
  ): (Option[String], Option[String]) =
    root.get("id").flatMap(_.strOpt) -> root.get("status").flatMap(_.strOpt)

  private def parseFunctionCall(value: ujson.Value): Option[OpenAiCodexFunctionCall] =
    value.objOpt.flatMap { json =>
      Option.when(json.get("type").flatMap(_.strOpt).contains("function_call")) {
        OpenAiCodexFunctionCall(
          json
            .get("call_id")
            .flatMap(_.strOpt)
            .getOrElse(throw IllegalArgumentException("function call id is missing")),
          json.get("name").flatMap(_.strOpt).getOrElse(throw IllegalArgumentException("function call name is missing")),
          json.get("arguments").flatMap(_.strOpt).getOrElse("{}")
        )
      }
    }

  private def parseText(value: ujson.Value): Vector[String] =
    value.objOpt.toVector.flatMap { message =>
      if !message.get("type").flatMap(_.strOpt).contains("message") then Vector.empty
      else
        message
          .get("content")
          .collect { case content: ujson.Arr => content.value.toVector }
          .getOrElse(Vector.empty)
          .flatMap {
            _.objOpt.toVector.collect {
              case block if block.get("type").flatMap(_.strOpt).contains("output_text") =>
                block.get("text").flatMap(_.strOpt).getOrElse("")
            }
          }
    }

  private def schemaType(value: ToolSchemaType): String = value.toString.toLowerCase

  private def thinkingEffort(value: String): String =
    value match {
      case "minimal" => "low"
      case other     => other
    }
}

object OpenAiCodexConfig {
  val Model = "gpt-5.6-terra"
}

private[openai] final case class OpenAiCodexFunctionCall(callId: String, name: String, arguments: String)
private[openai] final case class OpenAiCodexResponse(
    output: Vector[ujson.Value],
    functionCalls: Vector[OpenAiCodexFunctionCall],
    text: Option[String],
    responseId: Option[String] = None,
    status: Option[String] = None
)
