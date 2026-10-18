package fmgp.yallmo.provider.oai

import zio.*
import zio.http.*
import fmgp.yallmo.*
import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.llm.*

/** LLM provider for an OpenAI-compatible Chat Completions endpoint. */
final case class OAILlmProvider private[yallmo] (
    client: Client,
    config: OAIConfig,
) extends LlmProvider
    with LlmProviderAudio {
  import OAILlmProvider.*

  override val defaultModel: String = config.model

  override def inferenceWithText(
      prompt: String,
      options: ProviderGenerateOptions,
      model: Option[String]
  ): IO[YallmoError, String] =
    generate(prompt, Chunk.empty, options, model).mapError(YallmoError.fromThrowable)

  /** Sends one audio-only Chat Completions request with no transcript, tools, or retained state. */
  override def inferenceWithAudio(
      prompt: String,
      audio: AudioInput,
      model: Option[String] = None
  ): IO[YallmoError, String] =
    for {
      _ <- ZIO.fromEither(
        Either
          .cond(prompt.trim.nonEmpty, (), "Transcription system prompt must not be blank.")
          .left
          .map(YallmoError.InvalidConfig.apply)
      )
      _ <- ZIO.fromEither(OAILlmProvider.validateAudio(audio).left.map(YallmoError.InvalidConfig.apply))
      selectedModel <- ZIO.fromEither(resolveModel(model))
      response <- complete(OAIChatCompletionsMapper.audioRequestBody(selectedModel, prompt, audio))
      text <- ZIO.fromEither(
        response.content.toRight(YallmoError.EmptyResponse("OpenAI-compatible endpoint returned no transcription text"))
      )
    } yield text

  override def generateWithTools[R](
      prompt: String,
      tools: Chunk[LlmTool[R]],
      options: ProviderGenerateOptions,
      model: Option[String]
  ): ZIO[R, YallmoError, String] =
    generate(prompt, tools, options, model).mapError(YallmoError.fromThrowable)

  private def generate[R](
      prompt: String,
      tools: Chunk[LlmTool[R]],
      options: ProviderGenerateOptions,
      model: Option[String]
  ): ZIO[R, Throwable, String] = {
    for
      selectedModel <- ZIO.fromEither(resolveModel(model))
      capture <- LlmDebugCapture.captureCallStarted(
        provider = "OpenAI-compatible endpoint",
        model = selectedModel,
        prompt = prompt,
        options = options,
        toolNames = tools.map(_.definition.name)
      )
      result <- (ZIO.fromEither(options.validate.left.map(YallmoError.InvalidConfig.apply)) *>
        toolLoop(
          selectedModel,
          OAIChatCompletionsMapper.initialMessages(prompt, options),
          tools,
          options,
          remainingIterations = options.maxToolIterations
        ))
        .tapBoth(
          error => LlmDebugCapture.captureCallFailed(capture, error),
          output => LlmDebugCapture.captureCallSucceeded(capture, output)
        )
        .onInterrupt(
          LlmDebugCapture
            .captureCallFailed(capture, InterruptedException("OpenAI-compatible endpoint generation was interrupted"))
        )
    yield result
  }

  private def resolveModel(model: Option[String]): Either[YallmoError, String] =
    val selected = model.getOrElse(defaultModel)
    Either.cond(selected.trim.nonEmpty, selected, YallmoError.InvalidConfig("An explicit OAI model is required"))

  private def toolLoop[R](
      model: String,
      messages: ujson.Arr,
      tools: Chunk[LlmTool[R]],
      options: ProviderGenerateOptions,
      remainingIterations: Int
  ): ZIO[R, Throwable, String] =
    call(model, messages, tools, options).flatMap { response =>
      if response.functionCalls.isEmpty then
        ZIO.fromEither(
          response.content.toRight(YallmoError.EmptyResponse("OpenAI-compatible endpoint returned no text response"))
        )
      else if remainingIterations <= 0 then ZIO.fail(YallmoError.ToolLoopLimitExceeded(options.maxToolIterations))
      else
        ZIO.foreach(response.functionCalls)(executeTool(_, tools)).flatMap { toolResults =>
          val nextMessages = ujson.Arr.from(messages.value ++ Vector(response.message) ++ toolResults)
          toolLoop(model, nextMessages, tools, options, remainingIterations - 1)
        }
    }

  private def executeTool[R](
      call: OAIFunctionCall,
      tools: Chunk[LlmTool[R]]
  ): ZIO[R, Throwable, ujson.Obj] =
    tools.find(_.definition.name == call.name) match
      case None       => ZIO.fail(YallmoError.LlmUnknownTool(call.name))
      case Some(tool) =>
        tool
          .run(LlmToolCall(Some(call.callId), call.name, call.arguments))
          .mapError(error => YallmoError.ToolExecutionFailure(call.name, error))
          .map(result => ujson.Obj("role" -> "tool", "tool_call_id" -> call.callId, "content" -> result.responseJson))

  private def call[R](
      model: String,
      messages: ujson.Arr,
      tools: Chunk[LlmTool[R]],
      options: ProviderGenerateOptions
  ): Task[OAIResponse] = {
    val body = OAIChatCompletionsMapper.requestBody(model, messages, tools, options)
    ZIO.logInfo(
      s"Calling OpenAI-compatible endpoint model=$model messages=${messages.value.size} tools=${tools.map(_.definition.name).mkString("[", ",", "]")}"
    ) *>
      complete(body)
  }

  private[oai] def complete(body: ujson.Obj): IO[YallmoError, OAIResponse] =
    val request = Request
      .post(config.chatCompletionsUrl, Body.fromString(ujson.write(body)))
      .addHeader("Content-Type", "application/json")
      .addHeader("Accept", "application/json")

    Client
      .batched(request)
      .provideEnvironment(ZEnvironment(client))
      .flatMap { response =>
        response.body.asString.flatMap { body =>
          if response.status.isSuccess then
            ZIO.fromEither(OAIChatCompletionsMapper.parseResponse(body)).mapError(YallmoError.ProtocolFailure.apply)
          else
            ZIO.fail(
              YallmoError.HttpFailure(
                s"OpenAI-compatible endpoint returned HTTP ${response.status.code}: ${safeErrorBody(body)}"
              )
            )
        }
      }
      .timeoutFail(YallmoError.HttpFailure("OpenAI-compatible endpoint request timed out"))(RequestTimeout)
      .mapError(YallmoError.fromThrowable)
}

