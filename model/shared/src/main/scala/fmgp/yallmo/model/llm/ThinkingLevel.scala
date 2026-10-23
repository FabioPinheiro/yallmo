package fmgp.yallmo.model.llm

import zio.json.JsonCodec

/** Provider-neutral preference for model reasoning effort; adapters may ignore unsupported values. */
enum ThinkingLevel derives JsonCodec {
  case Minimal
  case Low
  case Medium
  case High
}

object ThinkingLevel {
  def parse(value: String): Either[String, ThinkingLevel] =
    value.trim.toLowerCase match {
      case "minimal" | "min" => Right(ThinkingLevel.Minimal)
      case "low"             => Right(ThinkingLevel.Low)
      case "medium" | "med"  => Right(ThinkingLevel.Medium)
      case "high"            => Right(ThinkingLevel.High)
      case other             => Left(s"Unsupported thinking level: $other")
    }
}
