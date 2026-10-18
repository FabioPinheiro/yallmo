package fmgp.yallmo.repl

import fmgp.yallmo.model.{AgentId, MessagePayload, YallmoError}
import fmgp.yallmo.model.llm.{AudioFormat, AudioInput, ThinkingLevel}
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.orchestration.config.{
  AgentConfig,
  Config,
  ConfigFile,
  OAuthCredentialsConfig,
  ProviderConfig,
  ProviderId
}
import fmgp.yallmo.provider.openai.OpenAiCodexClient
import fmgp.yallmo.tool.{BashExecutor, GitClient, WorkspaceFileSystem}
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
    assertEquals(overridden.selected.map(_.systemPrompt), Some(ReplAgentSettings.DefaultSystemPrompt))
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

  test("voice uses only the selected audio-capable connection/model") {
    val client = fakeClient(incoming =>
      incoming.body.asString.flatMap { body =>
        ZIO.attempt {
          assertEquals(incoming.url.toString, "http://localhost:12434/v1/chat/completions")
          assertEquals(ujson.read(body)("model").str, "local-model")
          Response.text("""{"choices":[{"message":{"content":"transcript"}}]}""")
        }
      }
    )
    val current = run(ReplStartup.make(config, options).provideEnvironment(ZEnvironment(client)))
    val audio = AudioInput(Chunk[Byte](1, 2), AudioFormat.Wav)
    assertEquals(run(current.transcriber.flatMap(_.transcribe(audio))), "transcript")
    val subscription = startup(config, options.copy(agentId = Some(eve)))
    assert(run(subscription.transcriber.flatMap(_.transcribe(audio)).either).isLeft)
    val unselected = startup(Config("operator"))
    assert(run(unselected.transcriber.flatMap(_.transcribe(audio)).either).isLeft)
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
      assertEquals(run(second.transcriber.get.transcribe(AudioInput(Chunk[Byte](1, 2), AudioFormat.Wav))), "answer")
      assertEquals(requests.last("model").str, "eve-model")
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
