package fmgp.yallmo.repl

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.llm.{ProviderContinuation, ProviderId}
import java.nio.file.{Files, Path}
import java.util.{Comparator, UUID}
import java.util.concurrent.CopyOnWriteArrayList
import munit.FunSuite
import zio.*
import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.repl.debug.{ReplDebug, ReplDebugSnapshot}
import fmgp.yallmo.repl.grammar.*
import fmgp.yallmo.repl.terminal.TerminalRepl

final class ReplSuite extends FunSuite {
  test("REPL startup selects only tools whose requirements pass") {
    val tools = runZIO(
      ReplTools.evaluate.provide(
        ZLayer.succeed(workspaceFileSystem(workspaceAccessible = true)) ++
          ZLayer.succeed(bashExecutor(bashAvailable = true)) ++
          ZLayer.succeed(gitClient(gitAvailable = false, workTree = false))
      )
    )

    assertEquals(tools.available.map(_.definition.key), Chunk("readFile", "listFiles", "bash"))
    assertEquals(
      tools.availability,
      ReplToolAvailability(
        available = Chunk("readFile", "listFiles", "bash"),
        unavailable = Chunk("repositoryChanges" -> Chunk("git is not available on PATH"))
      )
    )
  }

  test("typed grammar is the source for command parsing") {
    assertEquals(ReplGrammar.parse("/history"), Right(ReplInput.Command(ReplCommand.History)))
    assertEquals(ReplGrammar.parse("/tools"), Right(ReplInput.Command(ReplCommand.Tools)))
    assertEquals(ReplGrammar.parse("/new"), Right(ReplInput.Command(ReplCommand.NewSession(None))))
    assertEquals(ReplGrammar.parse("/new research"), Right(ReplInput.Command(ReplCommand.NewSession(Some("research")))))
    assertEquals(
      ReplGrammar.parse("/session list"),
      Right(ReplInput.Command(ReplCommand.Session(SessionCommand.List)))
    )
    assertEquals(
      ReplGrammar.parse("/session resume research"),
      Right(ReplInput.Command(ReplCommand.Session(SessionCommand.Resume("research"))))
    )
    assert(ReplGrammar.parse("/session resume").isLeft)
    assert(ReplGrammar.parse("/voice 5").isLeft)
    assert(ReplGrammar.parse("/session resume too many ids").isLeft)
    assertEquals(
      ReplGrammar.parse("/debug print"),
      Right(ReplInput.Command(ReplCommand.Debug(DebugTarget.Display)))
    )
    assertEquals(
      ReplGrammar.parse("/debug request"),
      Right(ReplInput.Command(ReplCommand.Debug(DebugTarget.ProviderRequest)))
    )
    assertEquals(ReplGrammar.parse("/log off"), Right(ReplInput.Command(ReplCommand.Log(LogLevel.None))))
    assertEquals(ReplGrammar.parse("   "), Right(ReplInput.Empty))

    assert(ReplGrammar.parse("/debug").isLeft)
    assert(ReplGrammar.parse("/debug display").isLeft)
    assert(ReplGrammar.parse("/debug file").isLeft)
    assert(ReplGrammar.parse("/debug provider").isLeft)
    assert(ReplGrammar.parse("/removed-command").isLeft)
    assert(ReplGrammar.parse("/unknown").isLeft)
    ReplGrammar.parse("//help") match {
      case Right(ReplInput.Prompt(prompt)) => assertEquals(prompt.value, "/help")
      case value                           => fail(s"Expected an escaped prompt, got $value")
    }
  }

