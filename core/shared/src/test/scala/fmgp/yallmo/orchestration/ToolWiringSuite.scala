package fmgp.yallmo.orchestration

import fmgp.yallmo.skill.Skill
import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.*
import fmgp.yallmo.orchestration.config.AgentCapabilities
import fmgp.yallmo.tool.*
import munit.FunSuite
import zio.*

import scala.concurrent.Future
import RunnerTestSupport.*

final class ToolWiringSuite extends FunSuite {
  private val id = AgentId("wiring-test")
  private def function(name: String, description: String = "fake"): AgentFunction[Any] = new AgentFunction[Any] {
    val definition = FunctionToolDefinition(FunctionKey.unsafe(name), description, JsonSchema.ObjectSchema(Map.empty))
    def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
      ZIO.succeed(ExternalFunctionResult(s"$description:${call.functionKey.value}"))
  }
  private def tool(name: String, functions: AgentFunction[Any]*): AgentTool[Any] =
    AgentTool(ToolDefinition(ToolKey.unsafe(name), functions.map(_.definition).toSet), Chunk.fromIterable(functions))

  private def resolve(
      base: Chunk[AgentTool[Any]],
      wiring: ToolWiring = tools => ZIO.succeed(tools),
      policy: AgentCapabilities = AgentCapabilities(),
      declared: Chunk[AgentCapabilities] = Chunk.empty
  ): IO[YallmoError, ToolWiringResolver.Result] =
    ToolWiringResolver.resolve(
      id,
      StaticContext[Any]("system", tools = base.map(_.definition)),
      base.flatMap(_.functions),
      wiring,
      policy,
      declared
    )

  test("identity wiring preserves definitions and implementations; composed factories run in explicit order") {
    run {
      val original = tool("base", function("original"))
      val added = tool("added", function("added"))
      for {
        unchanged <- resolve(Chunk(original))
        seen <- Ref.make(Vector.empty[String])
        append: ToolWiring = tools => seen.update(_ :+ "append").as(tools :+ added)
        remove: ToolWiring = tools => seen.update(_ :+ tools.map(_.name.value).mkString(",")).as(tools.drop(1))
        changed <- resolve(
          Chunk(original),
          tools =>
            for {
              appended <- append(tools)
              removed <- remove(appended)
            } yield removed
        )
        order <- seen.get
      } yield {
        assertEquals(unchanged.context.tools, Chunk(original.definition))
        assert(unchanged.functions.head eq original.functions.head)
        assertEquals(order, Vector("append", "base,added"))
        assertEquals(changed.context.tools, Chunk(added.definition))
        assertEquals(changed.functions.map(_.definition), changed.context.functions)
      }
    }
  }

  test("wiring failures are propagated without producing a resolved catalog") {
    run {
      val error = YallmoError.InvalidConfig("wiring failed")
      resolve(Chunk.empty, _ => ZIO.fail(error)).either.map(result => assertEquals(result, Left(error)))
    }
  }

