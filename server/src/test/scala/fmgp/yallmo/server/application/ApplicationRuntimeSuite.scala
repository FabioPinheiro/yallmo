package fmgp.yallmo.server.application

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AgentTurnRequest, AgentTurnResponse}
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.orchestration.config.{
  AgentCapabilities,
  AgentConfig,
  Config,
  FileSystemConfig,
  HasAgentCapabilities,
  OAuthCredentialsConfig,
  ProviderConfig,
  ProviderId
}
import java.nio.file.{Files, Path}
import java.util.Comparator
import munit.FunSuite
import scala.concurrent.Future
import zio.*
import fmgp.yallmo.tool.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

final class ApplicationRuntimeSuite extends FunSuite {
  private val human = new Agent[Any] {
    override val id: AgentId = AgentId("operator")
    override def turn(request: AgentTurnRequest): IO[YallmoError, AgentTurnResponse] =
      ZIO.dieMessage("Runtime assembly must not invoke agents")
  }
  private val first = AgentId("first")
  private val second = AgentId("second")
  private val provider = ProviderId.unsafe("local")
  private def definition(prompt: Option[String] = None): AgentConfig =
    AgentConfig(
      "OAIAgent",
      Seq(provider),
      Json.Obj(
        Chunk("model" -> Json.Str("test-model")) ++ Chunk.fromIterable(prompt.map("systemPrompt" -> Json.Str(_)))
      )
    )
  private val config = Config(
    agents = Map(first -> definition(Some("First prompt")), second -> definition()),
    providers = Map(provider -> ProviderConfig.OAI("http://127.0.0.1:1/v1"))
  )

  test("workspace assembly registers the restricted Scala tool without executing snippets") {
    run {
      ZIO.scoped {
        for {
          root <- temporaryRoot
          events <- Ref.make(Vector.empty[String])
          alive <- Ref.make(false)
          runtime <- ApplicationRuntime.make(Config(), Chunk.empty)
          executor = new ScalaCodeExecutor {
            def isAvailable: UIO[Boolean] = ZIO.succeed(true)
            def execute(
                code: String,
                timeout: Duration,
                maxOutputBytes: Int,
                dependencies: Seq[String]
            ): Task[ExternalToolResult.ScalaMode] =
              ZIO.dieMessage("Assembly must not execute snippets")
          }
          services = toolServices(events, alive).map(_.add[ScalaCodeExecutor](executor))
          opened <- runtime.openWorkspace(None, root, services)
        } yield {
          val definition = opened._1.context(None).tools.find(_.key == ToolKey.Scalamode).get
          assertEquals(definition, ScalaTool.definition)
          assert(opened._2.available.contains(ToolKey.Scalamode))
        }
      }
    }
  }

