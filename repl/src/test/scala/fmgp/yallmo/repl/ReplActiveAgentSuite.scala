package fmgp.yallmo.repl

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.*
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.orchestration.config.ProviderId
import munit.ZSuite
import zio.*

final class ReplActiveAgentSuite extends ZSuite {
  testZ("configured profile ID and actual bound native settings drive footer feedback") {
    ZIO.foreachDiscard(List("local-1", "chatgpt-2").map(ProviderId.unsafe)) { profile =>
      ZIO.foreachDiscard(None :: ThinkingLevel.values.toList.map(Some(_))) { thinking =>
        var outgoing: Option[AgentTurnRequest] = None
        val client = new Agent[Any] {
          val id = AgentId("bob")
          def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] = ZIO.succeed {
            outgoing = Some(request)
            AgentTurnResponse(Chunk(MessagePayload.Assistant("done")), LlmStopReason.Stop)
          }
        }
        val native = NativeFunctionAgent[Agent[Any]](
          client.id,
          "configured-model",
          ProviderGenerateOptions(responseMimeType = None, thinkingLevel = thinking)
        )
        val feedback = ReplActiveAgent.fromNative(profile, native)
        for {
          _ <- native.bind[Any](client).next(AgentTask("test"), StaticContext[Any]("test"), RunContext())
        } yield {
          assertEquals(feedback.agentId, "bob")
          assertEquals(feedback.provider, ProviderId.value(profile))
          assertEquals(feedback.model, outgoing.get.model)
          assertEquals(feedback.thinkingLevel, outgoing.get.options.thinkingLevel)
          assertEquals(feedback.thinkingLabel, thinking.fold("default")(_.toString.toLowerCase(java.util.Locale.ROOT)))
          assertEquals(outgoing.get.options.responseMimeType, None)
        }
      }
    }
  }
}
