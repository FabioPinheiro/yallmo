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
      model: Option[String] = None,
      systemPrompt: Option[String] = None,
      thinkingLevel: Option[String] = None,
      parallelToolCalls: Boolean = false
  ) derives JsonCodec

  /** Uses explicit settings first, with caller-supplied prompt/model fallbacks only when absent. Without a fallback,
    * the corresponding setting is required. Explicit blank or malformed models never silently select a default. Errors
    * never contain setting values.
    */
  def decode(
      definition: AgentConfig,
      defaultSystemPrompt: Option[String] = None,
      defaultModel: Option[String] = None
  ): Either[YallmoError, ConfiguredAgentSettings] =
    for {
      _ <- Either.cond(
        definition.config.fields
          .collectFirst { case ("model", value) => value }
          .forall(_.isInstanceOf[zio.json.ast.Json.Str]),
        (),
        YallmoError.InvalidConfig("Agent config.model must be a string when supplied")
      )
      fields <- definition.config.toJson
        .fromJson[Fields]
        .left
        .map(_ =>
          YallmoError.InvalidConfig(
            "Agent settings require valid model/systemPrompt/thinkingLevel/parallelToolCalls fields"
          )
        )
      model <- fields.model
        .orElse(defaultModel)
        .toRight(
          YallmoError.InvalidConfig("Agent config.model is required when the provider has no default model")
        )
      _ <- Either.cond(
        model.trim.nonEmpty,
        (),
        YallmoError.InvalidConfig("Agent config.model or provider default model must not be blank")
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
    } yield ConfiguredAgentSettings(model.trim, prompt, thinking, fields.parallelToolCalls)
}