  test("RPC assembly binds selected private roots consistently for invoke and session tools without duplicate keys") {
    run(ZIO.scoped {
      for {
        root <- temporaryRoot
        oneRoot <- ZIO.attempt(Files.createDirectory(root.resolve("one")))
        twoRoot <- ZIO.attempt(Files.createDirectory(root.resolve("two")))
        _ <- ZIO.attempt {
          Files.writeString(root.resolve("notes.txt"), "workspace")
          Files.writeString(oneRoot.resolve("notes.txt"), "one")
          Files.writeString(twoRoot.resolve("notes.txt"), "two")
        }
        events <- Ref.make(Vector.empty[String])
        alive <- Ref.make(false)
        seen <- Ref.make(Vector.empty[fmgp.yallmo.fs.FsRead])
        route <- fmgp.rpc.RpcWebSocket.route()
        executor = new ScalaCodeExecutor {
          def isAvailable: UIO[Boolean] = ZIO.succeed(true)
          def execute(
              code: String,
              timeout: Duration,
              maxOutputBytes: Int,
              dependencies: Seq[String]
          ): Task[ExternalToolResult.ScalaMode] =
            ZIO.dieMessage("Expected explicit filesystem mode")
          override def executeFilesystem(
              code: String,
              endpoint: String,
              timeout: Duration,
              maxOutputBytes: Int
          ): ZIO[fmgp.yallmo.fs.FsRead & fmgp.rpc.RpcWebSocket.RpcRoute, Throwable, ExternalToolResult.ScalaMode] =
            for {
              fs <- ZIO.service[fmgp.yallmo.fs.FsRead]
              supplied <- ZIO.service[fmgp.rpc.RpcWebSocket.RpcRoute]
              _ <- ZIO.attempt { assert(supplied eq route); assertEquals(endpoint, "ws://127.0.0.1:1/rpc") }
              _ <- seen.update(_ :+ fs)
              text <- fs.readUtf8("notes.txt").mapError(error => new Exception(error.toString))
            } yield ExternalToolResult.ScalaMode(ScalaModeResult.Executed(text))
        }
        coachId = AgentId("coach-private")
        folderId = AgentId("folder-private")
        fallback = AgentId("workspace")
        invalid = AgentId("invalid")
        runtime <- ApplicationRuntime
          .make(
            config.copy(agents =
              Map(
                first -> definition().copy(filesystem = Some(FileSystemConfig(oneRoot.toString))),
                second -> definition().copy(filesystem = Some(FileSystemConfig(twoRoot.toString))),
                fallback -> definition(),
                coachId -> definition()
                  .copy(agentType = "CoachAgent", filesystem = Some(FileSystemConfig(oneRoot.toString))),
                folderId -> AgentConfig("FolderStructureAgent", filesystem = Some(FileSystemConfig(twoRoot.toString))),
                invalid -> definition().copy(filesystem = Some(FileSystemConfig(root.resolve("absent").toString)))
              )
            ),
            Chunk(human)
          )
          .provideEnvironment(ZEnvironment(selectedFilesystemClient))
        opened <- runtime
          .openWorkspaceRpc(
            None,
            root,
            toolServices(events, alive).map(_.add[ScalaCodeExecutor](executor)),
            "ws://127.0.0.1:1/rpc"
          )
          .provideSomeEnvironment[Scope](_.add[fmgp.rpc.RpcWebSocket.RpcRoute](route))
        one <- runtime.resolve(first)
        two <- runtime.resolve(second)
        workspace <- runtime.resolve(fallback)
        again <- runtime.resolve(first)
        failed <- runtime.resolve(invalid).either
        results <- ZIO.foreachPar(List(one -> "one", two -> "two", workspace -> "workspace")) { (binding, expected) =>
          val input = Message(MessageData(human.id, None, java.time.Instant.EPOCH, MessagePayload.User("read")))
          opened._1.invoke(input, binding).map { message =>
            assertEquals(message.payload.contentStr, expected)
          }
        }
        submission = opened._1.submission(human.id, Some(two))
        session <- submission.create(Some("private-root"))
        turn <- submission.submit(Some(session.id.value), "read")
        coach <- runtime.resolve(coachId)
        folder <- runtime.resolve(folderId)
        input = Message(MessageData(human.id, None, java.time.Instant.EPOCH, MessagePayload.User("review")))
        folderReply <- opened._1.invoke(input, folder)
        capabilities <- seen.get
      } yield {
        assert(!coach.adapter.asInstanceOf[HasAgentCapabilities].capabilities.tools.get.contains(ToolKey.Scalamode))
        assert(folderReply.payload.contentStr.contains("notes.txt"))
        assertEquals(capabilities.size, 4, "Folder execution must not invoke scalamode")
        assertEquals(results.size, 3)
        assertEquals(turn.result, AgentResult(second, "two"))
        assert(failed.isLeft, "An invalid explicit private root must not fall back to workspace")
        assert(one.filesystem.get eq again.filesystem.get)
        assert(capabilities.exists(_ eq one.filesystem.get))
        assertEquals(capabilities.count(_ eq two.filesystem.get), 2)
        val keys = opened._1.context(Some(one)).tools.map(_.key)
        assertEquals(keys.distinct, keys)
        assertEquals(
          opened._1.context(Some(one)).tools.find(_.key == ToolKey.Scalamode).get,
          ScalaTool.filesystemDefinition
        )
      }
    })
  }

