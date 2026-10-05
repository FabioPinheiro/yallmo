package fmgp.yallmo.orchestration

import fmgp.yallmo.model.YallmoError
import zio.*
import munit.FunSuite
import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.*

final class NativeFunctionAgentSuite extends FunSuite:
  private val NativeId = AgentId("native")

  test("runner records provider calls and links the trusted output before the next native turn") {
    val result = runZIO {
      for {
        requests <- Ref.make(Chunk.empty[AgentTurnRequest])
        call = FunctionCall(FunctionCallId.unsafe("call-clock"), "clock", "{}")
        continuation = ProviderContinuation(ProviderId.unsafe("openai-codex-responses"), Some("rs-1"), "encrypted")
        client = new Agent[Any] {
          override val id: AgentId = AgentId("test-client")

          override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
            requests.updateAndGet(_ :+ request).map {
              case history if history.size == 1 =>
                AgentTurnResponse(
                  Chunk(
                    Message.Reasoning(Some("Checking the time"), continuation),
                    Message.FunctionCall(call)
                  ),
                  LlmStopReason.ToolUse
                )
              case _ =>
                AgentTurnResponse(Chunk(Message.Assistant("It is noon.")), LlmStopReason.Stop)
            }
        }
        tool = new AgentTool[Agent[Any]] {
          override val definition: AgentToolDefinition = AgentToolDefinition("clock", "Returns a fixed time")
          override def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
            ZIO.succeed(ExternalToolResult("12:00"))
        }
        runner = new AgentRunner[Agent[Any]](Map("clock" -> tool), Map.empty)
        run <- runner
          .runWithContext(
            NativeFunctionAgent(NativeId, "test-model"),
            AgentTask("What time is it?"),
            StaticContext[Agent[Any]]("System", tools = Chunk(tool.definition)),
            RunContext()
          )
          .provide(ZLayer.succeed[Agent[Any]](client))
        seenRequests <- requests.get
      } yield run -> seenRequests
    }

    assertEquals(result._1.result, AgentResult(NativeId, "It is noon."))
    assert(!result._1.runContext.messages.exists {
      case Message.Assistant(text) => text.isEmpty; case _ => false
    })
    assert(result._1.runContext.messages.exists {
      case Message.FunctionCall(call) => call.id.value == "call-clock"; case _ => false
    })
    assert(result._1.runContext.messages.exists {
      case Message.FunctionCallOutput(output) => output.output == "12:00"; case _ => false
    })
    assert(!result._2(1).input.exists {
      case Message.Assistant(text) => text.isEmpty; case _ => false
    })
    assert(result._2(1).input.exists {
      case Message.FunctionCallOutput(output) => output.callId.value == "call-clock"; case _ => false
    })
    assert(result._2(1).input.exists {
      case Message.Reasoning(_, value) => value.encryptedContent == "encrypted"
      case _                           => false
    })
  }

  test("native rejected and failed calls become linked outputs and rejected tools never execute") {
    val result = runZIO {
      for {
        requests <- Ref.make(Chunk.empty[AgentTurnRequest])
        hiddenExecutions <- Ref.make(0)
        deniedExecutions <- Ref.make(0)
        invalidExecutions <- Ref.make(0)
        failingExecutions <- Ref.make(0)
        calls = Chunk(
          FunctionCall(FunctionCallId.unsafe("unknown"), "missing", "{}"),
          FunctionCall(FunctionCallId.unsafe("unadvertised"), "hidden", "{}"),
          FunctionCall(FunctionCallId.unsafe("denied"), "guarded", "{}"),
          FunctionCall(FunctionCallId.unsafe("invalid"), "validated", "{}"),
          FunctionCall(FunctionCallId.unsafe("failing"), "broken", "{}")
        )
        client = new Agent[Any] {
          override val id: AgentId = AgentId("test-client")

          override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
            requests.updateAndGet(_ :+ request).map {
              case history if history.size == 1 =>
                AgentTurnResponse(calls.map(Message.FunctionCall.apply), LlmStopReason.ToolUse)
              case _ =>
                AgentTurnResponse(Chunk(Message.Assistant("Recovered.")), LlmStopReason.Stop)
            }
        }
        hidden = countingTool("hidden", hiddenExecutions, ZIO.succeed(ExternalToolResult("unexpected")))
        guardedBase = countingTool("guarded", deniedExecutions, ZIO.succeed(ExternalToolResult("unexpected")))
        approval = new ToolApproval[Agent[Any]] {
          override def approve(definition: AgentToolDefinition, call: ExternalToolCall): IO[YallmoError, Unit] =
            ZIO.fail(YallmoError.ToolApprovalDenied(definition.key, "not approved"))
        }
        guarded = ToolApproval.guard(guardedBase, approval)
        validatedDefinition = AgentToolDefinition(
          "validated",
          "Requires a value",
          JsonSchema.ObjectSchema(
            Map("value" -> JsonSchema.StringSchema()),
            required = Set("value"),
            additionalProperties = false
          )
        )
        validated = new AgentTool[Agent[Any]] {
          override val definition: AgentToolDefinition = validatedDefinition
          override def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
            invalidExecutions.update(_ + 1).as(ExternalToolResult("unexpected"))
        }
        broken = countingTool(
          "broken",
          failingExecutions,
          ZIO.fail(
            YallmoError.ToolExecutionFailed("broken", "service unavailable", new RuntimeException("down"))
          )
        )
        runner = new AgentRunner[Agent[Any]](
          Map("hidden" -> hidden, "guarded" -> guarded, "validated" -> validated, "broken" -> broken),
          Map.empty
        )
        run <- runner
          .run(
            NativeFunctionAgent(NativeId, "test-model"),
            AgentTask("Run checks"),
            StaticContext[Agent[Any]](
              "System",
              tools = Chunk(
                AgentToolDefinition("missing", "Advertised but unregistered"),
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
    }

    assertEquals(result._1, AgentResult(NativeId, "Recovered."))
    assertEquals((result._3, result._4, result._5, result._6), (0, 0, 0, 1))
    val outputs = result
      ._2(1)
      .input
      .collect { case Message.FunctionCallOutput(output) => output.callId.value -> output.output }
      .toMap
    assert(outputs("unknown").contains("UnknownTool"))
    assert(outputs("unadvertised").contains("ToolUnavailableToAgent"))
    assert(outputs("denied").contains("ToolApprovalDenied"))
    assert(outputs("invalid").contains("InvalidToolArguments"))
    assert(outputs("failing").contains("ToolExecutionFailed"))
  }

  private def countingTool(
      key: String,
      executions: Ref[Int],
      result: IO[YallmoError, ExternalToolResult]
  ): AgentTool[Agent[Any]] =
    new AgentTool[Agent[Any]] {
      override val definition: AgentToolDefinition = AgentToolDefinition(key, s"$key test tool")
      override def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
        executions.update(_ + 1) *> result
    }

  private def runZIO[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
