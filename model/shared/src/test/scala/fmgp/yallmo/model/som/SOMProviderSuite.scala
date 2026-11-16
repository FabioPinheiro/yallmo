package fmgp.yallmo.model.som

import fmgp.yallmo.model.YallmoError
import munit.ZSuite
import zio.{IO, ZIO}
import zio.json.{JsonCodec, jsonField}

final class SOMProviderSuite extends ZSuite {
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

  testZ("encodes dynamic requests before delegating to the provider adapter")(ZIO.suspendSucceed {
    val provider = new StubProvider(response)
    val request = SystemOneRequest(State("Review this change"), "jev-latest", Map("worker" -> questions.worker))

    for {
      result <- provider.evaluate(request)
    } yield {
      assertEquals(result, response)
      assert(provider.sentJson.exists(_.contains("\"worker\"")))
      assert(provider.sentJson.exists(_.contains("\"jev-latest\"")))
    }
  })

  testZ("validates typed responses before exposing typed answers")(ZIO.suspendSucceed {
    val provider = new StubProvider(response)
    val request = SORequest(State("Review this change"), "jev-latest", questions)

    for {
      answers <- provider.evaluate(request)
    } yield {
      assertEquals(answers.worker.choice, "review")
      assertEquals(answers.needsReview.noul, 0.95)
      assert(provider.sentJson.exists(_.contains("\"needs_review\"")))
    }
  })
}