  test("coach submits with scoped notebook tools and persists drafts without exposing other roots") {
    run {
      ZIO.scoped {
        for {
          root <- temporaryRoot
          _ <- ZIO.attempt {
            Files.createDirectories(root.resolve("experiences"))
            Files.writeString(root.resolve("experiences/project.md"), "User-reported Scala experience")
          }
          requests <- Ref.make(0)
          coach = definition().copy(agentType = "CoachAgent", filesystem = Some(FileSystemConfig(root.toString)))
          otherRoot <- ZIO.attempt(Files.createDirectory(root.resolve("other-coach")))
          runtime <- ApplicationRuntime
            .make(
              config.copy(agents =
                Map(first -> coach, second -> coach.copy(filesystem = Some(FileSystemConfig(otherRoot.toString))))
              ),
              Chunk(human)
            )
            .provideEnvironment(
              ZEnvironment(
                responseClient(
                  requests,
                  Some(
                    """{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"save-call","type":"function","function":{"name":"coach_save_application","arguments":"{\"expectedRevision\":0,\"application\":{\"id\":\"acme-scala\",\"company\":\"Acme\",\"role\":\"Scala engineer\",\"stage\":\"interviewing\",\"replyDraft\":\"Tuesday works.\"}}"}}]},"finish_reason":"tool_calls"}]}"""
                  )
                )
              )
            )
          binding <- runtime.resolve(first)
          before <- requests.get
          assembled <- runtime.open[Any](None, Chunk.empty)
          submission = assembled.submission(human.id, Some(binding))
          session <- submission.create(Some("coach-session"))
          result <- submission.submit(Some(session.id.value), "Help me plan my next career move")
          after <- requests.get
          other <- runtime.resolve(second)
          otherTools = other.adapter.asInstanceOf[HasLocalAgentTools].localTools
          otherNotebook <- otherTools
            .find(_.definition.key == fmgp.yallmo.agents.CoachApplicationTools.ListKey)
            .get
            .execute(ExternalToolCall(fmgp.yallmo.agents.CoachApplicationTools.ListKey, "{}"))
        } yield {
          assertEquals(otherNotebook.text, "[]")
          assert(binding.isInstanceOf[ConfiguredAgentBinding.Native])
          assertEquals(before, 0)
          assertEquals(after, 2)
          assertEquals(result.result.agentId, first)
          assertEquals(
            binding.adapter.asInstanceOf[HasAgentCapabilities].capabilities.tools,
            Some(fmgp.yallmo.agents.CoachApplicationTools.Definitions.map(_.key).toSet)
          )
          assert(Files.readString(root.resolve("applications.jsonl")).contains("Tuesday works."))
          assert(assembled.context(None).tools.isEmpty)
          assertEquals(
            assembled.context(Some(binding)).tools.map(_.key).toSet,
            fmgp.yallmo.agents.CoachApplicationTools.Definitions.map(_.key).toSet
          )
          assert(!Files.exists(root.resolve("state.jsonl")))
        }
      }
    }
  }

  test("coach can omit model and inherit the ChatGPT provider default without inference or authentication") {
    run {
      ZIO.scoped {
        for {
          root <- temporaryRoot
          requests <- Ref.make(0)
          coach = AgentConfig("CoachAgent", Seq(provider), filesystem = Some(FileSystemConfig(root.toString)))
          runtime <- ApplicationRuntime
            .make(
              config.copy(agents = Map(first -> coach), providers = Map(provider -> ProviderConfig.ChatGPT())),
              Chunk.empty
            )
            .provideEnvironment(ZEnvironment(responseClient(requests)))
          selected <- runtime.resolve(first)
          explicitRuntime <- ApplicationRuntime
            .make(
              config.copy(
                agents = Map(first -> coach.copy(config = Json.Obj("model" -> Json.Str("explicit-model")))),
                providers = Map(provider -> ProviderConfig.ChatGPT())
              ),
              Chunk.empty
            )
            .provideEnvironment(ZEnvironment(responseClient(requests)))
          explicit <- explicitRuntime.resolve(first)
          oaiRuntime <- ApplicationRuntime.make(config.copy(agents = Map(first -> coach)), Chunk.empty)
          missing <- oaiRuntime.resolve(first).either
          calls <- requests.get
        } yield {
          def model(binding: ConfiguredAgentBinding): String = binding match {
            case ConfiguredAgentBinding.Native(_, _, program, _, _) => program.model
            case _                                                  => fail("Coach must use a native program")
          }
          assertEquals(model(selected), fmgp.yallmo.provider.openai.OpenAiCodexConfig.Model)
          assertEquals(model(explicit), "explicit-model")
          assertEquals(runtime.agentSummaries.head.model, None)
          assert(missing.swap.toOption.get.getMessage.contains("provider has no default model"))
          assertEquals(calls, 0)
        }
      }
    }
  }

  test("selection reuses one lazy registry and opened storage without changing existing bindings") {
    run {
      for {
        runtime <- ApplicationRuntime.make(
          config.copy(agents = config.agents.updated(AgentId("unrelated"), AgentConfig("Unsupported"))),
          Chunk(human)
        )
        one <- runtime.resolve(first)
        opened <- runtime.open[Any](None, Chunk.empty)
        saved <- opened.orchestrator.createSession(SessionId.unsafe("shared"), Chunk(human.id, first))
        two <- runtime.resolve(second)
        again <- runtime.resolve(first)
        loaded <- opened.orchestrator.loadSession(saved.id)
        unknown <- runtime.resolve(AgentId("missing")).either
      } yield {
        assert(one.adapter eq again.adapter)
        assert(one.adapter.id != two.adapter.id)
        assertEquals(opened.context(Some(one)).systemPrompt, "First prompt")
        assertEquals(opened.context(Some(two)).systemPrompt, ApplicationRuntime.DefaultSystemPrompt)
        assertEquals(opened.context(None).systemPrompt, ApplicationRuntime.DefaultSystemPrompt)
        assertEquals(loaded, saved)
        assertEquals(unknown, Left(YallmoError.UnknownAgent(AgentId("missing"))))
      }
    }
  }

