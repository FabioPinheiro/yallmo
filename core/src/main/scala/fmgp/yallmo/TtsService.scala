package fmgp.yallmo

import fmgp.yallmo.provider.gemini.{GenAiClient, GenAiConfig, GeminiTtsService}
import fmgp.yallmo.provider.openai.OpenAiTtsService
import zio.*
import zio.http.Client
import zio.stream.*

/** TTS providers supported by ASAR. */
enum TtsProvider(val cliName: String, val defaultModel: String, val defaultVoice: String) {
  case Gemini extends TtsProvider("gemini", GenAiConfig.DefaultTtsModel, "Kore")
  case OpenAi extends TtsProvider("openai", OpenAiTtsService.DefaultModel, OpenAiTtsService.DefaultVoice)
}

object TtsProvider {
  def parse(value: String): Either[String, TtsProvider] =
    TtsProvider.values
      .find(_.cliName.equalsIgnoreCase(value.trim))
      .toRight(
        s"Unsupported TTS provider '$value'. Expected one of: ${TtsProvider.values.map(_.cliName).mkString(", ")}"
      )
}

/** Provider-neutral text-to-speech boundary. */
trait TtsService {
  def synthesize(request: TtsRequest): Task[Chunk[AudioChunk]]
  def synthesizeStream(request: TtsRequest): ZStream[Any, Throwable, AudioChunk] =
    ZStream.fromZIO(synthesize(request)).flatMap(ZStream.fromChunk)
}

object TtsService {

  /** Selects the concrete provider when the service layer is assembled. */
  def live(provider: TtsProvider): ZLayer[Client & GenAiClient, Throwable, TtsService] =
    provider match {
      case TtsProvider.Gemini => GeminiTtsService.live
      case TtsProvider.OpenAi => OpenAiTtsService.live
    }

  def synthesize(request: TtsRequest): RIO[TtsService, Chunk[AudioChunk]] =
    ZIO.serviceWithZIO[TtsService](_.synthesize(request))
}
