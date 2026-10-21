package fmgp.yallmo.repl

import fmgp.yallmo.repl.voice.CapturedAudio
import fmgp.yallmo.model.{AgentId, AudioFormat, MessageId, MessagePayload, YallmoError}
import fmgp.yallmo.model.llm.ThinkingLevel
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.orchestration.config.{
  AgentConfig,
  AgentCapabilities,
  Config,
  ConfigFile,
  OAuthCredentialsConfig,
  ProviderConfig,
  ProviderId
}
import fmgp.yallmo.provider.openai.OpenAiCodexClient
import fmgp.yallmo.tool.{BashExecutor, GitClient, WorkspaceFileSystem}
import fmgp.yallmo.repl.voice.Transcriber
import munit.FunSuite
import zio.*
import zio.http.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.{Base64, Comparator}

final class ReplStartupSuite extends FunSuite {
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private val tools = ZLayer.make[ReplTools.Environment](WorkspaceFileSystem.live, BashExecutor.live, GitClient.live)
  private val availability = ReplToolAvailability(Chunk.empty, Chunk.empty)
  private val options = ReplLaunchOptions(Paths.get("yallmo.json"))
  private val bob = AgentId("bob")
  private val eve = AgentId("eve")
  private val local = ProviderId.unsafe("local-1")
  private val account = ProviderId.unsafe("chatgpt-2")

