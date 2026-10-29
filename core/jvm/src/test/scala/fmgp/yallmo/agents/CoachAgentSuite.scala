package fmgp.yallmo.agents

import fmgp.yallmo.fs.{FileSystem, WriteMode}
import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse, LlmStopReason}
import fmgp.yallmo.orchestration.RuntimeInstances
import fmgp.yallmo.orchestration.config.{
  AgentConfig,
  Config,
  FileSystemConfig,
  HasAgentCapabilities,
  ProviderConfig,
  ProviderId
}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import zio.{Config as _, *}
import zio.http.Client
import zio.json.*

final class CoachAgentSuite extends FunSuite {
  private val id = AgentId("Coach")
  private val request = AgentTurnRequest(
    "test-model",
    Chunk(MessagePayload.User("Help me find a better role"), MessagePayload.Instructions("Be concise")),
    tools =
      Chunk(FunctionToolDefinition(ToolKey.Bash, "shell", fmgp.yallmo.model.llm.JsonSchema.ObjectSchema(Map.empty))),
    parallelToolCalls = true
  )
  private val answer =
    AgentTurnResponse(Chunk(MessagePayload.Assistant("Let's review your goals.")), LlmStopReason.Stop)

  private def delegate(send: AgentTurnRequest => IO[YallmoError, AgentTurnResponse]): Agent[Any] =
    new Agent[Any] {
      val id: AgentId = CoachAgentSuite.this.id
      def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] = send(request)
    }

  test("loads approved evidence as data, preserves conversation and options, disables tools, and never writes") {
    withWorkspace { (root, fs) =>
      run(fs.createDirectories("experiences/Company"))
      run(fs.writeUtf8("experiences/Company/project.md", "User-reported impact; TODO metrics", WriteMode.CreateNew))
      run(fs.writeUtf8("profile.md", "Target: staff engineer", WriteMode.CreateNew))
      run(fs.writeUtf8(".env", "SECRET", WriteMode.CreateNew))
      run(fs.writeUtf8("experiences/TEMPLATE.md", "NOT AN EXPERIENCE", WriteMode.CreateNew))
      run(fs.writeUtf8("experiences/ignored.pdf", "BINARY", WriteMode.CreateNew))
      val agent = makeCoach(
        delegate { prepared =>
          assertEquals(prepared.input.head, request.input.head)
          assertEquals(prepared.model, request.model)
          assertEquals(prepared.options, request.options)
          assertEquals(prepared.tools, Chunk.empty)
          assertEquals(prepared.toolChoice, ToolChoice.None)
          assert(!prepared.parallelToolCalls)
          val context = prepared.input.collect { case MessagePayload.Context(_, text) => text }.mkString
          val evidence = context.fromJson[Chunk[CoachAgent.Evidence]].toOption.get
          assertEquals(evidence.map(_.path), Chunk("profile.md", "experiences/Company/project.md"))
          assert(context.contains("TODO metrics"))
          assert(!context.contains("SECRET"))
          assert(!context.contains("NOT AN EXPERIENCE"))
          assert(!context.contains("BINARY"))
          assertEquals(prepared.input.last, MessagePayload.Instructions(s"Be concise\n\n${CoachAgent.SystemPrompt}"))
          ZIO.succeed(answer)
        },
        fs
      )
      assertEquals(run(agent.turn(request)), answer)
      assert(!Files.exists(root.resolve("state.jsonl")))
      assert(!Files.exists(root.resolve("fs-structure.json")))
      assertEquals(run(fs.readUtf8("experiences/Company/project.md")), "User-reported impact; TODO metrics")
    }
  }

  test("missing optional files are allowed and evidence is refreshed each turn") {
    withWorkspace { (_, fs) =>
      assertEquals(run(CoachAgent.loadEvidence(fs)), Chunk.empty)
      run(fs.createDirectories("experiences"))
      run(fs.writeUtf8("experiences/project.md", "old", WriteMode.CreateNew))
      val seen = run(Ref.make(Vector.empty[String]))
      val agent = makeCoach(delegate(r => seen.update(_ :+ r.input.toString).as(answer)), fs)
      run(agent.turn(request))
      run(fs.writeUtf8("experiences/project.md", "new", WriteMode.Replace))
      run(agent.turn(request))
      val contexts = run(seen.get)
      assert(contexts.head.contains("old"))
      assert(contexts.last.contains("new"))
    }
  }

  test("oversize, invalid UTF-8, symlinked and excessive-depth evidence fail before the model is invoked") {
    withWorkspace { (root, fs) =>
      run(fs.createDirectories("experiences"))
      val agent = makeCoach(delegate(_ => ZIO.dieMessage("Bad evidence must not reach the provider")), fs)
      run(fs.writeUtf8("experiences/large.md", "x" * (32 * 1024 + 1), WriteMode.CreateNew))
      assert(run(agent.turn(request).either).isLeft)
      run(fs.deleteFile("experiences/large.md"))
      Files.write(root.resolve("experiences/bad.md"), Array(0xc3.toByte, 0x28.toByte))
      assert(run(agent.turn(request).either).isLeft)
      run(fs.deleteFile("experiences/bad.md"))
      Files.createSymbolicLink(root.resolve("experiences/link.md"), root.resolve("outside.md"))
      assert(run(agent.turn(request).either).isLeft)
      Files.delete(root.resolve("experiences/link.md"))
      run(fs.createDirectories("experiences/a/b/c/d/e"))
      assert(run(agent.turn(request).either).isLeft)
    }
  }

  test("entry and aggregate size limits fail explicitly") {
    withWorkspace { (_, fs) =>
      run(fs.createDirectories("experiences"))
      run(ZIO.foreachDiscard(1 to 129)(i => fs.writeUtf8(s"experiences/$i.md", "x", WriteMode.CreateNew)))
      assert(run(CoachAgent.loadEvidence(fs).either).isLeft)
    }
    withWorkspace { (_, fs) =>
      run(fs.createDirectories("experiences"))
      run(ZIO.foreachDiscard(1 to 9)(i => fs.writeUtf8(s"experiences/$i.md", "x" * (32 * 1024), WriteMode.CreateNew)))
      assert(run(CoachAgent.loadEvidence(fs).either).isLeft)
    }
  }

  test("rejects invalid envelopes and provider tool calls even for direct callers") {
    withWorkspace { (_, fs) =>
      val invalid = makeCoach(delegate(_ => ZIO.dieMessage("Invalid request must not reach provider")), fs)
      assert(run(invalid.turn(request.copy(input = Chunk.empty)).either).isLeft)
      val toolResponse = AgentTurnResponse(
        Chunk(MessagePayload.ToolCall(FunctionCall(FunctionCallId.unsafe("call"), ToolKey.Bash, "{}"))),
        LlmStopReason.ToolUse
      )
      val agent = makeCoach(delegate(_ => ZIO.succeed(toolResponse)), fs)
      assertEquals(
        run(agent.turn(request).either),
        Left(YallmoError.InvalidAgentDecision("CoachAgent returned a tool call outside its local notebook policy"))
      )
    }
  }

  test("registry initializes either native provider lazily, binds the root, and cannot widen coach policy") {
    withWorkspace { (root, _) =>
      val provider = ProviderId.unsafe("local")
      val settings = AgentConfig("CoachAgent", Seq(provider), filesystem = Some(FileSystemConfig(root.toString)))
      for connection <- List(ProviderConfig.OAI("http://127.0.0.1:1/v1"), ProviderConfig.ChatGPT()) do {
        val config = Config(agents = Map(id -> settings), providers = Map(provider -> connection))
        val registry = run(RuntimeInstances.make(config).provide(Client.default))
        val agent = run(registry.getAgent(id))
        assert(run(registry.getAgent(id)) eq agent)
        assertEquals(agent.asInstanceOf[HasAgentCapabilities].capabilities, CoachAgent.Capabilities)
      }
      for definition <- List(settings.copy(providerIds = Seq.empty), settings.copy(filesystem = None)) do {
        val config = Config(
          agents = Map(id -> definition),
          providers = Map(provider -> ProviderConfig.OAI("http://127.0.0.1:1/v1"))
        )
        val registry = run(RuntimeInstances.make(config).provide(Client.default))
        assert(run(registry.getAgent(id).either).isLeft)
      }
    }
  }

  private def makeCoach(delegate: Agent[Any], fs: FileSystem): CoachAgent = run(CoachAgent.make(delegate, fs))

  private def withWorkspace(test: (Path, FileSystem) => Unit): Unit = {
    val root = Files.createTempDirectory("yallmo-coach-").toRealPath()
    try test(root, run(FileSystem.open(root.toString)))
    finally {
      val paths = Files.walk(root)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete(_))
      finally paths.close()
    }
  }

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