  test("grammar completion is derived from the command definitions") {
    assertEquals(ReplGrammar.complete(CompletionRequest("/lo", 3)).candidates.map(_.value), Chunk("/log"))
    assertEquals(
      ReplGrammar.complete(CompletionRequest("/deb", 4)).candidates.map(_.value),
      Chunk("/debug")
    )
    assertEquals(
      ReplGrammar.complete(CompletionRequest("/debug ", 7)).candidates.map(_.value),
      Chunk("print", "export", "request")
    )
    assertEquals(
      ReplGrammar.complete(CompletionRequest("/log ", 5)).candidates.map(_.value),
      Chunk("trace", "debug", "info", "warning", "error", "fatal", "off")
    )
    assert(ReplGrammar.complete(CompletionRequest("/voi", 4)).candidates.isEmpty)
    assertEquals(
      ReplGrammar.complete(CompletionRequest("/session ", 9)).candidates.map(_.value),
      Chunk("list", "resume")
    )
  }

  test("shared command grammar treats login as unknown and excludes it from help, completion and suggestions") {
    for command <- List("/login", "/login ", "/login browser", "/login invalid") do {
      val error = ReplGrammar.parse(command).swap.toOption.get
      assertEquals(error.message, "Unknown command: /login")
      assert(error.suggestions.isEmpty)
      assert(ReplGrammar.complete(CompletionRequest(command, command.length)).candidates.isEmpty)
    }
    assert(!ReplGrammar.help().exists(_.usage.startsWith("/login")))
    assert(!ReplGrammar.complete(CompletionRequest("/", 1)).candidates.exists(_.value == "/login"))
    assert(!ReplGrammar.parse("/lo").swap.toOption.get.suggestions.exists(_.value == "/login"))
    ReplGrammar.parse("//login") match {
      case Right(ReplInput.Prompt(prompt)) => assertEquals(prompt.value, "/login")
      case other                           => fail(s"Expected a literal prompt, got $other")
    }
  }

  test("line terminal exits only after consecutive Ctrl+C interrupts") {
    assert(!TerminalRepl.shouldExitAfterInterrupt(previousReadWasInterrupt = false))
    assert(TerminalRepl.shouldExitAfterInterrupt(previousReadWasInterrupt = true))
  }

  test("new REPL sessions default to error-level logging and have no durable session") {
    assertEquals(ReplState().logLevel, LogLevel.Error)
    assertEquals(ReplState().sessionId, None)
  }

  test("application emits client-neutral events for grammar commands") {
    val application = new ReplApplication[Any](
      new ReplSession[Any](
        runner = new AgentRunner[Any](Map.empty, Map.empty),
        agent = Some(new AgentInstance[Any] {
          override val id: AgentId = AgentId("unused")

          override def next(
              _task: AgentTask,
              _staticContext: StaticContext[Any],
              _runContext: RunContext
          ): IO[YallmoError, AgentTurn] =
            ZIO.dieMessage("This test only submits REPL commands.")
        }),
        staticContext = StaticContext[Any]("Test")
      ),
      ReplToolAvailability(Chunk.empty, Chunk.empty),
      inMemoryReplOrchestrator(AgentId("unused"))
    )

    val existing = ReplState(
      sessionId = Some("keep-selected-session"),
      logLevel = LogLevel.Debug,
      runContext = RunContext(messages = Chunk(Message.User("keep transcript"))),
      lastProviderRequest = Some(Path.of("keep-request.json"))
    )
    for command <- List("/login", "/login browser") do {
      val rejected = runZIO(application.submit(existing, command))
      assertEquals(rejected.state, existing)
      assertEquals(
        rejected.events.collect { case ReplEvent.InputRejected(error) => error.message },
        Chunk("Unknown command: /login")
      )
    }
    assertEquals(
      runZIO(application.submit(existing, "/session list")).events,
      Chunk(ReplEvent.SessionList(Chunk.empty, existing.sessionId))
    )

    val help = runZIO(application.submit(existing, "/help"))
    assertEquals(help.events, Chunk(ReplEvent.Help(ReplGrammar.help())))

    val session = runZIO(application.submit(ReplState(), "/session list"))
    assertEquals(session, ReplTransition(ReplState(), Chunk(ReplEvent.SessionList(Chunk.empty, None))))

    val reset = runZIO(application.submit(ReplState(), "/new"))
    assertEquals(reset.events, Chunk(ReplEvent.SessionReset))
    assert(reset.state.sessionId.nonEmpty)

    val exported = runZIO(application.submit(ReplState(), "/debug export"))
    assertEquals(
      exported.events,
      Chunk(ReplEvent.DebugSnapshot(DebugTarget.Export, ReplDebugSnapshot(Chunk.empty)))
    )

    val providerRequest = runZIO(application.submit(ReplState(), "/debug request"))
    assertEquals(providerRequest.events, Chunk(ReplEvent.ProviderRequest(None)))

    val currentSessionRequest = Path.of("debug", "current-session-request.json")
    val sessionProviderRequest = runZIO(
      application.submit(ReplState(lastProviderRequest = Some(currentSessionRequest)), "/debug request")
    )
    assertEquals(sessionProviderRequest.events, Chunk(ReplEvent.ProviderRequest(Some(currentSessionRequest))))

    val logLevel = runZIO(application.submit(ReplState(), "/log error"))
    assertEquals(logLevel.state.logLevel, LogLevel.Error)
    assertEquals(logLevel.events, Chunk(ReplEvent.LogLevelChanged(LogLevel.Error)))

    val invalid = runZIO(application.submit(ReplState(), "/not-a-command"))
    assert(invalid.events.headOption.exists(_.isInstanceOf[ReplEvent.InputRejected]))

  }

