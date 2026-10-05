package fmgp.yallmo.provider.gemini

import fmgp.yallmo.{AudioChunk, GeminiTtsSpeaker, PcmFormat, TtsRequest, TtsService, TtsTurn}
import zio.*
import zio.http.*

import java.util.Base64

/** Gemini 3.8 Flash TTS adapter.
  *
  * Gemini 3.8 adds `speechMetadata` and `responseFormat.audio`, which are not yet represented by the Java SDK types
  * used elsewhere in ASAR. This adapter calls its documented GenerateContent REST schema directly while preserving
  * ASAR's 24 kHz raw-PCM TTS contract.
  */
final case class GeminiTtsService private[gemini] (client: Client, apiKey: String) extends TtsService {
  import GeminiTtsService.*

  override def synthesize(request: TtsRequest): Task[Chunk[AudioChunk]] =
    for {
      _ <- ZIO.fromEither(validate(request).left.map(GenAiError.InvalidConfig.apply))
      turns = request.effectiveTurns
      groups = if request.options.gemini.speakers.nonEmpty then Seq(turns) else turns.map(Seq(_))
      _ <- ZIO.logInfo(
        s"Gemini TTS synthesize: model=${request.model} voice=${request.options.voiceName} turns=${turns.size} requests=${groups.size}"
      )
      chunks <- ZIO.foreach(Chunk.fromIterable(groups.zipWithIndex)) { case (group, index) =>
        ZIO.logInfo(
          s"Gemini TTS GenerateContent call: model=${request.model} request=${index + 1}/${groups.size} turns=${group.size}"
        ) *>
          synthesizeGroup(request, group)
      }
      result = chunks.flatten
      _ <- ZIO.fail(GenAiError.EmptyResponse("No audio returned from Gemini TTS")).when(result.isEmpty)
    } yield result

  private def synthesizeGroup(request: TtsRequest, turns: Seq[TtsTurn]): Task[Chunk[AudioChunk]] =
    Client
      .batched(generateContentRequest(request, turns))
      .provideEnvironment(ZEnvironment(client))
      .flatMap { response =>
        response.body.asString.flatMap { body =>
          if response.status.isSuccess then
            ZIO.fromEither(decodeResponse(body, request.options.format).left.map(failure))
          else
            ZIO.fail(
              failure(s"Gemini TTS returned HTTP ${response.status.code}: ${safeErrorBody(body)}")
            )
        }
      }
      .timeoutFail(failure("Gemini TTS request timed out"))(RequestTimeout)

  private def generateContentRequest(request: TtsRequest, turns: Seq[TtsTurn]): Request =
    Request
      .post(generateContentUrl(request.model), Body.fromString(ujson.write(requestBody(request, turns))))
      .addHeader("x-goog-api-key", apiKey)
      .addHeader("Content-Type", "application/json")
      .addHeader("Accept", "application/json")
}

object GeminiTtsService {
  val GenerateContentUrl = "https://generativelanguage.googleapis.com/v1beta/models"
  val DefaultResponseMimeType = "AUDIO_L16"
  val SampleRateHz = 24000
  val RequestTimeout: Duration = 2.minutes

  val live: ZLayer[Client & GenAiClient, Throwable, TtsService] =
    ZLayer.fromZIO {
      for {
        client <- ZIO.service[Client]
        genAi <- ZIO.service[GenAiClient]
        apiKey <- ZIO.fromEither(
          genAi.config.apiKey
            .filter(_.trim.nonEmpty)
            .toRight(GenAiError.MissingApiKey("GEMINI_API_KEY is required for Gemini TTS"))
        )
      } yield GeminiTtsService(client, apiKey)
    }

  private[gemini] def generateContentUrl(model: String): String =
    s"$GenerateContentUrl/${model.trim}:generateContent"

