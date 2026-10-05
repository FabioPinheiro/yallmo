package fmgp.yallmo.repl

import fmgp.yallmo.model.AgentId
import fmgp.yallmo.model.MessagePayload
import fmgp.yallmo.model.llm.*
import munit.FunSuite
import zio.*

final class HumanAgentSuite extends FunSuite {
  private val request = AgentTurnRequest("human", Chunk(MessagePayload.Instructions("Await a response.")))

  test("human agent publishes a pending turn and waits for the REPL response") {
    val response = runZIO {
      for
        channel <- HumanTurnChannel.make
        agent = HumanAgent.make(AgentId("TestHumanAgent"), channel)
        turn <- agent.turn(request).fork
        pending <- channel.takePending
        _ = assertEquals(pending.request, request)
        completed <- pending.respond("Hello from the terminal.")
        response <- turn.join
      yield completed -> response
    }

    assertEquals(response._1, true)
    assertEquals(
      response._2,
      AgentTurnResponse(Chunk(MessagePayload.Assistant("Hello from the terminal.")), LlmStopReason.Stop)
    )
  }

  private def runZIO[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }
}
