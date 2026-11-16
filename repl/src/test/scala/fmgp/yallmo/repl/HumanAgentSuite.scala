package fmgp.yallmo.repl

import fmgp.yallmo.model.AgentId
import fmgp.yallmo.model.MessagePayload
import fmgp.yallmo.model.llm.*
import munit.ZSuite
import zio.*

final class HumanAgentSuite extends ZSuite {
  private val request = AgentTurnRequest("human", Chunk(MessagePayload.Instructions("Await a response.")))

  testZ("human agent publishes a pending turn and waits for the REPL response") {
    for
      channel <- HumanTurnChannel.make
      agent = HumanAgent.make(AgentId("TestHumanAgent"), channel)
      turn <- agent.turn(request).fork
      pending <- channel.takePending
      _ = assertEquals(pending.request, request)
      completed <- pending.respond("Hello from the terminal.")
      response <- turn.join
    yield {
      assertEquals(completed, true)
      assertEquals(
        response,
        AgentTurnResponse(Chunk(MessagePayload.Assistant("Hello from the terminal.")), LlmStopReason.Stop)
      )
    }
  }
}
