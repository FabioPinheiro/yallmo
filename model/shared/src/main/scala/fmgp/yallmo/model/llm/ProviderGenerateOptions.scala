package fmgp.yallmo.model.llm

import zio.Chunk
import zio.json.JsonCodec

/** Controls provider-neutral language-model generation behavior. */
final case class ProviderGenerateOptions(
    systemInstruction: Option[String] = None,
    temperature: Option[Double] = None,
    topP: Option[Double] = None,
    topK: Option[Int] = None,
    candidateCount: Option[Int] = None,
    maxOutputTokens: Option[Int] = None,
    stopSequences: Chunk[String] = Chunk.empty,
    responseMimeType: Option[String] = Some("application/json"),
    seed: Option[Int] = None,
    thinkingLevel: Option[ThinkingLevel] = None,
    mediaResolution: Option[MediaResolution] = None,
    maxToolIterations: Int = 8
) derives JsonCodec {
  def validate: Either[String, Unit] =
    for {
      _ <- validateRange("temperature", temperature, min = 0.0, max = 2.0)
      _ <- validateRange("topP", topP, min = 0.0, max = 1.0)
      _ <- topK.filter(_ <= 0).map(v => Left(s"topK must be positive, got $v")).getOrElse(Right(()))
      _ <- candidateCount.filter(_ <= 0).map(v => Left(s"candidateCount must be positive, got $v")).getOrElse(Right(()))
      _ <- maxOutputTokens
        .filter(_ <= 0)
        .map(v => Left(s"maxOutputTokens must be positive, got $v"))
        .getOrElse(Right(()))
      _ <- Either.cond(maxToolIterations > 0, (), s"maxToolIterations must be positive, got $maxToolIterations")
    } yield ()

  private def validateRange(name: String, value: Option[Double], min: Double, max: Double): Either[String, Unit] =
    value match {
      case Some(v) if v < min || v > max => Left(s"$name must be between $min and $max, got $v")
      case _                             => Right(())
    }
}

object ProviderGenerateOptions {
  val fast: ProviderGenerateOptions = ProviderGenerateOptions(
    responseMimeType = Some("application/json"),
    maxOutputTokens = Some(8192)
  )
  val creative: ProviderGenerateOptions = ProviderGenerateOptions(
    temperature = Some(0.9),
    topP = Some(0.95),
    responseMimeType = Some("application/json"),
    maxOutputTokens = Some(16000)
  )
  val research: ProviderGenerateOptions = ProviderGenerateOptions(
    temperature = Some(0.4),
    maxOutputTokens = Some(32000),
    responseMimeType = Some("application/json")
  )
}