  test("capability validation precedes filesystem creation; valid assembly opens the configured root") {
    run {
      ZIO.scoped {
        for {
          root <- temporaryRoot
          target = root.resolve("runtime")
          restricted = config.copy(agents =
            Map(
              first -> definition().copy(
                capabilities = AgentCapabilities(tools = Some(Set(ToolKey.unsafe("unavailable"))))
              )
            )
          )
          invalid <- ApplicationRuntime.make(restricted, Chunk(human))
          rejected <- invalid.open[Any](Some(target), Chunk.empty).either
          _ = {
            assert(rejected.isLeft)
            assert(!Files.exists(target), "Invalid capability configuration must not create storage")
          }
          runtime <- ApplicationRuntime.make(config, Chunk(human))
          opened <- runtime.open[Any](Some(target), Chunk.empty)
          session <- opened.orchestrator.createSession(SessionId.unsafe("persisted"), Chunk(human.id))
        } yield {
          assertEquals(session.id, SessionId.unsafe("persisted"))
          assert(Files.exists(target.resolve("objects.jsonl")))
        }
      }
    }
  }

  test("server factory owns default workspace storage while explicit storage APIs remain independent") {
    run {
      ZIO.scoped {
        for {
          root <- temporaryRoot
          storage = root.resolve("storage")
          overrideStorage = root.resolve("override")
          path = root.resolve("server.json")
          _ <- ZIO.attempt(Files.writeString(path, Config().toJson))
          memory <- ApplicationRuntime.make(Config(), Chunk(human))
          first <- memory.openWorkspace(root)
          _ <- first._1.orchestrator.createSession(SessionId.unsafe("memory"), Chunk(human.id))
          reopened <- memory.openWorkspace(root)
          empty <- reopened._1.orchestrator.listSessions
          loaded <- ApplicationRuntime.load(Chunk(human), path, storageRoot = Some(storage))
          persisted <- loaded._1.openWorkspace(root)
          _ <- persisted._1.orchestrator.createSession(SessionId.unsafe("persisted"), Chunk(human.id))
          again <- loaded._1.openWorkspace(root)
          saved <- again._1.orchestrator.loadSession(SessionId.unsafe("persisted"))
          explicit <- loaded._1.openWorkspace(Some(overrideStorage), root)
          separate <- explicit._1.orchestrator.listSessions
        } yield {
          assertEquals(empty, Chunk.empty)
          assertEquals(saved.id, SessionId.unsafe("persisted"))
          assertEquals(separate, Chunk.empty)
          assert(Files.exists(storage.resolve("objects.jsonl")))
          assert(Files.exists(overrideStorage.resolve("objects.jsonl")))
          assert(!Files.exists(root.resolve("objects.jsonl")))
        }
      }
    }
  }

  test("workspace tools stay alive for environment-free submissions and selections, then close with the application") {
    run {
      for {
        events <- Ref.make(Vector.empty[String])
        alive <- Ref.make(false)
        requests <- Ref.make(0)
        _ <- ZIO.scoped {
          for {
            root <- temporaryRoot
            workspace <- ZIO.attempt(Files.createDirectory(root.resolve("workspace")))
            _ <- ZIO.attempt(Files.writeString(workspace.resolve("notes.txt"), "fake content"))
            client = toolClient(requests)
            runtime <- ApplicationRuntime.make(config, Chunk(human)).provideEnvironment(ZEnvironment(client))
            opened <- runtime.openWorkspace(Some(root.resolve("storage")), workspace, toolServices(events, alive))
            (assembled, availability) = opened
            one <- runtime.resolve(first)
            selected = assembled.submission(human.id, Some(one))
            session <- selected.create(Some("shared-tools"))
            // No tool services are present at either submission call site.
            firstSubmission: IO[SessionSubmitFailure, SessionTurn] =
              selected.submit(Some(session.id.value), "read once")
            firstTurn <- firstSubmission
            two <- runtime.resolve(second)
            switched = assembled.submission(human.id, Some(two))
            secondSubmission: IO[SessionSubmitFailure, SessionTurn] =
              switched.submit(Some(session.id.value), "read again")
            secondTurn <- secondSubmission
            stored <- selected.load(session.id.value)
            running <- alive.get
            calls <- events.get
            requestCount <- requests.get
          } yield {
            assert(running, "Tool services must remain open after workspace startup and both submissions")
            assertEquals(availability.available, Chunk(ToolKey.ReadFile, ToolKey.ListFiles))
            assertEquals(assembled.context(None).tools, Chunk(ReadFileTool.definition, ListFilesTool.definition))
            assertEquals(firstTurn.result, AgentResult(first, "read successfully"))
            assertEquals(secondTurn.result, AgentResult(second, "read successfully"))
            assertEquals(firstTurn.session.id, secondTurn.session.id)
            assertEquals(stored, secondTurn.session)
            assertEquals(stored.agentIds, Chunk(human.id, first, second))
            assertEquals(requestCount, 4)
            assertEquals(
              calls,
              Vector(
                "acquire",
                "bash.available",
                "git.available",
                "scala.available"
              )
            )
          }
        }
        running <- alive.get
        calls <- events.get
      } yield {
        assert(!running)
        assertEquals(calls.last, "release")
        assertEquals(calls.count(_ == "acquire"), 1)
        assertEquals(calls.count(_ == "release"), 1)
      }
    }
  }

