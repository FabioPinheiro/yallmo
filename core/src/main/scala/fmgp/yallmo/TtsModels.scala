package fmgp.yallmo

import fmgp.yallmo.provider.gemini.GenAiConfig

final case class PcmFormat(
    sampleRateHz: Float = 24000.0f,
    sampleSizeBits: Int = 16,
    channels: Int = 1,
    signed: Boolean = true,
    bigEndian: Boolean = false
) {
  def toJavax: javax.sound.sampled.AudioFormat =
    javax.sound.sampled.AudioFormat(sampleRateHz, sampleSizeBits, channels, signed, bigEndian)
}

final case class AudioChunk(
    bytes: Array[Byte],
    format: PcmFormat = PcmFormat(),
    mimeType: Option[String] = Some("audio/pcm")
)

final case class OpenAiTtsOptions(
    instructions: Option[String] = None,
    speed: Option[Double] = None
)

/** A named Gemini voice used for a multi-speaker synthesis request. */
final case class GeminiTtsSpeaker(name: String, voiceName: String)

/** A spoken TTS turn. Speaker and style are used by Gemini 3.8; other providers use its text. */
final case class TtsTurn(
    text: String,
    speaker: Option[String] = None,
    style: Option[String] = None
)

/** Gemini 3.8-specific synthesis controls. */
final case class GeminiTtsOptions(
    style: Option[String] = None,
    speakers: Seq[GeminiTtsSpeaker] = Seq.empty
)

final case class TtsOptions(
    voiceName: String = "Kore",
    languageCode: Option[String] = None,
    format: PcmFormat = PcmFormat(),
    openAi: OpenAiTtsOptions = OpenAiTtsOptions(),
    gemini: GeminiTtsOptions = GeminiTtsOptions()
)

object TtsTextSplitter {
  val DefaultMaxChars = 3800

  def split(text: String, maxChars: Int = DefaultMaxChars): Seq[String] = {
    val clean = text.trim
    if clean.isEmpty then Seq.empty
    else {
      val paragraphs = clean.split("\\n\\s*\\n").toSeq.map(_.trim).filter(_.nonEmpty)
      val chunks = paragraphs.foldLeft(Vector.empty[String]) { (acc, paragraph) =>
        appendPieces(acc, splitLongPiece(paragraph, maxChars), maxChars)
      }
      chunks.filter(_.nonEmpty)
    }
  }

  private def appendPieces(chunks: Vector[String], pieces: Seq[String], maxChars: Int): Vector[String] =
    pieces.foldLeft(chunks) { (acc, piece) =>
      acc.lastOption match {
        case Some(last) if last.length + 2 + piece.length <= maxChars => acc.init :+ s"$last\n\n$piece"
        case _                                                        => acc :+ piece
      }
    }

  private def splitLongPiece(text: String, maxChars: Int): Seq[String] =
    if text.length <= maxChars then Seq(text)
    else {
      val sentences = text.split("(?<=[.!?])\\s+").toSeq.map(_.trim).filter(_.nonEmpty)
      if sentences.size <= 1 then text.grouped(maxChars).toSeq
      else
        appendPieces(
          Vector.empty,
          sentences.flatMap(sentence =>
            if sentence.length <= maxChars then Seq(sentence) else sentence.grouped(maxChars).toSeq
          ),
          maxChars
        )
    }
}

final case class TtsRequest(
    text: Seq[String],
    model: String = GenAiConfig.DefaultTtsModel,
    options: TtsOptions = TtsOptions(),
    turns: Seq[TtsTurn] = Seq.empty
) {

  /** Turns take precedence when a provider needs per-turn speaker or delivery metadata. */
  def effectiveTurns: Seq[TtsTurn] =
    if turns.nonEmpty then turns else text.map(value => TtsTurn(value))
}

object TtsRequest {
  def fromText(
      text: String,
      model: String = GenAiConfig.DefaultTtsModel,
      options: TtsOptions = TtsOptions(),
      maxChars: Int = TtsTextSplitter.DefaultMaxChars
  ): TtsRequest = TtsRequest(TtsTextSplitter.split(text, maxChars), model, options)

  def fromText(text: String, provider: TtsProvider): TtsRequest =
    fromText(text, provider, TtsTextSplitter.DefaultMaxChars)

  def fromText(text: String, provider: TtsProvider, maxChars: Int): TtsRequest =
    fromText(
      text,
      model = provider.defaultModel,
      options = TtsOptions(voiceName = provider.defaultVoice),
      maxChars = maxChars
    )
}
