package fmgp.yallmo.repl.terminal

import fmgp.yallmo.repl.voice.CapturedAudio
import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.orchestration.config.{Config, OAuthCredentialsConfig, ProviderConfig, ProviderId}
import fmgp.yallmo.provider.openai.*
import fmgp.yallmo.repl.*
import fmgp.yallmo.repl.voice.{AudioRecorder, VoiceRecordingTermination}
import fmgp.yallmo.tool.{BashExecutor, GitClient, ScalaCodeExecutor, WorkspaceFileSystem}
import munit.FunSuite
import org.jline.reader.{LineReader, Reference, Widget}
import org.jline.terminal.Size
import org.jline.terminal.Attributes.LocalFlag
import java.util.concurrent.{CompletableFuture, TimeUnit}
import org.jline.terminal.impl.DumbTerminal
import org.jline.utils.{AttributedString, Status}
import zio.*
import zio.http.Client
import zio.json.*

import java.io.{ByteArrayOutputStream, PipedInputStream, PipedOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions

final class LineTerminalProviderIntegrationSuite extends FunSuite {
  private val esc = "\u001b"
  private val down = "\u001b[B"
  private def paste(text: String): String = "\u001b[200~" + text + "\u001b[201~"
  private val fixture = """{
    "userName":"synthetic-user",
    "providers": {
      "account":{"providerType":"ChatGPT","credentials":{
        "accessToken":"synthetic-access","refreshToken":"synthetic-refresh","expiresAtMillis":123}},
      "local":{"providerType":"OAI","endpoint":"https://user:synthetic-password@example.com/v1?key=synthetic-query#synthetic-fragment"}
    },
    "future":{"keep":[1,true,null]}
  }"""
  private val availability = ReplToolAvailability(Chunk.empty, Chunk.empty)
  private val tools = ZLayer
    .make[ReplTools.Environment](WorkspaceFileSystem.live, BashExecutor.live, GitClient.live, ScalaCodeExecutor.live)
  private val recorder = new AudioRecorder {
    def record(seconds: Int): Task[CapturedAudio] = ZIO.dieMessage("Menus must not record")
    def recordUntil(termination: UIO[VoiceRecordingTermination]): Task[Option[CapturedAudio]] =
      ZIO.dieMessage("Menus must not record")
  }

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def withConfig(text: String = fixture)(check: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("provider-integration-")
    val path = directory.resolve("synthetic.json")
    Files.writeString(path, text, UTF_8)
    try check(path)
    finally {
      val children = Files.list(directory)
      try children.forEach(file => { Files.deleteIfExists(file); () })
      finally children.close()
      Files.deleteIfExists(directory)
    }
  }

  private def application(
      catalog: ReplProviderCatalog,
      prompts: scala.collection.mutable.ListBuffer[String],
      login: Option[ProviderLoginService] = None,
      active: ReplActiveAgent = ReplActiveAgent("fixed-agent", "fixed-provider", "fixed-model", None),
      choices: Vector[ReplAgentChoice] = Vector.empty,
      selector: Option[AgentId => IO[YallmoError, ReplApplication[Any]]] = None,
      userId: AgentId = AgentId("synthetic-user")
  ) = {
    val agent = new Agent[Any] {
      val id = AgentId(active.agentId)
      def turn(
          request: fmgp.yallmo.model.llm.AgentTurnRequest
      ): IO[YallmoError, fmgp.yallmo.model.llm.AgentTurnResponse] = ZIO.dieMessage("Unexpected provider call")
    }
    val user = new Agent[Any] {
      val id: AgentId = userId
      def turn(
          request: fmgp.yallmo.model.llm.AgentTurnRequest
      ): IO[YallmoError, fmgp.yallmo.model.llm.AgentTurnResponse] =
        ZIO.dieMessage("The user must not invoke a provider")
    }
    val instances = run(RuntimeInstances.fromAgents(Chunk(user, agent)))
    val orchestrator = run(OrchestratorLive.inMemory(instances))
    new ReplApplication[Any](
      new ReplSession[Any](
        new AgentRunner[Any](Map.empty, Map.empty),
        Some(new AgentInstance[Any] {
          val id = agent.id
          def next(task: AgentTask, context: StaticContext[Any], runContext: RunContext): IO[YallmoError, AgentTurn] =
            ZIO.succeed {
              prompts += task.prompt
              AgentTurn(Some(Message.Assistant("ok")), AgentDecision.FinalAnswer("ok"))
            }
        }),
        StaticContext[Any]("synthetic")
      ),
      availability,
      ReplOrchestratorLive.make(orchestrator, user, Some(agent)),
      activeAgent = Some(active),
      providerCatalog = Some(catalog),
      providerLogin = login,
      agentChoices = choices,
      agentSelector = selector,
      userId = userId
    )
  }

  private final case class Observation(
      output: String,
      history: List[String],
      restored: List[(String, Int)],
      displays: List[String]
  )

  /** Runs the real terminal loop; only the catalog's file and the history destination are synthetic. */
  private def interact[R](
      app: ReplApplication[R],
      keys: String,
      onReady: (StatusLineReader, String => Unit) => Unit = (_, _) => (),
      output: ByteArrayOutputStream = new ByteArrayOutputStream()
  )(
      provide: ZIO[R, Throwable, Unit] => Task[Unit]
  ): Observation = {
    val pipe = new PipedInputStream(65536)
    val writer = new PipedOutputStream(pipe)
    def send(text: String): Unit = { writer.write(text.getBytes(UTF_8)); writer.flush() }
    send("idraft" + esc + "hp" + keys)
    val terminal = new DumbTerminal(
      "test",
      "xterm",
      pipe,
      output,
      UTF_8
    )
    terminal.setSize(new Size(160, 24))
    val historyFile = Files.createTempFile("provider-integration-history-", ".txt")
    val variables = new java.util.HashMap[String, Object]()
    variables.put(LineReader.AMBIGUOUS_BINDING, java.lang.Long.valueOf(50L))
    variables.put(LineReader.HISTORY_FILE, historyFile)
    val reader = new StatusLineReader(terminal, variables)
    reader.getKeyMaps.put(LineReader.MAIN, reader.getKeyMaps.get(LineReader.VIINS))
    val client = new TerminalRepl(app, reader, recorder)
    val restored = scala.collection.mutable.ListBuffer.empty[(String, Int)]
    val displays = scala.collection.mutable.ListBuffer.empty[String]
    def displayed: String = reader.getDisplayedBufferWithPrompts(new java.util.ArrayList[AttributedString]()).toString
    for name <- List("up", "down", "enter", "back", "cancel", "paste") do {
      val widgetName = s"${TerminalProviderMode.KeyMapName}-$name"
      val original = reader.getWidgets.get(widgetName)
      reader.getWidgets.put(
        widgetName,
        new Widget {
          def apply(): Boolean = {
            val result = original.apply()
            displays += displayed
            result
          }
        }
      )
    }
    reader.getWidgets.put(
      "test-resize-provider",
      new Widget {
        def apply(): Boolean = {
          assertEquals("PROVIDER — configured providers".r.findAllIn(output.toString(UTF_8)).size, 1)
          assertEquals("Catalog only:".r.findAllIn(output.toString(UTF_8)).size, 1)
          terminal.setSize(new Size(42, 12))
          terminal.raise(org.jline.terminal.Terminal.Signal.WINCH)
          assertEquals(Status.getStatus(terminal, false).size(), 1)
          val rows = displayed.linesIterator.toVector.drop(1)
          assert(rows.size <= reader.availablePostRows)
          assert(rows.exists(_.startsWith("> ")))
          assert(rows.forall(new AttributedString(_).columnLength(terminal) <= 42))
          displays += displayed
          true
        }
      }
    )
    reader.getKeyMaps
      .get(TerminalProviderMode.KeyMapName)
      .bind(new Reference("test-resize-provider"), "!")
    val existing = reader.getWidgets.get(LineReader.CALLBACK_INIT)
    reader.getWidgets.put(
      LineReader.CALLBACK_INIT,
      new Widget {
        def apply(): Boolean = {
          val result = existing.apply()
          if reader.getKeyMap == TerminalProviderMode.KeyMapName then {
            restored += ((reader.getBuffer.toString, reader.getBuffer.cursor()))
            displays += displayed
          }
          result
        }
      }
    )
    onReady(reader, send)
    try {
      run(provide(client.run.timeoutFail(new Exception("integration timeout"))(5.seconds)))
      val history = scala.collection.mutable.ListBuffer.empty[String]
      val iterator = reader.getHistory.iterator()
      while iterator.hasNext do history += iterator.next().line()
      val persisted = Files.readString(historyFile, UTF_8)
      for line <- history do assert(persisted.contains(line))
      for privateText <- List(
          "synthetic-password",
          "synthetic-query",
          "synthetic-fragment",
          "new-local",
          "new-account",
          "cancelled-id",
          "invalid-url"
        )
      do assert(!persisted.contains(privateText), privateText)
      assert(restored.nonEmpty)
      assert(restored.forall(_ == (("draft", 3))), restored.toString)
      assert(!displayed.contains("PROVIDER"))
      Observation(output.toString(UTF_8), history.toList, restored.toList, displays.toList)
    } finally {
      writer.close()
      terminal.close()
      Files.deleteIfExists(historyFile)
    }
  }

  private def selected(path: Path, keys: String): Observation = {
    val prompts = scala.collection.mutable.ListBuffer.empty[String]
    val app = application(run(ReplProviderCatalog.make(path)), prompts)
    val before = app.activeAgent
    val result = interact(app, keys)(identity)
    assertEquals(prompts.toList, List("draXft"))
    assertEquals(result.history, List("draXft", "/exit"))
    assertEquals(app.activeAgent, before)
    result
  }

  private val resume = esc + "iX\n/exit\n"
  private val newOai = down + down + "\n\n"

  test("real terminal loop redraws arrows in place, resizes alongside the footer, and clears on exit") {
    withConfig() { path =>
      val result = selected(path, (down + "\u001b[A") * 20 + "!" + resume)
      // WINCH may repaint the live area once; the 40 navigation keys must not append menu blocks.
      assertEquals("PROVIDER — configured providers".r.findAllIn(result.output).size, 2)
      assert("Catalog only:".r.findAllIn(result.output).size <= 2)
      assert(result.displays.count(_.contains("PROVIDER")) >= 40)
      assert(result.displays.exists(_.linesIterator.exists(_.startsWith("> account [ChatGPT]"))))
      assert(result.output.contains("fixed-agent"))
    }
  }

  test("AGENTS resolution failure retains the old runtime; selecting the current agent is a no-op") {
    for fail <- List(true, false) do
      withConfig() { path =>
        val catalog = run(ReplProviderCatalog.make(path))
        val prompts = scala.collection.mutable.ListBuffer.empty[String]
        var attempts = 0
        val choices = Vector(
          ReplAgentChoice(AgentId("fixed-agent"), "OAIAgent", Vector("fixed-provider"), "fixed-model"),
          ReplAgentChoice(AgentId("broken"), "OAIAgent", Vector("missing"), "invalid")
        )
        val initial = application(
          catalog,
          prompts,
          choices = choices,
          selector = Some(_ => {
            attempts += 1
            ZIO.fail(YallmoError.InvalidConfig("Invalid agent configuration"))
          })
        )
        val keys = esc + "a" + (if fail then down else "") + "\n" + (if fail then esc else "") + "iX\n/exit\n"
        val result = interact(initial, keys)(identity)
        assertEquals(attempts, if fail then 1 else 0)
        assertEquals(prompts.toList, List("draXft"))
        if fail then assert(result.output.contains("Selection failed"))
      }
  }

  test("NORMAL p lists all providers, real arrows inspect safely, AGENTS and editing preserve exact draft") {
    withConfig() { path =>
      val before = Files.readString(path)
      val result = selected(path, down + "\u001bOA" + down + "\n" + esc + esc + "aiX\n/exit\n")
      assert(result.output.contains("account [ChatGPT]"))
      assert(result.output.contains("local [OAI]"))
      assert(result.output.contains("PROVIDER configuration"))
      assert(result.output.contains("https://example.com/v1"))
      assert(result.output.contains("selected: fixed-agent"))
      for secret <- List(
          "synthetic-password",
          "synthetic-query",
          "synthetic-fragment",
          "synthetic-access",
          "synthetic-refresh"
        )
      do assert(!result.output.contains(secret), secret)
      assertEquals(Files.readString(path), before)
    }
  }

  test("bracketed URL paste and explicit OAI save publish private config without chat, history or secret leakage") {
    withConfig() { path =>
      val url = "https://user:synthetic-password@example.net/v1?key=synthetic-query#synthetic-fragment"
      val result =
        selected(path, newOai + "new-localX\u007f\n" + paste(url) + "\n\n" + down + down + "\n" + esc + resume)
      val config = Files.readString(path).fromJson[Config].toOption.get
      assertEquals(config.providers(ProviderId.unsafe("new-local")), ProviderConfig.OAI(url))
      assertEquals(config.providers.size, 3)
      assert(Files.readString(path).contains("synthetic-refresh"))
      assert(Files.readString(path).contains("future"))
      assertEquals(Files.getPosixFilePermissions(path), PosixFilePermissions.fromString("rw-------"))
      assert(result.output.contains("new-local [OAI]"))
      assert(result.displays.exists(_.contains("Base URL: [hidden]")))
      assert(result.output.contains("active agent/runtime unchanged"))
      for secret <- List("synthetic-password", "synthetic-query", "synthetic-fragment") do
        assert(!result.output.contains(secret))
    }
  }

  test("multiline ID and confirmation bracketed pastes cannot publish config or submit form text") {
    for (form, payload) <- List(
        newOai -> "my-id\nhttps://example.com\n\n",
        (newOai + "new-local\nhttps://example.net\n") -> "\n\n"
      )
    do
      withConfig() { path =>
        val before = Files.readString(path)
        selected(path, form + paste(payload) + "\u0003" + resume)
        assertEquals(Files.readString(path), before)
      }
  }

  test(
    "unselected startup passes explicit config catalog through application; ChatGPT creation leaves runtime unchanged"
  ) {
    withConfig("""{"userName":"synthetic-user"}""") { path =>
      val original = Files.readString(path).fromJson[Config].toOption.get
      val startup = run(ReplStartup.make(original, ReplLaunchOptions(path)).provideLayer(Client.default))
      val app = run(startup.application(Chunk.empty, availability))
      assert(app.providerCatalog.contains(startup.providerCatalog))
      assert(app.providerLogin.contains(startup.providerLogin))
      val result = interact(app, "\n" + down + "\nnew-account\n\n" + resume)(_.provideLayer(tools))
      assertEquals(result.history, List("draXft", "/exit"))
      assertEquals(startup.selected, None)
      assertEquals(app.activeAgent, None)
      assert(run(startup.instances.getProvider(ProviderId.unsafe("new-account")).either).isLeft)
      assertEquals(
        Files.readString(path).fromJson[Config].toOption.get.providers(ProviderId.unsafe("new-account")),
        ProviderConfig.ChatGPT()
      )
      assert(result.output.contains("new-account [ChatGPT]"))
      assert(result.output.contains("Credentials: logged out"))
    }
  }

  test("Esc backtracking and Ctrl-C abandon local forms without config, history or agent leakage") {
    for cancel <- List(esc + esc + esc + esc, "\u0003") do
      withConfig() { path =>
        val before = Files.readString(path)
        selected(path, newOai + "cancelled-id\nhttps://example.net\n" + cancel + resume)
        assertEquals(Files.readString(path), before)
      }
  }

  test("invalid endpoint keeps confirmation draft for correction and a successful retry") {
    withConfig() { path =>
      val result = selected(
        path,
        newOai + "new-local\ninvalid-url\n\n" + esc + ("\u007f" * "invalid-url".length) + "https://example.net/v1\n\n" + resume
      )
      assert(result.output.contains("Base URL must be an absolute HTTP(S) URL"))
      assert(result.output.contains("new-local [OAI]"))
      assertEquals(
        Files.readString(path).fromJson[Config].toOption.get.providers(ProviderId.unsafe("new-local")),
        ProviderConfig.OAI("https://example.net/v1")
      )
    }
  }

  test("scoped browser login saves only the arrow-selected ChatGPT profile and returns to its details") {
    for target <- List("account", "second") do profileLogin(target, "saved")
  }

  test("scoped cancellation and authentication failure keep exact draft cursor history and file") {
    for outcome <- List("cancelled", "failed") do profileLogin("second", outcome)
  }

  private def profileLogin(target: String, outcome: String): Unit = {
    val text = fixture.replace("\"local\":", "\"second\":{\"providerType\":\"ChatGPT\"},\"local\":")
    withConfig(text) { path =>
      val before = Files.readString(path)
      val catalog = run(ReplProviderCatalog.make(path))
      val prompts = scala.collection.mutable.ListBuffer.empty[String]
      val calls = scala.collection.mutable.ListBuffer.empty[String]
      var send: String => Unit = _ => fail("input not ready")
      var readingDisplay: () => String = () => ""
      val callback = "http://localhost:1455/auth/callback?state=synthetic&code=private-code"
      val credentials = OAuthCredentialsConfig("new-access", "new-refresh", 1000)
      val flow = new OpenAiCodexAuth.OAuthFlow {
        def startBrowserLogin: Task[OpenAiCodexBrowserLogin] = ZIO.succeed {
          assert(!readingDisplay().contains("PROVIDER"), "private input must not coexist with stale menu")
          calls += "browser-start"
          send(if outcome == "cancelled" then "\n" else callback + "\n")
          OpenAiCodexBrowserLogin("https://auth.example/authorize", "synthetic", "private-verifier")
        }
        def completeBrowserLogin(login: OpenAiCodexBrowserLogin, redirect: String): Task[OAuthCredentialsConfig] = {
          assertEquals(redirect, callback)
          calls += "browser-complete"
          if outcome == "failed" then ZIO.fail(new Exception("private-code new-access"))
          else ZIO.succeed(credentials)
        }
      }
      val app = application(catalog, prompts, Some(new ProviderLoginService(catalog, flow)))
      val active = app.activeAgent
      val selection = if target == "second" then down + down else ""
      var resultDisplay = ""
      val result = interact(
        app,
        selection + "\n\n",
        (reader, write) => {
          send = write
          readingDisplay =
            () => reader.getDisplayedBufferWithPrompts(new java.util.ArrayList[AttributedString]()).toString
          val init = reader.getWidgets.get(LineReader.CALLBACK_INIT)
          reader.getWidgets.put(
            LineReader.CALLBACK_INIT,
            new Widget {
              def apply(): Boolean = {
                val value = init.apply()
                val display = readingDisplay()
                if display.contains("Profile login ") || display.contains("Credentials saved to this profile") then {
                  resultDisplay = display
                  send(esc + resume)
                }
                value
              }
            }
          )
        }
      )(identity)
      assert(resultDisplay.contains(s"ID: $target"))
      assert(resultDisplay.contains(if outcome == "saved" then "Restart required" else s"Profile login $outcome"))
      assert(resultDisplay.contains(if outcome == "saved" || target == "account" then "Credentials: present"
      else "Credentials: logged out"))
      for secret <- List("private-code", "new-access", "new-refresh", "private-verifier") do
        assert(!result.output.contains(secret), secret)
      assertEquals(result.history, List("draXft", "/exit"))
      assertEquals(prompts.toList, List("draXft"))
      assertEquals(app.activeAgent, active)
      assert(calls.contains("browser-start"))
      if outcome == "saved" then {
        val original = before.fromJson[Config].toOption.get
        val saved = Files.readString(path).fromJson[Config].toOption.get
        assertEquals(
          saved.providers(ProviderId.unsafe(target)),
          ProviderConfig.ChatGPT(credentials = Some(OAuthCredentialsConfig("new-access", "new-refresh", 1000)))
        )
        assertEquals(
          saved.providers.removed(ProviderId.unsafe(target)),
          original.providers.removed(ProviderId.unsafe(target))
        )
        assert(Files.readString(path).contains("future"))
        assertEquals(Files.getPosixFilePermissions(path), PosixFilePermissions.fromString("rw-------"))
      } else assertEquals(Files.readString(path), before)
    }
  }

  test("profile timeout keeps partial suffix private before catalog redraw and resumes clean help input") {
    withConfig() { path =>
      val cleanup = new CompletableFuture[Unit]()
      val output = new ByteArrayOutputStream() {
        override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = synchronized {
          super.write(bytes, offset, length)
          if toString(UTF_8).contains("Discarding unfinished login input") then cleanup.complete(())
        }
      }
      var send: String => Unit = _ => fail("input not ready")
      var currentReader: StatusLineReader = null
      val flow = new OpenAiCodexAuth.OAuthFlow {
        private def partial[A]: Task[A] = ZIO.succeed(send("partial-private")) *> ZIO.never
        def startBrowserLogin = partial[OpenAiCodexBrowserLogin]
        def completeBrowserLogin(login: OpenAiCodexBrowserLogin, redirect: String) = ZIO.never
      }
      val before = Files.readString(path)
      val catalog = run(ReplProviderCatalog.make(path))
      val prompts = scala.collection.mutable.ListBuffer.empty[String]
      val app = application(catalog, prompts, Some(new ProviderLoginService(catalog, flow, 1.second)))
      val result = interact(
        app,
        "\n\n",
        (reader, write) => {
          currentReader = reader
          send = write
          val initial = reader.getTerminal.getAttributes
          initial.setLocalFlag(LocalFlag.ECHO, true)
          reader.getTerminal.setAttributes(initial)
          val init = reader.getWidgets.get(LineReader.CALLBACK_INIT)
          reader.getWidgets.put(
            LineReader.CALLBACK_INIT,
            new Widget {
              def apply(): Boolean = {
                val value = init.apply()
                val display =
                  reader.getDisplayedBufferWithPrompts(new java.util.ArrayList[AttributedString]()).toString
                if display.contains("Profile login timed out") then send(esc + esc + "iX\n/help\n/exit\n")
                value
              }
            }
          )
        },
        output
      ) { loop =>
        for {
          suffix <- (ZIO.attemptBlocking(cleanup.get(5, TimeUnit.SECONDS)) *> ZIO.attempt {
            val echo = currentReader.getTerminal.getAttributes.getLocalFlag(LocalFlag.ECHO)
            val history = currentReader.getVariable(LineReader.DISABLE_HISTORY)
            val remainder = "-private-suffix\n"
            // Model kernel echo at suffix arrival, rather than trusting DumbTerminal's output behavior.
            if echo then output.write(remainder.getBytes(UTF_8))
            send(remainder)
            assert(!echo)
            assertEquals(history, java.lang.Boolean.TRUE)
          }).fork
          _ <- loop
          _ <- suffix.join
        } yield ()
      }
      assertEquals(result.history, List("draXft", "/help", "/exit"))
      assertEquals(prompts.toList, List("draXft"))
      assert(!result.output.contains("private-suffix"))
      assertEquals(Files.readString(path), before)
    }
  }

  test("OAI details and list keys cannot dispatch auth; scoped bracketed paste cannot login") {
    withConfig() { path =>
      val before = Files.readString(path)
      val prompts = scala.collection.mutable.ListBuffer.empty[String]
      val catalog = run(ReplProviderCatalog.make(path))
      var calls = 0
      val flow = new OpenAiCodexAuth.OAuthFlow {
        private def unexpected[A]: Task[A] = ZIO.succeed { calls += 1 } *> ZIO.dieMessage("unexpected login")
        def startBrowserLogin = unexpected[OpenAiCodexBrowserLogin]
        def completeBrowserLogin(login: OpenAiCodexBrowserLogin, redirect: String) = unexpected[OAuthCredentialsConfig]
      }
      val app = application(catalog, prompts, Some(new ProviderLoginService(catalog, flow)))
      val result = interact(app, "ld" + down + "\nld\n" + esc + "\n" + paste("ld\n" + down) + esc + resume)(identity)
      assertEquals(calls, 0)
      assert(!result.displays.head.contains("Login"))
      assert(!result.output.contains("legacy/global"))
      assertEquals(Files.readString(path), before)
      assertEquals(result.history, List("draXft", "/exit"))
    }
  }

  test("duplicate save can be cancelled; list login keys do nothing") {
    for login <- List("l", "d") do
      withConfig() { path =>
        val before = Files.readString(path)
        val result = selected(path, newOai + "local\nhttps://example.net\n\n\u0003" + login + resume)
        assert(result.output.contains("Provider ID already exists"))
        assert(!result.output.contains("legacy/global"))
        assertEquals(Files.readString(path), before)
      }
  }
}
