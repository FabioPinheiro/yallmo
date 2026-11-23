package fmgp.yallmo.orchestration

import fmgp.yallmo.skill.Skill
import fmgp.yallmo.tool.{FunctionCall, FunctionCallId, FunctionKey, FunctionToolDefinition, ToolKey}

import RunnerTestSupport.*

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.*
import fmgp.yallmo.orchestration.config.{AgentCapabilities, AgentConfig, ConfiguredAgentSettings}
import munit.FunSuite
import zio.*
import zio.json.*
final class AgentCapabilitiesSuite extends FunSuite {
  private val id = AgentId("policy-test")
  private val disabled =
    AgentCapabilities(tools = Some(Set.empty), skills = Some(Set.empty), functions = Some(Set.empty))
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
  private val available = StaticContext[Any](
    "system",
    functions = Chunk(
      FunctionToolDefinition(FunctionKey.unsafe("clock"), "time"),
      FunctionToolDefinition(FunctionKey.unsafe("shell"), "commands")
    ),
    skills = Chunk(Skill("brief", "be brief", "SECRET_SKILL_INSTRUCTIONS"))
  )
  private val request = AgentTurnRequest(
    "model",
    Chunk(MessagePayload.Instructions("system")),
    tools = Chunk(
      FunctionToolDefinition(
        FunctionKey.unsafe("clock"),
        "time",
        JsonSchema.ObjectSchema(Map.empty)
      ),
      FunctionToolDefinition(
        FunctionKey.unsafe("shell"),
        "commands",
        fmgp.yallmo.model.llm.JsonSchema.ObjectSchema(Map.empty)
      )
    ),
    parallelToolCalls = true
  )

  test("omitted, empty object, explicit empties and partial capability objects decode distinctly") {
    def decode(suffix: String) = s"""{"agentType":"OAIAgent"$suffix}""".fromJson[AgentConfig].toOption.get
    assertEquals(decode("").capabilities, AgentCapabilities())
    assertEquals(decode(",\"capabilities\":{}").capabilities, AgentCapabilities())
    val partial = decode(",\"capabilities\":{\"tools\":[]}")
    assertEquals(partial.capabilities.tools, Some(Set.empty[ToolKey]))
    val context = partial.capabilities.resolve(id, available).toOption.get
    assert(context.tools.isEmpty)
    assertEquals(context.skills, available.skills)
    assertEquals(partial.toJson.fromJson[AgentConfig], Right(partial))
    assertEquals(AgentCapabilities().resolve(id, available), Right(available))
    val empty = disabled.resolve(id, available).toOption.get
    assert(empty.tools.isEmpty && empty.functions.isEmpty && empty.skills.isEmpty)
  }

  for field <- List("retrievers", "extensions") do
    test(s"removed $field config is rejected instead of silently ignored") {
      val json = s"""{"agentType":"OAIAgent","capabilities":{"$field":[]}}"""
      assert(json.fromJson[AgentConfig].isLeft)
      assert(!AgentCapabilities().toJson.contains(field))
    }

  test("tool allowlists retain string JSON, support custom tools, and reject blank keys") {
    val json = """{"functions":["clock"]}"""
    val policy = AgentCapabilities(functions = Some(Set(FunctionKey.unsafe("clock"))))
    assertEquals(json.fromJson[AgentCapabilities], Right(policy))
    assertEquals(policy.toJson, json)
    assert("""{"tools":[" "]}""".fromJson[AgentCapabilities].isLeft)
    assert("""{"tools":[""]}""".fromJson[AgentCapabilities].isLeft)
  }

  test("nonempty allowlists restrict each catalog and preserve ordering") {
    val context =
      AgentCapabilities(functions = Some(Set(FunctionKey.unsafe("shell")))).resolve(id, available).toOption.get
    assertEquals(context.functions.map(_.name), Chunk(FunctionKey.unsafe("shell")))
    assertEquals(context.skills, available.skills)
  }

  test("validateIds checks the catalog without applying restrictions; resolve still restricts") {
    val policy = AgentCapabilities(functions = Some(Set(FunctionKey.unsafe("shell"))), skills = Some(Set.empty))
    assertEquals(policy.validateIds(id, available), Right(()))
    val resolved = policy.resolve(id, available).toOption.get
    assertEquals(resolved, policy.restrict(available))
    assertEquals(resolved.functions.map(_.name), Chunk(FunctionKey.unsafe("shell")))
    assert(resolved.skills.isEmpty)
  }

  test("validateIds and resolve reject duplicate function keys even when all capabilities are disabled") {
    val duplicates = available.copy(functions = available.functions ++ available.functions)
    val error = disabled.validateIds(id, duplicates).swap.toOption.get
    assertEquals(error.getMessage, "Duplicate function keys in available catalog: clock, shell")
    assertEquals(disabled.resolve(id, duplicates), Left(error))
  }