  test("history loads full stored messages without changing state or running the agent") {
    val orchestrator = inMemoryReplOrchestrator(AgentId("bob"))
    val application = new ReplApplication[Any](
      new ReplSession[Any](new AgentRunner[Any](Map.empty, Map.empty), None, StaticContext[Any]("Test")),
      ReplToolAvailability(Chunk.empty, Chunk.empty),
      orchestrator
    )
    val created = runZIO(orchestrator.newReplSession(Some("history")))
    val id = SessionId.unsafe("history")
    runZIO(orchestrator.append(id, AgentId("TestAgentId"), Chunk(Message.User("hello"))))
    val stored = runZIO(orchestrator.append(id, AgentId("bob"), Chunk(Message.Assistant("answer"))))
    val before = created.copy(logLevel = LogLevel.Debug)
    val history = runZIO(application.submit(before, "/history"))
    assertEquals(history.state, before)
    assertEquals(history.events, Chunk(ReplEvent.Transcript(stored.messages, Some(stored.id.value))))
    assertEquals(stored.messages.map(_.agentId), Chunk(AgentId("TestAgentId"), AgentId("bob")))

    val resumed = runZIO(application.submit(before, "/session resume history"))
    assertEquals(runZIO(application.submit(resumed.state, "/history")).events, history.events)

    val reset = runZIO(application.submit(before, "/new empty-history"))
    assertEquals(
      runZIO(application.submit(reset.state, "/history")),
      ReplTransition(reset.state, Chunk(ReplEvent.Transcript(Chunk.empty, reset.state.sessionId)))
    )
  }

  test("history is empty before a session exists and reports invalid or missing sessions without changing state") {
    val application = new ReplApplication[Any](
      new ReplSession[Any](new AgentRunner[Any](Map.empty, Map.empty), None, StaticContext[Any]("Test")),
      ReplToolAvailability(Chunk.empty, Chunk.empty),
      inMemoryReplOrchestrator(AgentId("bob"))
    )
    assertEquals(
      runZIO(application.submit(ReplState(), "/history")),
      ReplTransition(ReplState(), Chunk(ReplEvent.Transcript(Chunk.empty, None)))
    )
    for id <- List("../invalid", "missing") do {
      val before = ReplState(sessionId = Some(id), logLevel = LogLevel.Debug)
      val history = runZIO(application.submit(before, "/history"))
      assertEquals(history.state, before)
      assertEquals(history.events.size, 1)
      assert(history.events.head.isInstanceOf[ReplEvent.SessionFailed])
    }
  }

