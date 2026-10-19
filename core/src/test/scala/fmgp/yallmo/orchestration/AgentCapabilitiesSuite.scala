package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.*
import fmgp.yallmo.orchestration.config.{AgentCapabilities, AgentConfig, ConfiguredAgentSettings}
import munit.FunSuite
import zio.*
import zio.json.*

final class AgentCapabilitiesSuite extends FunSuite {
  private val id = AgentId("policy-test")
  private val disabled = AgentCapabilities(Some(Set.empty), Some(Set.empty), Some(Set.empty), Some(Set.empty))
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
  private val extension = new ContextExtension[Any] {
    val key = "memory"
    def apply(context: StaticContext[Any], current: RunContext): IO[YallmoError, RunContext] = {
      val _ = context
      ZIO.succeed(current.append(MessagePayload.Context("memory", "extension text")))
    }
  }
  private val available = StaticContext[Any](
    "system",
    tools = Chunk(AgentToolDefinition("clock", "time"), AgentToolDefinition("shell", "commands")),
    retrievers = Chunk(RetrieverDefinition("docs", "documents")),
    skills = Chunk(AgentSkill("brief", "be brief", "SECRET_SKILL_INSTRUCTIONS")),
    extensions = Chunk(extension)
  )
  private val request = AgentTurnRequest(
    "model",
    Chunk(MessagePayload.Instructions("system")),
    tools = Chunk(
      FunctionToolDefinition("clock", "time", JsonSchema.ObjectSchema(Map.empty)),
      FunctionToolDefinition("shell", "commands", fmgp.yallmo.model.llm.JsonSchema.ObjectSchema(Map.empty))
    ),
    toolChoice = ToolChoice.Function("shell"),
    parallelToolCalls = true
  )

  test("omitted, empty object, explicit empties and partial capability objects decode distinctly") {
    def decode(suffix: String) = s"""{"agentType":"OAIAgent"$suffix}""".fromJson[AgentConfig].toOption.get
    assertEquals(decode("").capabilities, AgentCapabilities())
    assertEquals(decode(",\"capabilities\":{}").capabilities, AgentCapabilities())
    val partial = decode(",\"capabilities\":{\"tools\":[]}")
    assertEquals(partial.capabilities.tools, Some(Set.empty[String]))
    val context = partial.capabilities.resolve(id, available).toOption.get
    assert(context.tools.isEmpty)
    assertEquals(context.retrievers, available.retrievers)
    assertEquals(context.skills, available.skills)
    assertEquals(context.extensions, available.extensions)
    assertEquals(partial.toJson.fromJson[AgentConfig], Right(partial))
    assertEquals(AgentCapabilities().resolve(id, available), Right(available))
    val empty = disabled.resolve(id, available).toOption.get
    assert(empty.tools.isEmpty && empty.retrievers.isEmpty && empty.skills.isEmpty && empty.extensions.isEmpty)
  }

  test("nonempty allowlists restrict each catalog and preserve ordering") {
    val context = AgentCapabilities(tools = Some(Set("shell"))).resolve(id, available).toOption.get
    assertEquals(context.tools.map(_.key), Chunk("shell"))
    assertEquals(context.skills, available.skills)
  }

  for (kind, policy) <- List(
      "tools" -> AgentCapabilities(tools = Some(Set("typo"))),
      "retrievers" -> AgentCapabilities(retrievers = Some(Set("typo"))),
      "skills" -> AgentCapabilities(skills = Some(Set("typo"))),
      "extensions" -> AgentCapabilities(extensions = Some(Set("typo")))
    )
  do
    test(s"unknown $kind IDs fail with agent and field, not context contents") {
      val error = policy.resolve(id, available).swap.toOption.get.getMessage
      assert(error.contains("policy-test") && error.contains(s"capabilities.$kind") && error.contains("typo"))
      assert(!error.contains("SECRET_SKILL_INSTRUCTIONS"))
    }

  test("assembled request filtering removes forbidden definitions and forced choice; empty disables parallel calls") {
    val filtered = AgentCapabilities(tools = Some(Set("clock"))).filterRequest(request)
    assertEquals(filtered.tools.map(_.name), Chunk("clock"))
    assertEquals(filtered.toolChoice, ToolChoice.None)
    val empty = disabled.filterRequest(request)
    assert(empty.tools.isEmpty)
    assertEquals(empty.toolChoice, ToolChoice.None)
    assert(!empty.parallelToolCalls)
    assertEquals(AgentCapabilities().filterRequest(request), request)
  }

