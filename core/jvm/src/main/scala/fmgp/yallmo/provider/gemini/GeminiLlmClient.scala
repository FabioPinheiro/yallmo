package fmgp.yallmo.provider.gemini

import com.google.genai.Client as GoogleGenAiClient
import zio.*
import fmgp.yallmo.LlmDebugCapture
import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.llm.{LlmProvider, ProviderGenerateOptions}

final case class GenAiClient(
    val client: GoogleGenAiClient,
    val config: GenAiConfig
) extends LlmProvider {

  override val defaultModel: String = config.model

  def inferenceWithText(
      prompt: String,
      options: ProviderGenerateOptions = config.defaultOptions,
      model: Option[String] = None
  ): IO[YallmoError, String] =
    (for {
      selectedModel <- ZIO.succeed(model.getOrElse(config.model))
      capture <- LlmDebugCapture.captureCallStarted(
        provider = "Gemini",
        model = selectedModel,
        prompt = prompt,
        options = options,
        toolNames = Seq.empty
      )
      text <- (
        for {
          sdkConfig <- ZIO.fromEither(GenAiMappers.toGenerateContentConfig(options))
          response <- ZIO
            .attemptBlocking(client.models.generateContent(selectedModel, prompt, sdkConfig))
            .mapError(error =>
              GenAiError.SdkFailure(s"google-genai generateContent failed: ${error.getMessage}", error)
            )
          text <- ZIO.fromEither(GenAiMappers.textFromResponse(response))
        } yield text
      ).tapBoth(
        error => LlmDebugCapture.captureCallFailed(capture, error),
        output => LlmDebugCapture.captureCallSucceeded(capture, output)
      ).onInterrupt(
        LlmDebugCapture.captureCallFailed(capture, InterruptedException("Gemini generation was interrupted"))
      )
    } yield text).mapError(YallmoError.fromThrowable)

  def generateWithTools[R](
      prompt: String,
      tools: Chunk[GeminiTool[R]],
      options: ProviderGenerateOptions = ProviderGenerateOptions.fast,
      model: Option[String] = None
  ): ZIO[R, YallmoError, String] =
    GenAiTools
      .generateWithTools(
        client = client,
        defaultModel = config.model,
        prompt = prompt,
        tools = tools,
        options = options,
        model = model
      )
      .mapError(YallmoError.fromThrowable)
}

object GenAiClient {

  val live: ZLayer[Any, Throwable, GenAiClient] =
    ZLayer.scoped {
      for {
        config <- loadConfig
        client <- makeClient(config)
      } yield GenAiClient(client, config)
    }

  private def loadConfig: Task[GenAiConfig] =
    for {
      model <- System.env("ASAR_GEMINI_MODEL")
      apiVersion <- System.env("ASAR_GEMINI_API_VERSION")
      temperature <- optionalDoubleEnv("ASAR_GEMINI_TEMPERATURE")
      topP <- optionalDoubleEnv("ASAR_GEMINI_TOP_P")
      topK <- optionalIntEnv("ASAR_GEMINI_TOP_K")
      maxOutputTokens <- optionalIntEnv("ASAR_GEMINI_MAX_OUTPUT_TOKENS")
      responseMimeType <- System.env("ASAR_GEMINI_RESPONSE_MIME_TYPE")
      timeout <- optionalIntEnv("ASAR_GEMINI_TIMEOUT_MILLIS")
      geminiApiKey <- System.env("GEMINI_API_KEY")
      options = ProviderGenerateOptions.fast.copy(
        temperature = temperature,
        topP = topP,
        topK = topK,
        maxOutputTokens = maxOutputTokens.orElse(ProviderGenerateOptions.fast.maxOutputTokens),
        responseMimeType = responseMimeType,
      )
    } yield GenAiConfig(
      model = model.getOrElse(GenAiConfig.DefaultModel),
      apiKey = geminiApiKey,
      apiVersion = apiVersion.orElse(Some("v1beta")),
      timeoutMillis = timeout,
      defaultOptions = options
    )

  private def makeClient(config: GenAiConfig): ZIO[Scope, Throwable, GoogleGenAiClient] =
    ZIO.acquireRelease {
      ZIO
        .attempt {
          var builder = GoogleGenAiClient.builder()
          config.apiKey.foreach(key => builder = builder.apiKey(key))
          config.project.foreach(value => builder = builder.project(value))
          config.location.foreach(value => builder = builder.location(value))
          if config.vertexAI then builder = builder.vertexAI(true)
          if (config.apiVersion.nonEmpty || config.timeoutMillis.nonEmpty) then {
            var httpOptionsBuilder = com.google.genai.types.HttpOptions.builder()
            config.apiVersion.foreach(v => httpOptionsBuilder = httpOptionsBuilder.apiVersion(v))
            config.timeoutMillis.foreach(v => httpOptionsBuilder = httpOptionsBuilder.timeout(v))
            val options = httpOptionsBuilder.build()
            builder = builder.httpOptions(options)
          }
          builder.build()
        }
        .mapError(error => GenAiError.SdkFailure(s"Failed to create google-genai client: ${error.getMessage}", error))
    } { client =>
      ZIO.attempt(client.close()).ignoreLogged
    }

  private def optionalDoubleEnv(name: String): Task[Option[Double]] =
    System.env(name).flatMap {
      case None        => ZIO.none
      case Some(value) =>
        ZIO
          .attempt(value.toDouble)
          .map(Some(_))
          .mapError(error => GenAiError.InvalidConfig(s"Invalid $name='$value': ${error.getMessage}"))
    }

  private def optionalIntEnv(name: String): Task[Option[Int]] =
    System.env(name).flatMap {
      case None        => ZIO.none
      case Some(value) =>
        ZIO
          .attempt(value.toInt)
          .map(Some(_))
          .mapError(error => GenAiError.InvalidConfig(s"Invalid $name='$value': ${error.getMessage}"))
    }
}