  test("replacement aligns advertisement, direct invocation and managed loops without rerunning wiring") {
    run {
      val original = tool("capability", function("probe", "old"))
      val replacement = tool("capability", function("probe", "replacement"))
      for {
        stages <- Ref.make(0)
        wired <- resolve(Chunk(original), _ => stages.update(_ + 1).as(Seq(replacement)))
        seen <- Ref.make(Option.empty[AgentTurnRequest])
        adapter = new Agent[Any] {
          val id = ToolWiringSuite.this.id
          def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] = {
            val output = request.input.collectFirst { case MessagePayload.ToolOutput(value) => value.output }
            val response =
              if request.input.contains(MessagePayload.User("loop")) && output.isEmpty then
                AgentTurnResponse(
                  Chunk(
                    MessagePayload.ToolCall(
                      FunctionCall(FunctionCallId.unsafe("loop-call"), FunctionKey.unsafe("probe"), "{}")
                    )
                  ),
                  LlmStopReason.ToolUse
                )
              else AgentTurnResponse(Chunk(MessagePayload.Assistant(output.getOrElse("done"))), LlmStopReason.Stop)
            seen.set(Some(request)).as(response)
          }
        }
        program = NativeFunctionAgent[Agent[Any]](id, "fake-model").bind[Any](adapter)
        resolved = ResolvedAgent(program, wired.context, wired.functions, adapter, None, None, None)
        invocation = AgentRunner.invocation(resolved)
        reply <- invocation.invoke(MessagePayload.User("hello"))
        output <- invocation.invoke(
          MessagePayload.ToolCall(FunctionCall(FunctionCallId.unsafe("probe-call"), FunctionKey.unsafe("probe"), "{}"))
        )
        loop <- new AgentRunner[Any](wired.functions.map(f => f.definition.name -> f).toMap)
          .run(program, AgentTask("loop"), wired.context)
        advertised <- seen.get
        stageCount <- stages.get
      } yield {
        assertEquals(stageCount, 1)
        assertEquals(loop, AgentResult(id, "replacement:probe"))
        assertEquals(reply, MessagePayload.Assistant("done"))
        assertEquals(advertised.get.tools, Chunk(replacement.functions.head.definition))
        assertEquals(advertised.get.toolGroups, Chunk(replacement.definition))
        assertEquals(output.contentStr, "replacement:probe")
        assertEquals(resolved.tools.map(_.definition), advertised.get.toolGroups)
      }
    }
  }

  test("filtering happens only before wiring; composed factories and resolution retain added tools") {
    run {
      val allowed = function("allowed")
      val denied = function("denied")
      val deniedAddition = tool("blocked", function("introduced"))
      val policy = AgentCapabilities(
        tools = Some(Set(ToolKey.unsafe("base"))),
        functions = Some(Set(allowed.definition.name))
      )
      for {
        seen <- Ref.make(Vector.empty[Seq[FunctionKey]])
        first: ToolWiring = tools =>
          seen.update(_ :+ tools.flatMap(_.functions.map(_.definition.name))).as(tools :+ deniedAddition)
        second: ToolWiring = tools => seen.update(_ :+ tools.flatMap(_.functions.map(_.definition.name))).as(tools)
        result <- resolve(
          Chunk(tool("base", allowed, denied)),
          tools =>
            for {
              added <- first(tools)
              result <- second(added)
            } yield result,
          policy
        )
        inputs <- seen.get
      } yield {
        assertEquals(
          inputs,
          Vector(
            Seq(allowed.definition.name),
            Seq(allowed.definition.name, deniedAddition.functions.head.definition.name)
          )
        )
        assertEquals(result.context.tools.map(_.name), Chunk(ToolKey.unsafe("base"), deniedAddition.name))
        assertEquals(result.context.functions, Chunk(allowed.definition) ++ deniedAddition.functions.map(_.definition))
        assertEquals(result.functions.map(_.definition), result.context.functions)
      }
    }
  }

  test("post-wiring ID validation accepts local IDs without restricting additions") {
    run {
      val local = tool("local", function("local_operation"))
      val denied = tool("denied", function("denied_operation"))
      val explicit = AgentCapabilities(
        tools = Some(Set(local.name, denied.name)),
        functions = Some(Set(local.functions.head.definition.name, denied.functions.head.definition.name))
      )
      val effective = AgentCapabilities(tools = Some(Set(local.name)), functions = explicit.functions)
      val wiring: ToolWiring = tools => ZIO.succeed(tools :+ denied :+ local)
      resolve(Chunk.empty, wiring, effective, Chunk(explicit)).map { result =>
        assertEquals(result.context.tools, Chunk(denied.definition, local.definition))
        assertEquals(result.context.functions, (denied.functions ++ local.functions).map(_.definition))
      }
    }
  }

  test("skill restrictions are retained without filtering wired tools again") {
    run {
      val skill = Skill("brief", "be brief", "instructions")
      val added = tool("local", function("local_operation"))
      ToolWiringResolver
        .resolve(
          id,
          StaticContext[Any]("system", skills = Chunk(skill)),
          Chunk.empty,
          _ => ZIO.succeed(Seq(added)),
          AgentCapabilities(tools = Some(Set.empty), skills = Some(Set.empty)),
          Chunk(AgentCapabilities(skills = Some(Set(skill.name))))
        )
        .map { result =>
          assertEquals(result.context.skills, Chunk.empty)
          assertEquals(result.context.tools, Chunk(added.definition))
          assertEquals(result.functions, added.functions)
        }
    }
  }

  test("valid base IDs removed by policy are not mistaken for typos") {
    run {
      val base = tool("base", function("base_function"))
      resolve(
        Chunk(base),
        policy = AgentCapabilities(tools = Some(Set.empty)),
        declared = Chunk(AgentCapabilities(functions = Some(Set(base.functions.head.definition.name))))
      ).map(result => assert(result.context.functions.isEmpty))
    }
  }

  for (field, policy) <- List(
      "tools" -> AgentCapabilities(tools = Some(Set(ToolKey.unsafe("typo")))),
      "functions" -> AgentCapabilities(functions = Some(Set(FunctionKey.unsafe("typo"))))
    )
  do
    test(s"unknown $field IDs fail validation after wiring, even when an intersected policy hides them") {
      run {
        for {
          ran <- Ref.make(false)
          outcome <- resolve(
            Chunk.empty,
            tools => ran.set(true).as(tools),
            AgentCapabilities(tools = Some(Set.empty)),
            Chunk(policy)
          ).either
          completed <- ran.get
        } yield {
          assert(completed)
          assert(outcome.swap.toOption.get.getMessage.contains(s"capabilities.$field contains unknown IDs: typo"))
        }
      }
    }

  test("duplicate base functions and duplicate base tool declarations fail before any stage") {
    run {
      val base = tool("base", function("probe"))
      for {
        ran <- Ref.make(false)
        stage: ToolWiring = tools => ran.set(true).as(tools)
        duplicateTools <- resolve(Chunk(base, base), stage).either
        duplicateFunctions <- resolve(
          Chunk(base.copy(functions = base.functions ++ base.functions)),
          stage
        ).either
        didRun <- ran.get
      } yield {
        assert(duplicateTools.swap.toOption.get.getMessage.contains("Duplicate tool"))
        assert(duplicateFunctions.swap.toOption.get.getMessage.contains("Duplicate function"))
        assert(!didRun)
      }
    }
  }

  test("unavailable base declarations cannot be enabled and unowned implementations fail before wiring") {
    run {
      val unavailable = tool("unavailable", function("unavailable_function"))
      for {
        unavailableResult <- ToolWiringResolver
          .resolve(
            id,
            StaticContext[Any]("system", tools = Chunk(unavailable.definition)),
            Chunk.empty,
            tools => ZIO.succeed(tools),
            AgentCapabilities(tools = Some(Set(unavailable.name))),
            Chunk.empty
          )
          .either
        unownedResult <- ToolWiringResolver
          .resolve(
            id,
            StaticContext[Any]("system"),
            unavailable.functions,
            tools => ZIO.succeed(tools),
            AgentCapabilities(),
            Chunk.empty
          )
          .either
      } yield {
        assert(unavailableResult.swap.toOption.get.getMessage.contains("unknown IDs: unavailable"))
        assert(unownedResult.swap.toOption.get.getMessage.contains("no declared tool membership"))
      }
    }
  }

  test("invalid wiring output fails validation even when denied by policy") {
    run {
      val base = tool("blocked", function("probe"))
      val mismatched = base.copy(functions = Chunk(function("probe", "different schema description")))
      val missing = base.copy(functions = Chunk.empty)
      val duplicateMembership = tool("other", base.functions.head)
      for {
        outcomes <- ZIO.foreach(
          Chunk(Chunk(base, base), Chunk(mismatched), Chunk(missing), Chunk(base, duplicateMembership))
        ) { output =>
          resolve(Chunk.empty, _ => ZIO.succeed(output), AgentCapabilities(tools = Some(Set.empty))).either
        }
      } yield {
        assert(outcomes.forall(_.isLeft))
      }
    }
  }

  test("ConfiguredAgent forwards wiring without running it per turn and does not infer group membership") {
    run {
      val local = tool("local", function("local_operation"))
      for {
        count <- Ref.make(0)
        seen <- Ref.make(Vector.empty[Chunk[FunctionToolDefinition]])
        delegate = new AudioAgent[Any] {
          val id = ToolWiringSuite.this.id
          override def toolWirings(tools: Seq[AgentTool[Any]]): IO[YallmoError, Seq[AgentTool[Any]]] =
            count.update(_ + 1).as(tools :+ local)
          def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
            seen
              .update(_ :+ request.tools)
              .as(AgentTurnResponse(Chunk(MessagePayload.Assistant("done")), LlmStopReason.Stop))
        }
        policy = AgentCapabilities(tools = Some(Set(local.name)))
        configured = ConfiguredAgent(delegate, policy)
        wired <- resolve(Chunk.empty, configured.toolWirings, policy)
        request = AgentTurnRequest(
          "model",
          Chunk(MessagePayload.Instructions("system")),
          tools = wired.context.functions,
          toolGroups = wired.context.tools
        )
        _ <- configured.turn(request)
        _ <- configured.turn(request.copy(toolGroups = Chunk.empty))
        calls <- count.get
        requests <- seen.get
      } yield {
        assert(configured.isInstanceOf[AudioAgent[?]])
        assertEquals(calls, 1)
        assertEquals(requests, Vector(local.functions.map(_.definition), Chunk.empty))
      }
    }
  }

  private def run[E, A](effect: ZIO[Any, E, A]): Future[A] =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.runToFuture(effect.mapError(error => FiberFailure(Cause.fail(error))))
    }
}
