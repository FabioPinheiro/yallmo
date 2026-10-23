package fmgp.yallmo.provider.gemini
import fmgp.yallmo.model.llm.ProviderGenerateOptions

/** ASAR-owned generation controls. Keep these independent from google-genai Java SDK classes. */
final case class GenAiConfig(
    model: String = GenAiConfig.DefaultModel,
    apiKey: Option[String] = None,
    apiVersion: Option[String] = Some("v1beta"),
    vertexAI: Boolean = false,
    project: Option[String] = None,
    location: Option[String] = None,
    timeoutMillis: Option[Int] = None,
    defaultOptions: ProviderGenerateOptions = ProviderGenerateOptions.fast
)

object GenAiConfig {
  val DefaultModel = "gemini-3.5-flash"
  val DefaultTtsModel = "gemini-3.8-flash-tts"
}

sealed trait GenAiError extends RuntimeException
object GenAiError {
  final case class InvalidConfig(message: String) extends RuntimeException(message) with GenAiError
  final case class MissingApiKey(message: String) extends RuntimeException(message) with GenAiError
  final case class SdkFailure(message: String, cause: Throwable)
      extends RuntimeException(message, cause)
      with GenAiError
  final case class EmptyResponse(message: String = "No content returned from Gemini")
      extends RuntimeException(message)
      with GenAiError
  final case class JsonDecodeFailure(body: String, message: String)
      extends RuntimeException(s"Failed to decode JSON: $message\nBody: $body")
      with GenAiError
  final case class UnknownTool(name: String)
      extends RuntimeException(s"Gemini requested unknown tool: $name")
      with GenAiError
  final case class ToolExecutionFailure(name: String, message: String, cause: Throwable)
      extends RuntimeException(s"Tool '$name' failed: $message", cause)
      with GenAiError
  final case class ToolLoopLimitExceeded(limit: Int)
      extends RuntimeException(s"Gemini tool loop exceeded max iterations: $limit")
      with GenAiError
}
