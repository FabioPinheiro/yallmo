package fmgp.yallmo.provider.gemini

import fmgp.yallmo.{GeminiTtsOptions, GeminiTtsSpeaker, PcmFormat, TtsOptions, TtsProvider, TtsRequest, TtsTurn}
import munit.FunSuite

import java.util.Base64

final class GeminiTtsSuite extends FunSuite:
  test("Gemini provider creates a Gemini 3.8 Flash TTS request") {
    val request = TtsRequest.fromText("Hello", TtsProvider.Gemini)

    assertEquals(request.model, "gemini-3.8-flash-tts")
    assertEquals(request.options.voiceName, "Kore")
  }

  test("builds a 24 kHz L16 Gemini 3.8 single-speaker request with speech metadata") {
    val request = TtsRequest(
      text = Seq("The lights of Faro shimmered across the marina."),
      options = TtsOptions(voiceName = "Kore", gemini = GeminiTtsOptions(style = Some("warm and cinematic")))
    )

    val body = GeminiTtsService.requestBody(request, request.effectiveTurns)
    val config = body("generationConfig")

    assertEquals(body("contents")(0)("parts")(0)("speechMetadata")("style").str, "warm and cinematic")
    assertEquals(config("responseModalities")(0).str, "AUDIO")
    assertEquals(config("responseFormat")("audio")("mimeType").str, "AUDIO_L16")
    assertEquals(config("responseFormat")("audio")("sampleRate").num, 24000.0)
    assertEquals(config("speechConfig")("voiceConfig")("prebuiltVoiceConfig")("voiceName").str, "Kore")
  }

  test("builds a two-speaker Gemini 3.8 dialogue request") {
    val request = TtsRequest(
      text = Seq.empty,
      options = TtsOptions(
        gemini = GeminiTtsOptions(
          speakers = Seq(GeminiTtsSpeaker("Joana", "Aoede"), GeminiTtsSpeaker("Manuel", "Puck"))
        )
      ),
      turns = Seq(
        TtsTurn("The marina is waking up.", Some("Joana"), Some("curious and delighted")),
        TtsTurn("Then let's follow the music.", Some("Manuel"), Some("relaxed and playful"))
      )
    )

    val body = GeminiTtsService.requestBody(request, request.effectiveTurns)
    val speakerConfigs = body("generationConfig")("speechConfig")("multiSpeakerVoiceConfig")("speakerVoiceConfigs").arr

    assertEquals(body("contents")(0)("parts")(0)("speechMetadata")("speaker").str, "Joana")
    assertEquals(body("contents")(0)("parts")(1)("speechMetadata")("speaker").str, "Manuel")
    assertEquals(speakerConfigs.map(_("speaker").str).toSeq, Seq("Joana", "Manuel"))
    assertEquals(
      speakerConfigs.map(_("voiceConfig")("prebuiltVoiceConfig")("voiceName").str).toSeq,
      Seq("Aoede", "Puck")
    )
  }

  test("decodes Gemini inline PCM audio") {
    val bytes = Array[Byte](1, 2, 3, 4)
    val body = s"""{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16","data":"${Base64.getEncoder
        .encodeToString(bytes)}"}}]}}]}"""

    val decoded = GeminiTtsService.decodeResponse(body, PcmFormat())

    decoded match {
      case Right(chunks) => assertEquals(chunks.toList.map(_.bytes.toSeq), List(bytes.toSeq))
      case Left(error)   => fail(error)
    }
  }

  test("validates Gemini multi-speaker configuration") {
    val request = TtsRequest(
      text = Seq.empty,
      options = TtsOptions(gemini = GeminiTtsOptions(speakers = Seq(GeminiTtsSpeaker("Joana", "Aoede")))),
      turns = Seq(TtsTurn("Hello", Some("Joana")))
    )

    assertEquals(
      GeminiTtsService.validate(request),
      Left("Gemini TTS multi-speaker synthesis requires exactly two speakers")
    )
  }
