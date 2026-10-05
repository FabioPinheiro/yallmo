package fmgp.yallmo.model.llm

import zio.Chunk

/** The container format of audio supplied to a multimodal model request. */
enum AudioFormat(val wireName: String) {
  case Wav extends AudioFormat("wav")
}

/** In-memory audio input. Persistence is deliberately outside this type. */
final case class AudioInput(bytes: Chunk[Byte], format: AudioFormat) {
  def validate: Either[String, AudioInput] =
    Either.cond(bytes.nonEmpty, this, "Audio bytes must not be empty.")
}
