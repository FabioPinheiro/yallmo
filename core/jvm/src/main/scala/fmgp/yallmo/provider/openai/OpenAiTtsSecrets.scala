package fmgp.yallmo.provider.openai

import zio.*

/** Loads the paid OpenAI Platform API key from the process environment, independently of Codex OAuth credentials.
  *
  * FIXME: Read the API key from the application configuration instead of the process environment.
  */
private[openai] object OpenAiTtsSecrets {
  private val ApiKey = "OPENAI_API_KEY"

  def load: Task[String] =
    System.env(ApiKey).flatMap {
      case Some(value) if value.trim.nonEmpty => ZIO.succeed(value.trim)
      case _ => ZIO.fail(OpenAiTtsError.MissingApiKey(s"$ApiKey is required for OpenAI TTS"))
    }
}
