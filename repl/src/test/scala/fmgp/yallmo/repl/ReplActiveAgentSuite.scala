package fmgp.yallmo.repl

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.*
import fmgp.yallmo.orchestration.*
import munit.FunSuite
import zio.*

final class ReplActiveAgentSuite extends FunSuite {
  test("both selected providers and model overrides derive feedback from the actual native request configuration") {
    for {
      selection <- ReplProviderSelection.values
      overrideModel <- List(None, Some("override-model"))
      thinking <- None :: ThinkingLevel.values.toList.map(Some(_))
    } {
      var outgoing: Option[AgentTurnRequest] = None
      val client: Agent[Any] = new Agent[Any] {
        val id = AgentId(if selection == ReplProviderSelection.Oai then "Gamma" else "openai-codex")
        def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] = ZIO.succeed {
          outgoing = Some(request)
          AgentTurnResponse(Chunk(MessagePayload.Assistant("done")), LlmStopReason.Stop)
        }
      }
      val provider = ReplProvider.selected(
        ReplLaunchOptions(provider = selection, model = overrideModel),
        client,
        "configured-model"
      )
      val defaults = NativeFunctionAgent[Agent[Any]](client.id, provider.model)
      val agent =
        if thinking.isEmpty then defaults else defaults.copy(options = defaults.options.copy(thinkingLevel = thinking))
      val feedback = ReplActiveAgent.fromNative(provider.selection, agent)
      Unsafe.unsafe { unsafe ?=>
        Runtime.default.unsafe
          .run(
            agent
              .next(AgentTask("test"), StaticContext[Agent[Any]]("test"), RunContext())
              .provide(ZLayer.succeed(client))
          )
          .getOrThrowFiberFailure()
      }
      assertEquals(feedback.agentId, AgentId.value(client.id))
      assertEquals(feedback.provider, if selection == ReplProviderSelection.Oai then "oai" else "openai-codex")
      assertEquals(feedback.model, overrideModel.getOrElse("configured-model"))
      assertEquals(feedback.model, outgoing.get.model)
      assertEquals(feedback.thinkingLevel, outgoing.get.options.thinkingLevel)
      assertEquals(feedback.thinkingLabel, thinking.fold("default")(_.toString.toLowerCase(java.util.Locale.ROOT)))
      assertEquals(defaults.options.thinkingLevel, None)
      assertEquals(outgoing.get.options.responseMimeType, None)
    }
  }
}
