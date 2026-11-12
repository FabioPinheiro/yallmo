package fmgp.yallmo.orchestration

import fmgp.yallmo.tool.{FunctionKey, FunctionToolDefinition}

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse, JsonSchema}
import munit.FunSuite
import zio.*

import scala.concurrent.Future

final class ResolvedAgentSuite extends FunSuite {
  test("resolved invocation consumes its cached effective context and retains permission and schema checks") {
    run {
      val id = AgentId("resolved")
      val key = FunctionKey.unsafe("probe")
      for {
        calls <- Ref.make(0)
        tool = new AgentFunction[Any] {
          val definition = FunctionToolDefinition(
            key,
            "Probe",
            JsonSchema.ObjectSchema(
              Map("value" -> JsonSchema.StringSchema()),
              required = Set("value"),
              additionalProperties = false
            )
          )
          def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
            calls.update(_ + 1).as(ExternalFunctionResult("ok"))
        }
        program = new AgentInstance[Any] {
          val id = AgentId("resolved")
          override def configuredContext(context: StaticContext[Any]): IO[YallmoError, StaticContext[Any]] =
            ZIO.dieMessage("A complete generation already resolved context")
          def next(task: AgentTask, context: StaticContext[Any], current: RunContext): IO[YallmoError, AgentTurn] =
            ZIO.succeed(
              AgentTurn(
                Some(MessagePayload.Assistant(context.systemPrompt)),
                AgentDecision.FinalAnswer(context.systemPrompt)
              )
            )
        }
        adapter = new Agent[Any] {
          val id = AgentId("resolved")
          def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] = ZIO.dieMessage("Program only")
        }
        resolved = ResolvedAgent(
          program,
          StaticContext[Any]("cached", functions = Chunk(tool.definition)),
          Chunk(tool),
          adapter,
          None,
          None,
          None
        )
        invocation = AgentRunner.invocation(resolved)
        answer <- invocation.invoke(MessagePayload.User("hello"))
        invalid <- invocation.invoke(MessagePayload.ToolCall(FunctionCall(FunctionCallId.unsafe("invalid"), key, "{}")))
        denied <- AgentRunner
          .invocation(resolved.copy(context = resolved.context.copy(functions = Chunk.empty)))
          .invoke(MessagePayload.ToolCall(FunctionCall(FunctionCallId.unsafe("denied"), key, """{"value":"x"}""")))
        valid <- invocation.invoke(
          MessagePayload.ToolCall(FunctionCall(FunctionCallId.unsafe("valid"), key, """{"value":"x"}"""))
        )
        count <- calls.get
      } yield {
        assertEquals(invocation.agentId, id)
        assertEquals(answer, MessagePayload.Assistant("cached"))
        assert(invalid.contentStr.contains("InvalidToolArguments"))
        assert(denied.contentStr.contains("ToolUnavailableToAgent"))
        assertEquals(valid.contentStr, "ok")
        assertEquals(count, 1)
      }
    }
  }

  private def run[E, A](effect: ZIO[Any, E, A]): Future[A] =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.runToFuture(effect.mapError(error => FiberFailure(Cause.fail(error))))
    }
}