  test("server loader accepts terminal-free config, returns safe summaries and persists the supplied local author") {
    run {
      ZIO.scoped {
        for {
          root <- temporaryRoot
          requests <- Ref.make(0)
          account = ProviderId.unsafe("account")
          folder = AgentId("folder")
          privateConfig = config.copy(
            agents = config.agents ++ Map(
              AgentId("account-agent") -> AgentConfig("ChatGPTAgent", Seq(account)),
              folder -> AgentConfig("FolderStructureAgent", filesystem = Some(FileSystemConfig("PRIVATE_ROOT")))
            ),
            providers = config.providers.updated(
              account,
              ProviderConfig.ChatGPT(Some(OAuthCredentialsConfig("PRIVATE_ACCESS", "PRIVATE_REFRESH", 0)))
            ),
            browserAudioPort = 5123
          )
          path <- ZIO.attemptBlocking(Files.writeString(root.resolve("yallmo.json"), privateConfig.toJson))
          startup <- ApplicationRuntime
            .load(Chunk(human), path)
            .provideEnvironment(ZEnvironment(responseClient(requests)))
          (runtime, catalog) = startup
          ids <- runtime.instances.listAgentIds
          registered <- runtime.instances.getAgent(human.id)
          entries <- catalog.list
          binding <- runtime.resolve(first)
          opened <- runtime.open[Any](Some(root.resolve("storage")), Chunk.empty)
          before <- requests.get
          submission = opened.submission(human.id, Some(binding))
          session <- submission.create(Some("local-author"))
          turn <- submission.submit(Some(session.id.value), "supplied author")
          // Reopen the durable store to prove authorship is not just an in-memory UI value.
          reloaded <- runtime.open[Any](Some(root.resolve("storage")), Chunk.empty)
          stored <- reloaded.orchestrator.loadSession(session.id)
          after <- requests.get
        } yield {
          assertEquals(before, 0, "Loading, listing, resolving and opening must not invoke providers or authentication")
          assertEquals(after, 1)
          assert(registered eq human)
          assert(ids.contains(human.id))
          assertEquals(runtime.browserAudioPort, 5123)
          assertEquals(ApplicationRuntime.DefaultConfigPath, Path.of("yallmo.json"))
          assertEquals(runtime.agentSummaries.map(_.id), Vector(AgentId("account-agent"), first, folder, second))
          assertEquals(
            runtime.agentSummaries.find(_.id == first).get,
            ApplicationRuntime.AgentSummary(first, "OAIAgent", Vector("local"), Some("test-model"))
          )
          assertEquals(runtime.agentSummaries.find(_.id == folder).get.model, None)
          val display = runtime.agentSummaries.toString + entries.toString
          List("PRIVATE_ACCESS", "PRIVATE_REFRESH", "PRIVATE_ROOT", "First prompt").foreach(value =>
            assert(!display.contains(value))
          )
          assertEquals(stored, turn.session)
          assertEquals(stored.messages.map(_.agentId), Chunk(human.id, first))
          assertEquals(
            stored.messages.map(_.payload),
            Chunk(MessagePayload.User("supplied author"), MessagePayload.Assistant("local answer"))
          )
        }
      }
    }
  }

  test("server loader initializes an empty catalog without defaults and rejects registered ID collisions") {
    run {
      ZIO.scoped {
        for {
          root <- temporaryRoot
          path = root.resolve("yallmo.json")
          startup <- ApplicationRuntime.load(Chunk(human), path)
          (runtime, _) = startup
          ids <- runtime.instances.listAgentIds
          initialized <- ZIO.attemptBlocking(Files.readString(path).fromJson[Config].toOption.get)
          opened <- runtime.open[Any](None, Chunk.empty)
          rejected <- opened.submission(human.id, None).submit(None, "no fallback").either
          sessions <- opened.orchestrator.listSessions
          duplicate <- ApplicationRuntime.load(Chunk(human, human), path).either
          _ <- ZIO.attemptBlocking(Files.writeString(path, config.copy(agents = Map(human.id -> definition())).toJson))
          collision <- ApplicationRuntime.load(Chunk(human), path).either
        } yield {
          assertEquals(initialized, Config())
          assertEquals(Files.readString(path).fromJson[Config].toOption.get.agents.keySet, Set(human.id))
          assertEquals(ids, Chunk(human.id))
          assertEquals(runtime.agentSummaries, Vector.empty)
          assert(rejected.isLeft)
          assertEquals(sessions, Chunk.empty)
          assert(duplicate.isLeft)
          assert(collision.isLeft)
        }
      }
    }
  }