  for (kind, policy) <- List(
      "tools" -> AgentCapabilities(tools = Some(Set(ToolKey.unsafe("typo")))),
      "functions" -> AgentCapabilities(functions = Some(Set(FunctionKey.unsafe("typo")))),
      "skills" -> AgentCapabilities(skills = Some(Set("typo")))
    )
  do
    test(s"unknown $kind IDs fail with agent and field, not context contents") {
      val validationError = policy.validateIds(id, available).swap.toOption.get
      assertEquals(policy.resolve(id, available), Left(validationError))
      val error = validationError.getMessage
      assert(error.contains("policy-test") && error.contains(s"capabilities.$kind") && error.contains("typo"))
      assert(!error.contains("SECRET_SKILL_INSTRUCTIONS"))
    }

  test("assembled request filtering removes forbidden definitions; empty disables parallel calls") {
    val filtered = AgentCapabilities(functions = Some(Set(FunctionKey.unsafe("clock")))).filterRequest(request)
    assertEquals(filtered.tools.map(_.name), Chunk(FunctionKey.unsafe("clock")))
    assert(filtered.parallelToolCalls)
    val empty = disabled.filterRequest(request)
    assert(empty.tools.isEmpty)
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
                  Chunk(
                    MessagePayload
                      .ToolCall(FunctionCall(FunctionCallId.unsafe("call"), FunctionKey.unsafe("shell"), "{}"))
                  ),
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
    assert(!result._3.parallelToolCalls)
  }

