package fmgp.yallmo.model.som

import fmgp.yallmo.model.YallmoError
import munit.FunSuite
import zio.{IO, Runtime, Unsafe, ZIO}
import zio.json.{JsonCodec, jsonField}

final class SOMProviderSuite extends FunSuite {
  private final case class State(task: String) derives JsonCodec

  private final case class Questions(
      worker: SystemOneQuestion.Choice,
      @jsonField("needs_review") needsReview: SystemOneQuestion.Noul
  ) derives JsonCodec,
        SOSchema

  private final class StubProvider(response: SystemOneResponse) extends SOMProvider {
    var sentJson: Option[String] = None

    override protected def evaluateRequestJson(requestJson: String): IO[YallmoError, SystemOneResponse] =
      ZIO.succeed {
        sentJson = Some(requestJson)
        response
      }
  }

  private def run[A](effect: IO[YallmoError, A]): A =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.run(effect).getOrThrow()
    }

  private val questions = Questions(
    worker = SystemOneQuestion.Choice("Select a worker", Map("review" -> "Review the change")),
    needsReview = SystemOneQuestion.Noul("Does this change need review?")
  )

  private val response = SystemOneResponse(
    model = "jev-1.13.0",
    answers = Map(
      "worker" -> SystemOneAnswer.Choice("review", Map("review" -> 1.0), 1.0),
      "needs_review" -> SystemOneAnswer.Noul(0.95)
    ),
    usage = SystemOneUsage(inputTokens = 1, outputTokens = 1)
  )

  test("encodes dynamic requests before delegating to the provider adapter") {
    val provider = new StubProvider(response)
    val request = SystemOneRequest(State("Review this change"), "jev-latest", Map("worker" -> questions.worker))

    val result = run(provider.evaluate(request))

    assertEquals(result, response)
    assert(provider.sentJson.exists(_.contains("\"worker\"")))
    assert(provider.sentJson.exists(_.contains("\"jev-latest\"")))
  }

  test("validates typed responses before exposing typed answers") {
    val provider = new StubProvider(response)
    val request = SORequest(State("Review this change"), "jev-latest", questions)

    val answers = run(provider.evaluate(request))

    assertEquals(answers.worker.choice, "review")
    assertEquals(answers.needsReview.noul, 0.95)
    assert(provider.sentJson.exists(_.contains("\"needs_review\"")))
  }
}
