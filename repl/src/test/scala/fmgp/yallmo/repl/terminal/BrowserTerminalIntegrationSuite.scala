package fmgp.yallmo.repl.terminal

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.{AudioFormat, AudioInput, LlmProviderAudio}
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.provider.Transcriber
import fmgp.yallmo.repl.*
import fmgp.yallmo.repl.browser.{BrowserAudioBridge, BrowserAudioError}
import fmgp.yallmo.repl.voice.{AudioRecorder, VoiceRecordingTermination}
import java.io.{ByteArrayOutputStream, PipedInputStream, PipedOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{CompletableFuture, CountDownLatch, LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import munit.FunSuite
import org.jline.reader.{LineReader, Widget}
import org.jline.terminal.Size
import org.jline.terminal.impl.DumbTerminal
import zio.*

/** Real JLine loop and HTTP server, with synthetic recorders, transcription and browser acknowledgements. */
final class BrowserTerminalIntegrationSuite extends FunSuite {
  // Tests use isolated ephemeral ports; the REPL defaults to 5180.
  private def makeBridge: ZIO[Scope, BrowserAudioError, BrowserAudioBridge] =
    BrowserAudioBridge.start(zio.http.Server.Config.default.binding("127.0.0.1", 0))

  private val esc = "\u001b"
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
  private def eventually(check: => Boolean): Unit = {
    val deadline = java.lang.System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
    while !check && java.lang.System.nanoTime() < deadline do Thread.sleep(20)
    assert(check)
  }
  private val unusedAudio = AudioInput(Chunk(1.toByte), AudioFormat.Wav)
  private val jvmRecorder = new AudioRecorder {
    def record(seconds: Int): Task[AudioInput] = ZIO.succeed(unusedAudio)
    def recordUntil(termination: UIO[VoiceRecordingTermination]): Task[Option[AudioInput]] =
      termination.map {
        case VoiceRecordingTermination.Stop   => Some(unusedAudio)
        case VoiceRecordingTermination.Cancel => None
      }
  }

  private final class Fixture(initial: ReplState = ReplState()) {
    val transcriptions = new LinkedBlockingQueue[String]()
    val prompts = new LinkedBlockingQueue[String]()
    def audio(name: String): Transcriber = new Transcriber(new LlmProviderAudio {
      def inferenceWithAudio(prompt: String, audio: AudioInput, model: Option[String]): IO[YallmoError, String] =
        ZIO.succeed { transcriptions.add(name); name }
    })
    private val agent = new Agent[Any] {
      val id = AgentId("alpha")
      def turn(
          request: fmgp.yallmo.model.llm.AgentTurnRequest
      ): IO[YallmoError, fmgp.yallmo.model.llm.AgentTurnResponse] =
        ZIO.dieMessage("No live provider calls")
    }
    private val user = new Agent[Any] {
      val id = AgentId("user")
      def turn(
          request: fmgp.yallmo.model.llm.AgentTurnRequest
      ): IO[YallmoError, fmgp.yallmo.model.llm.AgentTurnResponse] =
        ZIO.dieMessage("The user must not call a provider")
    }
    val storage = run(OrchestratorLive.inMemory(run(RuntimeInstances.fromAgents(Chunk(agent, user)))))
    val orchestrator = ReplOrchestratorLive.make(storage, user, Some(agent))
    val choices =
      Vector("alpha", "beta", "unavailable").map(id => ReplAgentChoice(AgentId(id), "synthetic", Vector(id), id))
    def application(name: String = "alpha"): ReplApplication[Any] = new ReplApplication[Any](
      new ReplSession[Any](
        new AgentRunner[Any](Map.empty, Map.empty),
        Some(new AgentInstance[Any] {
          val id = AgentId(name)
          def next(task: AgentTask, context: StaticContext[Any], runContext: RunContext): IO[YallmoError, AgentTurn] =
            ZIO.succeed {
              prompts.add(task.prompt)
              AgentTurn(Some(MessagePayload.Assistant("new reply")), AgentDecision.FinalAnswer("new reply"))
            }
        }),
        StaticContext[Any]("synthetic")
      ),
      ReplToolAvailability(Chunk.empty, Chunk.empty),
      orchestrator,
      initialState = initial,
      activeAgent = Some(ReplActiveAgent(name, name, name, None)),
      agentChoices = choices,
      agentSelector = Some(id => ZIO.succeed(application(AgentId.value(id)))),
      transcriber = if name == "unavailable" then None else Some(audio(name))
    )
  }

  private final class TerminalHarness(
      fixture: Fixture,
      failStartup: Boolean = false,
      logger: Option[ZLogger[String, Unit]] = None
  ) extends AutoCloseable {
    val starts = new AtomicInteger()
    val closes = new AtomicInteger()
    val setup = new CompletableFuture[String]()
    val completed = new LinkedBlockingQueue[String]()
    val promptCount = new AtomicInteger()
    val output = new ByteArrayOutputStream()
    private val input = new PipedInputStream(65536)
    private val writer = new PipedOutputStream(input)
    private val terminal = new DumbTerminal("test", "xterm", input, output, UTF_8)
    terminal.setSize(new Size(200, 24))
    private val variables = new java.util.HashMap[String, Object]()
    variables.put(LineReader.AMBIGUOUS_BINDING, java.lang.Long.valueOf(50L))
    val reader = new StatusLineReader(terminal, variables)
    reader.getKeyMaps.put(LineReader.MAIN, reader.getKeyMaps.get(LineReader.VIINS))
    private val create = ZIO.succeed(starts.incrementAndGet()) *> (
      if failStartup then ZIO.fail(BrowserAudioError("startup", "Synthetic startup failure"))
      else
        ZIO.addFinalizer(ZIO.succeed(closes.incrementAndGet()).unit) *>
          makeBridge.tap(bridge => ZIO.succeed(setup.complete(bridge.setupUrl)))
    )
    private val client = new TerminalRepl(fixture.application(), reader, jvmRecorder, fixture.audio("fallback"), create)
    private val ready = new CountDownLatch(1)
    private val originalInit = reader.getWidgets.get(LineReader.CALLBACK_INIT)
    reader.getWidgets.put(
      LineReader.CALLBACK_INIT,
      new Widget {
        def apply(): Boolean = {
          val result = originalInit.apply()
          promptCount.incrementAndGet()
          if ready.getCount > 0 then {
            for command <- List("v", "m", "t") do {
              val name = if command == "t" then "browser-speak" else s"voice-insert-$command"
              val original = reader.getWidgets.get(name)
              reader.getWidgets.put(
                name,
                new Widget {
                  def apply(): Boolean = try original.apply()
                  finally { completed.add(command); () }
                }
              )
            }
            ready.countDown()
          }
          result
        }
      }
    )
    private val execution = logger.fold(client.run) { value =>
      ZIO.scoped((Runtime.removeDefaultLoggers >>> Runtime.addLogger(value)).build *> client.run)
    }
    private val fiber = run(execution.forkDaemon)
    assert(ready.await(3, TimeUnit.SECONDS))
    def send(keys: String): Unit = { writer.write(keys.getBytes(UTF_8)); writer.flush() }
    def waitFor(command: String): Unit = assertEquals(completed.poll(4, TimeUnit.SECONDS), command)
    def text: String = output.toString(UTF_8)
    def browser: BrowserTestClient = new BrowserTestClient(setup.get(4, TimeUnit.SECONDS))
    def quit(): Unit = {
      send(esc + "q")
      run(fiber.join.timeoutFail(new Exception("terminal did not exit"))(4.seconds))
    }
    def close(): Unit = {
      writer.close()
      run(fiber.interrupt.timeout(4.seconds))
      terminal.close()
    }
  }

  test("m and t lifecycle logs reach repl.log without tokens or speech text") {
    val root = java.nio.file.Files.createTempDirectory("browser-audio-logs-")
    val privateText = "synthetic speech text must never be logged"
    try {
      for command <- List("m", "t") do {
        val storage = root.resolve(command)
        run(ReplLoggers.initialize(storage))
        val logFile = ReplLoggers.replFilePath(storage)
        def logs: String = java.nio.file.Files.readString(logFile)
        val fixture = new Fixture(ReplState(RunContext(Chunk(MessagePayload.Assistant(privateText)))))
        val terminal = new TerminalHarness(fixture, logger = Some(ReplLoggers.filesAt(storage)))
        try {
          terminal.send(command)
          terminal.waitFor(command)
          eventually(logs.contains("Browser audio service started"))
          val browser = terminal.browser
          browser.connect()
          browser.get("/api/poll")
          browser.get("/api/poll")
          browser.connect()
          assertEquals(browser.post("/api/disconnect", "").statusCode(), 200)
          browser.connect()
          // No heartbeat: the scoped monitor must emit a single timeout transition.
          Thread.sleep(5300)
          eventually(logs.contains("reason=heartbeat_timeout"))
          browser.connect()
          terminal.send("t")
          terminal.waitFor("t")
          val operation = browser.awaitOperation("speak")
          assertEquals(operation._2, Some(privateText))
          browser.event(operation._1, "started")
          browser.event(operation._1, "ended")
          terminal.quit()
          val output = logs
          assert(output.contains("Browser audio client connected"), output)
          assert(output.contains("Browser audio client reconnected"), output)
          assert(output.contains("reason=explicit"), output)
          assert(output.contains("reason=service_shutdown"), output)
          assert(output.contains("Browser audio service stopped"), output)
          assertEquals("reason=heartbeat_timeout".r.findAllIn(output).length, 1)
          assert(!output.contains(privateText), output)
          assert(!output.contains("#token="), output)
          val token = java.net.URI.create(terminal.setup.get()).getFragment.stripPrefix("token=")
          assert(!output.contains(token), "credential found in lifecycle log")
          assert(!output.contains("synthetic_browser_client"), output)
        } finally terminal.close()
      }
    } finally {
      val paths = java.nio.file.Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(path => java.nio.file.Files.deleteIfExists(path))
      finally paths.close()
    }
  }

  test("ordinary text and JVM v never start browser audio") {
    val fixture = new Fixture()
    val terminal = new TerminalHarness(fixture)
    try {
      terminal.send("idraft" + esc + "v ")
      terminal.waitFor("v")
      assertEquals(terminal.reader.getBuffer.toString, "draft alpha")
      assertEquals(terminal.starts.get(), 0)
      assertEquals(fixture.prompts.size(), 0)
      terminal.quit()
      assertEquals(terminal.closes.get(), 0)
    } finally terminal.close()
  }

  test("failed lazy startup leaves draft, text and JVM voice usable") {
    val fixture = new Fixture()
    val terminal = new TerminalHarness(fixture, failStartup = true)
    try {
      terminal.send("idraft" + esc + "m")
      terminal.waitFor("m")
      assertEquals(terminal.reader.getBuffer.toString, "draft")
      assert(terminal.text.contains("Synthetic startup failure"))
      terminal.send("v ")
      terminal.waitFor("v")
      terminal.send("i!\n")
      eventually(fixture.prompts.size() == 1)
      assertEquals(fixture.prompts.poll(), "draft alpha!")
      terminal.quit()
      assertEquals(terminal.starts.get(), 1)
    } finally terminal.close()
  }

  test("disconnected browser actions print setup URL and fail locally without submission") {
    val initial = ReplState(RunContext(Chunk(MessagePayload.Assistant("reply"))))
    val fixture = new Fixture(initial)
    val terminal = new TerminalHarness(fixture)
    try {
      terminal.send("idraft" + esc + "m")
      terminal.waitFor("m")
      assertEquals(terminal.reader.getBuffer.toString, "draft")
      assertEquals(terminal.reader.getKeyMap, LineReader.VICMD)
      terminal.send("t")
      terminal.waitFor("t")
      eventually(terminal.text.contains("Browser speech failed:"))
      assert(terminal.text.contains(terminal.setup.get(3, TimeUnit.SECONDS)))
      assertEquals(fixture.prompts.size(), 0)
      assertEquals(terminal.starts.get(), 1)
      terminal.quit()
      assertEquals(terminal.closes.get(), 1)
    } finally terminal.close()
  }

  test("m and v use changed providers and never retain one after switching to unavailable audio") {
    for command <- List("v", "m") do {
      val fixture = new Fixture()
      val terminal = new TerminalHarness(fixture)
      try {
        var browser = Option.empty[BrowserTestClient]
        if command == "m" then {
          terminal.send("m")
          terminal.waitFor("m")
          browser = Some(terminal.browser)
          browser.foreach(_.connect())
        }
        terminal.send("idraft" + esc)
        for (name, index) <- List("alpha", "beta", "unavailable").zipWithIndex do {
          if index > 0 then {
            val promptsBefore = terminal.promptCount.get()
            terminal.send("aj\n")
            eventually(terminal.promptCount.get() > promptsBefore)
            eventually(terminal.reader.getKeyMap == LineReader.VICMD)
          }
          val before = terminal.reader.getBuffer.toString
          terminal.send(command)
          if command == "v" then terminal.send(" ")
          else {
            val client = browser.get
            val (id, _) = client.awaitOperation()
            client.event(id, "started")
            terminal.send(" ")
            client.awaitOperation(phase = "stop")
            client.upload(id)
          }
          terminal.waitFor(command)
          if name == "unavailable" then {
            assertEquals(terminal.reader.getBuffer.toString, before)
            assertEquals(fixture.transcriptions.size(), 0)
          } else assertEquals(fixture.transcriptions.poll(1, TimeUnit.SECONDS), name)
          assertEquals(terminal.reader.getKeyMap, LineReader.VICMD)
          assertEquals(fixture.prompts.size(), 0)
        }
        terminal.quit()
      } finally terminal.close()
    }
  }

  test("browser cancellation, browser error and start timeout restore the draft without transcription or another key") {
    val fixture = new Fixture()
    val terminal = new TerminalHarness(fixture)
    try {
      terminal.send("m")
      terminal.waitFor("m")
      val browser = terminal.browser
      browser.connect()
      terminal.send("idraft" + esc + "hh")
      for scenario <- List("cancel-before-start", "cancel-after-start", "browser-error", "start-timeout") do {
        terminal.send("m")
        val (id, _) = browser.awaitOperation()
        if scenario == "cancel-after-start" || scenario == "browser-error" then browser.event(id, "started")
        scenario match {
          case "cancel-before-start" | "cancel-after-start" => terminal.send(esc)
          case "browser-error"                              => browser.event(id, "error")
          case _                                            => ()
        }
        if scenario == "start-timeout" then {
          val deadline = java.lang.System.nanoTime() + TimeUnit.SECONDS.toNanos(7)
          while terminal.completed.isEmpty && java.lang.System.nanoTime() < deadline do {
            browser.get("/api/poll") // Keep heartbeat alive so this exercises the start deadline, not disconnect.
            Thread.sleep(100)
          }
        }
        terminal.waitFor("m")
        assertEquals(terminal.reader.getBuffer.toString, "draft", scenario)
        assertEquals(terminal.reader.getBuffer.cursor(), 2, scenario)
        assertEquals(terminal.reader.getKeyMap, LineReader.VICMD, scenario)
        assertEquals(fixture.transcriptions.size(), 0, scenario)
        assertEquals(fixture.prompts.size(), 0, scenario)
        assertEquals(browser.get("/api/poll").body(), "{\"heartbeatMs\":1000}")
      }
      assert(terminal.text.contains("did not start"))
      terminal.quit()
    } finally terminal.close()
  }

  test("t reads current resumed/new session state, never auto-speaks, and does not block editing") {
    val fixture = new Fixture(
      ReplState(
        RunContext(
          Chunk(
            MessagePayload.Assistant("initial reply"),
            MessagePayload.User("private user"),
            MessagePayload.ToolResult("x", "private tool")
          )
        )
      )
    )
    val id = SessionId.unsafe("alternate")
    run(
      fixture.storage.createSession(id, Chunk(AgentId("alpha"))) *>
        fixture.storage.append(id, AgentId("alpha"), Chunk(MessagePayload.Assistant("resumed reply")))
    )
    val terminal = new TerminalHarness(fixture)
    try {
      terminal.send("t")
      terminal.waitFor("t")
      eventually(terminal.text.contains("Browser speech failed:"))
      val browser = terminal.browser
      browser.connect()
      for expected <- List("initial reply", "resumed reply") do {
        terminal.send("t")
        terminal.waitFor("t")
        val (operation, text) = browser.awaitOperation("speak")
        assertEquals(text, Some(expected))
        browser.event(operation, "started")
        terminal.send("idraft")
        eventually(terminal.reader.getBuffer.toString == "draft")
        assertEquals(fixture.prompts.size(), 0)
        browser.event(operation, "ended")
        terminal.send("\u0015/session resume alternate\n" + esc)
        eventually(terminal.text.contains("alternate"))
        eventually(terminal.reader.getKeyMap == LineReader.VICMD)
      }
      terminal.send("i/new fresh\n" + esc)
      eventually(terminal.text.contains("fresh"))
      terminal.send("t")
      terminal.waitFor("t")
      eventually(terminal.text.contains("No assistant response in the current conversation."))
      assertEquals(browser.get("/api/poll").body(), "{\"heartbeatMs\":1000}")
      terminal.send("iquestion\n" + esc)
      eventually(fixture.prompts.size() == 1)
      assertEquals(
        browser.get("/api/poll").body(),
        "{\"heartbeatMs\":1000}",
        "assistant replies are not spoken automatically"
      )
      terminal.send("t")
      terminal.waitFor("t")
      assertEquals(browser.awaitOperation("speak")._2, Some("new reply"))
      terminal.quit() // A pending speech operation must not hold the REPL open.
      assertEquals(terminal.closes.get(), 1)
    } finally terminal.close()
  }
}