  test("bound native program inherits runtime restrictions before advertising skills") {
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
        runner = new AgentRunner[Any](Map.empty)
        _ <- runner.run(program, AgentTask("hello"), available)
        captured <- seen.get
      } yield captured.get
    }
    assert(result.tools.isEmpty)
    assertEquals(result.input.last, MessagePayload.Instructions("system"))
  }

  test("unbound native program resolves runtime policy before advertising skills") {
    val delegate = new Agent[Any] {
      val id = AgentCapabilitiesSuite.this.id
      def turn(r: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] = {
        assertEquals(r.input.last, MessagePayload.Instructions("system"))
        ZIO.succeed(AgentTurnResponse(Chunk(MessagePayload.Assistant("done")), LlmStopReason.Stop))
      }
    }
    val context = StaticContext[Agent[Any]](
      "system",
      skills = available.skills
    )
    run(
      new AgentRunner[Agent[Any]](Map.empty)
        .run(NativeFunctionAgent[Agent[Any]](id, "model"), AgentTask("hello"), context)
        .provideEnvironment(ZEnvironment[Agent[Any]](ConfiguredAgent(delegate, disabled)))
    )
  }

  test("runner rejects disallowed tool actions even from a malicious program") {
    val result = run {
      for {
        executed <- Ref.make(false)
        malicious = new AgentInstance[Any] {
          val id = AgentCapabilitiesSuite.this.id
          override val capabilities = disabled
          def next(t: AgentTask, c: StaticContext[Any], r: RunContext): IO[YallmoError, AgentTurn] = {
            val _ = (t, c)
            if r.messages.exists(_.isInstanceOf[MessagePayload.ToolOutput]) then
              ZIO.succeed(AgentTurn(None, AgentDecision.FinalAnswer("done")))
            else
              ZIO.succeed(
                AgentTurn(
                  None,
                  AgentDecision.Actions(
                    Chunk(
                      LlmAction.CallExternalFunction(ExternalFunctionCall(FunctionKey.unsafe("shell"), "{}"))
                    )
                  )
                )
              )
          }
        }
        tool = new AgentFunction[Any] {
          val definition =
            FunctionToolDefinition(FunctionKey.unsafe("shell"), "commands")
          def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] = {
            val _ = call
            executed.set(true).as(ExternalFunctionResult("unsafe"))
          }
        }
        outcome <- new AgentRunner[Any](Map(FunctionKey.unsafe("shell") -> tool))
          .run(malicious, AgentTask("hello"), available)
          .either
        didExecute <- executed.get
      } yield (outcome, didExecute)
    }
    assert(!result._2)
    assert(result._1.isRight)
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

  test("settings inherit a supplied provider model only when the agent model is absent") {
    def definition(json: String) = AgentConfig("CoachAgent", config = json.fromJson[zio.json.ast.Json.Obj].toOption.get)
    val inherited = ConfiguredAgentSettings
      .decode(definition("{}"), Some("Coach prompt"), Some(" provider-model "))
      .toOption
      .get
    assertEquals(inherited.model, "provider-model")
    val explicit = ConfiguredAgentSettings
      .decode(definition("""{"model":" agent-model "}"""), Some("Coach prompt"), Some("provider-model"))
      .toOption
      .get
    assertEquals(explicit.model, "agent-model")
    assert(ConfiguredAgentSettings.decode(definition("{}"), Some("Coach prompt")).isLeft)
    assert(ConfiguredAgentSettings.decode(definition("{}"), Some("Coach prompt"), Some(" ")).isLeft)
    for json <- List("""{"model":" "}""", """{"model":null}""", """{"model":42}""") do
      assert(ConfiguredAgentSettings.decode(definition(json), Some("Coach prompt"), Some("provider-model")).isLeft)
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
    val adapter = ConfiguredAgent(delegate, AgentCapabilities(functions = Some(Set(FunctionKey.unsafe("clock")))))
    run(new AgentRunner[Any](Map.empty).run(program.bind[Any](adapter), AgentTask("hello"), available))
    val invalid = ConfiguredAgent(delegate, AgentCapabilities(functions = Some(Set(FunctionKey.unsafe("typo")))))
    val error = run(
      new AgentRunner[Any](Map.empty)
        .run(program.bind[Any](invalid), AgentTask("hello"), available)
        .either
    ).swap.toOption.get
    assert(error.getMessage.contains("typo"))
  }

  test("configured nonempty tool allowlist preserves allowed calls and rejects unadvertised allowed IDs") {
    val policy = AgentCapabilities(functions = Some(Set(FunctionKey.unsafe("clock"))))
    val call =
      MessagePayload.ToolCall(FunctionCall(FunctionCallId.unsafe("allowed-call"), FunctionKey.unsafe("clock"), "{}"))
    val delegate = new Agent[Any] {
      val id = AgentCapabilitiesSuite.this.id
      def turn(r: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] = {
        assert(!r.tools.exists(_.name == FunctionKey.unsafe("shell")))
        ZIO.succeed(AgentTurnResponse(Chunk(call), LlmStopReason.ToolUse))
      }
    }
    val adapter = ConfiguredAgent(delegate, policy)
    val allowed = run(adapter.turn(request))
    assertEquals(allowed.output, Chunk(call))
    assert(run(adapter.turn(request.copy(tools = Chunk.empty)).either).isLeft)
  }

  test("configured Coach direct requests require group metadata without rerunning private wiring") {
    val root = java.nio.file.Files.createTempDirectory("configured-coach-wiring-")
    try {
      run {
        for {
          seen <- Ref.make(Vector.empty[Chunk[FunctionToolDefinition]])
          delegate = new Agent[Any] {
            val id = AgentCapabilitiesSuite.this.id
            def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
              seen
                .update(_ :+ request.tools)
                .as(AgentTurnResponse(Chunk(MessagePayload.Assistant("done")), LlmStopReason.Stop))
          }
          fs <- fmgp.yallmo.fs.FileSystem.open(root.toString)
          coach <- fmgp.yallmo.agents.CoachAgent.make(delegate, fs)
          configured = ConfiguredAgent(coach, fmgp.yallmo.agents.CoachAgent.Capabilities)
          wired <- ToolWiringResolver.resolve(
            id,
            StaticContext[Any]("system"),
            Chunk.empty,
            configured.toolWirings,
            fmgp.yallmo.agents.CoachAgent.Capabilities,
            Chunk.empty
          )
          prepared = request.copy(tools = wired.context.functions, toolGroups = wired.context.tools)
          _ <- configured.turn(prepared)
          _ <- configured.turn(prepared.copy(toolGroups = Chunk.empty))
          requests <- seen.get
        } yield {
          assertEquals(requests, Vector(wired.context.functions, Chunk.empty))
          assertEquals(wired.context.tools.map(_.name), Chunk(ToolKey.Coach))
        }
      }
    } finally java.nio.file.Files.delete(root)
  }

  test("startup catalog validation catches unknown IDs on unselected agents without constructing adapters") {
    import fmgp.yallmo.orchestration.config.{Config as AppConfig, ConfigFile}
    val invalid = AppConfig(
      agents = Map(id -> AgentConfig("OAIAgent", capabilities = AgentCapabilities(skills = Some(Set("typo")))))
    )
    val error = ConfigFile.validateCapabilities(invalid, available).swap.toOption.get
    assert(error.getMessage.contains("policy-test") && error.getMessage.contains("capabilities.skills"))
    assertEquals(
      ConfigFile.validateCapabilities(AppConfig(agents = Map(id -> AgentConfig("OAIAgent"))), available),
      Right(())
    )
  }

}
