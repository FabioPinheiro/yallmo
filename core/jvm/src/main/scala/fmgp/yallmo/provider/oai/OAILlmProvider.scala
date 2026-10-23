package fmgp.yallmo.provider.oai

import fmgp.yallmo.model.MessagePayload.AudioAttachment
import zio.*
import zio.http.*
import fmgp.yallmo.*
import fmgp.yallmo.model.*
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

  override def inferenceWithAudio(
      prompt: String,
      audio: AudioAttachment,
      model: Option[String] = None,
      readBlob: BlobId => IO[YallmoError, Blob] = Blob.unavailable
  ): IO[YallmoError, String] =
    for {
      selectedModel <- ZIO.fromEither(resolveModel(model))
      _ <- ZIO.fromEither(
        Either
          .cond(prompt.trim.nonEmpty, (), "Audio inference prompt must not be blank.")
          .left
          .map(YallmoError.InvalidConfig.apply)
      )
      resolved <- resolveAudio(audio, readBlob)
      captureId = LlmProviderPayloadDebug.newTurnId()
      body = OAIChatCompletionsMapper.audioRequestBody(selectedModel, prompt, audio)
      _ <- LlmProviderPayloadDebug.captureRequest(captureId, "OpenAI-compatible endpoint", selectedModel, body)
      wire = OAIChatCompletionsMapper.audioRequestBody(selectedModel, prompt, audio, Map(audio.blobId -> resolved))
      response <- complete(wire)
      _ <- ZIO
        .fail(YallmoError.ProtocolFailure("Audio completion returned unexpected tool calls"))
        .when(response.functionCalls.nonEmpty)
      text <- ZIO
        .fromOption(response.content)
        .orElseFail(YallmoError.EmptyResponse("OpenAI-compatible endpoint returned no audio completion text"))
    } yield text

  /** Legacy provider-owned tool execution retained for compatibility; prefer the configured agent/runner path. */
  @deprecated("Use Agent with NativeFunctionAgent so AgentRunner owns tool execution.", "0.2.0")
  override def generateWithTools[R](
      prompt: String,
      tools: Chunk[LlmTool[R]],
      options: ProviderGenerateOptions,
      model: Option[String]
  ): ZIO[R, YallmoError, String] =
    generate(prompt, tools, options, model).mapError(YallmoError.fromThrowable)

  // Compatibility bridge: prompt-only inference supplies no tools; only this bridge may call the deprecated loop.
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

  @deprecated("Use Agent with NativeFunctionAgent so AgentRunner owns tool execution.", "0.2.0")
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

  @deprecated("Use Agent with NativeFunctionAgent so AgentRunner owns tool execution.", "0.2.0")
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
    val containsAudio = body("messages").arr.exists(message =>
      message.obj
        .get("content")
        .flatMap(_.arrOpt)
        .exists(_.value.exists(part => part.objOpt.exists(_.get("type").flatMap(_.strOpt).contains("input_audio"))))
    )
    // Transport exceptions may include the submitted request, including the recording. Drop their causes too.
    def transportFailure(error: Throwable): YallmoError =
      if containsAudio then YallmoError.HttpFailure("OpenAI-compatible audio request failed (details omitted)")
      else YallmoError.fromThrowable(error)

    val request = Request
      .post(config.chatCompletionsUrl, Body.fromString(ujson.write(body)))
      .addHeader("Content-Type", "application/json")
      .addHeader("Accept", "application/json")

    Client
      .batched(request)
      .provideEnvironment(ZEnvironment(client))
      .mapError(transportFailure)
      .flatMap { response =>
        response.body.asString.mapError(transportFailure).flatMap { body =>
          if response.status.isSuccess then
            ZIO.fromEither(OAIChatCompletionsMapper.parseResponse(body)).mapError(YallmoError.ProtocolFailure.apply)
          else
            ZIO.fail(
              YallmoError.HttpFailure(
                s"OpenAI-compatible endpoint returned HTTP ${response.status.code}: ${
                    if containsAudio then "<response body omitted for audio privacy>" else safeErrorBody(body)
                  }"
              )
            )
        }
      }
      .timeoutFail(YallmoError.HttpFailure("OpenAI-compatible endpoint request timed out"))(RequestTimeout)
}

object OAILlmProvider {
  val RequestTimeout: Duration = 2.minutes
  val MaxAudioBytes = 1024 * 1024

  private[oai] def validateAudio(bytes: Chunk[Byte]): Either[String, Chunk[Byte]] =
    for {
      _ <- Either.cond(bytes.nonEmpty, (), "Audio bytes must not be empty.")
      _ <- Either.cond(bytes.size <= MaxAudioBytes, (), s"Audio input exceeds the $MaxAudioBytes byte limit.")
    } yield bytes

  private[oai] def resolveAudio(
      audio: AudioAttachment,
      reader: BlobId => IO[YallmoError, Blob]
  ): IO[YallmoError, String] =
    for {
      blob <- reader(audio.blobId)
      _ <- ZIO
        .fail(YallmoError.InvalidConfig("Blob lookup returned a different ID"))
        .unless(blob.id == audio.blobId)
      bytes <- ZIO.fromEither(validateAudio(blob.bytes).left.map(YallmoError.InvalidConfig.apply))
    } yield java.util.Base64.getEncoder.encodeToString(bytes.toArray)

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
