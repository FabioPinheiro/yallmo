package fmgp.yallmo.model.som

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import zio.json.{JsonCodec, JsonEncoder, jsonField}

final class SystemOneModelsMacroSuite extends FunSuite {
  private final case class SelectionState(request: String, changedFiles: Int) derives JsonCodec

  private final case class SelectionQuestions(
      worker: SystemOneQuestion.Choice,
      @jsonField("needs_review") needsReview: SystemOneQuestion.Noul,
      complexity: SystemOneQuestion.Score
  ) derives JsonCodec, SOSchema

  test("SOSchema derivation rejects non-question fields at compile time") {
    val errors = typeCheckErrors("""{
      final case class InvalidQuestions(
        worker: fmgp.yallmo.model.som.SystemOneQuestion.Choice,
        accidental: Int
      ) derives fmgp.yallmo.model.som.SOSchema
    }""")

    assert(errors.exists(_.message.contains("accidental")))
    assert(errors.exists(_.message.contains("expected Choice, Score, or Noul")))
  }

  test("derives SOSchema and validates each question answer") {
    val schema = summon[SOSchema[SelectionQuestions]]
    val questions = SelectionQuestions(
      worker = SystemOneQuestion.Choice("Select a worker", Map("review" -> "Review the change")),
      needsReview = SystemOneQuestion.Noul("Does this change need review?"),
      complexity = SystemOneQuestion.Score("How difficult is this change?", Seq("simple", "complex"))
    )
    val request = SORequest(SelectionState("review this change", changedFiles = 7), "jev-latest", questions)
    val response = SystemOneResponse(
      model = "jev-1.13.0",
      answers = Map(
        "worker" -> SystemOneAnswer.Choice("review", Map("review" -> 1.0), 1.0),
        "needs_review" -> SystemOneAnswer.Noul(0.95),
        "complexity" -> SystemOneAnswer.Score(0.0, Map("0" -> "simple"), Map("0" -> 1.0), 1.0)
      ),
      usage = SystemOneUsage(inputTokens = 1, outputTokens = 1)
    )

    val decoded = schema.decodeAnswer(response).fold(error => fail(error), identity)
    val requestJson = schema.encodeRequest(request)

    assertEquals(
      schema.fields,
      Seq(
        SOField("worker", SOPrimitive.Choice),
        SOField("needs_review", SOPrimitive.Noul),
        SOField("complexity", SOPrimitive.Score)
      )
    )
    assert(requestJson.contains("\"needs_review\""))
    assertEquals(schema.decodeRequest[SelectionState](requestJson), Right(request))
    assertEquals(schema.decodeAnswer(schema.encodeAnswer(decoded)), Right(decoded))
    assertEquals(decoded.worker.choice, "review")
    assertEquals(decoded.needsReview.noul, 0.95)
    assertEquals(decoded.complexity.score, 0.0)
    assertEquals(
      schema.decodeAnswer(response.copy(answers = response.answers - "worker")),
      Left("missing answer 'worker'")
    )
    assertEquals(
      schema.decodeAnswer(response.copy(answers = response.answers.updated("worker", SystemOneAnswer.Noul(0.5)))),
      Left("answer 'worker' has type Noul; expected choice")
    )
  }

  test("SOAnswer derives answer field types from a question case class") {
    val questions = SelectionQuestions(
      worker = SystemOneQuestion.Choice("Select a worker", Map("review" -> "Review the change")),
      needsReview = SystemOneQuestion.Noul("Does this change need review?"),
      complexity = SystemOneQuestion.Score("How difficult is this change?", Seq("simple", "complex"))
    )
    val request = SORequest(SelectionState("review this change", changedFiles = 7), "jev-latest", questions)
    val response = SystemOneResponse(
      model = "jev-1.13.0",
      answers = Map(
        "worker" -> SystemOneAnswer.Choice(
          choice = "review",
          probabilities = Map("review" -> 1.0),
          confidence = 1.0,
          answerConfidence = Some(1.0)
        ),
        "needs_review" -> SystemOneAnswer.Noul(0.95),
        "complexity" -> SystemOneAnswer.Score(0.0, Map("0" -> "simple"), Map("0" -> 1.0), 1.0),
      ),
      usage = SystemOneUsage(inputTokens = 1, outputTokens = 1)
    )
    val json = JsonEncoder[SystemOneResponse].encodeJson(response).toString
    val expected =
      """{"model":"jev-1.13.0","answers":{
        |"worker":{"type":"choice","choice":"review","probabilities":{"review":1.0},"confidence":1.0,"answer_confidence":1.0},
        |"needs_review":{"type":"noul","noul":0.95},
        |"complexity":{"type":"score","score":0.0,"legend":{"0":"simple"},"probabilities":{"0":1.0},"confidence":1.0}
        |},"usage":{"input_tokens":1,"output_tokens":1,"truncated":false,"truncated_questions":[]}
        |}""".stripMargin.replaceAll("\n", "")

    val answers = request.answers(response).fold(error => fail(error), identity)
    val answersFromJson = request.answersFromJson(json).fold(error => fail(error), identity)

    assertEquals(json, expected)
    assertEquals(answers, answersFromJson)
    assertEquals(request.questions, questions)
    assertEquals(answers.worker.choice, "review")
    assertEquals(answers.worker.choice, "review")
    assertEquals(answers.worker.answerConfidence, Some(1.0))
    assertEquals(answers.worker.confidence, 1.0)
    assertEquals(answersFromJson.worker.choice, "review")
    assertEquals(answersFromJson.worker.answerConfidence, Some(1.0))
    assert(request.answers(response.copy(answers = response.answers.updated("worker", SystemOneAnswer.Noul(0.5)))).isLeft)
    assertEquals(answers.needsReview.noul, 0.95)
    assertEquals(answers.complexity.score, 0.0)
  }
}
