package fmgp.yallmo.orchestration

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

  test("even internal loop execution requires a transcript-saving operation") {
    val errors = typeCheckErrors("""
      import fmgp.yallmo.orchestration.*
      def missingSave(execution: AgentExecution[Any]) =
        execution.run(AgentTask("hello"), RunContext())
    """)
    assert(errors.exists(_.message.contains("saveTranscriptLater")))
  }

  test("agent identifiers reject blank values") {
    assert(AgentId.from(" ").isLeft)
    assertEquals(AgentId.from("coordinator"), Right(CoordinatorId))
  }

  test("runner feeds retrieved RAG documents into the agent context before the next turn") {
    runZIO {
      for
        turns <- Ref.make(0)
        agent = TestAgent[Any](
          CoordinatorId,
          (_, _, runContext) =>
            turns.updateAndGet(_ + 1).flatMap {
              case 1 => ZIO.succeed(AgentDecision.Actions(Chunk(LlmAction.RetrieveRag("knowledge", RagQuery("Faro")))))
              case 2 =>
                val documents = runContext.messages.collectFirst { case Message.RagResult("knowledge", found) =>
                  found
                }
                ZIO.succeed(AgentDecision.FinalAnswer(s"Found ${documents.fold(0)(_.size)} document"))
              case _ => ZIO.dieMessage("unexpected extra turn")
            }
        )
        retriever = StaticRetriever(
          RetrieverDefinition("knowledge", "Local destination knowledge"),
          Chunk(RagDocument("faro-1", "guide", "Faro is in southern Portugal."))
        )
        runner = new AgentRunner[Any](Map.empty, Map(retriever.definition.key -> retriever))
        answer <- runner.run(
          agent,
          AgentTask("Research Faro"),
          StaticContext[Any](systemPrompt = "Research context", retrievers = Chunk(retriever.definition))
        )
      yield answer
    } { result =>
      assertEquals(result, AgentResult(CoordinatorId, "Found 1 document"))
    }
  }

  test("runner executes an advertised external tool and gives its result to the next turn") {
    runZIO {
      for
        turns <- Ref.make(0)
        calls <- Ref.make(Chunk.empty[ExternalToolCall])
        agent = TestAgent[Any](
          CoordinatorId,
          (_, _, runContext) =>
            turns.updateAndGet(_ + 1).flatMap {
              case 1 =>
                ZIO.succeed(
                  AgentDecision
                    .Actions(Chunk(LlmAction.CallExternalTool(ExternalToolCall(ToolKey.unsafe("clock"), "{}"))))
                )
              case 2 =>
                val toolResult = runContext.messages.collectFirst {
                  case Message.ToolOutput(output) if output.name == ToolKey.unsafe("clock") =>
                    output.output
                }
                ZIO.succeed(AgentDecision.FinalAnswer(toolResult.getOrElse("missing tool result")))
              case _ => ZIO.dieMessage("unexpected extra turn")
            }
        )
        tool = new AgentTool[Any] {
          override val definition: AgentToolDefinition =
            AgentToolDefinition(ToolKey.unsafe("clock"), "Returns a fixed time")

          override def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
            calls.update(_ :+ call).as(ExternalToolResult("2026-10-02T12:00:00Z"))
        }
        runner = new AgentRunner[Any](Map(tool.definition.key -> tool), Map.empty)
        answer <- runner.runWithContext(
          agent,
          AgentTask("What time is it?"),
          StaticContext[Any](systemPrompt = "Tool context", tools = Chunk(tool.definition)),
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
      assertEquals(result._2, Chunk(ExternalToolCall(ToolKey.unsafe("clock"), "{}", Some(requests.head.id))))
      assertEquals(
        outputs,
        Chunk(
          FunctionGenericOutput(requests.head.id, ToolKey.unsafe("clock"), "2026-10-02T12:00:00Z")
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
    val call = FunctionCall(FunctionCallId.unsafe("scala-1"), ToolKey.Scalamode, "{}")
    val results = List(
      ExternalToolResult.ScalaMode(ScalaModeResult.Executed("42\n"), Seq("info")),
      ExternalToolResult.ScalaMode(ScalaModeResult.CompilationError("[E006] Not found: missing"), Seq("diagnostic"), 1),
      ExternalToolResult.ScalaMode(ScalaModeResult.Executed("ArithmeticException"), Seq("stack"), 1, truncated = true),
      ExternalToolResult.ScalaMode(ScalaModeResult.TimedOut("deadline exceeded"), exitCode = 137)
    ).map(_.copy(compilationTime = 1200L, executionTime = 250L, totalTime = 1500L))
    val agent = TestAgent[Any](CoordinatorId, (_, _, _) => ZIO.dieMessage("must not infer"))
    runZIO(ZIO.foreach(results) { result =>
      val tool = new AgentTool[Any] {
        val definition = AgentToolDefinition(ToolKey.Scalamode, "Scala")
        def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] = ZIO.succeed(result)
      }
      new AgentRunner[Any](Map(ToolKey.Scalamode -> tool), Map.empty)
        .invocation(agent, StaticContext[Any]("Test", tools = Chunk(tool.definition)))
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
              result.totalTime
            )
          )
        )
      )
    }
  }

  test("runner represents Scala host infrastructure and argument failures as execution attempts") {
    val agent = TestAgent[Any](CoordinatorId, (_, _, _) => ZIO.dieMessage("must not infer"))
    val tool = new AgentTool[Any] {
      val definition = AgentToolDefinition(ToolKey.Scalamode, "Scala")
      def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
        ZIO.fail(
          YallmoError.ToolExecutionFailed(call.toolKey, "CLI unavailable", new java.io.IOException("CLI unavailable"))
        )
    }
    val execution = new AgentRunner[Any](Map(ToolKey.Scalamode -> tool), Map.empty)
      .invocation(agent, StaticContext[Any]("Test", tools = Chunk(tool.definition)))
    runZIO(ZIO.foreach(List("{}", "not-json")) { arguments =>
      execution.invoke(
        Message.ToolCall(FunctionCall(FunctionCallId.unsafe("scala-error"), ToolKey.Scalamode, arguments))
      )
    }) { outputs =>
      outputs.foreach {
        case Message.ToolOutput(output: ScalaModeOutput) =>
          assert(output.result.isInstanceOf[ScalaModeResult.Executed])
          assertEquals(output.name, ToolKey.Scalamode)
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
        executions <- Ref.make(Chunk.empty[ExternalToolCall])
        agent = TestAgent[Any](
          CoordinatorId,
          (_, _, _) =>
            turns.updateAndGet(_ + 1).map {
              case 1 =>
                AgentDecision.Actions(
                  Chunk(
                    LlmAction.CallExternalTool(ExternalToolCall(ToolKey.unsafe("clock"), "{}")),
                    LlmAction.CallExternalTool(ExternalToolCall(ToolKey.unsafe("clock"), "{}", Some(suppliedId))),
                    LlmAction.CallExternalTool(ExternalToolCall(ToolKey.unsafe("missing"), "{}"))
                  )
                )
              case 2 =>
                AgentDecision
                  .Actions(Chunk(LlmAction.CallExternalTool(ExternalToolCall(ToolKey.unsafe("clock"), "{}"))))
              case _ => AgentDecision.FinalAnswer("done")
            }
        )
        tool = new AgentTool[Any] {
          override val definition = AgentToolDefinition(ToolKey.unsafe("clock"), "Returns a fixed time")
          override def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
            executions.update(_ :+ call).as(ExternalToolResult("12:00"))
        }
        run <- new AgentRunner[Any](Map(ToolKey.unsafe("clock") -> tool), Map.empty).runWithContext(
          agent,
          AgentTask("Check repeatedly"),
          StaticContext[Any](
            "System",
            tools = Chunk(tool.definition, AgentToolDefinition(ToolKey.unsafe("missing"), "Not registered"))
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
        requests.filter(_.name == ToolKey.unsafe("clock")).map(call => Some(call.id))
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
                  .Actions(Chunk(LlmAction.CallExternalTool(ExternalToolCall(ToolKey.unsafe("email"), "{}"))))
              case 2 =>
                AgentDecision.FinalAnswer(
                  runContext.messages
                    .collectFirst {
                      case Message.ToolOutput(output) if output.name == ToolKey.unsafe("email") => output.output
                    }
                    .getOrElse("missing")
                )
              case _ => throw new IllegalStateException("unexpected extra turn")
            }
        )
        tool = new AgentTool[Any] {
          override val definition: AgentToolDefinition = AgentToolDefinition(ToolKey.unsafe("email"), "Sends an email")

          override def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
            executions.update(_ + 1).as(ExternalToolResult("sent"))
        }
        approval = new ToolApproval[Any] {
          override def approve(
              definition: AgentToolDefinition,
              call: ExternalToolCall
          ): IO[YallmoError, Unit] =
            ZIO.fail(YallmoError.ToolApprovalDenied(definition.key, "recipient is not authorized"))
        }
        guarded = ToolApproval.guard(tool, approval)
        runner = new AgentRunner[Any](Map(guarded.definition.key -> guarded), Map.empty)
        outcome <- runner.run(
          agent,
          AgentTask("Send an email"),
          StaticContext[Any](systemPrompt = "Tool context", tools = Chunk(tool.definition))
        )
        count <- executions.get
      yield outcome -> count
    } { result =>
      assertEquals(
        result._1,
        AgentResult(
          CoordinatorId,
          "{\"error\":{\"kind\":\"ToolApprovalDenied\",\"message\":\"Tool 'email' was not approved: recipient is not authorized\"}}"
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
        toolDefinition = AgentToolDefinition(
          ToolKey.unsafe("clock"),
          "Returns a fixed time",
          fmgp.yallmo.model.llm.JsonSchema.ObjectSchema(
            Map("timezone" -> fmgp.yallmo.model.llm.JsonSchema.StringSchema()),
            required = Set("timezone"),
            additionalProperties = false
          )
        )
        tool = new AgentTool[Any] {
          override val definition: AgentToolDefinition = toolDefinition
          override def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
            executions.update(_ + 1).as(ExternalToolResult("unexpected"))
        }
        agent = TestAgent[Any](
          CoordinatorId,
          (_, _, runContext) =>
            turns.updateAndGet(_ + 1).map {
              case 1 =>
                AgentDecision
                  .Actions(Chunk(LlmAction.CallExternalTool(ExternalToolCall(ToolKey.unsafe("clock"), "{}"))))
              case 2 =>
                AgentDecision.FinalAnswer(
                  runContext.messages
                    .collectFirst {
                      case Message.ToolOutput(output) if output.name == ToolKey.unsafe("clock") => output.output
                    }
                    .getOrElse("missing")
                )
              case _ => throw new IllegalStateException("unexpected extra turn")
            }
        )
        answer <- new AgentRunner[Any](Map(ToolKey.unsafe("clock") -> tool), Map.empty).run(
          agent,
          AgentTask("What time is it?"),
          StaticContext[Any](systemPrompt = "Tool context", tools = Chunk(toolDefinition))
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
        |{"kind":"retrieve_rag","retrieverKey":"knowledge","query":"Faro beaches"},
        |{"kind":"call_external_tool","toolKey":"clock","arguments":{}}
        |]}""".stripMargin

    runZIO(JsonAgentDecisionCodec.decode(response, staticContext)) { result =>
      assertEquals(
        result,
        AgentDecision.Actions(
          Chunk(
            LlmAction.RetrieveRag("knowledge", RagQuery("Faro beaches")),
            LlmAction.CallExternalTool(ExternalToolCall(ToolKey.unsafe("clock"), "{}"))
          )
        )
      )
    }
  }

  test("context extensions run before every agent turn") {
    val extension = new ContextExtension[Any] {
      override val key: String = "request-metadata"

      override def apply(
          _staticContext: StaticContext[Any],
          runContext: RunContext
      ): IO[YallmoError, RunContext] =
        ZIO.succeed(runContext.append(Message.Context(key, "request-id=42")))
    }
    val agent = TestAgent[Any](
      CoordinatorId,
      (_, _, runContext) =>
        val metadata = runContext.messages.collectFirst { case Message.Context("request-metadata", text) =>
          text
        }
        ZIO.succeed(AgentDecision.FinalAnswer(metadata.getOrElse("missing metadata")))
    )
    val runner = new AgentRunner[Any](Map.empty, Map.empty)

    runZIO(
      runner.run(agent, AgentTask("Hello"), StaticContext[Any](systemPrompt = "Test", extensions = Chunk(extension)))
    ) { result =>
      assertEquals(result, AgentResult(CoordinatorId, "request-id=42"))
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
    val runner = new AgentRunner[Any](Map.empty, Map.empty, runnerId)

    runZIO(runner.run(agent, AgentTask("test"), StaticContext[Any](systemPrompt = "Test"))) { _ =>
      assertEquals(observed, Some(runnerId.toString))
    }
  }

  test("runner stops an agent that exceeds its turn budget") {
    val agent = TestAgent[Any](
      CoordinatorId,
      (_, _, _) => ZIO.succeed(AgentDecision.Actions(Chunk(LlmAction.RetrieveRag("knowledge", RagQuery("again")))))
    )
    val retriever = StaticRetriever(RetrieverDefinition("knowledge", "Test retriever"), Chunk.empty)
    val runner = new AgentRunner[Any](Map.empty, Map(retriever.definition.key -> retriever))
    val budget = AgentBudget(maxTurns = 2, maxActions = 3, timeout = 1.second)

    runZIO(
      runner
        .run(
          agent,
          AgentTask("Loop"),
          StaticContext[Any](systemPrompt = "Loop context", retrievers = Chunk(retriever.definition)),
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

  private final case class StaticRetriever(
      definition: RetrieverDefinition,
      documents: Chunk[RagDocument]
  ) extends KnowledgeRetriever[Any] {
    override def retrieve(query: RagQuery): IO[YallmoError, Chunk[RagDocument]] = ZIO.succeed(documents)
  }

  private def runZIO[E, A](effect: ZIO[Any, E, A])(check: A => Unit): Future[Unit] =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.runToFuture(effect.map(check).mapError(error => FiberFailure(Cause.fail(error))))
    }
