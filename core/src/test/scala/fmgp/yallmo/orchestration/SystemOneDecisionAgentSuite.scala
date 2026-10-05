package fmgp.yallmo.orchestration

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.som.*
import munit.FunSuite
import zio.{IO, Runtime, Unsafe, ZEnvironment, ZIO}
import zio.json.JsonCodec

final class SystemOneDecisionAgentSuite extends FunSuite {
  private final case class State(task: String) derives JsonCodec

  private final case class Questions(worker: SystemOneQuestion.Choice) derives JsonCodec,
        SOSchema

  private final class StubSOMProvider(response: SystemOneResponse) extends SOMProvider {
    override protected def evaluateRequestJson(requestJson: String): IO[YallmoError, SystemOneResponse] =
      ZIO.succeed(response)
  }

  private def run[A](effect: IO[YallmoError, A]): A =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.run(effect).getOrThrow()
    }

  test("returns typed answers without pretending to be a text agent") {
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

    val answer = run(agent.decide(State("Review this change")).provideEnvironment(ZEnvironment(provider)))

    assertEquals(answer.worker.choice, "review")
  }
}