object OAILlmProvider {
  val RequestTimeout: Duration = 2.minutes
  val MaxAudioBytes = 1024 * 1024

  private[oai] def validateAudio(audio: AudioInput): Either[String, AudioInput] =
    for {
      _ <- audio.validate
      _ <- Either.cond(
        audio.bytes.size <= MaxAudioBytes,
        (),
        s"Audio input exceeds the $MaxAudioBytes byte limit."
      )
    } yield audio

  val live: ZLayer[Client, Throwable, OAILlmProvider] =
    ZLayer.fromZIO {
      for {
        client <- ZIO.service[Client]
        config <- OAIConfig.load
      } yield OAILlmProvider(client, config)
    }

  private def safeErrorBody(body: String): String =
    body.trim.replaceAll("\\s+", " ").take(1000) match
      case ""    => "<empty response body>"
      case value => value
}

/** Connection settings. An empty model means request-level model selection is required. */
final case class OAIConfig(baseUrl: String, model: String) {
  def chatCompletionsUrl: String = s"$baseUrl/chat/completions"
}

object OAIConfig {
  val UrlEnvironmentVariable = "MY_MODEL_URL"
  val ModelEnvironmentVariable = "MY_MODEL_NAME"
  def load: Task[OAIConfig] =
    for {
      url <- requiredEnvironmentValue(UrlEnvironmentVariable)
      model <- requiredEnvironmentValue(ModelEnvironmentVariable)
      config <- ZIO.fromEither(from(url, model))
    } yield config

  private def requiredEnvironmentValue(name: String): Task[String] =
    System.env(name).flatMap {
      case Some(value) => ZIO.succeed(value)
      case None        => ZIO.fail(YallmoError.InvalidConfig(s"$name is required"))
    }

  private[oai] def from(url: String, model: String): Either[YallmoError.InvalidConfig, OAIConfig] =
    for {
      config <- fromEndpoint(url)
      trimmedModel = model.trim
      _ <- Either.cond(
        trimmedModel.nonEmpty,
        (),
        YallmoError.InvalidConfig(s"$ModelEnvironmentVariable must not be blank")
      )
    } yield config.copy(model = trimmedModel)

  private[yallmo] def fromEndpoint(url: String): Either[YallmoError.InvalidConfig, OAIConfig] =
    val normalizedUrl = url.trim.reverse.dropWhile(_ == '/').reverse
    for {
      _ <- Either.cond(
        normalizedUrl.nonEmpty,
        (),
        YallmoError.InvalidConfig("OAI endpoint must not be blank")
      )
      uri <- scala.util
        .Try(java.net.URI.create(normalizedUrl))
        .toEither
        .left
        .map(_ => YallmoError.InvalidConfig("Invalid OAI endpoint"))
      _ <- Either.cond(
        uri.getScheme == "http" || uri.getScheme == "https",
        (),
        YallmoError.InvalidConfig("OAI endpoint must use http or https")
      )
      _ <- Either.cond(
        uri.getHost != null,
        (),
        YallmoError.InvalidConfig("OAI endpoint must include a host")
      )
      _ <- Either.cond(
        uri.getRawUserInfo == null && uri.getRawQuery == null && uri.getRawFragment == null,
        (),
        YallmoError.InvalidConfig("OAI endpoint must not contain credentials, query parameters or a fragment")
      )
    } yield OAIConfig(normalizedUrl, "")
}

private[oai] final case class OAIFunctionCall(callId: String, name: String, arguments: String)
private[oai] final case class OAIResponse(
    message: ujson.Obj,
    functionCalls: Vector[OAIFunctionCall],
    content: Option[String],
    finishReason: Option[String] = None,
    responseId: Option[String] = None
)