  test("agent summaries sanitize display fields and project only the model from opaque agent settings") {
    run {
      val configured = config.copy(agents =
        Map(
          first -> definition().copy(
            agentType = "OAIAgent\u001b\u202e",
            config = Json.Obj("model" -> Json.Str("display\nmodel"), "secret" -> Json.Str("PRIVATE_SETTING"))
          )
        )
      )
      ApplicationRuntime.make(configured, Chunk.empty).map { runtime =>
        assertEquals(
          runtime.agentSummaries,
          Vector(ApplicationRuntime.AgentSummary(first, "OAIAgent??", Vector("local"), Some("display?model")))
        )
        assert(!runtime.agentSummaries.toString.contains("PRIVATE_SETTING"))
      }
    }
  }

  test("summary models are optional configuration metadata independent of agent type and validity") {
    run {
      val cases = List(
        ("missing", Json.Obj(), None),
        ("blank", Json.Obj("model" -> Json.Str("  ")), None),
        ("null", Json.Obj("model" -> Json.Null), None),
        ("number", Json.Obj("model" -> Json.Num(42)), None),
        ("explicit", Json.Obj("model" -> Json.Str(" configured-model ")), Some("configured-model"))
      )
      val definitions = for {
        kind <- List("OAIAgent", "ChatGPTAgent", "CoachAgent", "FolderStructureAgent", "UnknownAgent")
        (label, settings, expected) <- cases
      } yield (AgentId(s"$kind-$label"), AgentConfig(kind, config = settings), expected)
      for {
        requests <- Ref.make(0)
        runtime <- ApplicationRuntime
          .make(
            Config(agents = definitions.map { case (id, definition, _) => id -> definition }.toMap),
            Chunk.empty
          )
          .provideEnvironment(ZEnvironment(responseClient(requests)))
        summaries = runtime.agentSummaries.map(summary => summary.id -> summary.model).toMap
        calls <- requests.get
      } yield {
        definitions.foreach { case (id, _, expected) => assertEquals(summaries(id), expected) }
        assertEquals(calls, 0)
      }
    }
  }

  private val voiceId = AgentId("voice")
  private val voiceDefinition = definition(Some("Transcribe exactly")).copy(
    capabilities = AgentCapabilities(Some(Set.empty), Some(Set.empty), Some(Set.empty), Some(Set.empty))
  )

  test("explicit transcriber validation returns a local audio adapter without provider or authentication calls") {
    run {
      for {
        requests <- Ref.make(0)
        runtime <- ApplicationRuntime
          .make(config.copy(agents = Map(voiceId -> voiceDefinition)), Chunk(human))
          .provideEnvironment(ZEnvironment(responseClient(requests)))
        selected <- runtime.resolveTranscriber(voiceId, StaticContext[Any]("chat"))
        calls <- requests.get
      } yield {
        assertEquals(selected._1.id, voiceId)
        assertEquals(selected._2.model, "test-model")
        assertEquals(selected._2.systemPrompt, "Transcribe exactly")
        assertEquals(calls, 0)
      }
    }
  }

  test("transcriber validation rejects missing IDs, invalid settings and non-audio adapters with sanitized errors") {
    run {
      val unsafeId = AgentId("selected\u001b\u202e")
      val invalid = List(
        voiceDefinition.copy(config = Json.Obj()),
        voiceDefinition.copy(config = Json.Obj("model" -> Json.Str("audio"))),
        voiceDefinition.copy(config = Json.Obj("model" -> Json.Str(" "), "systemPrompt" -> Json.Str("voice"))),
        voiceDefinition.copy(agentType = "ChatGPTAgent"),
        voiceDefinition.copy(
          agentType = "FolderStructureAgent",
          providerIds = Seq.empty,
          filesystem = Some(FileSystemConfig("PRIVATE_MISSING_ROOT"))
        )
      )
      for {
        requests <- Ref.make(0)
        errors <- ZIO.foreach(invalid) { definition =>
          for {
            runtime <- ApplicationRuntime
              .make(config.copy(agents = Map(unsafeId -> definition)), Chunk.empty)
              .provideEnvironment(ZEnvironment(responseClient(requests)))
            result <- runtime.resolveTranscriber(unsafeId, StaticContext[Any]("chat")).either
          } yield result.swap.toOption.get.getMessage
        }
        runtime <- ApplicationRuntime.make(config, Chunk(human))
        missing <- runtime.resolveTranscriber(AgentId("missing"), StaticContext[Any]("chat")).either
        registeredOnly <- runtime.resolveTranscriber(human.id, StaticContext[Any]("chat")).either
        calls <- requests.get
      } yield {
        errors.foreach { message =>
          assert(message.startsWith("Transcriber 'selected??':"))
          List("\u001b", "\u202e", "PRIVATE_MISSING_ROOT").foreach(value => assert(!message.contains(value)))
        }
        assert(missing.isLeft)
        assert(registeredOnly.isLeft, "A registered local human is not a configured transcriber")
        assertEquals(calls, 0)
      }
    }
  }

