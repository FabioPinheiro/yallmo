package fmgp.yallmo.provider

import zio.*
import fmgp.yallmo.model.llm.AudioInput
import fmgp.yallmo.model.llm.LlmProviderAudio

/** Dedicated transcription service backed by an OpenAI-compatible audio provider.
  *
  * It fixes the transcription policy while the provider owns the audio-inference capability.
  */
final class Transcriber(provider: LlmProviderAudio) {
  def transcribe(audio: AudioInput): Task[String] =
    provider.inferenceWithAudio(Transcriber.SystemPrompt, audio)
}

object Transcriber {
  val SystemPrompt =
    """Transcribe the supplied audio exactly. Preserve the spoken language. Do not translate, summarize, answer,
      |or call tools. Return only the transcript.""".stripMargin

  val live: URLayer[LlmProviderAudio, Transcriber] =
    ZLayer.fromFunction((provider: LlmProviderAudio) => new Transcriber(provider))
}