  test("selected log level applies to the next REPL turn") {
    var observed = Option.empty[LogLevel]
    val application = new ReplApplication[Any](
      new ReplSession[Any](
        runner = new AgentRunner[Any](Map.empty, Map.empty),
        agent = Some(new AgentInstance[Any] {
          override val id: AgentId = AgentId("logger")

          override def next(
              _task: AgentTask,
              _staticContext: StaticContext[Any],
              _runContext: RunContext
          ): IO[YallmoError, AgentTurn] =
            FiberRef.currentLogLevel.get.map { level =>
              observed = Some(level)
              AgentTurn(Some(Message.Assistant("ok")), AgentDecision.FinalAnswer("ok"))
            }
        }),
        staticContext = StaticContext[Any]("Test")
      ),
      ReplToolAvailability(Chunk.empty, Chunk.empty),
      inMemoryReplOrchestrator(AgentId("logger"))
    )

    runZIO(application.submit(ReplState(logLevel = LogLevel.Debug), "hello"))
    assertEquals(observed, Some(LogLevel.Debug))
  }

  test("file logs are initialized inside the selected storage root") {
    val root = Files.createTempDirectory("yallmo-repl-logs-")
    try {
      runZIO(ReplLoggers.initialize(root))
      assert(Files.isRegularFile(ReplLoggers.replFilePath(root)))
      assert(Files.isDirectory(ReplLoggers.agentRunnerDirectory(root)))
    } finally {
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }

  test("REPL log level filters terminal output while files are routed by AgentRunner") {
    val terminalOutput = new CopyOnWriteArrayList[String]()
    val replOutput = new CopyOnWriteArrayList[String]()
    val agentRunnerOutput = new CopyOnWriteArrayList[String]()
    val runnerId = UUID.randomUUID()
    val consoleLogger = ReplLoggers.console(line => { terminalOutput.add(line); () })
    val fileLogger = ReplLoggers.files(
      line => { replOutput.add(line); () },
      (id, line) => if id == runnerId then agentRunnerOutput.add(line) else ()
    )
    runZIO {
      ZIO.scoped {
        (Runtime.removeDefaultLoggers >>> Runtime.addLogger(consoleLogger) >>> Runtime.addLogger(fileLogger)).build *>
          ZIO.logLevelScoped(LogLevel.Error) *>
          ZIO.logInfo("hidden from terminal") *>
          ZIO.logAnnotate(AgentRunner.LogAnnotationKey, runnerId.toString) {
            ZIO.logInfo("runner hidden from terminal") *>
              ZIO.logError("runner visible on terminal")
          }
      }
    }

    assertEquals(terminalOutput.size, 1)
    assert(terminalOutput.get(0).contains("runner visible on terminal"), terminalOutput)
    assertEquals(replOutput.size, 1)
    assert(replOutput.get(0).contains("hidden from terminal"), replOutput)
    assertEquals(agentRunnerOutput.size, 2)
    assert(agentRunnerOutput.get(0).contains("runner hidden from terminal"), agentRunnerOutput)
    assert(agentRunnerOutput.get(1).contains("runner visible on terminal"), agentRunnerOutput)
  }

  test("switching keeps conversation history but never replays a previous binding's opaque provider continuation") {
    val oldReasoning = MessagePayload.Reasoning(
      Some("earlier summary"),
      ProviderContinuation(ProviderId.unsafe("openai-codex-responses"), Some("old"), "old-encrypted-state")
    )
    val currentReasoning = MessagePayload.Reasoning(
      Some("current summary"),
      ProviderContinuation(ProviderId.unsafe("openai-codex-responses"), Some("current"), "current-encrypted-state")
    )
    val history = Chunk(Message.User("earlier"), oldReasoning, Message.Assistant("earlier answer"))
    val requests = scala.collection.mutable.ListBuffer.empty[Chunk[MessagePayload]]
    val agent = new AgentInstance[Any] {
      override val id = AgentId("new-agent")
      override def next(task: AgentTask, context: StaticContext[Any], current: RunContext): IO[YallmoError, AgentTurn] =
        ZIO.succeed {
          requests += current.messages
          AgentTurn(Some(Message.Assistant("ok")), AgentDecision.FinalAnswer("ok"), Chunk(currentReasoning))
        }
    }
    val session =
      new ReplSession[Any](new AgentRunner[Any](Map.empty, Map.empty), Some(agent), StaticContext[Any]("Test"))
    val before = ReplState(RunContext(history), sessionId = Some("same-session"), continuationStart = history.size)
    val first = runZIO(session.submit(before, "first"))
    val second = runZIO(session.submit(first.state, "second"))
    assert(requests.forall(!_.contains(oldReasoning)))
    assert(requests.forall(_.contains(Message.User("earlier"))))
    assert(requests(1).contains(currentReasoning))
    assertEquals(second.state.runContext.messages.take(history.size), history)
    assertEquals(second.state.sessionId, before.sessionId)
    assertEquals(second.state.continuationStart, history.size)
    assertEquals(first.newMessages, Chunk(Message.User("first"), Message.Assistant("ok"), currentReasoning))
  }

  test("application preserves existing history and clears it on a new session") {
    val bob = AgentId("bob")
    val agent = new AgentInstance[Any] {
      override val id = bob
      override def next(task: AgentTask, context: StaticContext[Any], history: RunContext): IO[YallmoError, AgentTurn] =
        ZIO.succeed(AgentTurn(Some(Message.Assistant("new answer")), AgentDecision.FinalAnswer("new answer")))
    }
    val application = new ReplApplication[Any](
      new ReplSession[Any](new AgentRunner[Any](Map.empty, Map.empty), Some(agent), StaticContext[Any]("Test")),
      ReplToolAvailability(Chunk.empty, Chunk.empty),
      inMemoryReplOrchestrator(bob),
      userId = AgentId("fabio")
    )
    val before = ReplState(
      RunContext(Chunk(Message.User("legacy prompt"), Message.Assistant("legacy answer")))
    )
    val next = runZIO(application.submit(before, "hello")).state
    assertEquals(next.runContext.messages.take(2), before.runContext.messages)
    assertEquals(next.runContext.messages.drop(2), Chunk(Message.User("hello"), Message.Assistant("new answer")))
    assertEquals(runZIO(application.submit(next, "/invalid")).state, next)
    assertEquals(runZIO(application.submit(next, "/new new-history")).state.runContext.messages, Chunk.empty)
  }

  test("session retains the transcript across submitted prompts") {
    val agent = new AgentInstance[Any] {
      override val id: AgentId = AgentId("echo")

      override def next(
          task: AgentTask,
          _staticContext: StaticContext[Any],
          _runContext: RunContext
      ): IO[YallmoError, AgentTurn] = {
        val answer = s"Echo: ${task.prompt}"
        ZIO.succeed(AgentTurn(Some(Message.Assistant(answer)), AgentDecision.FinalAnswer(answer)))
      }

    }
    val session = new ReplSession[Any](
      runner = new AgentRunner[Any](Map.empty, Map.empty),
      agent = Some(agent),
      staticContext = StaticContext[Any]("Test")
    )

    assertEquals(session.debugSnapshot(ReplState()), ReplDebugSnapshot(Chunk.empty))

    val secondTurn = runZIO {
      for
        first <- session.submit(ReplState(), "first")
        second <- session.submit(first.state, "second")
      yield second
    }

    assertEquals(secondTurn.result, AgentResult(AgentId("echo"), "Echo: second"))
    assertEquals(
      secondTurn.state.runContext.messages,
      Chunk(
        Message.User("first"),
        Message.Assistant("Echo: first"),
        Message.User("second"),
        Message.Assistant("Echo: second")
      )
    )
  }

  test("debug renderer emits newline-terminated Pi-style JSON objects") {
    val rendered = ReplDebug.renderJsonLines(
      Chunk(Message.User("hello"), Message.ToolResult("clock", "12:00")),
      timestamp = 42L
    )

    assert(!rendered.startsWith("\n"))
    assert(rendered.endsWith("\n"))
    assertEquals(
      rendered.trim,
      """{"role":"user","content":[{"type":"text","text":"hello"}],"timestamp":42}
        |{"role":"toolResult","toolName":"clock","content":[{"type":"text","text":"12:00"}],"timestamp":42}""".stripMargin
    )
  }

  private def inMemoryReplOrchestrator(assistantId: AgentId): ReplOrchestrator[Any] = {
    val user = new Agent[Any] {
      override val id: AgentId = AgentId("TestAgentId")

      override def turn(
          request: fmgp.yallmo.model.llm.AgentTurnRequest
      ): IO[YallmoError, fmgp.yallmo.model.llm.AgentTurnResponse] =
        ZIO.dieMessage("The user agent must not run in this test")
    }
    val assistant = new Agent[Any] {
      override val id: AgentId = assistantId

      override def turn(
          request: fmgp.yallmo.model.llm.AgentTurnRequest
      ): IO[YallmoError, fmgp.yallmo.model.llm.AgentTurnResponse] =
        ZIO.dieMessage(s"Agent '${AgentId.value(assistantId)}' must not run in this test")
    }
    val instances = runZIO(RuntimeInstances.fromAgents(Chunk(user, assistant)))
    val orchestrator = runZIO(OrchestratorLive.inMemory(instances))
    ReplOrchestratorLive.make(orchestrator, user, Some(assistant))
  }

  private def workspaceFileSystem(workspaceAccessible: Boolean): fmgp.yallmo.tool.WorkspaceFileSystem =
    new fmgp.yallmo.tool.WorkspaceFileSystem {
      override def isDirectory(_path: Path): UIO[Boolean] = ZIO.succeed(workspaceAccessible)
      override def readUtf8(_path: Path): Task[String] = ZIO.dieMessage("not used by this test")
      override def list(_path: Path): Task[Chunk[String]] = ZIO.dieMessage("not used by this test")
    }

  private def bashExecutor(bashAvailable: Boolean): fmgp.yallmo.tool.BashExecutor = new fmgp.yallmo.tool.BashExecutor {
    override def isAvailable: UIO[Boolean] = ZIO.succeed(bashAvailable)
    override def execute(
        _command: String,
        _workspace: Path,
        _timeout: Duration,
        _maxOutputBytes: Int
    ): Task[fmgp.yallmo.tool.CommandOutput] = ZIO.dieMessage("not used by this test")
  }

  private def gitClient(gitAvailable: Boolean, workTree: Boolean): fmgp.yallmo.tool.GitClient =
    new fmgp.yallmo.tool.GitClient {
      override def isAvailable: UIO[Boolean] = ZIO.succeed(gitAvailable)
      override def isWorkTree(_workspace: Path): UIO[Boolean] = ZIO.succeed(workTree)
      override def status(_workspace: Path): IO[fmgp.yallmo.tool.GitError, fmgp.yallmo.tool.CommandOutput] =
        ZIO.dieMessage("not used by this test")
      override def unstagedDiff(_workspace: Path): IO[fmgp.yallmo.tool.GitError, fmgp.yallmo.tool.CommandOutput] =
        ZIO.dieMessage("not used by this test")
      override def stagedDiff(_workspace: Path): IO[fmgp.yallmo.tool.GitError, fmgp.yallmo.tool.CommandOutput] =
        ZIO.dieMessage("not used by this test")
    }

  private def runZIO[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }
}
