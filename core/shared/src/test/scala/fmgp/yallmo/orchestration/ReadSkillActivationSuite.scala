package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.*
import fmgp.yallmo.orchestration.config.AgentCapabilities
import fmgp.yallmo.skill.{ReadSkillTool, Skill}
import fmgp.yallmo.tool.*
import munit.ZSuite
import zio.*
import RunnerTestSupport.*

final class ReadSkillActivationSuite extends ZSuite {
  private val id = AgentId("reader-agent")
  private val a =
    Skill("a", "A metadata", "MAIN A", sections = Map("refs/a.md" -> "SECTION A", "b.md" -> "OTHER SECTION"))
  private val b = Skill("b", "B metadata", "MAIN B", sections = Map("refs/a.md" -> "SECTION B"))
  private val hidden = Skill("hidden", "HIDDEN META", "HIDDEN MAIN", disableModelInvocation = true)
  private val reader = ReadSkillTool(Seq(a, b, hidden))
  private val context =
    StaticContext[Any]("system", Chunk(reader.definition), Chunk(a, b, hidden), Chunk(SkillsToolDefinition))
  private val policyA = AgentCapabilities(skills = Some(Set("a", "hidden")))
  private def call(name: String, section: Boolean = false) = FunctionCall(
    FunctionCallId.unsafe(if section then "section-call" else "main-call"),
    SkillsToolDefinition.ReadSkill,
    if section then s"""{"name":"$name","section":"refs/a.md"}""" else s"""{"name":"$name"}"""
  )
  private val codec = new AgentDecisionCodec {
    val instructions = "decide"
    def decode(response: String, context: StaticContext[?]): IO[YallmoError, AgentDecision] =
      ZIO.succeed(AgentDecision.FinalAnswer(response))
  }

  testZ("recording provider loop loads main then explicit section through matching ordinary tool results") {
    for {
      requests <- Ref.make(Vector.empty[AgentTurnRequest])
      provider = new Agent[Any] {
        val id = ReadSkillActivationSuite.this.id
        def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
          requests.updateAndGet(_ :+ request).map { history =>
            history.size match {
              case 1 => AgentTurnResponse(Chunk(MessagePayload.ToolCall(call("a"))), LlmStopReason.ToolUse)
              case 2 => AgentTurnResponse(Chunk(MessagePayload.ToolCall(call("a", true))), LlmStopReason.ToolUse)
              case _ => AgentTurnResponse(Chunk(MessagePayload.Assistant("done")), LlmStopReason.Stop)
            }
          }
      }
      program = NativeFunctionAgent[Agent[Any]](id, "model", capabilities = policyA).bind[Any](provider)
      result <- new AgentRunner[Any](Map(reader.definition.name -> reader))
        .runWithContext(program, AgentTask("use a"), context, RunContext())
      seen <- requests.get
    } yield {
      assertEquals(seen.size, 3)
      assertEquals(seen.head.tools, Chunk(reader.definition))
      assertEquals(seen.head.input.last, MessagePayload.Instructions("system"))
      val first = seen.head.modelInput.map(_.contentStr).mkString
      assert(first.contains("<name>a</name>"))
      assert(first.contains("call readSkill"))
      assert(
        !first.contains("MAIN") && !first.contains("SECTION") && !first.contains("HIDDEN") && !first.contains(
          "B metadata"
        )
      )
      val second = seen(1).modelInput.map(_.contentStr).mkString
      assert(second.contains("MAIN A"))
      assert(!second.contains("SECTION A") && !second.contains("OTHER SECTION"))
      val outputs = result.runContext.messages.collect { case MessagePayload.ToolOutput(value) => value }
      assertEquals(outputs.map(_.callId.value), Chunk("main-call", "section-call"))
      assertEquals(outputs.map(_.name), Chunk(reader.definition.name, reader.definition.name))
      assertEquals(outputs(1).output, """{"name":"a","section":"refs/a.md","content":"SECTION A"}""")
      assert(!outputs(1).output.contains("MAIN A") && !outputs(1).output.contains("OTHER SECTION"))
      assert(seen(2).modelInput.map(_.contentStr).mkString.contains("SECTION A"))
    }
  }

