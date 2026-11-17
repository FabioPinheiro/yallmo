package fmgp.yallmo.orchestration

import fmgp.yallmo.tool.{
  FunctionCall,
  FunctionCallId,
  FunctionGenericOutput,
  FunctionKey,
  FunctionToolDefinition,
  ScalaModeOutput,
  ScalaModeResult
}
import fmgp.yallmo.tool.scalamode.ScalamodeToolDefinition

import RunnerTestSupport.*

import fmgp.yallmo.model.YallmoError
import java.util.UUID
import munit.FunSuite
import scala.concurrent.Future
import scala.compiletime.testing.typeCheckErrors
import zio.*
import fmgp.yallmo.model.*
final class AgentRunnerSuite extends FunSuite:
  private val CoordinatorId = AgentId("coordinator")

  test("even internal loop execution requires managed history") {
    val errors = typeCheckErrors("""
      import fmgp.yallmo.orchestration.*
      def missingSave(execution: AgentExecution[Any]) =
        execution.run(AgentTask("hello"))
    """)
    assert(errors.exists(_.message.contains("history")))
  }

  test("agent identifiers reject blank values") {
    assert(AgentId.from(" ").isLeft)
    assertEquals(AgentId.from("coordinator"), Right(CoordinatorId))
  }

  test("runner executes an advertised external tool and gives its result to the next turn") {
    runZIO {
      for
        turns <- Ref.make(0)
        calls <- Ref.make(Chunk.empty[ExternalFunctionCall])
        agent = TestAgent[Any](
          CoordinatorId,
          (_, _, runContext) =>
            turns.updateAndGet(_ + 1).flatMap {
              case 1 =>
                ZIO.succeed(
                  AgentDecision
                    .Actions(
                      Chunk(LlmAction.CallExternalFunction(ExternalFunctionCall(FunctionKey.unsafe("clock"), "{}")))
                    )
                )
              case 2 =>
                val toolResult = runContext.messages.collectFirst {
                  case Message.ToolOutput(output) if output.name == FunctionKey.unsafe("clock") =>
                    output.output
                }
                ZIO.succeed(AgentDecision.FinalAnswer(toolResult.getOrElse("missing tool result")))
              case _ => ZIO.dieMessage("unexpected extra turn")
            }
        )
        tool = new AgentFunction[Any] {
          override val definition: FunctionToolDefinition =
            FunctionToolDefinition(
              FunctionKey.unsafe("clock"),
              "Returns a fixed time"
            )

          override def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
            calls.update(_ :+ call).as(ExternalFunctionResult("2026-10-02T12:00:00Z"))
        }
        runner = new AgentRunner[Any](Map(tool.definition.name -> tool))
        answer <- runner.runWithContext(
          agent,
          AgentTask("What time is it?"),
          StaticContext[Any](systemPrompt = "Tool context", functions = Chunk(tool.definition)),
          RunContext()
        )
        recordedCalls <- calls.get
      yield answer -> recordedCalls
    } { result =>
      assertEquals(result._1.result, AgentResult(CoordinatorId, "2026-10-02T12:00:00Z"))
      val requests = result._1.runContext.messages.collect { case Message.ToolCall(call) => call }
      val outputs = result._1.runContext.messages.collect { case Message.ToolOutput(output) => output }
      assertEquals(requests.size, 1)
      assert(FunctionCallId.from(requests.head.id.value).isRight)
      assertEquals(result._2, Chunk(ExternalFunctionCall(FunctionKey.unsafe("clock"), "{}", Some(requests.head.id))))
      assertEquals(
        outputs,
        Chunk(
          FunctionGenericOutput(requests.head.id, FunctionKey.unsafe("clock"), "2026-10-02T12:00:00Z")
        )
      )
      val transcript = result._1.runContext.messages
      assert(
        transcript.indexOf(Message.ToolCall(requests.head)) < transcript.indexOf(
          Message.ToolOutput(outputs.head)
        )
      )
    }
  }

  test("runner propagates typed Scala results and metadata without rendering JSON") {
    val call = FunctionCall(FunctionCallId.unsafe("scala-1"), FunctionKey.scalamode, "{}")
    val results = List(
      ExternalFunctionResult.ScalaMode(ScalaModeResult.Executed("42\n"), Seq("info")),
      ExternalFunctionResult.ScalaMode(
        ScalaModeResult.CompilationError("[E006] Not found: missing"),
        Seq("diagnostic"),
        1
      ),
      ExternalFunctionResult.ScalaMode(
        ScalaModeResult.Executed("ArithmeticException"),
        Seq("stack"),
        1,
        truncated = true
      ),
      ExternalFunctionResult.ScalaMode(ScalaModeResult.TimedOut("deadline exceeded"), exitCode = 137)
    ).map(_.copy(compilationTime = 1200L, executionTime = 250L, totalTime = 1500L, executionSetupTime = Some(100L)))
    val agent = TestAgent[Any](CoordinatorId, (_, _, _) => ZIO.dieMessage("must not infer"))
    runZIO(ZIO.foreach(results) { result =>
      val tool = new AgentFunction[Any] {
        val definition = FunctionToolDefinition(FunctionKey.scalamode, "Scala")
        def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] = ZIO.succeed(result)
      }
      new AgentRunner[Any](Map(FunctionKey.scalamode -> tool))
        .invocation(agent, StaticContext[Any]("Test", functions = Chunk(tool.definition)))
        .invoke(Message.ToolCall(call))
    }) { outputs =>
      assertEquals(
        outputs,
        results.map(result =>
          Message.ToolOutput(
            ScalaModeOutput(
              call.id,
              result.result,
              result.logs,
              result.exitCode,
              result.truncated,
              result.compilationTime,
              result.executionTime,
              result.totalTime,
              result.executionSetupTime
            )
          )
        )
      )
    }
  }

  test("runner represents Scala host infrastructure and argument failures as execution attempts") {
    val agent = TestAgent[Any](CoordinatorId, (_, _, _) => ZIO.dieMessage("must not infer"))
    val tool = new AgentFunction[Any] {
      val definition = FunctionToolDefinition(FunctionKey.scalamode, "Scala")
      def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
        ZIO.fail(
          YallmoError.ToolExecutionFailed(
            call.functionKey,
            "CLI unavailable",
            new java.io.IOException("CLI unavailable")
          )
        )
    }
    val execution = new AgentRunner[Any](Map(FunctionKey.scalamode -> tool))
      .invocation(agent, StaticContext[Any]("Test", functions = Chunk(tool.definition)))
    runZIO(ZIO.foreach(List("{}", "not-json")) { arguments =>
      execution.invoke(
        Message.ToolCall(FunctionCall(FunctionCallId.unsafe("scala-error"), FunctionKey.scalamode, arguments))
      )
    }) { outputs =>
      outputs.foreach {
        case Message.ToolOutput(output: ScalaModeOutput) =>
          assert(output.result.isInstanceOf[ScalaModeResult.Executed])
          assertEquals(output.name, FunctionKey.scalamode)
          assertEquals(output.exitCode, 1)
          assert(output.output.contains("error"))
          assertEquals(output.logs, Seq.empty)
        case other => fail(s"Expected ScalaModeOutput, got $other")
      }
      assert(outputs.head.contentStr.contains("CLI unavailable"))
      assert(outputs.last.contentStr.contains("InvalidToolArguments"))
    }
  }

  test("runner assigns distinct IDs across batches and turns, preserves supplied IDs and links failures") {
    val suppliedId = FunctionCallId.unsafe("provider-call")
    runZIO {
      for
        turns <- Ref.make(0)
        executions <- Ref.make(Chunk.empty[ExternalFunctionCall])
        agent = TestAgent[Any](
          CoordinatorId,
          (_, _, _) =>
            turns.updateAndGet(_ + 1).map {
              case 1 =>
                AgentDecision.Actions(
                  Chunk(
                    LlmAction.CallExternalFunction(ExternalFunctionCall(FunctionKey.unsafe("clock"), "{}")),
                    LlmAction
                      .CallExternalFunction(ExternalFunctionCall(FunctionKey.unsafe("clock"), "{}", Some(suppliedId))),
                    LlmAction.CallExternalFunction(ExternalFunctionCall(FunctionKey.unsafe("missing"), "{}"))
                  )
                )
              case 2 =>
                AgentDecision
                  .Actions(
                    Chunk(LlmAction.CallExternalFunction(ExternalFunctionCall(FunctionKey.unsafe("clock"), "{}")))
                  )
              case _ => AgentDecision.FinalAnswer("done")
            }
        )
        tool = new AgentFunction[Any] {
          override val definition = FunctionToolDefinition(
            FunctionKey.unsafe("clock"),
            "Returns a fixed time"
          )
          override def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
            executions.update(_ :+ call).as(ExternalFunctionResult("12:00"))
        }
        run <- new AgentRunner[Any](Map(FunctionKey.unsafe("clock") -> tool)).runWithContext(
          agent,
          AgentTask("Check repeatedly"),
          StaticContext[Any](
            "System",
            functions = Chunk(
              tool.definition,
              FunctionToolDefinition(
                FunctionKey.unsafe("missing"),
                "Not registered"
              )
            )
          ),
          RunContext()
        )
        executed <- executions.get
      yield run -> executed
    } { case (run, executed) =>
      val transcript = run.runContext.messages
      val requests = transcript.collect { case Message.ToolCall(call) => call }
      val outputs = transcript.collect { case Message.ToolOutput(output) => output }
      assertEquals(requests.size, 4)
      assertEquals(requests.map(_.id).distinct.size, 4)
      assertEquals(requests(1).id, suppliedId)
      assert(requests.forall(call => FunctionCallId.from(call.id.value).isRight))
      assertEquals(outputs.map(_.callId), requests.map(_.id))
      assertEquals(outputs.map(_.name), requests.map(_.name))
      assertEquals(outputs.take(2).map(_.output), Chunk("12:00", "12:00"))
      assert(outputs(2).output.contains("UnknownTool"))
      assertEquals(outputs(3).output, "12:00")
      assertEquals(
        executed.map(_.callId),
        requests.filter(_.name == FunctionKey.unsafe("clock")).map(call => Some(call.id))
      )
      requests.zip(outputs).foreach { case (call, output) =>
        assert(transcript.indexOf(Message.ToolCall(call)) < transcript.indexOf(Message.ToolOutput(output)))
      }
    }
  }

  test("approval guard prevents an unapproved external tool from executing") {
    runZIO {
      for
        executions <- Ref.make(0)
        turns <- Ref.make(0)
        agent = TestAgent[Any](
          CoordinatorId,
          (_, _, runContext) =>
            turns.updateAndGet(_ + 1).map {
              case 1 =>
                AgentDecision
                  .Actions(
                    Chunk(LlmAction.CallExternalFunction(ExternalFunctionCall(FunctionKey.unsafe("email"), "{}")))
                  )
              case 2 =>
                AgentDecision.FinalAnswer(
                  runContext.messages
                    .collectFirst {
                      case Message.ToolOutput(output) if output.name == FunctionKey.unsafe("email") => output.output
                    }
                    .getOrElse("missing")
                )
              case _ => throw new IllegalStateException("unexpected extra turn")
            }
        )
        tool = new AgentFunction[Any] {
          override val definition: FunctionToolDefinition =
            FunctionToolDefinition(FunctionKey.unsafe("email"), "Sends an email")

          override def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
            executions.update(_ + 1).as(ExternalFunctionResult("sent"))
        }
        approval = new FunctionApproval[Any] {
          override def approve(
              definition: FunctionToolDefinition,
              call: ExternalFunctionCall
          ): IO[YallmoError, Unit] =
            ZIO.fail(YallmoError.FunctionApprovalDenied(definition.name, "recipient is not authorized"))
        }
        guarded = FunctionApproval.guard(tool, approval)
        runner = new AgentRunner[Any](Map(guarded.definition.name -> guarded))
        outcome <- runner.run(
          agent,
          AgentTask("Send an email"),
          StaticContext[Any](systemPrompt = "Tool context", functions = Chunk(tool.definition))
        )
        count <- executions.get
      yield outcome -> count
    } { result =>
      assertEquals(
        result._1,
        AgentResult(
          CoordinatorId,
          "{\"error\":{\"kind\":\"FunctionApprovalDenied\",\"message\":\"Tool 'email' was not approved: recipient is not authorized\"}}"
        )
      )
      assertEquals(result._2, 0)
    }
  }

  test("runner returns a tool error without executing invalid arguments") {
    runZIO {
      for
        executions <- Ref.make(0)
        turns <- Ref.make(0)
        toolDefinition = FunctionToolDefinition(
          FunctionKey.unsafe("clock"),
          "Returns a fixed time",
          fmgp.yallmo.model.llm.JsonSchema.ObjectSchema(
            Map("timezone" -> fmgp.yallmo.model.llm.JsonSchema.StringSchema()),
            required = Set("timezone"),
            additionalProperties = false
          )
        )
        tool = new AgentFunction[Any] {
          override val definition: FunctionToolDefinition = toolDefinition
          override def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
            executions.update(_ + 1).as(ExternalFunctionResult("unexpected"))
        }
        agent = TestAgent[Any](
          CoordinatorId,
          (_, _, runContext) =>
            turns.updateAndGet(_ + 1).map {
              case 1 =>
                AgentDecision
                  .Actions(
                    Chunk(LlmAction.CallExternalFunction(ExternalFunctionCall(FunctionKey.unsafe("clock"), "{}")))
                  )
              case 2 =>
                AgentDecision.FinalAnswer(
                  runContext.messages
                    .collectFirst {
                      case Message.ToolOutput(output) if output.name == FunctionKey.unsafe("clock") => output.output
                    }
                    .getOrElse("missing")
                )
              case _ => throw new IllegalStateException("unexpected extra turn")
            }
        )
        answer <- new AgentRunner[Any](Map(FunctionKey.unsafe("clock") -> tool)).run(
          agent,
          AgentTask("What time is it?"),
          StaticContext[Any](systemPrompt = "Tool context", functions = Chunk(toolDefinition))
        )
        count <- executions.get
      yield answer -> count
    } { result =>
      assert(result._1.text.contains("InvalidToolArguments"))
      assert(result._1.text.contains("timezone"))
      assertEquals(result._2, 0)
    }
  }

  test("JSON decision codec converts model actions") {
    val staticContext = StaticContext[Any](systemPrompt = "Coordinator context")
    val response =
      """{"actions":[
        |{"kind":"call_external_tool","toolKey":"clock","arguments":{}}
        |]}""".stripMargin

    runZIO(JsonAgentDecisionCodec.decode(response, staticContext)) { result =>
      assertEquals(
        result,
        AgentDecision.Actions(
          Chunk(
            LlmAction.CallExternalFunction(ExternalFunctionCall(FunctionKey.unsafe("clock"), "{}"))
          )
        )
      )
    }
  }

  test("JSON decision codec rejects removed retrieval actions") {
    val response = """{"actions":[{"kind":"retrieve_rag","retrieverKey":"docs","query":"query"}]}"""
    runZIO(JsonAgentDecisionCodec.decode(response, StaticContext[Any]("test")).either) { result =>
      assert(result.isLeft)
    }
  }

  test("runner annotates its work with its stable identity") {
    val runnerId = UUID.randomUUID()
    var observed = Option.empty[String]
    val agent = TestAgent[Any](
      CoordinatorId,
      (_, _, _) =>
        FiberRef.currentLogAnnotations.get.map { annotations =>
          observed = annotations.get(AgentRunner.LogAnnotationKey)
          AgentDecision.FinalAnswer("done")
        }
    )
    val runner = new AgentRunner[Any](Map.empty, runnerId)

    runZIO(runner.run(agent, AgentTask("test"), StaticContext[Any](systemPrompt = "Test"))) { _ =>
      assertEquals(observed, Some(runnerId.toString))
    }
  }

  test("runner stops an agent that exceeds its turn budget") {
    val agent = TestAgent[Any](
      CoordinatorId,
      (_, _, _) =>
        ZIO.succeed(
          AgentDecision.Actions(
            Chunk(LlmAction.CallExternalFunction(ExternalFunctionCall(FunctionKey.unsafe("loop"), "{}")))
          )
        )
    )
    val runner = new AgentRunner[Any](Map.empty)
    val budget = AgentBudget(maxTurns = 2, maxActions = 3, timeout = 1.second)

    runZIO(
      runner
        .run(
          agent,
          AgentTask("Loop"),
          StaticContext[Any](systemPrompt = "Loop context"),
          budget
        )
        .either
    ) { result =>
      assertEquals(result, Left(YallmoError.TurnLimitExceeded(2)))
    }
  }

  private final case class TestAgent[R](
      id: AgentId,
      decide: (AgentTask, StaticContext[R], RunContext) => ZIO[R, YallmoError, AgentDecision]
  ) extends AgentInstance[R] {
    override def next(
        task: AgentTask,
        staticContext: StaticContext[R],
        runContext: RunContext
    ): ZIO[R, YallmoError, AgentTurn] =
      decide(task, staticContext, runContext).map { decision =>
        AgentTurn(Some(Message.Assistant(decision.toString)), decision)
      }
  }

  private def runZIO[E, A](effect: ZIO[Any, E, A])(check: A => Unit): Future[Unit] =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.runToFuture(effect.map(check).mapError(error => FiberFailure(Cause.fail(error))))
    }
