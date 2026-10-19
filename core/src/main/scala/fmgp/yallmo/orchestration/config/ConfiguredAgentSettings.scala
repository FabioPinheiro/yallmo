package fmgp.yallmo.orchestration.config

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.llm.ThinkingLevel
import zio.json.*

/** Shared settings for native request assembly; decoding is local and never probes a provider. */
final case class ConfiguredAgentSettings(
    model: String,
    systemPrompt: String,
    thinkingLevel: Option[ThinkingLevel],
    parallelToolCalls: Boolean
)

object ConfiguredAgentSettings {
  private final case class Fields(
      model: String,
      systemPrompt: Option[String] = None,
      thinkingLevel: Option[String] = None,
      parallelToolCalls: Boolean = false
  ) derives JsonCodec

  /** Uses the configured prompt, or a caller-supplied fallback when absent. Without a fallback, the prompt is required.
    * An explicitly blank prompt is always invalid. Errors never contain setting values.
    */
  def decode(
      definition: AgentConfig,
      defaultSystemPrompt: Option[String] = None
  ): Either[YallmoError, ConfiguredAgentSettings] =
    for {
      fields <- definition.config.toJson
        .fromJson[Fields]
        .left
        .map(_ =>
          YallmoError.InvalidConfig(
            "Agent settings require an explicit model and valid systemPrompt/thinkingLevel/parallelToolCalls fields"
          )
        )
      _ <- Either.cond(
        fields.model.trim.nonEmpty,
        (),
        YallmoError.InvalidConfig("Agent config.model must not be blank")
      )
      prompt <- fields.systemPrompt
        .orElse(defaultSystemPrompt)
        .toRight(
          YallmoError.InvalidConfig("Agent config.systemPrompt must be explicit or supplied by the application")
        )
      _ <- Either.cond(
        prompt.trim.nonEmpty,
        (),
        YallmoError.InvalidConfig("Agent config.systemPrompt must not be blank")
      )
      thinking <- fields.thinkingLevel match {
        case None        => Right(None)
        case Some(value) =>
          ThinkingLevel
            .parse(value)
            .map(Some(_))
            .left
            .map(_ => YallmoError.InvalidConfig("Agent thinkingLevel must be minimal, low, medium or high"))
      }
    } yield ConfiguredAgentSettings(fields.model.trim, prompt, thinking, fields.parallelToolCalls)
}