  test("configured adapter prevents caller tool bypass and rejects unexpected calls without execution") {
    val result = run {
      for {
        seen <- Ref.make(Option.empty[AgentTurnRequest])
        delegate = new AudioAgent[Any] {
          val id = AgentCapabilitiesSuite.this.id
          def turn(r: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
            seen
              .set(Some(r))
              .as(
                AgentTurnResponse(
                  Chunk(MessagePayload.FunctionCallRequest(FunctionCall(FunctionCallId.unsafe("call"), "shell", "{}"))),
                  LlmStopReason.ToolUse
                )
              )
        }
        adapter = ConfiguredAgent(delegate, disabled)
        outcome <- adapter.turn(request).either
        captured <- seen.get
      } yield (adapter, outcome, captured.get)
    }
    assert(result._1.isInstanceOf[AudioAgent[?]])
    assert(result._2.isLeft)
    assert(result._3.tools.isEmpty)
    assertEquals(result._3.toolChoice, ToolChoice.None)
    assert(!result._3.parallelToolCalls)
  }

  test("bound native program inherits runtime restrictions before advertising skills or applying extensions") {
    val result = run {
      for {
        seen <- Ref.make(Option.empty[AgentTurnRequest])
        delegate = new Agent[Any] {
          val id = AgentCapabilitiesSuite.this.id
          def turn(r: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
            seen.set(Some(r)).as(AgentTurnResponse(Chunk(MessagePayload.Assistant("done")), LlmStopReason.Stop))
        }
        adapter = ConfiguredAgent(delegate, disabled)
        program = NativeFunctionAgent[Agent[Any]](id, "model").bind[Any](adapter)
        runner = new AgentRunner[Any](Map.empty, Map.empty)
        _ <- runner.run(program, AgentTask("hello"), available)
        captured <- seen.get
      } yield captured.get
    }
    assert(result.tools.isEmpty)
    assertEquals(result.input.last, MessagePayload.Instructions("system"))
    assert(!result.input.exists { case MessagePayload.Context("memory", _) => true; case _ => false })
  }

  test("unbound native program resolves runtime policy before extensions") {
    val delegate = new Agent[Any] {
      val id = AgentCapabilitiesSuite.this.id
      def turn(r: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] = {
        assertEquals(r.input.last, MessagePayload.Instructions("system"))
        assert(!r.input.exists { case MessagePayload.Context("memory", _) => true; case _ => false })
        ZIO.succeed(AgentTurnResponse(Chunk(MessagePayload.Assistant("done")), LlmStopReason.Stop))
      }
    }
    val context = StaticContext[Agent[Any]](
      "system",
      skills = available.skills,
      extensions = Chunk(new ContextExtension[Agent[Any]] {
        val key = "memory"
        def apply(c: StaticContext[Agent[Any]], r: RunContext): IO[YallmoError, RunContext] = {
          val _ = (c, r)
          ZIO.dieMessage("Forbidden extension ran")
        }
      })
    )
    run(
      new AgentRunner[Agent[Any]](Map.empty, Map.empty)
        .run(NativeFunctionAgent[Agent[Any]](id, "model"), AgentTask("hello"), context)
        .provideEnvironment(ZEnvironment[Agent[Any]](ConfiguredAgent(delegate, disabled)))
    )
  }

  test("runner rejects disallowed tool and retriever actions even from a malicious program") {
    for retrieve <- List(false, true) do {
      val result = run {
        for {
          executed <- Ref.make(false)
          malicious = new AgentInstance[Any] {
            val id = AgentCapabilitiesSuite.this.id
            override val capabilities = disabled
            def next(t: AgentTask, c: StaticContext[Any], r: RunContext): IO[YallmoError, AgentTurn] = {
              val _ = (t, c)
              if r.messages.exists(_.isInstanceOf[MessagePayload.ToolResult]) then
                ZIO.succeed(AgentTurn(None, AgentDecision.FinalAnswer("done")))
              else
                ZIO.succeed(
                  AgentTurn(
                    None,
                    AgentDecision.Actions(
                      Chunk(
                        if retrieve then LlmAction.RetrieveRag("docs", RagQuery("query"))
                        else LlmAction.CallExternalTool(ExternalToolCall("shell", "{}"))
                      )
                    )
                  )
                )
            }
          }
          tool = new AgentTool[Any] {
            val definition = AgentToolDefinition("shell", "commands")
            def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] = {
              val _ = call
              executed.set(true).as(ExternalToolResult("unsafe"))
            }
          }
          retriever = new KnowledgeRetriever[Any] {
            val definition = RetrieverDefinition("docs", "documents")
            def retrieve(query: RagQuery): IO[YallmoError, Chunk[RagDocument]] = {
              val _ = query
              executed.set(true).as(Chunk.empty)
            }
          }
          outcome <- new AgentRunner[Any](Map("shell" -> tool), Map("docs" -> retriever))
            .run(malicious, AgentTask("hello"), available)
            .either
          didExecute <- executed.get
        } yield (outcome, didExecute)
      }
      assert(!result._2)
      if retrieve then assert(result._1.isLeft) else assert(result._1.isRight)
    }
  }

  test("shared settings require an explicit or application-supplied prompt without a built-in default") {
    def definition(json: String) = AgentConfig("OAIAgent", config = json.fromJson[zio.json.ast.Json.Obj].toOption.get)
    val absentPrompt = definition("""{"model":" m "}""")
    assert(ConfiguredAgentSettings.decode(absentPrompt).isLeft)
    val inherited = ConfiguredAgentSettings.decode(absentPrompt, Some("Application prompt")).toOption.get
    assertEquals(inherited.model, "m")
    assertEquals(inherited.systemPrompt, "Application prompt")
    assert(ConfiguredAgentSettings.decode(absentPrompt, Some(" ")).isLeft)
    for json <- List("{}", """{"model":" " ,"systemPrompt":"explicit"}""", """{"model":"m","systemPrompt":" "}""") do
      assert(ConfiguredAgentSettings.decode(definition(json)).isLeft)
    assert(
      ConfiguredAgentSettings.decode(definition("""{"model":"m","systemPrompt":" "}"""), Some("Fallback")).isLeft
    )
    val good = ConfiguredAgentSettings
      .decode(
        definition("""{"model":"m","systemPrompt":"explicit","thinkingLevel":"low","parallelToolCalls":true}"""),
        Some("Application prompt")
      )
      .toOption
      .get
    assertEquals(good.systemPrompt, "explicit")
    assertEquals(good.thinkingLevel, Some(ThinkingLevel.Low))
    val bad = ConfiguredAgentSettings
      .decode(definition("""{"model":"m","systemPrompt":"explicit","thinkingLevel":"SECRET_BAD_VALUE"}"""))
      .swap
      .toOption
      .get
    assert(!bad.getMessage.contains("SECRET_BAD_VALUE"))
  }

  test("binding intersects policies without widening or hiding unknown configured IDs") {
    val delegate = new Agent[Any] {
      val id = AgentCapabilitiesSuite.this.id
      def turn(r: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] = {
        assert(r.tools.isEmpty)
        ZIO.succeed(AgentTurnResponse(Chunk(MessagePayload.Assistant("done")), LlmStopReason.Stop))
      }
    }
    val program = NativeFunctionAgent[Agent[Any]](id, "model", capabilities = disabled)
    val adapter = ConfiguredAgent(delegate, AgentCapabilities(tools = Some(Set("clock"))))
    run(new AgentRunner[Any](Map.empty, Map.empty).run(program.bind[Any](adapter), AgentTask("hello"), available))
    val invalid = ConfiguredAgent(delegate, AgentCapabilities(tools = Some(Set("typo"))))
    val error = run(
      new AgentRunner[Any](Map.empty, Map.empty)
        .run(program.bind[Any](invalid), AgentTask("hello"), available)
        .either
    ).swap.toOption.get
    assert(error.getMessage.contains("typo"))
  }

  test("configured nonempty tool allowlist preserves allowed calls and rejects unadvertised allowed IDs") {
    val policy = AgentCapabilities(tools = Some(Set("clock")))
    val call = MessagePayload.FunctionCallRequest(FunctionCall(FunctionCallId.unsafe("allowed-call"), "clock", "{}"))
    val delegate = new Agent[Any] {
      val id = AgentCapabilitiesSuite.this.id
      def turn(r: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] = {
        assert(!r.tools.exists(_.name == "shell"))
        ZIO.succeed(AgentTurnResponse(Chunk(call), LlmStopReason.ToolUse))
      }
    }
    val adapter = ConfiguredAgent(delegate, policy)
    val allowed = run(adapter.turn(request.copy(toolChoice = ToolChoice.Auto)))
    assertEquals(allowed.output, Chunk(call))
    assert(run(adapter.turn(request.copy(tools = Chunk.empty)).either).isLeft)
    assert(run(adapter.turn(request.copy(toolChoice = ToolChoice.None)).either).isLeft)
  }

  test("startup catalog validation catches unknown IDs on unselected agents without constructing adapters") {
    import fmgp.yallmo.orchestration.config.{Config as AppConfig, ConfigFile}
    val invalid = AppConfig(
      "operator",
      agents = Map(id -> AgentConfig("OAIAgent", capabilities = AgentCapabilities(skills = Some(Set("typo")))))
    )
    val error = ConfigFile.validateCapabilities(invalid, available).swap.toOption.get
    assert(error.getMessage.contains("policy-test") && error.getMessage.contains("capabilities.skills"))
    assertEquals(
      ConfigFile.validateCapabilities(AppConfig("operator", agents = Map(id -> AgentConfig("OAIAgent"))), available),
      Right(())
    )
  }

}
