package fmgp.yallmo.model.som

import munit.FunSuite
import zio.json.{JsonCodec, JsonDecoder, JsonEncoder}

final class SystemOneModelsSuite extends FunSuite {
  private final case class SelectionState(request: String, changedFiles: Int) derives JsonCodec

  test("encodes a caller-defined state and questions with System One wire names") {
    val request = SystemOneRequest(
      state = SelectionState("review this change", changedFiles = 7),
      model = "jev-latest",
      questions = Map(
        "worker" -> SystemOneQuestion.Choice(
          "Which worker should handle `request`?",
          Map("review" -> "Audit a change", "coding" -> "Implement code")
        ),
        "needs_review" -> SystemOneQuestion.Noul(
          "Does `request` require review?",
          Some(SystemOneNoulCriteria("Review is needed", "Review is not needed"))
        ),
        "complexity" -> SystemOneQuestion.Score(
          "How difficult is `request`?",
          Seq("simple", "complex")
        )
      )
    )

    val json = JsonEncoder[SystemOneRequest[SelectionState]].encodeJson(request).toString

    println(json)

    assert(json.contains("\"state\":{\"request\":\"review this change\",\"changedFiles\":7}"))
    assert(json.contains("\"type\":\"choice\""))
    assert(json.contains("\"type\":\"noul\""))
    assert(json.contains("\"type\":\"score\""))
    assert(json.contains("\"true\":\"Review is needed\""))
    assert(json.contains("\"false\":\"Review is not needed\""))
  }

  test("DSL SO builds equivalent requests") {
    import SO.*
    val state = SelectionState("review this change", changedFiles = 7)
    val worker = choice(
      "Which worker should handle `request`?",
      "review" -> "Audit a change",
      "coding" -> "Implement code"
    )
    val needsReview = noul(
      "Does `request` require review?",
      whenTrue = "Review is needed",
      whenFalse = "Review is not needed"
    )
    val complexity = score("How difficult is `request`?", "simple", "complex")

    val curried = request(state = state, model = "jev-latest")(
      "worker" -> worker,
      "needs_review" -> needsReview,
      "complexity" -> complexity
    )

    assertEquals(curried.questions("worker").instructions, "Which worker should handle `request`?")
  }

  test("decodes an observed Laya response while retaining official answer fields") {
    val response =
      """{
        |  "model": "laya-rl-agent",
        |  "answers": {
        |    "worker": {
        |      "type": "choice",
        |      "choice": "review",
        |      "probabilities": {"coding": 0.0047, "review": 0.9908, "research": 0.0045},
        |      "confidence": 0.9467,
        |      "answer_confidence": 0.9908,
        |      "action": {"act_probability": 1.0},
        |      "x_jev_confidence": 0.9862
        |    },
        |    "needs_human": {
        |      "type": "noul",
        |      "noul": 0.8563,
        |      "confidence": 0.8563,
        |      "answer_confidence": 0.8563,
        |      "action": {"act_probability": 1.0}
        |    }
        |  },
        |  "usage": {
        |    "input_tokens": 211,
        |    "output_tokens": 0,
        |    "state_tokens": 33,
        |    "state_tokens_dropped": 0,
        |    "truncated": false,
        |    "truncated_questions": []
        |  },
        |  "routing": {
        |    "model": "english",
        |    "repo": "convaiinnovations/laya",
        |    "reason": "English Latin text",
        |    "detection": {
        |      "script": "latin",
        |      "script_profile": {"latin": 1.0},
        |      "language": "en",
        |      "is_english": true,
        |      "language_undecided": false,
        |      "diacritic_rate": 0.0,
        |      "non_latin_fraction": 0.0,
        |      "mixed_segment": null
        |    },
        |    "workflow": null
        |  }
        |}""".stripMargin

    val decoded = JsonDecoder[SystemOneResponse].decodeJson(response)

    assertEquals(decoded.map(_.model), Right("laya-rl-agent"))
    assertEquals(decoded.map(_.usage.stateTokens), Right(Some(33)))
    assertEquals(decoded.map(_.routing.map(_.repo)), Right(Some("convaiinnovations/laya")))
    assertEquals(
      decoded.map(_.answers("worker")),
      Right(
        SystemOneAnswer.Choice(
          choice = "review",
          probabilities = Map("coding" -> 0.0047, "review" -> 0.9908, "research" -> 0.0045),
          confidence = 0.9467,
          answerConfidence = Some(0.9908),
          action = Some(SystemOneAction(1.0)),
          xJevConfidence = Some(0.9862)
        )
      )
    )
  }

  test("decodes an official Jev response without Laya extensions") {
    val response =
      """{
        |  "model": "jev-1.13.0",
        |  "answers": {
        |    "is_urgent": {"type": "noul", "noul": 0.95}
        |  },
        |  "usage": {"input_tokens": 296, "output_tokens": 20}
        |}""".stripMargin

    val decoded = JsonDecoder[SystemOneResponse].decodeJson(response)

    assertEquals(
      decoded,
      Right(
        SystemOneResponse(
          model = "jev-1.13.0",
          answers = Map("is_urgent" -> SystemOneAnswer.Noul(0.95)),
          usage = SystemOneUsage(inputTokens = 296, outputTokens = 20)
        )
      )
    )
  }
}
