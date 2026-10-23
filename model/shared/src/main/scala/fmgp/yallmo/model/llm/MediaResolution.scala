package fmgp.yallmo.model.llm

import zio.json.JsonCodec

/** Provider-neutral preference for multimodal input resolution; adapters may ignore unsupported values. */
enum MediaResolution derives JsonCodec {
  case Low
  case Medium
  case High
}

object MediaResolution {
  def parse(value: String): Either[String, MediaResolution] =
    value.trim.toLowerCase.replace("-", "_") match {
      case "low" | "media_resolution_low"       => Right(MediaResolution.Low)
      case "medium" | "media_resolution_medium" => Right(MediaResolution.Medium)
      case "high" | "media_resolution_high"     => Right(MediaResolution.High)
      case other                                => Left(s"Unsupported media resolution: $other")
    }
}