  private val config = Config(
    "operator",
    defaultAgentId = Some(bob),
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

  private def startup(definition: Config = config, launch: ReplLaunchOptions = options): ReplStartup =
    run(ReplStartup.make(definition, launch).provideEnvironment(ZEnvironment(noNetwork)))

  private def submit(app: ReplApplication[ReplTools.Environment], state: ReplState, line: String): ReplTransition =
    run(app.submit(state, line).provideLayer(tools))

  test("empty configuration starts unselected, retains the human identity and rejects prompts without changing state") {
    val current = startup(Config("operator"))
    val application = run(current.application(Chunk.empty, availability))
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

  test(
    "CLI selection overrides defaultAgentId; a missing selection stays unselected and never chooses the first agent"
  ) {
    assertEquals(startup().selected.map(_.adapter.id), Some(bob))
    val overridden = startup(config, options.copy(agentId = Some(eve)))
    assertEquals(overridden.selected.map(_.adapter.id), Some(eve))
    assertEquals(overridden.selected.map(_.feedback.provider), Some("chatgpt-2"))
    assertEquals(overridden.selected.map(_.systemPrompt), Some(ReplStartup.DefaultSystemPrompt))
    assertEquals(startup(config.copy(defaultAgentId = None)).selected, None)
    val missing = run(
      ReplStartup
        .make(config, options.copy(agentId = Some(AgentId("missing"))))
        .provideEnvironment(ZEnvironment(noNetwork))
        .either
    )
    assertEquals(missing, Left(YallmoError.UnknownAgent(AgentId("missing"))))
  }

  test("selected agent settings validate without leaking values or initializing unrelated providers") {
    val bad = Seq(
      Json.Obj(),
      Json.Obj("model" -> Json.Str(" ")),
      Json.Obj("model" -> Json.Str("ok"), "systemPrompt" -> Json.Str(" ")),
      Json.Obj("model" -> Json.Str("ok"), "thinkingLevel" -> Json.Str("secret-placeholder")),
      Json.Obj("model" -> Json.Bool(false))
    )
    bad.foreach { fields =>
      val definition = config.copy(agents = config.agents.updated(bob, config.agents(bob).copy(config = fields)))
      val result = run(ReplStartup.make(definition, options).provideEnvironment(ZEnvironment(noNetwork)).either)
      assert(result.isLeft)
      assert(!result.swap.toOption.get.getMessage.contains("secret-placeholder"))
    }
    val unrelatedInvalid = config.copy(providers =
      config.providers.updated(account, ProviderConfig.ChatGPT(endpoint = Some("https://untrusted.invalid")))
    )
    assertEquals(startup(unrelatedInvalid).selected.map(_.adapter.id), Some(bob))
  }

  test("operator IDs cannot collide with configured agents") {
    val result = run(
      ReplStartup
        .make(config.copy(userName = "bob"), options)
        .provideEnvironment(ZEnvironment(noNetwork))
        .either
    )
    assert(result.swap.toOption.exists(_.isInstanceOf[YallmoError.InvalidConfig]))
  }

  test(
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
        ReplStartup
          .make(config, options.copy(storageRoot = Some(root)))
          .provideEnvironment(ZEnvironment(client))
      )
      val application = run(current.application(Chunk.empty, availability))
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

  test("ChatGPTAgent sends its configured systemPrompt as instructions on every turn") {
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
      ReplStartup.make(definition, options.copy(agentId = Some(eve))).provideEnvironment(ZEnvironment(client))
    )
    val application = run(current.application(Chunk.empty, availability))
    val first = submit(application, application.initialState, "Hello")
    assert(first.events.exists(_.isInstanceOf[ReplEvent.AgentTurn]))
    val second = submit(application, first.state, "Hello again")
    assert(second.events.exists(_.isInstanceOf[ReplEvent.AgentTurn]))
  }

  test("a missing --config file initializes and composes an unselected REPL startup") {
    val root = Files.createTempDirectory("yallmo-new-config-")
    val path = root.resolve("yallmo.json")
    try {
      val launch = ReplLaunchOptions.parse(Chunk("--config", path.toString)).toOption.get
      val current = run {
        for {
          definition <- ConfigFile.loadOrInitialize(launch.configPath, "new-operator")
          initialized <- ReplStartup.make(definition, launch).provideEnvironment(ZEnvironment(noNetwork))
        } yield initialized
      }
      assert(Files.exists(path))
      assertEquals(current.userAgent.id, AgentId("new-operator"))
      assertEquals(current.selected, None)
      val application = run(current.application(Chunk.empty, availability))
      assertEquals(application.activeAgent, None)
      assert(submit(application, ReplState(), "Hello").events.exists(_.isInstanceOf[ReplEvent.AgentFailed]))
    } finally {
      Files.deleteIfExists(path)
      Files.deleteIfExists(root)
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
    config.copy(transcriberAgent = Some(voiceId), agents = config.agents.updated(voiceId, voiceDefinition))

  private def configuredVoice(
      definition: Config,
      available: StaticContext[Any]
  ): IO[YallmoError, Option[Transcriber]] =
    for {
      instances <- RuntimeInstances.make(definition).provideEnvironment(ZEnvironment(noNetwork))
      orchestrator <- OrchestratorLive.inMemory(instances)
      voice <- ReplStartup.voice(definition, instances, available, orchestrator, AgentId("operator"))
    } yield voice

  test("REPL voice rejects invalid settings and unsupported agent/provider pairs without network or auth") {
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
      assert(error.getMessage.contains("transcriberAgent"))
      assert(error.getMessage.contains("Transcriber"))
      assert(!error.getMessage.contains("refresh-"))
    }
    val missing = run(
      configuredVoice(voiceConfig.copy(transcriberAgent = Some(AgentId("missing"))), StaticContext[Any]("chat")).either
    ).swap.toOption.get
    assert(missing.getMessage.contains("missing"))
    assertEquals(run(configuredVoice(config, StaticContext[Any]("chat"))), None)
    assert(run(configuredVoice(voiceConfig, StaticContext[Any]("chat"))).nonEmpty)
  }

  test("REPL voice checks effective empty capabilities and inherited fields in partial policies") {
    val tool = StaticContext[Any]("chat", tools = Chunk(AgentToolDefinition("shell", "commands")))
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
    assert(check(AgentCapabilities(tools = Some(Set("shell"))), tool).isLeft)
    assert(
      check(AgentCapabilities(tools = Some(Set("unknown"))), tool).swap.toOption.get.getMessage.contains("unknown")
    )
    assert(check(AgentCapabilities(tools = Some(Set.empty)), tool).isRight)
    assert(check(AgentCapabilities(), StaticContext[Any]("chat")).isRight)
  }

  test("tracked secret-free example loads and resolves its explicit transcriber locally") {
    val example = run(ConfigFile.loadOrInitialize(Paths.get("docs/examples/repl-config.json"), "unused"))
    val app = run(startup(example).application(Chunk.empty, availability))
    assert(app.transcriber.nonEmpty)
    assertEquals(example.transcriberAgent, Some(voiceId))
    assert(app.agentChoices.exists(_.id == voiceId))
  }

  test("voice is opt-in and invalid explicit transcribers fail locally during application startup") {
    assertEquals(run(startup().application(Chunk.empty, availability)).transcriber, None)
    assertEquals(run(startup(Config("operator")).application(Chunk.empty, availability)).transcriber, None)
    val invalid = List(
      voiceConfig.copy(transcriberAgent = Some(AgentId("missing"))),
      voiceConfig.copy(transcriberAgent = Some(eve)),
      voiceConfig.copy(agents =
        voiceConfig.agents.updated(voiceId, voiceDefinition.copy(config = Json.Obj("model" -> Json.Str("audio"))))
      )
    )
    invalid.foreach { definition =>
      val result = run(startup(definition).application(Chunk.empty, availability).either)
      assert(result.left.toOption.exists(_.getMessage.contains("transcriberAgent")))
    }
    val invalidSelected = voiceConfig.copy(
      defaultAgentId = Some(voiceId),
      agents = voiceConfig.agents.updated(
        voiceId,
        voiceDefinition.copy(config = Json.Obj("model" -> Json.Str(" "), "systemPrompt" -> Json.Str("transcribe")))
      )
    )
    val selectedFailure =
      run(ReplStartup.make(invalidSelected, options).provideEnvironment(ZEnvironment(noNetwork)).either)
    assert(
      selectedFailure.left.toOption.exists(error =>
        error.getMessage.contains("Transcriber") && error.getMessage.contains("model")
      )
    )
    val available = run(ReplTools.evaluate.provideLayer(tools))
    val inherited = voiceConfig.copy(agents =
      voiceConfig.agents.updated(voiceId, voiceDefinition.copy(capabilities = AgentCapabilities()))
    )
    val rejected = run(startup(inherited).application(available.available, available.availability).either)
    assert(rejected.left.toOption.exists(_.getMessage.contains("capabilities")))
    val typo = config.copy(agents =
      config.agents
        .updated(eve, config.agents(eve).copy(capabilities = AgentCapabilities(tools = Some(Set("unknown-tool")))))
    )
    assert(run(startup(typo).application(Chunk.empty, availability).either).isLeft)
  }

  test(
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
        ReplStartup
          .make(voiceConfig.copy(defaultAgentId = None), options.copy(storageRoot = Some(root)))
          .provideEnvironment(ZEnvironment(client))
      )
      val first = run(current.application(Chunk.empty, availability))
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

  test("agent choices are sorted and lazy; selection works from unselected startup without changing config") {
    val current = startup(config.copy(defaultAgentId = None))
    val app = run(current.application(Chunk.empty, availability))
    assertEquals(app.agentChoices.map(_.id), Vector(bob, eve))
    assertEquals(app.agentChoices.map(_.model), Vector("local-model", "account-model"))
    val changed = run(app.selectAgent(eve))
    assertEquals(changed.activeAgent.map(_.agentId), Some("eve"))
    assertEquals(changed.activeAgent.map(_.provider), Some("chatgpt-2"))
    assertEquals(changed.userId, AgentId("operator"))
    assertEquals(app.activeAgent, None)
    assertEquals(config.defaultAgentId, Some(bob))
    assertEquals(run(app.selectAgent(AgentId("missing")).either), Left(YallmoError.UnknownAgent(AgentId("missing"))))
    val invalid = config.copy(agents = config.agents.updated(eve, config.agents(eve).copy(config = Json.Obj())))
    val initial = run(startup(invalid).application(Chunk.empty, availability))
    assert(run(initial.selectAgent(eve).either).isLeft)
    assertEquals(initial.activeAgent.map(_.agentId), Some("bob"))
  }

  test("switching updates requests and authors while continuing the same conversation and durable session") {
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
        ReplStartup
          .make(changedConfig, options.copy(storageRoot = Some(root)))
          .provideEnvironment(ZEnvironment(client))
      )
      val first = run(current.application(Chunk.empty, availability))
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

  test("a logged-out ChatGPT agent starts but cannot infer or invoke global login") {
    val current = startup(config, options.copy(agentId = Some(eve)))
    val application = run(current.application(Chunk.empty, availability))
    assertEquals(application.activeAgent.map(_.agentId), Some("eve"))
    val before = ReplState()
    val failed = submit(application, before, "Hello")
    assertEquals(failed.state, before)
    assert(failed.events.collect { case ReplEvent.AgentFailed(message) => message }.exists(_.contains("logged out")))
    assert(submit(application, before, "/login browser").events.exists(_.isInstanceOf[ReplEvent.InputRejected]))
  }
}