  private[gemini] def requestBody(request: TtsRequest, turns: Seq[TtsTurn]): ujson.Obj = {
    val options = request.options
    val parts = turns.map { turn =>
      val part = ujson.Obj("text" -> turn.text)
      val metadata = ujson.Obj()
      turn.speaker.filter(_.trim.nonEmpty).foreach(value => metadata("speaker") = value)
      turn.style.orElse(options.gemini.style).filter(_.trim.nonEmpty).foreach(value => metadata("style") = value)
      if metadata.value.nonEmpty then part("speechMetadata") = metadata
      part
    }
    val speechConfig =
      if options.gemini.speakers.nonEmpty then
        ujson.Obj(
          "multiSpeakerVoiceConfig" -> ujson.Obj(
            "speakerVoiceConfigs" -> ujson.Arr(options.gemini.speakers.map(speakerVoiceConfig)*)
          )
        )
      else
        ujson.Obj(
          "voiceConfig" -> ujson.Obj(
            "prebuiltVoiceConfig" -> ujson.Obj("voiceName" -> options.voiceName)
          )
        )

    options.languageCode.foreach(code => speechConfig("languageCode") = code)

    ujson.Obj(
      "contents" -> ujson.Arr(ujson.Obj("role" -> "user", "parts" -> ujson.Arr(parts*))),
      "generationConfig" -> ujson.Obj(
        "responseModalities" -> ujson.Arr("AUDIO"),
        "responseFormat" -> ujson.Obj(
          "audio" -> ujson.Obj("mimeType" -> DefaultResponseMimeType, "sampleRate" -> SampleRateHz)
        ),
        "speechConfig" -> speechConfig
      )
    )
  }

  private[gemini] def decodeResponse(body: String, format: PcmFormat): Either[String, Chunk[AudioChunk]] =
    scala.util
      .Try {
        val candidateParts = ujson.read(body)("candidates").arr.toSeq.flatMap { candidate =>
          candidate("content")("parts").arr.toSeq
        }
        val chunks = candidateParts
          .flatMap { part =>
            part.obj.get("inlineData").flatMap(_.obj.get("data")).flatMap(_.strOpt).map { encoded =>
              AudioChunk(Base64.getDecoder.decode(encoded), format, Some("audio/pcm"))
            }
          }
          .filter(_.bytes.nonEmpty)
        Chunk.fromIterable(chunks)
      }
      .toEither
      .left
      .map(error => s"Invalid Gemini TTS response: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}")

  private[gemini] def validate(request: TtsRequest): Either[String, Unit] = {
    val turns = request.effectiveTurns
    val speakers = request.options.gemini.speakers
    val speakerNames = speakers.map(_.name)

    for {
      _ <- Either.cond(request.model.trim.nonEmpty, (), "Gemini TTS model must not be empty")
      _ <- Either.cond(turns.nonEmpty, (), "Gemini TTS requires at least one non-empty turn")
      _ <- Either.cond(turns.forall(_.text.trim.nonEmpty), (), "Gemini TTS turns must not be empty")
      _ <- Either.cond(request.options.format == PcmFormat(), (), "Gemini TTS requires 24 kHz 16-bit mono PCM")
      _ <- Either.cond(
        speakers.isEmpty || speakers.size == 2,
        (),
        "Gemini TTS multi-speaker synthesis requires exactly two speakers"
      )
      _ <- Either.cond(
        speakerNames.distinct.size == speakerNames.size && speakers.forall(speaker =>
          speaker.name.trim.nonEmpty && speaker.voiceName.trim.nonEmpty
        ),
        (),
        "Gemini TTS speaker names and voices must be unique and non-empty"
      )
      _ <- Either.cond(
        speakers.isEmpty || turns.forall(turn => turn.speaker.exists(speakerNames.contains)),
        (),
        "Gemini TTS multi-speaker turns must name a configured speaker"
      )
    } yield ()
  }

  private def speakerVoiceConfig(speaker: GeminiTtsSpeaker): ujson.Obj =
    ujson.Obj(
      "speaker" -> speaker.name,
      "voiceConfig" -> ujson.Obj("prebuiltVoiceConfig" -> ujson.Obj("voiceName" -> speaker.voiceName))
    )

  private def failure(message: String): GenAiError.SdkFailure =
    GenAiError.SdkFailure(message, RuntimeException(message))

  private def safeErrorBody(body: String): String =
    body.trim.replaceAll("\\s+", " ").take(1000) match {
      case ""    => "<empty response body>"
      case value => value
    }
}
