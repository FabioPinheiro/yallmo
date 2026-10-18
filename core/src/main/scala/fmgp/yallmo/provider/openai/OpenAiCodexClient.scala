package fmgp.yallmo.provider.openai

import fmgp.yallmo.orchestration.config.OAuthCredentialsConfig
import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.*
import fmgp.yallmo.model.{Agent, AgentId, MessagePayload, ToolChoice}
import fmgp.yallmo.model.llm.*
import zio.*
import zio.http.*

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Native client for the ChatGPT Codex subscription backend, using OAuth rather than platform API keys. */
final class OpenAiCodexClient private[openai] (client: Client, auth: OpenAiCodexAuth.CredentialAccess)
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
      _ <- LlmProviderPayloadDebug.captureResponse(turnId, "OpenAI Codex", request.model, turn)
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
                  model.getOrElse(defaultModel),
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
      model: String,
      remainingIterations: Int
  ): ZIO[R, Throwable, String] =
    call(accessToken, accountId, history, tools, options, model).flatMap { response =>
      if response.functionCalls.isEmpty then
        ZIO.fromEither(response.text.toRight(YallmoError.EmptyResponse("OpenAI Codex returned no text response")))
      else if remainingIterations <= 0 then ZIO.fail(YallmoError.ToolLoopLimitExceeded(options.maxToolIterations))
      else
        ZIO.foreach(response.functionCalls)(executeTool(_, tools)).flatMap { results =>
          val nextHistory = ujson.Arr.from(history.value ++ response.output ++ results)
          toolLoop(accessToken, accountId, nextHistory, tools, options, model, remainingIterations - 1)
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
      options: ProviderGenerateOptions,
      model: String
  ): Task[OpenAiCodexResponse] = {
    val request = Request
      .post(CodexResponsesUrl, Body.fromString(ujson.write(requestBody(history, tools, options, model))))
      .addHeader("Authorization", s"Bearer $accessToken")
      .addHeader("chatgpt-account-id", accountId)
      .addHeader("OpenAI-Beta", "responses=experimental")
      .addHeader("originator", "yallmo")
      .addHeader("User-Agent", "Yallmo")
      .addHeader("Content-Type", "application/json")
      .addHeader("Accept", "text/event-stream")

    ZIO.logInfo(
      s"Calling OpenAI Codex model=$model historyItems=${history.value.size} tools=${tools.map(_.definition.name).mkString("[", ",", "]")}"
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
  ): Task[OpenAiCodexResponse] =
    ZIO.fromEither(turnRequestBody(turnRequest).left.map(YallmoError.InvalidConfig.apply)).flatMap { body =>
      val request = Request
        .post(CodexResponsesUrl, Body.fromString(ujson.write(body)))
        .addHeader("Authorization", s"Bearer $accessToken")
        .addHeader("chatgpt-account-id", accountId)
        .addHeader("OpenAI-Beta", "responses=experimental")
        .addHeader("originator", "yallmo")
        .addHeader("User-Agent", "Yallmo")
        .addHeader("Content-Type", "application/json")
        .addHeader("Accept", "text/event-stream")

      LlmProviderPayloadDebug.captureRequest(turnId, "OpenAI Codex", turnRequest.model, body) *>
        ZIO.logInfo(
          s"Calling OpenAI Codex model=${turnRequest.model} turnId=$turnId inputItems=${turnRequest.input.size} tools=${turnRequest.tools.map(_.name).mkString("[", ",", "]")}"
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

  private def userInput(prompt: String): ujson.Arr =
    ujson.Arr(ujson.Obj("role" -> "user", "content" -> ujson.Arr(ujson.Obj("type" -> "input_text", "text" -> prompt))))
}

object OpenAiCodexClient {
  val CodexResponsesUrl = "https://chatgpt.com/backend-api/codex/responses"
  val RequestTimeout: Duration = 2.minutes

  /** Creates a new client with isolated, in-memory profile credentials, capturing the HTTP client from the ZIO
    * environment at construction. Credentials are checked/refreshed only when a turn is submitted; refreshed values
    * remain in memory.
    */
  def make(credentials: Option[OAuthCredentialsConfig]): ZIO[Client, Nothing, OpenAiCodexClient] =
    for {
      client <- ZIO.service[Client]
      access <- OpenAiCodexAuth.inMemory(credentials)
    } yield new OpenAiCodexClient(client, access)

  private[openai] def requestBody[R](
      history: ujson.Arr,
      tools: Chunk[LlmTool[R]],
      options: ProviderGenerateOptions,
      model: String = OpenAiCodexConfig.Model
  ): ujson.Obj = {
    val body = ujson.Obj(
      "model" -> model,
      "store" -> false,
      "stream" -> true,
      "include" -> ujson.Arr("reasoning.encrypted_content"),
      "instructions" -> options.systemInstruction.filter(_.trim.nonEmpty).getOrElse("You are a helpful assistant."),
      "input" -> history,
      "tool_choice" -> "auto",
      "parallel_tool_calls" -> true
    )
    options.thinkingLevel.foreach(level => body("reasoning") = ujson.Obj("effort" -> thinkingEffort(level)))
    if tools.nonEmpty then body("tools") = ujson.Arr.from(tools.map(tool => toolJson(tool.definition)))
    body
  }

  private[openai] def turnRequestBody(turnRequest: AgentTurnRequest): Either[String, ujson.Obj] =
    turnRequest.validate.flatMap { validated =>
      validated.input.lastOption match {
        // AgentTurnRequest requires the current instructions last, not at the start of history.
        case Some(MessagePayload.Instructions(instructions)) =>
          val body = ujson.Obj(
            "model" -> validated.model,
            "store" -> false,
            "stream" -> true,
            "include" -> ujson.Arr("reasoning.encrypted_content"),
            "instructions" -> instructions,
            "input" -> OpenAiCodexTurnMapper.input(validated.input.dropRight(1)),
            "tool_choice" -> toolChoiceJson(validated.toolChoice),
            "parallel_tool_calls" -> validated.parallelToolCalls
          )
          validated.options.thinkingLevel.foreach(level =>
            body("reasoning") = ujson.Obj("effort" -> thinkingEffort(level))
          )
          if validated.tools.nonEmpty then
            body("tools") = ujson.Arr.from(validated.tools.map(OpenAiCodexTurnMapper.tool))
          Right(body)
        case _ => Left("the final input item must be instructions")
      }
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

  private[openai] def validateModel(model: String): Either[String, Unit] =
    Either.cond(
      model.trim.nonEmpty,
      (),
      "OpenAI Codex model must not be blank"
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
        val account = json("https://api.openai.com/auth")("chatgpt_account_id").str
        if account.trim.isEmpty then throw IllegalArgumentException("ChatGPT account id is blank")
        account
      }
      .toEither
      .left
      .map(_ => "OpenAI Codex access token does not contain a ChatGPT account id")

  /** Buffers one SSE turn, preferring the terminal snapshot over provisional items and text deltas. */
  private[openai] def parseSseResponse(body: String): Either[String, OpenAiCodexResponse] =
    scala.util
      .Try {
        val events = body
          .replace("\r\n", "\n")
          .split("\n\n")
          .iterator
          .flatMap { frame =>
            val payload = frame.linesIterator
              .collect {
                case line if line.startsWith("data:") => line.drop(5).stripPrefix(" ")
              }
              .mkString("\n")
            Option.when(payload.nonEmpty && payload != "[DONE]")(payload)
          }
          .map(payload => ujson.read(payload).obj)

        val items = scala.collection.mutable.TreeMap.empty[Int, ujson.Value]
        val deltas = scala.collection.mutable.TreeMap.empty[Int, StringBuilder]
        var terminalResponse: Option[ujson.Obj] = None

        def outputIndex(event: scala.collection.Map[String, ujson.Value], fallback: Int): Int =
          event.get("output_index") match {
            case None        => fallback
            case Some(value) =>
              val index = value.num
              require(index >= 0 && index.isValidInt, "Invalid output index")
              index.toInt
          }

        events.foreach { event =>
          event.get("type").map(_.str).getOrElse(throw IllegalArgumentException("Missing event type")) match {
            case "response.output_text.delta" =>
              val index = outputIndex(event, 0)
              deltas.getOrElseUpdate(index, new StringBuilder).append(event("delta").str)
            case "response.output_item.done" =>
              val item = event("item")
              require(item.obj("type").str.nonEmpty, "Missing output item type")
              val index = outputIndex(event, items.lastOption.fold(0)(_._1 + 1))
              items(index) = item
            case "response.done" | "response.completed" | "response.incomplete" =>
              val response = ujson.Obj.from(event("response").obj)
              val status = response.obj.get("status").map(_.str).getOrElse {
                if event("type").str == "response.incomplete" then "incomplete" else "completed"
              }
              requireSuccessfulStatus(Some(status))
              response("status") = status
              terminalResponse = Some(response)
            case "response.failed" | "error" =>
              throw IllegalArgumentException("OpenAI Codex response failed")
            case _ => ()
          }
        }

        val terminal = terminalResponse.getOrElse(throw IllegalArgumentException("Missing terminal response"))
        val output = terminal.obj.get("output") match {
          case Some(value) => value.arr.toVector
          case None        =>
            // Delta-only streams still need canonical assistant items, not just legacy response.text.
            deltas.foreach { case (index, text) =>
              if !items.get(index).exists(item => parseText(item).mkString.nonEmpty) && text.nonEmpty then
                items.get(index) match {
                  case Some(item) if !item.obj.get("type").exists(_.str == "message") =>
                    throw IllegalArgumentException("Conflicting output index")
                  case _ => items(index) = assistantOutput(text.result())
                }
            }
            items.values.toVector
        }
        terminal("output") = ujson.Arr.from(output)
        decodeResponse(terminal)
      }
      .toEither
      .left
      .map(_ => "Invalid or failed OpenAI Codex response stream")

  private[openai] def parseResponse(body: String): Either[String, OpenAiCodexResponse] =
    scala.util.Try(decodeResponse(ujson.read(body))).toEither.left.map(_ => "Invalid or failed OpenAI Codex response")

  private def decodeResponse(value: ujson.Value): OpenAiCodexResponse = {
    val root = value.obj
    val metadata = responseMetadata(root)
    requireSuccessfulStatus(metadata._2)
    val output = root.get("output").map(_.arr.toVector).getOrElse(Vector.empty)
    // Validate all item envelopes here, including paths used by the legacy text/tool loop.
    output.foreach(item => require(item.obj("type").str.nonEmpty, "Missing output item type"))
    val text = output.flatMap(parseText).mkString.trim match {
      case ""    => None
      case value => Some(value)
    }
    OpenAiCodexResponse(output, output.flatMap(parseFunctionCall), text, metadata._1, metadata._2)
  }

  private def requireSuccessfulStatus(status: Option[String]): Unit =
    require(status.forall(value => value == "completed" || value == "incomplete"), "Unsuccessful response status")

  private def assistantOutput(text: String): ujson.Obj =
    ujson.Obj(
      "type" -> "message",
      "role" -> "assistant",
      "status" -> "completed",
      "content" -> ujson.Arr(ujson.Obj("type" -> "output_text", "text" -> text))
    )

  private def responseMetadata(
      root: scala.collection.Map[String, ujson.Value]
  ): (Option[String], Option[String]) =
    root.get("id").map(_.str) -> root.get("status").map(_.str)

  private def parseFunctionCall(value: ujson.Value): Option[OpenAiCodexFunctionCall] =
    value.objOpt.flatMap { json =>
      Option.when(json.get("type").flatMap(_.strOpt).contains("function_call")) {
        OpenAiCodexFunctionCall(
          json
            .get("call_id")
            .map(_.str)
            .filter(_.trim.nonEmpty)
            .getOrElse(throw IllegalArgumentException("function call id is missing")),
          json
            .get("name")
            .map(_.str)
            .filter(_.trim.nonEmpty)
            .getOrElse(throw IllegalArgumentException("function call name is missing")),
          json.get("arguments").map(_.str).getOrElse("{}")
        )
      }
    }

  private def parseText(value: ujson.Value): Vector[String] =
    value.objOpt.toVector.flatMap { message =>
      if !message.get("type").flatMap(_.strOpt).contains("message") then Vector.empty
      else
        message("content").arr.toVector.flatMap { content =>
          val block = content.obj
          if block("type").str == "output_text" then Vector(block("text").str) else Vector.empty
        }
    }

  private def schemaType(value: ToolSchemaType): String = value.toString.toLowerCase

  private def thinkingEffort(value: ThinkingLevel): String =
    value match {
      case ThinkingLevel.Minimal => "low"
      case other                 => other.toString.toLowerCase(java.util.Locale.ROOT)
    }
}

object OpenAiCodexConfig {
  val Model = "gpt-6.1-sol"
}

private[openai] final case class OpenAiCodexFunctionCall(callId: String, name: String, arguments: String)
private[openai] final case class OpenAiCodexResponse(
    output: Vector[ujson.Value],
    functionCalls: Vector[OpenAiCodexFunctionCall],
    text: Option[String],
    responseId: Option[String] = None,
    status: Option[String] = None
)