  testZ("resolver bindings and direct invocation cannot guess other agent names or sections") {
    for {
      wiredA <- ToolWiringResolver.resolve(
        id,
        context,
        Chunk(reader),
        tools => ZIO.succeed(tools),
        policyA,
        Chunk.empty
      )
      wiredB <- ToolWiringResolver.resolve(
        AgentId("reader-agent-b"),
        context,
        Chunk(reader),
        tools => ZIO.succeed(tools),
        AgentCapabilities(skills = Some(Set("b"))),
        Chunk.empty
      )
      permitted <- wiredA.functions.head.execute(ExternalFunctionCall(reader.definition.name, call("a").argumentsJson))
      denied <- wiredA.functions.head
        .execute(ExternalFunctionCall(reader.definition.name, call("b", true).argumentsJson))
        .either
      independent <- wiredB.functions.head
        .execute(ExternalFunctionCall(reader.definition.name, call("a").argumentsJson))
        .either
      adapter = new Agent[Any] {
        val id = ReadSkillActivationSuite.this.id
        def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] = ZIO.dieMessage("not needed")
      }
      program = NativeFunctionAgent[Agent[Any]](id, "model", capabilities = policyA).bind[Any](adapter)
      deniedInvoke <- new AgentRunner[Any](Map(reader.definition.name -> reader))
        .invocation(program, context)
        .invoke(MessagePayload.ToolCall(call("b", true)))
      invalidInvoke <- new AgentRunner[Any](Map(reader.definition.name -> reader))
        .invocation(program, context)
        .invoke(MessagePayload.ToolCall(call("a").copy(argumentsJson = """{"name":"a","section":null}""")))
    } yield {
      assert(permitted.text.contains("MAIN A"))
      assertEquals(denied, Left(YallmoError.UnknownSkill("b")))
      assertEquals(independent, Left(YallmoError.UnknownSkill("a")))
      assert(deniedInvoke.contentStr.contains("UnknownSkill"))
      assert(!deniedInvoke.contentStr.contains("SECTION B"))
      assert(invalidInvoke.contentStr.contains("InvalidToolArguments"))
    }
  }

  for (label, policy, skills, removeReader, advertise) <- List(
      ("allowed", AgentCapabilities(), Chunk(a), false, true),
      ("tool denied", AgentCapabilities(tools = Some(Set.empty)), Chunk(a), false, false),
      ("function denied", AgentCapabilities(functions = Some(Set.empty)), Chunk(a), false, false),
      ("skills empty", AgentCapabilities(skills = Some(Set.empty)), Chunk(a), false, false),
      ("empty", AgentCapabilities(), Chunk.empty[Skill], false, false),
      ("hidden", AgentCapabilities(), Chunk(hidden), false, false),
      ("wiring removed", AgentCapabilities(), Chunk(a), true, false)
    )
  do
    testZ(s"$label catalog co-availability in native and decision prompts") {
      for {
        wired <- ToolWiringResolver.resolve(
          id,
          context.copy(skills = skills),
          Chunk(reader),
          tools => ZIO.succeed(if removeReader then Seq.empty else tools),
          policy,
          Chunk.empty
        )
        request = AgentTurnRequest(
          "model",
          Chunk(MessagePayload.Instructions("system")),
          tools = wired.context.functions,
          toolGroups = wired.context.tools,
          skills = wired.context.skills
        )
        native = policy.filterRequest(request).modelInput.map(_.contentStr).mkString
        decision = LlmDecisionAgent.systemInstruction(wired.context, codec)
      } yield {
        assertEquals(native.contains("<available_skills>"), advertise)
        assertEquals(decision.contains("<available_skills>"), advertise)
        assertEquals(native.contains("call readSkill"), advertise)
        assertEquals(decision.contains("call readSkill"), advertise)
        if advertise then assert(wired.functions.exists(_.definition == SkillsToolDefinition.ReadSkillDefinition))
      }
    }

  for (label, programPolicy, adapterPolicy, declaredPolicy, owner) <- List(
      (
        "program functions denied",
        AgentCapabilities(functions = Some(Set.empty)),
        AgentCapabilities(),
        AgentCapabilities(),
        SkillsToolDefinition.name
      ),
      (
        "program tools denied",
        AgentCapabilities(tools = Some(Set.empty)),
        AgentCapabilities(),
        AgentCapabilities(),
        SkillsToolDefinition.name
      ),
      (
        "adapter functions denied",
        AgentCapabilities(),
        AgentCapabilities(functions = Some(Set.empty)),
        AgentCapabilities(),
        SkillsToolDefinition.name
      ),
      (
        "adapter tools denied",
        AgentCapabilities(),
        AgentCapabilities(tools = Some(Set.empty)),
        AgentCapabilities(),
        SkillsToolDefinition.name
      ),
      (
        "declared functions denied",
        AgentCapabilities(),
        AgentCapabilities(),
        AgentCapabilities(functions = Some(Set.empty)),
        SkillsToolDefinition.name
      ),
      (
        "declared tools denied",
        AgentCapabilities(),
        AgentCapabilities(),
        AgentCapabilities(tools = Some(Set.empty)),
        SkillsToolDefinition.name
      ),
      (
        "final ownership denied",
        AgentCapabilities(tools = Some(Set(SkillsToolDefinition.name))),
        AgentCapabilities(),
        AgentCapabilities(),
        ToolKey.unsafe("replacement-owner")
      )
    )
  do
    testZ(s"factory reinstallation cannot bypass $label") {
      val adapter = ConfiguredAgent(
        new Agent[Any] {
          val id = ReadSkillActivationSuite.this.id
          def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] = ZIO.dieMessage("not needed")
        },
        adapterPolicy
      )
      val program = NativeFunctionAgent[Agent[Any]](id, "model", capabilities = programPolicy).bind[Any](adapter)
      for {
        wired <- ToolWiringResolver.resolve(
          id,
          context,
          Chunk(reader),
          _ => {
            val replacement = ReadSkillTool(Seq(a))
            ZIO.succeed(Seq(AgentTool(ToolDefinition(owner, Set(replacement.definition)), Chunk(replacement))))
          },
          program.capabilities,
          Chunk(programPolicy, adapterPolicy, declaredPolicy)
        )
        resolved = ResolvedAgent(program, wired.context, wired.functions, adapter, None, None, None)
        main <- AgentRunner.invocation(resolved).invoke(MessagePayload.ToolCall(call("a")))
        section <- AgentRunner.invocation(resolved).invoke(MessagePayload.ToolCall(call("a", true)))
        request = AgentTurnRequest(
          "model",
          Chunk(MessagePayload.Instructions("system")),
          tools = wired.context.functions,
          toolGroups = wired.context.tools,
          skills = wired.context.skills
        )
      } yield {
        assert(!wired.context.functions.exists(_.name == SkillsToolDefinition.ReadSkill))
        assert(!wired.functions.exists(_.definition.name == SkillsToolDefinition.ReadSkill))
        assert(!wired.context.tools.exists(_.functions.exists(_.name == SkillsToolDefinition.ReadSkill)))
        assertEquals(wired.context.skills, context.skills)
        for output <- Seq(main, section) do {
          assert(output.contentStr.contains("ToolUnavailableToAgent"))
          assert(!output.contentStr.contains("MAIN A") && !output.contentStr.contains("SECTION A"))
        }
        for prompt <- Seq(
            request.modelInput.map(_.contentStr).mkString,
            LlmDecisionAgent.systemInstruction(wired.context, codec)
          )
        do {
          assert(!prompt.contains("<available_skills>") && !prompt.contains("call readSkill"))
        }
      }
    }

  testZ("allowed factory-installed reader uses final ownership and retains skill restrictions") {
    val policy = AgentCapabilities(
      tools = Some(Set(SkillsToolDefinition.name)),
      functions = Some(Set(SkillsToolDefinition.ReadSkill)),
      skills = Some(Set("a"))
    )
    for {
      wired <- ToolWiringResolver.resolve(
        id,
        context.copy(functions = Chunk.empty, tools = Chunk.empty),
        Chunk.empty,
        _ => ZIO.succeed(Seq(AgentTool(SkillsToolDefinition, Chunk(ReadSkillTool(context.skills))))),
        policy,
        Chunk.empty
      )
      installed = wired.functions.find(_.definition.name == SkillsToolDefinition.ReadSkill).get
      main <- installed.execute(ExternalFunctionCall(reader.definition.name, call("a").argumentsJson))
      section <- installed.execute(ExternalFunctionCall(reader.definition.name, call("a", true).argumentsJson))
      denied <- installed.execute(ExternalFunctionCall(reader.definition.name, call("b", true).argumentsJson)).either
    } yield {
      assertEquals(wired.context.functions, Chunk(reader.definition))
      assertEquals(wired.context.skills, Chunk(a))
      assert(main.text.contains("MAIN A") && section.text.contains("SECTION A"))
      assertEquals(denied, Left(YallmoError.UnknownSkill("b")))
      assert(LlmDecisionAgent.systemInstruction(wired.context, codec).contains("<available_skills>"))
    }
  }

  testZ("unknown factory reader replacements remain unavailable even when policies allow reading") {
    val replacement = new AgentFunction[Any] {
      val definition = reader.definition
      def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
        ZIO.dieMessage("unknown replacement must never execute")
    }
    ToolWiringResolver
      .resolve(
        id,
        context,
        Chunk(reader),
        _ => ZIO.succeed(Seq(AgentTool(SkillsToolDefinition, Chunk(replacement)))),
        AgentCapabilities(),
        Chunk.empty
      )
      .map { wired =>
        assert(!wired.context.functions.exists(_.name == SkillsToolDefinition.ReadSkill))
        assert(!wired.functions.exists(_.definition.name == SkillsToolDefinition.ReadSkill))
        assert(!LlmDecisionAgent.systemInstruction(wired.context, codec).contains("<available_skills>"))
      }
  }

  testZ("definition without executable reader never advertises, while skill IDs remain available for validation") {
    for {
      seen <- Ref.make(Option.empty[AgentTurnRequest])
      adapter = new Agent[Any] {
        val id = ReadSkillActivationSuite.this.id
        def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
          seen.set(Some(request)).as(AgentTurnResponse(Chunk(MessagePayload.Assistant("done")), LlmStopReason.Stop))
      }
      _ <- new AgentRunner[Any](Map.empty)
        .run(NativeFunctionAgent[Agent[Any]](id, "model").bind[Any](adapter), AgentTask("hello"), context)
      request <- seen.get
    } yield {
      assert(!request.get.modelInput.map(_.contentStr).mkString.contains("<available_skills>"))
      assert(request.get.tools.isEmpty)
      assertEquals(request.get.skills.map(_.name), Seq("a", "b", "hidden"))
    }
  }

  testZ("tool wiring cannot observe the broad application reader before final selected-agent binding") {
    for {
      checked <- Ref.make(false)
      _ <- ToolWiringResolver.resolve(
        id,
        context,
        Chunk(reader),
        tools =>
          for {
            result <- tools
              .flatMap(_.functions)
              .find(_.definition.name == reader.definition.name)
              .get
              .execute(ExternalFunctionCall(reader.definition.name, call("b", true).argumentsJson))
              .either
            _ <- checked.set(result == Left(YallmoError.UnknownSkill("b")))
          } yield tools,
        policyA,
        Chunk.empty
      )
      denied <- checked.get
    } yield assert(denied)
  }

  testZ("a narrower supplied reader cannot advertise skills outside its captured catalog") {
    val limited = ReadSkillTool(Seq(a))
    val executable = ReadSkillTool.executableContext(context, Seq(limited))
    ZIO.succeed {
      assertEquals(executable.skills, Chunk(a))
      assert(!LlmDecisionAgent.systemInstruction(executable, codec).contains("B metadata"))
    }
  }

  testZ("adapter intersection narrows skill permissions for runner reads and final request projection") {
    for {
      seen <- Ref.make(Option.empty[AgentTurnRequest])
      delegate = new Agent[Any] {
        val id = ReadSkillActivationSuite.this.id
        def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
          seen.set(Some(request)).as(AgentTurnResponse(Chunk(MessagePayload.Assistant("done")), LlmStopReason.Stop))
      }
      adapter = ConfiguredAgent(delegate, AgentCapabilities(skills = Some(Set("b")), functions = Some(Set.empty)))
      program = NativeFunctionAgent[Agent[Any]](id, "model", capabilities = policyA).bind[Any](adapter)
      _ <- new AgentRunner[Any](Map(reader.definition.name -> reader)).run(program, AgentTask("hi"), context)
      request <- seen.get
    } yield {
      assert(request.get.skills.isEmpty)
      assert(request.get.tools.isEmpty)
      assert(!request.get.modelInput.map(_.contentStr).mkString.contains("<available_skills>"))
    }
  }
}