  test("transcriber policy checks all four effective capability catalogs including partial inheritance") {
    run {
      val tool = StaticContext[Any]("chat", tools = Chunk(AgentToolDefinition(ToolKey.unsafe("shell"), "commands")))
      val rag = StaticContext[Any]("chat", retrievers = Chunk(RetrieverDefinition("docs", "documents")))
      val skill = StaticContext[Any]("chat", skills = Chunk(AgentSkill("brief", "brief", "be brief")))
      val ext = StaticContext[Any](
        "chat",
        extensions = Chunk(new ContextExtension[Any] {
          val key = "memory"
          def apply(context: StaticContext[Any], current: RunContext): IO[YallmoError, RunContext] = {
            val _ = (context, current)
            ZIO.dieMessage("Validation must not apply extensions")
          }
        })
      )
      def check(policy: AgentCapabilities, context: StaticContext[Any]) =
        ApplicationRuntime
          .make(config.copy(agents = Map(voiceId -> voiceDefinition.copy(capabilities = policy))), Chunk.empty)
          .flatMap(_.resolveTranscriber(voiceId, context))
          .either
      for {
        inherited <- ZIO.foreach(List(tool, rag, skill, ext))(check(AgentCapabilities(), _))
        partial <- check(AgentCapabilities(tools = Some(Set.empty)), skill)
        allowed <- check(AgentCapabilities(tools = Some(Set(ToolKey.unsafe("shell")))), tool)
        unknown <- check(AgentCapabilities(tools = Some(Set(ToolKey.unsafe("unknown")))), tool)
        empty <- check(AgentCapabilities(tools = Some(Set.empty)), tool)
        noCatalog <- check(AgentCapabilities(), StaticContext[Any]("chat"))
      } yield {
        assert(inherited.forall(_.isLeft))
        assert(partial.isLeft)
        assert(allowed.isLeft)
        assert(unknown.swap.toOption.get.getMessage.contains("unknown"))
        assert(empty.isRight)
        assert(noCatalog.isRight)
      }
    }
  }

  private def responseClient(requests: Ref[Int], firstReply: Option[String] = None): Client =
    Client.fromDriver(new ZClient.Driver[Any, Scope, Throwable] {
      override def request(
          version: Version,
          method: Method,
          url: URL,
          headers: Headers,
          body: Body,
          sslConfig: Option[ClientSSLConfig],
          proxy: Option[Proxy]
      )(using Trace): Task[Response] = {
        val _ = (version, method, url, headers, body, sslConfig, proxy)
        requests.updateAndGet(_ + 1).map { count =>
          Response.text(
            if count == 1 && firstReply.nonEmpty then firstReply.get
            else """{"choices":[{"message":{"role":"assistant","content":"local answer"},"finish_reason":"stop"}]}"""
          )
        }
      }
      override def socket[Env1 <: Any](version: Version, url: URL, headers: Headers, app: WebSocketApp[Env1])(using
          Trace,
          Scope =:= Scope
      ): ZIO[Env1, Throwable, Response] = {
        val _ = (version, url, headers, app)
        ZIO.dieMessage("Unexpected WebSocket request")
      }
    })

  private def selectedFilesystemClient: Client =
    Client.fromDriver(new ZClient.Driver[Any, Scope, Throwable] {
      override def request(
          version: Version,
          method: Method,
          url: URL,
          headers: Headers,
          body: Body,
          sslConfig: Option[ClientSSLConfig],
          proxy: Option[Proxy]
      )(using Trace): Task[Response] = {
        val _ = (version, method, url, headers, sslConfig, proxy)
        body.asString.map { text =>
          val request = ujson.read(text)
          val messages = request("messages").arr
          val outputs = messages.filter(_("role").str == "tool").map(_("content").str)
          val response = if outputs.nonEmpty then {
            assertEquals(outputs.size, 3)
            val read = outputs(0)
            assert(outputs(1).contains("notes.txt"))
            assertEquals(outputs(2), read)
            ujson.Obj("role" -> "assistant", "content" -> read)
          } else {
            val calls = List(
              ("readFile", ujson.Obj("path" -> "notes.txt")),
              ("listFiles", ujson.Obj("path" -> ".")),
              ("scalamode", ujson.Obj("code" -> "fs.readUtf8(\"notes.txt\")", "mode" -> "filesystem"))
            ).zipWithIndex.map { case ((name, arguments), index) =>
              ujson.Obj(
                "id" -> s"call-$index",
                "type" -> "function",
                "function" -> ujson.Obj("name" -> name, "arguments" -> ujson.write(arguments))
              )
            }
            ujson.Obj("role" -> "assistant", "tool_calls" -> ujson.Arr.from(calls))
          }
          Response.text(
            ujson.write(
              ujson.Obj(
                "choices" -> ujson.Arr(
                  ujson.Obj(
                    "message" -> response,
                    "finish_reason" -> (if outputs.nonEmpty then "stop" else "tool_calls")
                  )
                )
              )
            )
          )
        }
      }
      override def socket[Env1 <: Any](version: Version, url: URL, headers: Headers, app: WebSocketApp[Env1])(using
          Trace,
          Scope =:= Scope
      ): ZIO[Env1, Throwable, Response] = {
        val _ = (version, url, headers, app)
        ZIO.dieMessage("Unexpected WebSocket request")
      }
    })

