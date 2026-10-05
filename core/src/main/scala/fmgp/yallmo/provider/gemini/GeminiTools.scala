package fmgp.yallmo.provider.gemini

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.genai.Client as GoogleGenAiClient
import com.google.genai.types.{Content, FunctionCall, GenerateContentResponse, GenerateContentConfig, Part}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Try
import zio.*
import fmgp.yallmo.*
import fmgp.yallmo.model.llm.*

/** Compatibility aliases for Gemini-specific callers while tools are shared by all LLM providers. */
type ToolSchemaType = fmgp.yallmo.model.llm.ToolSchemaType
type ToolParameter = LlmToolParameter
type ToolDefinition = LlmToolDefinition
type ToolCallRequest = LlmToolCall
type ToolCallResult = LlmToolResult
type GeminiTool[-R] = LlmTool[R]

val ToolSchemaType = fmgp.yallmo.model.llm.ToolSchemaType
val ToolParameter = LlmToolParameter
val ToolDefinition = LlmToolDefinition
val ToolCallRequest = LlmToolCall
val ToolCallResult = LlmToolResult
val GeminiTool = LlmTool

object GenAiTools {

  private val jsonMapper = ObjectMapper()

  /** Converts Java values returned by the Google GenAI SDK into JSON for the internal `ToolCallRequest` format.
    *
    * TODO: Prefer a typed representation for tool arguments instead of passing JSON strings around. That would let each
    * `GeminiTool` decode only the shape it expects and report better validation errors.
    */
  private def stringifyJava(value: AnyRef): Either[GenAiError.JsonDecodeFailure, String] =
    Try(jsonMapper.writeValueAsString(value)).toEither.left.map { error =>
      GenAiError.JsonDecodeFailure(String.valueOf(value), error.getMessage)
    }

  /** Parses a tool response JSON string into the Java map shape expected by `Part.fromFunctionResponse`.
    *
    * TODO: Replace this catch-all ObjectMapper conversion with explicit response encoders/decoders, ideally backed by
    * zio-json or a small domain ADT. That would remove unchecked casts and make tool response contracts clearer.
    */
  private def parseResponseObject(json: String): Either[GenAiError.JsonDecodeFailure, java.util.Map[String, Object]] =
    Try {
      val node = jsonMapper.readTree(json)
      val value = jsonMapper.treeToValue(node, classOf[Object])
      value match {
        case map: java.util.Map[?, ?] => map.asInstanceOf[java.util.Map[String, Object]]
        case other: Object            => Map("result" -> other).asJava
      }
    }.toEither.left.map { error =>
      GenAiError.JsonDecodeFailure(json, error.getMessage)
    }

  def generateWithTools[R](
      client: GoogleGenAiClient,
      defaultModel: String,
      prompt: String,
      tools: Chunk[GeminiTool[R]],
      options: ProviderGenerateOptions = ProviderGenerateOptions.fast,
      model: Option[String] = None
  ): ZIO[R, Throwable, String] =
    for {
      selectedModel <- ZIO.succeed(model.getOrElse(defaultModel))
      capture <- LlmDebugCapture.captureCallStarted(
        provider = "Gemini",
        model = selectedModel,
        prompt = prompt,
        options = options,
        toolNames = tools.map(_.definition.name)
      )
      result <- (
        for {
          _ <- ZIO.fromEither(options.validate.left.map(GenAiError.InvalidConfig.apply))
          sdkConfig <- ZIO.fromEither(GenAiMappers.toGenerateContentConfig(options, tools.map(_.definition)))
          result <- toolLoop(
            client = client,
            model = selectedModel,
            history = Chunk(userContent(prompt)),
            tools = tools,
            sdkConfig = sdkConfig,
            remainingIterations = options.maxToolIterations,
            maxIterations = options.maxToolIterations
          )
        } yield result
      ).tapBoth(
        error => LlmDebugCapture.captureCallFailed(capture, error),
        output => LlmDebugCapture.captureCallSucceeded(capture, output)
      ).onInterrupt(
        LlmDebugCapture.captureCallFailed(capture, InterruptedException("Gemini tool generation was interrupted"))
      )
    } yield result

