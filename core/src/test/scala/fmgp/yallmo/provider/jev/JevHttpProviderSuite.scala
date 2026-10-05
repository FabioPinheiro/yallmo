package fmgp.yallmo.provider.jev

import fmgp.yallmo.model.som.*
import munit.FunSuite
import zio.{Duration, durationInt}

final class JevHttpProviderSuite extends FunSuite {
  test("normalizes the System One endpoint and optional API key") {
    val config = JevConfig
      .from(" https://api.typesafe.ai/v1/systemone/ ", Some(" secret "), 10.seconds)
      .fold(error => fail(error.getMessage), identity)

    assertEquals(config.systemOneUrl, "https://api.typesafe.ai/v1/systemone")
    assertEquals(config.apiKey, Some("secret"))
    assertEquals(config.requestTimeout, 10.seconds)
  }

  test("rejects invalid endpoint and timeout configuration") {
    assert(JevConfig.from(" ", None).isLeft)
    assert(JevConfig.from("ftp://api.typesafe.ai/v1/systemone", None).isLeft)
    assert(JevConfig.from("https://api.typesafe.ai/v1/systemone", None, Duration.Zero).isLeft)
  }

  test("decodes the stable Jev System One response envelope") {
    val body = """{
      |  "model": "jev-1.13.0",
      |  "answers": {
      |    "worker": {
      |      "type": "choice",
      |      "choice": "review",
      |      "probabilities": {"review": 1.0},
      |      "confidence": 1.0
      |    }
      |  },
      |  "usage": {"input_tokens": 8, "output_tokens": 2}
      |}""".stripMargin

    assertEquals(
      JevHttpProvider.decodeResponse(body),
      Right(
        SystemOneResponse(
          model = "jev-1.13.0",
          answers = Map("worker" -> SystemOneAnswer.Choice("review", Map("review" -> 1.0), 1.0)),
          usage = SystemOneUsage(inputTokens = 8, outputTokens = 2)
        )
      )
    )
  }
}
