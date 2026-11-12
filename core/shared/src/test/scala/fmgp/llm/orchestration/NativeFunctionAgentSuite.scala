package fmgp.yallmo.orchestration

import fmgp.yallmo.tool.{FunctionKey, FunctionToolDefinition}

import RunnerTestSupport.*

import fmgp.yallmo.model.YallmoError
import zio.*
import munit.FunSuite
import scala.concurrent.Future
import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.*

final class NativeFunctionAgentSuite extends FunSuite:
  private val NativeId = AgentId("native")

  test("runner records provider calls and links the trusted output before the next native turn") {
    runZIO {
      for {
        requests <- Ref.make(Chunk.empty[AgentTurnRequest])
        call = FunctionCall(FunctionCallId.unsafe("call-clock"), FunctionKey.unsafe("clock"), "{}")
        continuation = ProviderContinuation(ProviderId.unsafe("openai-codex-responses"), Some("rs-1"), "encrypted")
        client = new Agent[Any] {
          override val id: AgentId = AgentId("test-client")

          override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
            requests.updateAndGet(_ :+ request).map {
              case history if history.size == 1 =>
                AgentTurnResponse(
                  Chunk(
                    Message.Reasoning(Some("Checking the time"), continuation),
                    Message.ToolCall(call)
                  ),
                  LlmStopReason.ToolUse
                )
              case _ =>
                AgentTurnResponse(Chunk(Message.Assistant("It is noon.")), LlmStopReason.Stop)
            }
        }
        tool = new AgentFunction[Agent[Any]] {
          override val definition: FunctionToolDefinition =
            FunctionToolDefinition(
              FunctionKey.unsafe("clock"),
              "Returns a fixed time"
            )
          override def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
            ZIO.succeed(ExternalFunctionResult("12:00"))
        }
        runner = new AgentRunner[Agent[Any]](Map(FunctionKey.unsafe("clock") -> tool), Map.empty)
        run <- runner
          .runWithContext(
            NativeFunctionAgent(NativeId, "test-model"),
            AgentTask("What time is it?"),
            StaticContext[Agent[Any]]("System", functions = Chunk(tool.definition)),
            RunContext()
          )
          .provide(ZLayer.succeed[Agent[Any]](client))
        seenRequests <- requests.get
      } yield run -> seenRequests
    } { result =>
      assertEquals(result._1.result, AgentResult(NativeId, "It is noon."))
      assert(!result._1.runContext.messages.exists {
        case Message.Assistant(text) => text.isEmpty; case _ => false
      })
      assert(result._1.runContext.messages.exists {
        case Message.ToolCall(call) => call.id.value == "call-clock"; case _ => false
      })
      assert(result._1.runContext.messages.exists {
        case Message.ToolOutput(output) => output.output == "12:00"; case _ => false
      })
      assert(!result._2(1).input.exists {
        case Message.Assistant(text) => text.isEmpty; case _ => false
      })
      assert(result._2(1).input.exists {
        case Message.ToolOutput(output) => output.callId.value == "call-clock"; case _ => false
      })
      assert(result._2(1).input.exists {
        case Message.Reasoning(_, value) => value.encryptedContent == "encrypted"
        case _                           => false
      })
    }
  }

  test("native rejected and failed calls become linked outputs and rejected tools never execute") {
    runZIO {
      for {
        requests <- Ref.make(Chunk.empty[AgentTurnRequest])
        hiddenExecutions <- Ref.make(0)
        deniedExecutions <- Ref.make(0)
        invalidExecutions <- Ref.make(0)
        failingExecutions <- Ref.make(0)
        calls = Chunk(
          FunctionCall(FunctionCallId.unsafe("unknown"), FunctionKey.unsafe("missing"), "{}"),
          FunctionCall(FunctionCallId.unsafe("unadvertised"), FunctionKey.unsafe("hidden"), "{}"),
          FunctionCall(FunctionCallId.unsafe("denied"), FunctionKey.unsafe("guarded"), "{}"),
          FunctionCall(FunctionCallId.unsafe("invalid"), FunctionKey.unsafe("validated"), "{}"),
          FunctionCall(FunctionCallId.unsafe("failing"), FunctionKey.unsafe("broken"), "{}")
        )
        client = new Agent[Any] {
          override val id: AgentId = AgentId("test-client")

          override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
            requests.updateAndGet(_ :+ request).map {
              case history if history.size == 1 =>
                AgentTurnResponse(calls.map(Message.ToolCall.apply), LlmStopReason.ToolUse)
              case _ =>
                AgentTurnResponse(Chunk(Message.Assistant("Recovered.")), LlmStopReason.Stop)
            }
        }
        hidden = countingTool(
          FunctionKey.unsafe("hidden"),
          hiddenExecutions,
          ZIO.succeed(ExternalFunctionResult("unexpected"))
        )
        guardedBase = countingTool(
          FunctionKey.unsafe("guarded"),
          deniedExecutions,
          ZIO.succeed(ExternalFunctionResult("unexpected"))
        )
        approval = new FunctionApproval[Agent[Any]] {
          override def approve(definition: FunctionToolDefinition, call: ExternalFunctionCall): IO[YallmoError, Unit] =
            ZIO.fail(YallmoError.FunctionApprovalDenied(definition.name, "not approved"))
        }
        guarded = FunctionApproval.guard(guardedBase, approval)
        validatedDefinition = FunctionToolDefinition(
          FunctionKey.unsafe("validated"),
          "Requires a value",
          JsonSchema.ObjectSchema(
            Map("value" -> JsonSchema.StringSchema()),
            required = Set("value"),
            additionalProperties = false
          )
        )
        validated = new AgentFunction[Agent[Any]] {
          override val definition: FunctionToolDefinition = validatedDefinition
          override def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
            invalidExecutions.update(_ + 1).as(ExternalFunctionResult("unexpected"))
        }
        broken = countingTool(
          FunctionKey.unsafe("broken"),
          failingExecutions,
          ZIO.fail(
            YallmoError.ToolExecutionFailed(
              FunctionKey.unsafe("broken"),
              "service unavailable",
              new RuntimeException("down")
            )
          )
        )
        runner = new AgentRunner[Agent[Any]](
          Map(
            FunctionKey.unsafe("hidden") -> hidden,
            FunctionKey.unsafe("guarded") -> guarded,
            FunctionKey.unsafe("validated") -> validated,
            FunctionKey.unsafe("broken") -> broken
          ),
          Map.empty
        )
        run <- runner
          .run(
            NativeFunctionAgent(NativeId, "test-model"),
            AgentTask("Run checks"),
            StaticContext[Agent[Any]](
              "System",
              functions = Chunk(
                FunctionToolDefinition(
                  FunctionKey.unsafe("missing"),
                  "Advertised but unregistered"
                ),
                guarded.definition,
                validated.definition,
                broken.definition
              )
            )
          )
          .provide(ZLayer.succeed[Agent[Any]](client))
        seenRequests <- requests.get
        hiddenCount <- hiddenExecutions.get
        deniedCount <- deniedExecutions.get
        invalidCount <- invalidExecutions.get
        failingCount <- failingExecutions.get
      } yield (run, seenRequests, hiddenCount, deniedCount, invalidCount, failingCount)
    } { result =>
      assertEquals(result._1, AgentResult(NativeId, "Recovered."))
      assertEquals((result._3, result._4, result._5, result._6), (0, 0, 0, 1))
      val outputs = result
        ._2(1)
        .input
        .collect { case Message.ToolOutput(output) => output.callId.value -> output.output }
        .toMap
      assert(outputs("unknown").contains("UnknownTool"))
      assert(outputs("unadvertised").contains("ToolUnavailableToAgent"))
      assert(outputs("denied").contains("FunctionApprovalDenied"))
      assert(outputs("invalid").contains("InvalidToolArguments"))
      assert(outputs("failing").contains("ToolExecutionFailed"))
    }
  }

  private def countingTool(
      key: FunctionKey,
      executions: Ref[Int],
      result: IO[YallmoError, ExternalFunctionResult]
  ): AgentFunction[Agent[Any]] =
    new AgentFunction[Agent[Any]] {
      override val definition: FunctionToolDefinition =
        FunctionToolDefinition(key, s"$key test tool")
      override def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
        executions.update(_ + 1) *> result
    }

  private def runZIO[E, A](effect: ZIO[Any, E, A])(check: A => Unit): Future[Unit] =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.runToFuture(effect.map(check).mapError(error => FiberFailure(Cause.fail(error))))
    }
