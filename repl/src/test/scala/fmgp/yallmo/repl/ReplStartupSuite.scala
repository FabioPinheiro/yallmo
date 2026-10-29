package fmgp.yallmo.repl

import fmgp.yallmo.model.ToolKey
import fmgp.yallmo.server.application.{ApplicationRuntime, ProviderCatalog}
import fmgp.yallmo.repl.voice.CapturedAudio
import fmgp.yallmo.model.{AgentId, AudioFormat, MessageId, MessagePayload, YallmoError}
import fmgp.yallmo.model.llm.ThinkingLevel
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.orchestration.config.{
  AgentConfig,
  AgentCapabilities,
  Config,
  ConfigFile,
  FileSystemConfig,
  OAuthCredentialsConfig,
  ProviderConfig,
  ProviderId
}
import fmgp.yallmo.provider.openai.OpenAiCodexClient
import fmgp.yallmo.repl.voice.Transcriber
import munit.FunSuite
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.{Base64, Comparator}

final class ReplStartupSuite extends FunSuite {
  private def runUnscoped[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private val applicationScope = FunFixture[Scope.Closeable](
    _ => runUnscoped(Scope.make),
    scope => runUnscoped(scope.close(Exit.unit))
  )

  private def scopedTest(name: String)(body: Scope ?=> Any)(using munit.Location): Unit =
    applicationScope.test(name)(scope => body(using scope))

  private def run[E, A](effect: ZIO[Scope, E, A])(using scope: Scope): A =
    runUnscoped(effect.provideEnvironment(ZEnvironment(scope)))

  private val options = ReplLaunchOptions("operator", agentId = Some(AgentId("bob")))
  private val bob = AgentId("bob")
  private val eve = AgentId("eve")
  private val local = ProviderId.unsafe("local-1")
  private val account = ProviderId.unsafe("chatgpt-2")

  private val config = Config(
    agents = Map(
      bob -> AgentConfig(
        "OAIAgent",
        Seq(local),
        Json.Obj(
          "model" -> Json.Str("local-model"),
          "systemPrompt" -> Json.Str("Be precise"),
          "thinkingLevel" -> Json.Str("high"),
          "parallelToolCalls" -> Json.Bool(true)
        )
      ),
      eve -> AgentConfig("ChatGPTAgent", Seq(account), Json.Obj("model" -> Json.Str("account-model")))
    ),
    providers = Map(
      local -> ProviderConfig.OAI("http://localhost:12434/v1"),
      account -> ProviderConfig.ChatGPT()
    )
  )

  private def fakeClient(respond: Request => Task[Response]): Client =
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
        val _ = (sslConfig, proxy)
        respond(Request(version = version, method = method, url = url, headers = headers, body = body))
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

  private val noNetwork = fakeClient(_ => ZIO.dieMessage("Startup must not make provider/auth requests"))

  private def fixture(
      definition: Config,
      launch: ReplLaunchOptions,
      storageRoot: Option[Path] = None,
      transcriberAgentId: Option[AgentId] = None
  ): ZIO[Client, YallmoError, ReplStartup] =
    for {
      userId <- ZIO.fromEither(AgentId.from(launch.userName).left.map(YallmoError.InvalidConfig(_)))
      channel <- HumanTurnChannel.make
      human = HumanAgent.make(userId, channel)
      loaded <- storageRoot match {
        case Some(root) =>
          val path = root.resolve("server-config.json")
          for {
            _ <- ZIO
              .attempt(Files.writeString(path, definition.toJson))
              .mapError(error => YallmoError.InvalidConfig(error.getMessage))
            loaded <- ApplicationRuntime.load(Chunk(human), path, storageRoot = Some(root))
          } yield loaded
        case None =>
          for {
            runtime <- ApplicationRuntime.make(definition, Chunk(human))
            catalog <- ProviderCatalog.make(Paths.get("unused-test-catalog.json"))
          } yield (runtime, catalog)
      }
      (runtime, catalog) = loaded
      current <- ReplStartup.fromRuntime(runtime, catalog, human, launch, transcriberAgentId)
    } yield current

  private def startup(
      definition: Config = config,
      launch: ReplLaunchOptions = options,
      transcriberAgentId: Option[AgentId] = None
  )(using Scope): ReplStartup =
    run(
      fixture(definition, launch, transcriberAgentId = transcriberAgentId).provideEnvironment(ZEnvironment(noNetwork))
    )

  private def submit(
      app: ReplApplication[Any],
      state: ReplState,
      line: String
  )(using Scope): ReplTransition =
    run(app.submit(state, line))

  scopedTest(
    "empty configuration starts unselected, retains the human identity and rejects prompts without changing state"
  ) {
    val current = startup(Config(), options.copy(agentId = None))
    val application = run(current.application)
    assertEquals(current.selected, None)
    assertEquals(current.userAgent.id, AgentId("operator"))
    assert(run(current.instances.getAgent(current.userAgent.id)) eq current.userAgent)
    assertEquals(application.activeAgent, None)
    assertEquals(application.userId, AgentId("operator"))
    val before = ReplState(logLevel = LogLevel.Debug)
    val rejected = submit(application, before, "Hello")
    assertEquals(rejected.state, before)
    assert(
      rejected.events.collect { case ReplEvent.AgentFailed(message) => message }.exists(_.contains("No agent selected"))
    )
    assert(submit(application, before, "/help").events.exists(_.isInstanceOf[ReplEvent.Help]))
    assert(submit(application, before, "/tools").events.exists(_.isInstanceOf[ReplEvent.ToolAvailability]))
    val login = submit(application, before, "/login")
    assert(login.events.exists(_.isInstanceOf[ReplEvent.InputRejected]))
    val created = submit(application, before, "/new notes")
    assertEquals(created.state.sessionId, Some("notes"))
  }

  scopedTest(
    "only explicit CLI selection starts selected; absent selection never chooses the first agent"
  ) {
    assertEquals(startup().selected.map(_.adapter.id), Some(bob))
    val overridden = startup(config, options.copy(agentId = Some(eve)))
    assertEquals(overridden.selected.map(_.adapter.id), Some(eve))
    assertEquals(overridden.selected.map(_.feedback.provider), Some("chatgpt-2"))
    assertEquals(overridden.selected.map(_.systemPrompt), Some(ReplStartup.DefaultSystemPrompt))
    assertEquals(startup(config, options.copy(agentId = None)).selected, None)
    val missing = run(
      fixture(config, options.copy(agentId = Some(AgentId("missing"))))
        .provideEnvironment(ZEnvironment(noNetwork))
        .either
    )
    assertEquals(missing, Left(YallmoError.UnknownAgent(AgentId("missing"))))
  }

  scopedTest("startup log level initializes REPL turns and remains changeable with /log") {
    assertEquals(run(startup().application).initialState.logLevel, LogLevel.Warning)
    for level <- List(LogLevel.Debug, LogLevel.Trace, LogLevel.None) do {
      val application = run(startup(launch = options.copy(logLevel = level)).application)
      assertEquals(application.initialState.logLevel, level)
      val created = submit(application, application.initialState, "/new")
      assertEquals(created.state.logLevel, level)
      val changed = submit(application, created.state, "/log error")
      assertEquals(changed.state.logLevel, LogLevel.Error)
      val selected = run(application.selectAgent(eve))
      assertEquals(selected.initialState.logLevel, level)
    }
  }

  scopedTest("selected agent settings validate without leaking values or initializing unrelated providers") {
    val bad = Seq(
      Json.Obj(),
      Json.Obj("model" -> Json.Str(" ")),
      Json.Obj("model" -> Json.Str("ok"), "systemPrompt" -> Json.Str(" ")),
      Json.Obj("model" -> Json.Str("ok"), "thinkingLevel" -> Json.Str("secret-placeholder")),
      Json.Obj("model" -> Json.Bool(false)),
      Json.Obj("model" -> Json.Str("ok"), "systemPrompt" -> Json.Bool(false)),
      Json.Obj("model" -> Json.Str("ok"), "thinkingLevel" -> Json.Bool(false)),
      Json.Obj("model" -> Json.Str("ok"), "parallelToolCalls" -> Json.Str("secret-placeholder"))
    )
    bad.foreach { fields =>
      val definition = config.copy(agents = config.agents.updated(bob, config.agents(bob).copy(config = fields)))
      val result = run(fixture(definition, options).provideEnvironment(ZEnvironment(noNetwork)).either)
      assert(result.isLeft)
      assert(!result.swap.toOption.get.getMessage.contains("secret-placeholder"))
    }
    val unrelatedInvalid = config.copy(providers =
      config.providers.updated(account, ProviderConfig.ChatGPT(endpoint = Some("https://untrusted.invalid")))
    )
    assertEquals(startup(unrelatedInvalid).selected.map(_.adapter.id), Some(bob))
  }

  scopedTest("folder agents start and switch without native settings or providers, executing only on submission") {
    val root = Files.createTempDirectory("yallmo-repl-folder-")
    val folderId = AgentId("folder-reviewer")
    try {
      Files.writeString(root.resolve("README.md"), "example")
      val definition = config.copy(
        agents = config.agents.updated(
          folderId,
          AgentConfig("FolderStructureAgent", filesystem = Some(FileSystemConfig(root.toString)))
        )
      )
      val current = startup(definition, options.copy(agentId = Some(folderId)))
      assertEquals(current.selected.map(_.adapter.id), Some(folderId))
      val application = run(current.application)
      assertEquals(application.activeAgent.map(_.provider), Some("none"))
      assertEquals(application.activeAgent.map(_.model), Some("n/a"))
      assertEquals(application.agentChoices.find(_.id == folderId).map(_.model), Some(None))
      assert(!Files.exists(root.resolve("state.jsonl")))
      assert(!Files.exists(root.resolve("fs-structure.json")))

      val first = submit(application, application.initialState, "Review the folder")
      assert(first.events.exists(_.isInstanceOf[ReplEvent.AgentTurn]), first.events.toString)
      assertEquals(ujson.read(Files.readString(root.resolve("state.jsonl")))("reviewCount").num, 1.0)
      assertEquals(ujson.read(Files.readString(root.resolve("fs-structure.json")))("files")(0)("name").str, "README.md")
      val native = run(application.selectAgent(bob))
      assertEquals(native.activeAgent.map(_.model), Some("local-model"))
      val reselected = run(native.selectAgent(folderId))
      val second = submit(reselected, first.state, "Review again")
      assert(second.events.exists(_.isInstanceOf[ReplEvent.AgentTurn]), second.events.toString)
      assertEquals(second.state.sessionId, first.state.sessionId)
      assertEquals(Files.readString(root.resolve("state.jsonl")).linesIterator.size, 2)
      assertEquals(
        second.state.runContext.messages.collect { case MessagePayload.Assistant(text) => text }.size,
        2
      )
    } finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }

  scopedTest("folder selection validates its own filesystem settings, not native model settings") {
    val folderId = AgentId("folder-reviewer")
    val definition = config.copy(agents = config.agents.updated(folderId, AgentConfig("FolderStructureAgent")))
    val application = run(startup(definition).application)
    val error = run(application.selectAgent(folderId).either).swap.toOption.get
    assert(error.getMessage.contains("filesystem.root"))
    assert(!error.getMessage.contains("model"))
    assertEquals(application.activeAgent.map(_.agentId), Some("bob"))

    val unsupported = definition.copy(agents = definition.agents.updated(folderId, AgentConfig("UnknownAgent")))
    val result = run(
      fixture(unsupported, options.copy(agentId = Some(folderId)))
        .provideEnvironment(ZEnvironment(noNetwork))
        .either
    )
    assert(result.swap.toOption.exists(_.getMessage.contains("Unsupported agentType")))
  }

  scopedTest("operator IDs cannot collide with configured agents") {
    val result = run(
      fixture(config, options.copy(userName = "bob"))
        .provideEnvironment(ZEnvironment(noNetwork))
        .either
    )
    assert(result.swap.toOption.exists(_.isInstanceOf[YallmoError.InvalidConfig]))
  }

  scopedTest(
    "selected requests and footer share configured model, prompt, thinking and provider ID; provenance is persisted"
  ) {
    val root = Files.createTempDirectory("yallmo-config-startup-")
    try {
      val client = fakeClient(incoming =>
        incoming.body.asString.flatMap { body =>
          ZIO.attempt {
            assertEquals(incoming.url.toString, "http://localhost:12434/v1/chat/completions")
            val json = ujson.read(body)
            assertEquals(json("model").str, "local-model")
            assertEquals(json("messages").arr.last("content").str, "Be precise")
            assertEquals(json("parallel_tool_calls").bool, true)
            Response.text("""{"choices":[{"message":{"role":"assistant","content":"Hi"},"finish_reason":"stop"}]}""")
          }
        }
      )
      val current = run(
        fixture(config, options, storageRoot = Some(root))
          .provideEnvironment(ZEnvironment(client))
      )
      val application = run(current.application)
      assertEquals(application.activeAgent.map(_.provider), Some("local-1"))
      assertEquals(application.activeAgent.map(_.model), Some("local-model"))
      assertEquals(application.activeAgent.flatMap(_.thinkingLevel), Some(ThinkingLevel.High))
      val turn = submit(application, ReplState(), "Hello")
      assert(turn.events.exists(_.isInstanceOf[ReplEvent.AgentTurn]), turn.events.toString)
      val sessionId = SessionId.unsafe(turn.state.sessionId.get)
      val stored = run(OrchestratorLive.filesystem(root, current.instances).flatMap(_.loadSession(sessionId)))
      assertEquals(stored.agentIds, Chunk(AgentId("operator"), bob))
      assertEquals(stored.messages.map(_.agentId), Chunk(AgentId("operator"), bob))
      assertEquals(stored.messages.map(_.payload), Chunk(MessagePayload.User("Hello"), MessagePayload.Assistant("Hi")))
      assert(
        submit(application, turn.state, s"/session resume ${sessionId.value}").events
          .exists(_.isInstanceOf[ReplEvent.SessionResumed])
      )
    } finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }

  scopedTest("ChatGPTAgent sends its configured systemPrompt as instructions on every turn") {
    val payload = Base64.getUrlEncoder.withoutPadding.encodeToString(
      """{"https://api.openai.com/auth":{"chatgpt_account_id":"test-account"}}""".getBytes(StandardCharsets.UTF_8)
    )
    val credentials = OAuthCredentialsConfig(s"header.$payload.signature", "test-refresh-token", Long.MaxValue)
    val definition = config.copy(
      agents = config.agents.updated(
        eve,
        config
          .agents(eve)
          .copy(config =
            Json
              .Obj("model" -> Json.Str("account-model"), "systemPrompt" -> Json.Str("You are Eve, a careful reviewer."))
          )
      ),
      providers = config.providers.updated(account, ProviderConfig.ChatGPT(Some(credentials)))
    )
    val client = fakeClient(incoming =>
      incoming.body.asString.flatMap { body =>
        ZIO.attempt {
          assertEquals(incoming.url.toString, OpenAiCodexClient.CodexResponsesUrl)
          val json = ujson.read(body)
          assertEquals(json("model").str, "account-model")
          assertEquals(json("instructions").str, "You are Eve, a careful reviewer.")
          Response.text(
            """data: {"type":"response.done","response":{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Hi"}]}]}}""" + "\n\n"
          )
        }
      }
    )
    val current = run(
      fixture(definition, options.copy(agentId = Some(eve))).provideEnvironment(ZEnvironment(client))
    )
    val application = run(current.application)
    val first = submit(application, application.initialState, "Hello")
    assert(first.events.exists(_.isInstanceOf[ReplEvent.AgentTurn]))
    val second = submit(application, first.state, "Hello again")
    assert(second.events.exists(_.isInstanceOf[ReplEvent.AgentTurn]))
  }

  scopedTest("a missing server file initializes and composes an unselected REPL startup") {
    val root = Files.createTempDirectory("yallmo-new-config-")
    val path = root.resolve("yallmo.json")
    try {
      val launch = ReplLaunchOptions.parse(Chunk("--user-name", "new-operator")).toOption.get
      val current = run {
        for {
          channel <- HumanTurnChannel.make
          human = HumanAgent.make(AgentId("new-operator"), channel)
          loaded <- ApplicationRuntime.load(Chunk(human), path).provideEnvironment(ZEnvironment(noNetwork))
          initialized <- ReplStartup
            .fromRuntime(loaded._1, loaded._2, human, launch)
            .provideEnvironment(ZEnvironment(noNetwork))
        } yield initialized
      }
      assert(Files.exists(path))
      assertEquals(current.userAgent.id, AgentId("new-operator"))
      assertEquals(current.selected, None)
      val application = run(current.application)
      assertEquals(application.activeAgent, None)
      assert(submit(application, ReplState(), "Hello").events.exists(_.isInstanceOf[ReplEvent.AgentFailed]))
    } finally {
      Files.deleteIfExists(path)
      Files.deleteIfExists(root)
    }
  }

  scopedTest("server legacy preferences never select agents; supplied username and safe custom browser port reach UI") {
    val root = Files.createTempDirectory("repl-owned-config-")
    val path = root.resolve("server.json")
    val storage = Files.createDirectory(root.resolve("sessions"))
    val raw =
      """{"userName":"ignored-human","defaultAgentId":"missing-chat","transcriberAgent":"missing-voice","browserAudioPort":6199}"""
    try {
      Files.writeString(path, raw)
      val launch = ReplLaunchOptions("supplied-human")
      val current = run {
        (for {
          channel <- HumanTurnChannel.make
          human = HumanAgent.make(AgentId("supplied-human"), channel)
          loaded <- ApplicationRuntime.load(Chunk(human), path, storageRoot = Some(storage))
          initialized <- ReplStartup.fromRuntime(loaded._1, loaded._2, human, launch)
        } yield initialized).provideEnvironment(ZEnvironment(noNetwork))
      }
      val app = run(current.application)
      assertEquals(current.browserAudioPort, 6199)
      assertEquals(app.userId, AgentId("supplied-human"))
      assert(run(current.instances.getAgent(app.userId)) eq current.userAgent)
      assertEquals(current.selected, None)
      assertEquals(app.transcriber, None)
      def storageEntries = {
        val files = Files.walk(storage)
        try files.toArray.toVector.map(_.toString).sorted
        finally files.close()
      }
      val entriesBefore = storageEntries
      val before = app.initialState
      assertEquals(submit(app, before, "unselected prompt").state, before)
      assertEquals(storageEntries, entriesBefore)
      assertEquals(run(OrchestratorLive.filesystem(storage, current.instances).flatMap(_.listSessions)), Chunk.empty)
      assertEquals(Files.readString(path), raw)
    } finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }

  private val voiceId = AgentId("Transcriber")
  private val voiceDefinition = AgentConfig(
    "OAIAgent",
    Seq(local),
    Json.Obj("model" -> Json.Str("audio-model"), "systemPrompt" -> Json.Str("Transcribe exactly")),
    capabilities = AgentCapabilities(Some(Set.empty), Some(Set.empty), Some(Set.empty), Some(Set.empty))
  )
  private val voiceConfig =
    config.copy(agents = config.agents.updated(voiceId, voiceDefinition))

  private def configuredVoice(
      definition: Config,
      available: StaticContext[Any],
      selectedVoice: Option[AgentId] = Some(voiceId)
  ): IO[YallmoError, Option[Transcriber]] =
    for {
      runtime <- ApplicationRuntime.make(definition, Chunk.empty).provideEnvironment(ZEnvironment(noNetwork))
      orchestrator <- OrchestratorLive.inMemory(runtime.instances)
      voice <- ReplStartup.voice(runtime, selectedVoice, available, orchestrator, AgentId("operator"))
    } yield voice

  scopedTest("REPL voice rejects invalid settings and unsupported agent/provider pairs without network or auth") {
    val invalid = List(
      voiceDefinition.copy(config = Json.Obj()),
      voiceDefinition.copy(config = Json.Obj("model" -> Json.Str("audio"))),
      voiceDefinition.copy(config = Json.Obj("model" -> Json.Str(" "), "systemPrompt" -> Json.Str("voice"))),
      voiceDefinition.copy(config = Json.Obj("model" -> Json.Str("audio"), "systemPrompt" -> Json.Str(" "))),
      voiceDefinition.copy(agentType = "ChatGPTAgent", providerIds = Seq(account)),
      voiceDefinition.copy(providerIds = Seq(account))
    )
    invalid.foreach { definition =>
      val c = voiceConfig.copy(agents = voiceConfig.agents.updated(voiceId, definition))
      val error = run(configuredVoice(c, StaticContext[Any]("chat")).either).swap.toOption.get
      assert(error.getMessage.contains("Transcriber"))
      assert(error.getMessage.contains("Transcriber"))
      assert(!error.getMessage.contains("refresh-"))
    }
    val missing = run(
      configuredVoice(voiceConfig, StaticContext[Any]("chat"), Some(AgentId("missing"))).either
    ).swap.toOption.get
    assert(missing.getMessage.contains("missing"))
    assertEquals(run(configuredVoice(config, StaticContext[Any]("chat"), None)), None)
    assert(run(configuredVoice(voiceConfig, StaticContext[Any]("chat"))).nonEmpty)
  }

  scopedTest("REPL voice checks effective empty capabilities and inherited fields in partial policies") {
    val tool = StaticContext[Any]("chat", tools = Chunk(AgentToolDefinition(ToolKey.unsafe("shell"), "commands")))
    val rag = StaticContext[Any]("chat", retrievers = Chunk(RetrieverDefinition("docs", "documents")))
    val skill = StaticContext[Any]("chat", skills = Chunk(AgentSkill("brief", "brief", "be brief")))
    val ext = StaticContext[Any](
      "chat",
      extensions = Chunk(new ContextExtension[Any] {
        val key = "memory"
        def apply(c: StaticContext[Any], r: RunContext): IO[YallmoError, RunContext] = {
          val _ = (c, r)
          ZIO.dieMessage("Voice validation must not apply extensions")
        }
      })
    )
    def check(policy: AgentCapabilities, context: StaticContext[Any]) = {
      val c = voiceConfig.copy(
        agents = voiceConfig.agents.updated(voiceId, voiceDefinition.copy(capabilities = policy))
      )
      run(configuredVoice(c, context).either)
    }
    for context <- List(tool, rag, skill, ext) do assert(check(AgentCapabilities(), context).isLeft)
    assert(check(AgentCapabilities(tools = Some(Set.empty)), skill).isLeft)
    assert(check(AgentCapabilities(tools = Some(Set(ToolKey.unsafe("shell")))), tool).isLeft)
    assert(
      check(AgentCapabilities(tools = Some(Set(ToolKey.unsafe("unknown")))), tool).swap.toOption.get.getMessage
        .contains("unknown")
    )
    assert(check(AgentCapabilities(tools = Some(Set.empty)), tool).isRight)
    assert(check(AgentCapabilities(), StaticContext[Any]("chat")).isRight)
  }

  scopedTest("tracked secret-free example loads and resolves its explicit transcriber locally") {
    val example = run(ConfigFile.loadOrInitialize(Paths.get("docs/examples/yallmo.json")))
    val app = run(startup(example, transcriberAgentId = Some(voiceId)).application)
    assert(app.transcriber.nonEmpty)
    assertEquals(example.browserAudioPort, 5180)
    assert(app.agentChoices.exists(_.id == voiceId))
  }

  scopedTest("voice is opt-in and invalid explicit transcribers fail locally during application startup") {
    assertEquals(run(startup().application).transcriber, None)
    assertEquals(run(startup(Config(), options.copy(agentId = None)).application).transcriber, None)
    val invalid = List(
      voiceConfig -> AgentId("missing"),
      voiceConfig -> eve,
      voiceConfig.copy(agents =
        voiceConfig.agents.updated(voiceId, voiceDefinition.copy(config = Json.Obj("model" -> Json.Str("audio"))))
      ) -> voiceId
    )
    invalid.foreach { case (definition, selectedVoice) =>
      val result = run(startup(definition, transcriberAgentId = Some(selectedVoice)).application.either)
      assert(result.left.toOption.exists(_.getMessage.contains("Transcriber")))
    }
    val invalidSelected = voiceConfig.copy(
      agents = voiceConfig.agents.updated(
        voiceId,
        voiceDefinition.copy(config = Json.Obj("model" -> Json.Str(" "), "systemPrompt" -> Json.Str("transcribe")))
      )
    )
    val selectedFailure =
      run(
        fixture(invalidSelected, options.copy(agentId = Some(voiceId)))
          .provideEnvironment(ZEnvironment(noNetwork))
          .either
      )
    assert(
      selectedFailure.left.toOption.exists(error =>
        error.getMessage.contains("Transcriber") && error.getMessage.contains("model")
      )
    )
    val inherited = voiceConfig.copy(agents =
      voiceConfig.agents.updated(voiceId, voiceDefinition.copy(capabilities = AgentCapabilities()))
    )
    val rejected = run(startup(inherited, transcriberAgentId = Some(voiceId)).application.either)
    assert(rejected.left.toOption.exists(_.getMessage.contains("capabilities")))
    val typo = config.copy(agents =
      config.agents
        .updated(
          eve,
          config.agents(eve).copy(capabilities = AgentCapabilities(tools = Some(Set(ToolKey.unsafe("unknown-tool")))))
        )
    )
    assert(run(startup(typo).application.either).isLeft)
  }

  scopedTest(
    "configured voice is independent of text-only or absent chat and survives switches with isolated retained sessions"
  ) {
    val root = Files.createTempDirectory("voice-startup-")
    val requests = scala.collection.mutable.ListBuffer.empty[ujson.Value]
    val client = fakeClient(incoming =>
      incoming.body.asString.map { body =>
        requests += ujson.read(body)
        Response.text("""{"choices":[{"message":{"content":"transcript"},"finish_reason":"stop"}]}""")
      }
    )
    try {
      val current = run(
        fixture(voiceConfig, options.copy(agentId = None), storageRoot = Some(root), transcriberAgentId = Some(voiceId))
          .provideEnvironment(ZEnvironment(client))
      )
      val first = run(current.application)
      assert(first.agentChoices.exists(_.id == voiceId))
      val chat = submit(first, first.initialState, "/new ordinary-chat").state
      val second = run(first.selectAgent(eve))
      val third = run(second.selectAgent(bob))
      assert(first.transcriber.get eq second.transcriber.get)
      assert(second.transcriber.get eq third.transcriber.get)
      val chatTurn = submit(third, chat, "ordinary prompt").state
      val storeBefore = run(OrchestratorLive.filesystem(root, current.instances))
      val storedChatBefore = run(storeBefore.loadSession(SessionId.unsafe("ordinary-chat")))
      val chatRequestCount = requests.size
      val audio = CapturedAudio(
        fmgp.yallmo.model.MessagePayload.AudioAttachment(fmgp.yallmo.model.BlobId.fresh(), AudioFormat.Wav),
        Chunk[Byte](1, 2)
      )
      for app <- List(first, second, third) do
        assertEquals(
          run(
            app.transcriber.get
              .transcribe(audio.copy(input = audio.input.copy(blobId = fmgp.yallmo.model.BlobId.fresh())))
          ),
          "transcript"
        )
      assertEquals(requests.size, chatRequestCount + 3)
      requests.drop(chatRequestCount).foreach { request =>
        assertEquals(request("model").str, "audio-model")
        assertEquals(request("messages").arr.size, 2)
        assert(!request.obj.contains("tools"))
        assertEquals(request("tool_choice").str, "none")
        assertEquals(request("parallel_tool_calls").bool, false)
      }
      val store = run(OrchestratorLive.filesystem(root, current.instances))
      val sessions = run(store.listSessions)
      val retained = sessions.filter(_.id.value.startsWith("transcription-"))
      assertEquals(retained.size, 3)
      assertEquals(retained.map(_.id).distinct.size, 3)
      retained.foreach { summary =>
        val exchange = run(store.loadSession(summary.id))
        assertEquals(exchange.messages.map(_.agentId), Chunk(AgentId("operator"), voiceId))
        assertEquals(run(store.readBlob(exchange.messages.head.payload.blobIds.head)).bytes, audio.bytes)
        assertEquals(exchange.messages.head.id, MessageId.from(exchange.messages.head.data))
        assertEquals(exchange.messages.head.parentId, None)
        assertEquals(exchange.messages.last.parentId, Some(exchange.messages.head.id))
        assertEquals(exchange.messages.last.payload, MessagePayload.Assistant("transcript"))
      }
      val resumed = submit(third, chatTurn, "/session resume ordinary-chat").state
      assertEquals(resumed.sessionId, chatTurn.sessionId)
      assertEquals(resumed.runContext.messages, chatTurn.runContext.messages)
      assertEquals(resumed.continuationStart, chatTurn.runContext.messages.size)
      assertEquals(run(store.loadSession(SessionId.unsafe("ordinary-chat"))), storedChatBefore)
      assertEquals(
        chatTurn.runContext.messages,
        Chunk(MessagePayload.User("ordinary prompt"), MessagePayload.Assistant("transcript"))
      )
    } finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }

  scopedTest("agent choices are sorted and lazy; selection works from unselected startup without changing config") {
    val current = startup(config, options.copy(agentId = None))
    val app = run(current.application)
    assertEquals(app.agentChoices.map(_.id), Vector(bob, eve))
    assertEquals(app.agentChoices.map(_.model), Vector(Some("local-model"), Some("account-model")))
    val changed = run(app.selectAgent(eve))
    assertEquals(changed.activeAgent.map(_.agentId), Some("eve"))
    assertEquals(changed.activeAgent.map(_.provider), Some("chatgpt-2"))
    assertEquals(changed.userId, AgentId("operator"))
    assertEquals(app.activeAgent, None)
    assertEquals(options.agentId, Some(bob))
    assertEquals(run(app.selectAgent(AgentId("missing")).either), Left(YallmoError.UnknownAgent(AgentId("missing"))))
    val invalid = config.copy(agents = config.agents.updated(eve, config.agents(eve).copy(config = Json.Obj())))
    val initial = run(startup(invalid).application)
    assert(run(initial.selectAgent(eve).either).isLeft)
    assertEquals(initial.activeAgent.map(_.agentId), Some("bob"))
  }

  scopedTest("agent selection logs INFO on success and WARNING on failure to repl.log with terminal logging disabled") {
    val root = Files.createTempDirectory("yallmo-selection-logs-")
    try {
      val application = run(startup().application)
      val selected = run {
        ZIO.scoped {
          for {
            _ <- ReplLoggers.initialize(root)
            _ <- (Runtime.removeDefaultLoggers >>> Runtime.addLogger(ReplLoggers.filesAt(root))).build
            _ <- ZIO.logLevelScoped(LogLevel.None)
            next <- application.selectAgent(eve)
            failure <- next.selectAgent(AgentId("missing")).either
          } yield {
            assertEquals(failure, Left(YallmoError.UnknownAgent(AgentId("missing"))))
            next
          }
        }
      }
      assertEquals(selected.activeAgent.map(_.agentId), Some("eve"))
      val lines = Files.readString(ReplLoggers.replFilePath(root)).linesIterator.toVector
      assertEquals(lines.size, 2)
      assert(lines(0).contains("level=INFO"), lines(0))
      assert(lines(0).contains("Agent selected user=operator agent=eve previous=bob"), lines(0))
      assert(lines(1).contains("level=WARN"), lines(1))
      assert(lines(1).contains("Agent selection failed user=operator agent=missing"), lines(1))
      assert(lines(1).contains(YallmoError.UnknownAgent(AgentId("missing")).getMessage), lines(1))
    } finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }

  scopedTest("switching updates requests and authors while continuing the same conversation and durable session") {
    val root = Files.createTempDirectory("yallmo-switch-")
    val changedConfig = config.copy(agents =
      config.agents.updated(
        eve,
        AgentConfig(
          "OAIAgent",
          Seq(local),
          Json.Obj("model" -> Json.Str("eve-model"), "systemPrompt" -> Json.Str("Eve prompt"))
        )
      )
    )
    val requests = scala.collection.mutable.ListBuffer.empty[ujson.Value]
    val client = fakeClient(incoming =>
      incoming.body.asString.map { body =>
        requests += ujson.read(body)
        Response.text("""{"choices":[{"message":{"role":"assistant","content":"answer"},"finish_reason":"stop"}]}""")
      }
    )
    try {
      val current = run(
        fixture(changedConfig, options, storageRoot = Some(root))
          .provideEnvironment(ZEnvironment(client))
      )
      val first = run(current.application)
      val named = submit(first, first.initialState, "/new before-switch").state
      val oldTurn = submit(first, named, "old prompt")
      val second = run(first.selectAgent(eve))
      val resumed = submit(second, oldTurn.state, "/session resume before-switch")
      assert(resumed.events.exists(_.isInstanceOf[ReplEvent.SessionResumed]))
      assertEquals(resumed.state.sessionId, oldTurn.state.sessionId)
      assertEquals(resumed.state.runContext.messages, oldTurn.state.runContext.messages)
      val newTurn = submit(second, resumed.state, "new prompt")
      assert(newTurn.events.exists(_.isInstanceOf[ReplEvent.AgentTurn]))
      assertEquals(requests.map(_("model").str).toList, List("local-model", "eve-model"))
      val secondMessages = requests(1)("messages").arr
      assert(secondMessages.exists(_("content").str == "Eve prompt"))
      assert(secondMessages.exists(_("content").str == "old prompt"))
      assertEquals(second.activeAgent.map(_.model), Some("eve-model"))
      val store = run(OrchestratorLive.filesystem(root, current.instances))
      val after = run(store.loadSession(SessionId.unsafe(newTurn.state.sessionId.get)))
      assertEquals(newTurn.state.sessionId, oldTurn.state.sessionId)
      assertEquals(newTurn.state.runContext.messages.take(2), oldTurn.state.runContext.messages)
      assertEquals(after.agentIds, Chunk(AgentId("operator"), bob, eve))
      assertEquals(after.messages.map(_.agentId), Chunk(AgentId("operator"), bob, AgentId("operator"), eve))
      assertEquals(run(store.listSessions).map(_.id.value), Chunk("before-switch"))
      assertEquals(second.transcriber, None)
      val returned = run(second.selectAgent(bob))
      val restored = submit(returned, returned.initialState, "/session resume before-switch")
      assert(restored.events.exists(_.isInstanceOf[ReplEvent.SessionResumed]))
      assertEquals(restored.state.runContext.messages, newTurn.state.runContext.messages)
      val history = submit(returned, restored.state, "/history")
      assertEquals(history.state, restored.state)
      assertEquals(history.events, Chunk(ReplEvent.Transcript(after.messages, Some(after.id.value))))
    } finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }

  scopedTest("a logged-out ChatGPT agent starts but cannot infer or invoke global login") {
    val current = startup(config, options.copy(agentId = Some(eve)))
    val application = run(current.application)
    assertEquals(application.activeAgent.map(_.agentId), Some("eve"))
    val before = ReplState()
    val failed = submit(application, before, "Hello")
    assert(failed.state.sessionId.nonEmpty)
    assertEquals(failed.state.runContext.messages, Chunk(MessagePayload.User("Hello")))
    val resumed = submit(application, before, s"/session resume ${failed.state.sessionId.get}")
    assertEquals(resumed.state.runContext.messages, failed.state.runContext.messages)
    assert(failed.events.collect { case ReplEvent.AgentFailed(message) => message }.exists(_.contains("logged out")))
    assert(submit(application, before, "/login browser").events.exists(_.isInstanceOf[ReplEvent.InputRejected]))
  }
}
