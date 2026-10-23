package fmgp.yallmo.provider.openai

import fmgp.yallmo.{OpenAiTtsOptions, PcmFormat, TtsOptions, TtsProvider, TtsRequest}
import munit.FunSuite

final class OpenAiTtsSuite extends FunSuite:
  test("builds a PCM speech request with OpenAI controls") {
    val request = TtsRequest(
      text = Seq("Hello"),
      model = OpenAiTtsService.DefaultModel,
      options = TtsOptions(
        voiceName = "marin",
        openAi = OpenAiTtsOptions(instructions = Some("Speak warmly."), speed = Some(1.25))
      )
    )

    val body = OpenAiTtsService.requestBody(request, "Hello")

    assertEquals(body("model").str, "gpt-realtime-2.1-mini")
    assertEquals(body("input").str, "Hello")
    assertEquals(body("voice").str, "marin")
    assertEquals(body("response_format").str, "pcm")
    assertEquals(body("instructions").str, "Speak warmly.")
    assertEquals(body("speed").num, 1.25)
  }

  test("omits absent OpenAI controls") {
    val body = OpenAiTtsService.requestBody(TtsRequest(Seq("Hello"), options = TtsOptions("marin")), "Hello")

    assertEquals(body.value.get("instructions"), None)
    assertEquals(body.value.get("speed"), None)
  }

  test("validates OpenAI PCM and speed constraints") {
    val invalidFormat = TtsRequest(
      Seq("Hello"),
      options = TtsOptions("marin", format = PcmFormat(sampleRateHz = 16000.0f))
    )
    val invalidSpeed = TtsRequest(
      Seq("Hello"),
      options = TtsOptions("marin", openAi = OpenAiTtsOptions(speed = Some(4.1)))
    )

    assertEquals(OpenAiTtsService.validate(invalidFormat), Left("OpenAI TTS only supports 24 kHz 16-bit mono PCM"))
    assertEquals(OpenAiTtsService.validate(invalidSpeed), Left("OpenAI TTS speed must be between 0.25 and 4.0"))
  }

  test("OpenAI provider creates an OpenAI-default request") {
    val request = TtsRequest.fromText("Hello", TtsProvider.OpenAi)

    assertEquals(request.model, OpenAiTtsService.DefaultModel)
    assertEquals(request.options.voiceName, OpenAiTtsService.DefaultVoice)
    assertEquals(TtsProvider.parse("openai"), Right(TtsProvider.OpenAi))
  }