  private def toolServices(events: Ref[Vector[String]], alive: Ref[Boolean]): ULayer[ApplicationTools.Environment] = {
    def record(event: String): UIO[Unit] = events.update(_ :+ event)
    val bash = new BashExecutor {
      def isAvailable: UIO[Boolean] = record("bash.available").as(false)
      def execute(command: String, path: Path, timeout: Duration, maxOutputBytes: Int): Task[CommandOutput] =
        ZIO.dieMessage(s"Unexpected shell: $command $path $timeout $maxOutputBytes")
    }
    val git = new GitClient {
      def isAvailable: UIO[Boolean] = record("git.available").as(false)
      def isWorkTree(path: Path): UIO[Boolean] = ZIO.dieMessage(s"Unexpected git probe: $path")
      def status(path: Path): IO[GitError, CommandOutput] = ZIO.dieMessage(s"Unexpected git status: $path")
      def unstagedDiff(path: Path): IO[GitError, CommandOutput] = ZIO.dieMessage(s"Unexpected git diff: $path")
      def stagedDiff(path: Path): IO[GitError, CommandOutput] = ZIO.dieMessage(s"Unexpected git diff: $path")
    }
    val scala = new ScalaCodeExecutor {
      def isAvailable: UIO[Boolean] = record("scala.available").as(false)
      def execute(
          code: String,
          timeout: Duration,
          maxOutputBytes: Int,
          dependencies: Seq[String]
      ): Task[ExternalToolResult.ScalaMode] =
        ZIO.dieMessage(s"Unexpected Scala: $code $timeout $maxOutputBytes")
    }
    val bashLayer: ULayer[BashExecutor] = ZLayer.scoped(
      ZIO.acquireRelease(record("acquire") *> alive.set(true).as(bash))(_ => alive.set(false) *> record("release"))
    )
    bashLayer ++ ZLayer.succeed(git) ++ ZLayer.succeed(scala)
  }

  private def toolClient(requests: Ref[Int]): Client =
    Client.fromDriver(new ZClient.Driver[Any, Scope, Throwable] {
      override def request(
          version: Version,
          method: Method,
          url: URL,
          headers: Headers,
          body: Body,
          sslConfig: Option[ClientSSLConfig],
          proxy: Option[Proxy]
      )(using Trace): Task[Response] = {
        val _ = (version, method, url, headers, sslConfig, proxy)
        body.asString.flatMap { text =>
          requests.update(_ + 1) *> ZIO.attempt {
            val request = ujson.read(text)
            assertEquals(request("tools").arr.map(_("function")("name").str).toSet, Set("readFile", "listFiles"))
            val messages = request("messages").arr.filterNot(_("role").str == "system")
            if messages.last("role").str == "tool" then {
              assertEquals(messages.last("content").str, "fake content")
              Response.text(
                """{"choices":[{"message":{"role":"assistant","content":"read successfully"},"finish_reason":"stop"}]}"""
              )
            } else
              Response.text(
                """{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"read-call","type":"function","function":{"name":"readFile","arguments":"{\"path\":\"notes.txt\"}"}}]},"finish_reason":"tool_calls"}]}"""
              )
          }
        }
      }
      override def socket[Env1 <: Any](
          version: Version,
          url: URL,
          headers: Headers,
          app: WebSocketApp[Env1]
      )(using Trace, Scope =:= Scope): ZIO[Env1, Throwable, Response] = {
        val _ = (version, url, headers, app)
        ZIO.dieMessage("Unexpected WebSocket request")
      }
    })

  private def temporaryRoot: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("yallmo-application-runtime-"))) { root =>
      ZIO.attemptBlocking {
        val paths = Files.walk(root)
        try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
        finally paths.close()
      }.orDie
    }

  private def run[E, A](effect: ZIO[Client, E, A]): Future[A] =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.runToFuture(
        effect.provideLayer(Client.default).mapError(error => FiberFailure(Cause.fail(error)))
      )
    }
}