  private def toolLoop[R](
      client: GoogleGenAiClient,
      model: String,
      history: Chunk[Content],
      tools: Chunk[GeminiTool[R]],
      sdkConfig: GenerateContentConfig,
      remainingIterations: Int,
      maxIterations: Int
  ): ZIO[R, Throwable, String] =
    if remainingIterations <= 0 then
      ZIO.logWarning(s"Gemini tool loop limit reached. limit=$maxIterations") *>
        ZIO.fail(GenAiError.ToolLoopLimitExceeded(maxIterations))
    else
      for {
        _ <- ZIO.logInfo(s"Calling Gemini model=$model remainingToolIterations=$remainingIterations")
        response <- ZIO
          .attemptBlocking(client.models.generateContent(model, history.toList.asJava, sdkConfig))
          .mapError(error => GenAiError.SdkFailure(s"google-genai generateContent failed: ${error.getMessage}", error))
        functionCalls = Chunk.fromIterable(response.functionCalls().asScala)
        output <-
          if functionCalls.nonEmpty then
            for {
              _ <- ZIO.logInfo(s"Gemini requested ${functionCalls.size} tool call(s)")
              toolResults <- ZIO.foreach(functionCalls)(executeToolCall(_, tools))
              modelContent <- ZIO.fromEither(modelContentFromResponse(response))
              responseContent <- ZIO.fromEither(functionResponseContent(toolResults))
              _ <- ZIO.logInfo("Sending tool response(s) back to Gemini")
              result <- toolLoop(
                client = client,
                model = model,
                history = history ++ Chunk(modelContent, responseContent),
                tools = tools,
                sdkConfig = sdkConfig,
                remainingIterations = remainingIterations - 1,
                maxIterations = maxIterations
              )
            } yield result
          else
            ZIO.fromEither(GenAiMappers.textFromResponse(response)) <*
              ZIO.logInfo("Gemini returned final text response")
      } yield output

  private def executeToolCall[R](
      call: FunctionCall,
      tools: Chunk[GeminiTool[R]]
  ): ZIO[R, Throwable, ToolCallResult] = {
    val name = call.name().orElse("")
    val args = call.args().orElse(Map.empty.asJava)

    tools.find(_.definition.name == name) match {
      case None =>
        ZIO.logWarning(s"Gemini requested unknown tool: $name") *>
          ZIO.fail(GenAiError.UnknownTool(name))
      case Some(tool) =>
        for {
          argsJson <- ZIO.fromEither(stringifyJava(args))
          request = ToolCallRequest(id = None, name = name, argsJson = argsJson)
          _ <- ZIO.logInfo(s"Executing tool name=$name")
          _ <- ZIO.logDebug(s"Tool call args=${request.argsJson}")
          result <- tool.run(request).mapError { error =>
            GenAiError.ToolExecutionFailure(name, error.getMessage, error)
          }
          _ <- ZIO.fromEither(parseResponseObject(result.responseJson))
          _ <- ZIO.logInfo(s"Tool completed name=$name")
          _ <- ZIO.logDebug(s"Tool response=${result.responseJson}")
        } yield result
    }
  }

  private def userContent(text: String): Content =
    Content.builder().role("user").parts(Part.fromText(text)).build()

  private def modelContentFromResponse(response: GenerateContentResponse): Either[GenAiError.EmptyResponse, Content] =
    response
      .candidates()
      .flatMap(candidates => candidates.stream().findFirst())
      .flatMap(_.content())
      .toScala
      .toRight(GenAiError.EmptyResponse("No model content returned with Gemini tool call"))

  private def functionResponseContent(
      results: Chunk[ToolCallResult]
  ): Either[GenAiError.JsonDecodeFailure, Content] =
    for {
      parts <- results.foldLeft[Either[GenAiError.JsonDecodeFailure, List[Part]]](Right(Nil)) { (acc, result) =>
        for {
          collected <- acc
          response <- parseResponseObject(result.responseJson)
        } yield Part.fromFunctionResponse(result.name, response) :: collected
      }
    } yield Content
      .builder()
      .role("user")
      .parts(parts.reverse*)
      .build()
}
