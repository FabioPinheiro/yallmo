package fmgp.yallmo.orchestration

import fmgp.yallmo.model.YallmoError
import java.util.UUID
import munit.FunSuite
import zio.*
import fmgp.yallmo.model.*

final class AgentRunnerSuite extends FunSuite:
  private val CoordinatorId = AgentId("coordinator")

  test("agent identifiers reject blank values") {
    assert(AgentId.from(" ").isLeft)
    assertEquals(AgentId.from("coordinator"), Right(CoordinatorId))
  }

  test("runner feeds retrieved RAG documents into the agent context before the next turn") {
    val result = runZIO {
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
    }

    assertEquals(result, AgentResult(CoordinatorId, "Found 1 document"))
  }

  test("runner executes an advertised external tool and gives its result to the next turn") {
    val result = runZIO {
      for
        turns <- Ref.make(0)
        calls <- Ref.make(Chunk.empty[ExternalToolCall])
        agent = TestAgent[Any](
          CoordinatorId,
          (_, _, runContext) =>
            turns.updateAndGet(_ + 1).flatMap {
              case 1 =>
                ZIO.succeed(
                  AgentDecision.Actions(Chunk(LlmAction.CallExternalTool(ExternalToolCall("clock", "{}"))))
                )
              case 2 =>
                val toolResult = runContext.messages.collectFirst { case Message.ToolResult("clock", text) =>
                  text
                }
                ZIO.succeed(AgentDecision.FinalAnswer(toolResult.getOrElse("missing tool result")))
              case _ => ZIO.dieMessage("unexpected extra turn")
            }
        )
        tool = new AgentTool[Any] {
          override val definition: AgentToolDefinition = AgentToolDefinition("clock", "Returns a fixed time")

          override def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
            calls.update(_ :+ call).as(ExternalToolResult("2026-10-02T12:00:00Z"))
        }
        runner = new AgentRunner[Any](Map(tool.definition.key -> tool), Map.empty)
        answer <- runner.run(
          agent,
          AgentTask("What time is it?"),
          StaticContext[Any](systemPrompt = "Tool context", tools = Chunk(tool.definition))
        )
        recordedCalls <- calls.get
      yield answer -> recordedCalls
    }

    assertEquals(result._1, AgentResult(CoordinatorId, "2026-10-02T12:00:00Z"))
    assertEquals(result._2, Chunk(ExternalToolCall("clock", "{}")))
  }

  test("approval guard prevents an unapproved external tool from executing") {
    val result = runZIO {
      for
        executions <- Ref.make(0)
        turns <- Ref.make(0)
        agent = TestAgent[Any](
          CoordinatorId,
          (_, _, runContext) =>
            turns.updateAndGet(_ + 1).map {
              case 1 => AgentDecision.Actions(Chunk(LlmAction.CallExternalTool(ExternalToolCall("email", "{}"))))
              case 2 =>
                AgentDecision.FinalAnswer(
                  runContext.messages
                    .collectFirst { case Message.ToolResult("email", output) => output }
                    .getOrElse("missing")
                )
              case _ => throw new IllegalStateException("unexpected extra turn")
            }
        )
        tool = new AgentTool[Any] {
          override val definition: AgentToolDefinition = AgentToolDefinition("email", "Sends an email")

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
    }

    assertEquals(
      result._1,
      AgentResult(
        CoordinatorId,
        "{\"error\":{\"kind\":\"ToolApprovalDenied\",\"message\":\"Tool 'email' was not approved: recipient is not authorized\"}}"
      )
    )
    assertEquals(result._2, 0)
  }

  test("runner returns a tool error without executing invalid arguments") {
    val result = runZIO {
      for
        executions <- Ref.make(0)
        turns <- Ref.make(0)
        toolDefinition = AgentToolDefinition(
          "clock",
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
              case 1 => AgentDecision.Actions(Chunk(LlmAction.CallExternalTool(ExternalToolCall("clock", "{}"))))
              case 2 =>
                AgentDecision.FinalAnswer(
                  runContext.messages
                    .collectFirst { case Message.ToolResult("clock", output) => output }
                    .getOrElse("missing")
                )
              case _ => throw new IllegalStateException("unexpected extra turn")
            }
        )
        answer <- new AgentRunner[Any](Map("clock" -> tool), Map.empty).run(
          agent,
          AgentTask("What time is it?"),
          StaticContext[Any](systemPrompt = "Tool context", tools = Chunk(toolDefinition))
        )
        count <- executions.get
      yield answer -> count
    }

    assert(result._1.text.contains("InvalidToolArguments"))
    assert(result._1.text.contains("timezone"))
    assertEquals(result._2, 0)
  }

  test("JSON decision codec converts model actions") {
    val staticContext = StaticContext[Any](systemPrompt = "Coordinator context")
    val response =
      """{"actions":[
        |{"kind":"retrieve_rag","retrieverKey":"knowledge","query":"Faro beaches"},
        |{"kind":"call_external_tool","toolKey":"clock","arguments":{}}
        |]}""".stripMargin

    val result = runZIO(JsonAgentDecisionCodec.decode(response, staticContext))

    assertEquals(
      result,
      AgentDecision.Actions(
        Chunk(
          LlmAction.RetrieveRag("knowledge", RagQuery("Faro beaches")),
          LlmAction.CallExternalTool(ExternalToolCall("clock", "{}"))
        )
      )
    )
  }

  test("context extensions run before every agent turn") {
    val extension = new ContextExtension[Any] {
      override val key: String = "request-metadata"

      override def apply(
          _staticContext: StaticContext[Any],
          runContext: RunContext
      ): IO[YallmoError, RunContext] =
        ZIO.succeed(runContext.append(Message.ToolResult(key, "request-id=42")))
    }
    val agent = TestAgent[Any](
      CoordinatorId,
      (_, _, runContext) =>
        val metadata = runContext.messages.collectFirst { case Message.ToolResult("request-metadata", text) =>
          text
        }
        ZIO.succeed(AgentDecision.FinalAnswer(metadata.getOrElse("missing metadata")))
    )
    val runner = new AgentRunner[Any](Map.empty, Map.empty)

    val result = runZIO(
      runner.run(agent, AgentTask("Hello"), StaticContext[Any](systemPrompt = "Test", extensions = Chunk(extension)))
    )

    assertEquals(result, AgentResult(CoordinatorId, "request-id=42"))
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

    runZIO(runner.run(agent, AgentTask("test"), StaticContext[Any](systemPrompt = "Test")))

    assertEquals(observed, Some(runnerId.toString))
  }

  test("runner stops an agent that exceeds its turn budget") {
    val agent = TestAgent[Any](
      CoordinatorId,
      (_, _, _) => ZIO.succeed(AgentDecision.Actions(Chunk(LlmAction.RetrieveRag("knowledge", RagQuery("again")))))
    )
    val retriever = StaticRetriever(RetrieverDefinition("knowledge", "Test retriever"), Chunk.empty)
    val runner = new AgentRunner[Any](Map.empty, Map(retriever.definition.key -> retriever))
    val budget = AgentBudget(maxTurns = 2, maxActions = 3, timeout = 1.second)

    val result = runZIO(
      runner
        .run(
          agent,
          AgentTask("Loop"),
          StaticContext[Any](systemPrompt = "Loop context", retrievers = Chunk(retriever.definition)),
          budget
        )
        .either
    )

    assertEquals(result, Left(YallmoError.TurnLimitExceeded(2)))
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

  private def runZIO[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }
