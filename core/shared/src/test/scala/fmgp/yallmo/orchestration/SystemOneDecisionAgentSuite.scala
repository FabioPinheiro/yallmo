package fmgp.yallmo.orchestration

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.som.*
import munit.ZSuite
import zio.{IO, ZEnvironment, ZIO}
import zio.json.*

final class SystemOneDecisionAgentSuite extends ZSuite {
  private final case class State(task: String) derives JsonCodec

  private final case class Questions(worker: SystemOneQuestion.Choice) derives JsonCodec, SOSchema

  private final class StubSOMProvider(response: SystemOneResponse) extends SOMProvider {
    var sentJson = Option.empty[String]
    override protected def evaluateRequestJson(requestJson: String): IO[YallmoError, SystemOneResponse] =
      ZIO.succeed {
        sentJson = Some(requestJson)
        response
      }
  }

  testZ("returns typed answers without pretending to be a text agent")(ZIO.suspendSucceed {
    val agent = SystemOneDecisionAgent[State, Questions](
      model = "jev-latest",
      questions = _ => Questions(SystemOneQuestion.Choice("Select a worker", Map("review" -> "Review")))
    )
    val provider = new StubSOMProvider(
      SystemOneResponse(
        model = "jev-1.13.0",
        answers = Map("worker" -> SystemOneAnswer.Choice("review", Map("review" -> 1.0), 1.0)),
        usage = SystemOneUsage(inputTokens = 1, outputTokens = 1)
      )
    )

    agent.decide(State("Review this change")).provideEnvironment(ZEnvironment(provider)).map { answer =>
      assertEquals(answer.worker.choice, "review")
      assertEquals(
        provider.sentJson.map(_.fromJson[SystemOneRequest[State]]),
        Some(
          Right(
            SystemOneRequest(
              State("Review this change"),
              "jev-latest",
              Map("worker" -> SystemOneQuestion.Choice("Select a worker", Map("review" -> "Review")))
            )
          )
        )
      )
    }
  })
}
