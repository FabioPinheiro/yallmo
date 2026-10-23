package fmgp.yallmo.provider.openai

import fmgp.yallmo.{AudioChunk, PcmFormat, TtsRequest, TtsService}
import zio.*
import zio.http.*

/** Standard OpenAI API adapter for PCM text-to-speech generation.
  *
  * This uses a paid OpenAI Platform API key, not the ChatGPT/Codex subscription OAuth credentials.
  */
final case class OpenAiTtsService private[openai] (client: Client, apiKey: String) extends TtsService {
  import OpenAiTtsService.*

  override def synthesize(request: TtsRequest): Task[Chunk[AudioChunk]] =
    for {
      _ <- ZIO.fromEither(validate(request).left.map(OpenAiTtsError.InvalidRequest.apply))
      _ <- ZIO.logInfo(
        s"OpenAI TTS synthesize: model=${request.model} voice=${request.options.voiceName} textSegments=${request.effectiveTurns.size}"
      )
      _ <- ZIO
        .logDebug("OpenAI TTS ignores languageCode; the spoken language is inferred from the input text.")
        .when(request.options.languageCode.nonEmpty)
      chunks <- ZIO.foreach(Chunk.fromIterable(request.effectiveTurns.map(_.text).zipWithIndex)) { case (text, index) =>
        ZIO.logInfo(
          s"OpenAI TTS speech call: model=${request.model} segment=${index + 1}/${request.effectiveTurns.size} chars=${text.length}"
        ) *>
          synthesizeSegment(request, text)
      }
      result <-
        if chunks.nonEmpty then ZIO.succeed(chunks)
        else ZIO.fail(OpenAiTtsError.EmptyResponse("No audio requested from OpenAI TTS"))
    } yield result

  private def synthesizeSegment(request: TtsRequest, text: String): Task[AudioChunk] =
    Client
      .batched(speechRequest(request, text))
      .provideEnvironment(ZEnvironment(client))
      .flatMap { response =>
        if response.status.isSuccess then
          response.body.asChunk.flatMap { bytes =>
            if bytes.nonEmpty then ZIO.succeed(AudioChunk(bytes.toArray, PcmFormat(), Some("audio/pcm")))
            else ZIO.fail(OpenAiTtsError.EmptyResponse("OpenAI TTS returned an empty audio response"))
          }
        else
          response.body.asString.flatMap { body =>
            ZIO.fail(
              OpenAiTtsError.HttpFailure(
                s"OpenAI TTS returned HTTP ${response.status.code}: ${safeErrorBody(body)}"
              )
            )
          }
      }
      .timeoutFail(OpenAiTtsError.HttpFailure("OpenAI TTS request timed out"))(RequestTimeout)

  private def speechRequest(request: TtsRequest, text: String): Request =
    Request
      .post(SpeechUrl, Body.fromString(ujson.write(requestBody(request, text))))
      .addHeader("Authorization", s"Bearer $apiKey")
      .addHeader("Content-Type", "application/json")
      .addHeader("Accept", "audio/pcm")
}

object OpenAiTtsService {
  val SpeechUrl = "https://api.openai.com/v1/audio/speech"
  val DefaultModel = "gpt-realtime-2.1-mini"
  val DefaultVoice = "marin"
  val RequestTimeout: Duration = 2.minutes

  val live: ZLayer[Client, Throwable, TtsService] =
    ZLayer.fromZIO {
      for {
        client <- ZIO.service[Client]
        apiKey <- OpenAiTtsSecrets.load
      } yield OpenAiTtsService(client, apiKey)
    }

  private[openai] def requestBody(request: TtsRequest, text: String): ujson.Obj = {
    val body = ujson.Obj(
      "model" -> request.model,
      "input" -> text,
      "voice" -> request.options.voiceName,
      "response_format" -> "pcm"
    )
    request.options.openAi.instructions.filter(_.trim.nonEmpty).foreach(value => body("instructions") = value)
    request.options.openAi.speed.foreach(value => body("speed") = value)
    body
  }

  private[openai] def validate(request: TtsRequest): Either[String, Unit] =
    Either
      .cond(request.model.trim.nonEmpty, (), "OpenAI TTS model must not be empty")
      .flatMap(_ => Either.cond(request.options.voiceName.trim.nonEmpty, (), "OpenAI TTS voice must not be empty"))
      .flatMap(_ =>
        Either.cond(request.options.format == PcmFormat(), (), "OpenAI TTS only supports 24 kHz 16-bit mono PCM")
      )
      .flatMap(_ =>
        request.options.openAi.speed match {
          case Some(value) if value < 0.25 || value > 4.0 => Left("OpenAI TTS speed must be between 0.25 and 4.0")
          case _                                          => Right(())
        }
      )

  private def safeErrorBody(body: String): String =
    body.trim.replaceAll("\\s+", " ").take(1000) match {
      case ""    => "<empty response body>"
      case value => value
    }
}

sealed trait OpenAiTtsError extends RuntimeException
object OpenAiTtsError {
  final case class MissingApiKey(message: String) extends RuntimeException(message) with OpenAiTtsError
  final case class InvalidRequest(message: String) extends RuntimeException(message) with OpenAiTtsError
  final case class HttpFailure(message: String) extends RuntimeException(message) with OpenAiTtsError
  final case class EmptyResponse(message: String) extends RuntimeException(message) with OpenAiTtsError
}
