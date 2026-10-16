package fmgp.yallmo.repl.terminal

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.provider.openai.*
import fmgp.yallmo.repl.*
import fmgp.yallmo.repl.grammar.LoginMethod
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PipedInputStream, PipedOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import munit.FunSuite
import org.jline.reader.{LineReader, LineReaderBuilder}
import org.jline.terminal.impl.DumbTerminal
import zio.*

final class TerminalLoginInputSuite extends FunSuite {
  private val redirect = "http://localhost:1455/auth/callback?state=test&code=private-code"
  private val browser = OpenAiCodexBrowserLogin("https://auth.example/authorize", "test", "verifier")
  private class FakeAuth extends SubscriptionLoginAuth {
    var completed: Option[String] = None
    def startBrowser = ZIO.succeed(browser)
    def completeBrowser(login: OpenAiCodexBrowserLogin, value: String): Task[Unit] = ZIO.succeed {
      assertEquals(login, browser)
      completed = Some(value)
    }
    def startDevice = ZIO.succeed(OpenAiCodexDeviceCode("private-id", "PAIR", 1.second))
    def completeDevice(device: OpenAiCodexDeviceCode): Task[Unit] = ZIO.never
  }

  test("masked redirect prompt never changes JLine buffer, history or history file; restores settings") {
    val output = new ByteArrayOutputStream()
    val terminal = new DumbTerminal(new ByteArrayInputStream((redirect + "\n/help\n").getBytes(UTF_8)), output)
    val history = Files.createTempFile("yallmo-login-history-", ".txt")
    val reader = LineReaderBuilder.builder().terminal(terminal).variable(LineReader.HISTORY_FILE, history).build()
    reader.getHistory.add("/login")
    reader.getBuffer.write("pending draft")
    reader.getBuffer.cursor(3)
    reader.setVariable(LineReader.DISABLE_HISTORY, false)
    val attributes = terminal.getAttributes.toString
    val input = new TerminalLoginInput(terminal, Some(reader))
    try {
      val value = runZIO(input.privateInput {
        ZIO.succeed(assertEquals(reader.getVariable(LineReader.DISABLE_HISTORY), java.lang.Boolean.TRUE)) *>
          input.readRedirect
      })
      assertEquals(value, Some(redirect))
      assertEquals(reader.getBuffer.toString, "pending draft")
      assertEquals(reader.getBuffer.cursor(), 3)
      assertEquals(reader.getHistory.size(), 1)
      assertEquals(reader.getVariable(LineReader.DISABLE_HISTORY), java.lang.Boolean.FALSE)
      assertEquals(terminal.getAttributes.toString, attributes)
      reader.getHistory.save()
      assert(!Files.readString(history).contains("private-code"))
      assert(!output.toString(UTF_8).contains(redirect))
      assert(output.toString(UTF_8).contains("***"))
      assertEquals(runZIO(input.readLine()), Some("/help"))
    } finally {
      terminal.close()
      Files.deleteIfExists(history)
    }
  }

  test("Ctrl+C, EOF and empty redirect are consumed separately from ordinary command input") {
    for (cancel <- List("partial-private\u0003", "partial-private\u0004", "\n")) {
      val terminal =
        new DumbTerminal(new ByteArrayInputStream((cancel + "/help\n").getBytes(UTF_8)), new ByteArrayOutputStream())
      val input = new TerminalLoginInput(terminal)
      val attributes = terminal.getAttributes.toString
      try {
        val result = runZIO(input.privateInput(input.readRedirect))
        assert(result.isEmpty || result.contains(""))
        assertEquals(terminal.getAttributes.toString, attributes)
        assertEquals(runZIO(input.readLine()), Some("/help"))
      } finally terminal.close()
    }
    val eof = new DumbTerminal(new ByteArrayInputStream(redirect.getBytes(UTF_8)), new ByteArrayOutputStream())
    try assertEquals(runZIO(new TerminalLoginInput(eof).readRedirect), None)
    finally eof.close()
  }

  test("device cancel consumes its entire line, not a suffix that could become an agent prompt") {
    val terminal = new DumbTerminal(
      new ByteArrayInputStream("cancel with a suffix\n/help\n".getBytes(UTF_8)),
      new ByteArrayOutputStream()
    )
    val input = new TerminalLoginInput(terminal)
    try {
      runZIO(input.privateInput(input.awaitCancellation))
      assertEquals(runZIO(input.readLine()), Some("/help"))
    } finally terminal.close()
  }

  test("timeout discards a partial callback remainder and restores terminal and absent history setting") {
    val pipe = new PipedInputStream(65536)
    val writer = new PipedOutputStream(pipe)
    val output = new ByteArrayOutputStream()
    val terminal = new DumbTerminal(pipe, output)
    val reader = LineReaderBuilder.builder().terminal(terminal).build()
    val input = new TerminalLoginInput(terminal, Some(reader))
    val attributes = terminal.getAttributes.toString
    try {
      writer.write("partial-private".getBytes(UTF_8))
      writer.flush()
      assertEquals(runZIO(input.privateInput(input.readRedirect.timeout(100.millis))), None)
      assertEquals(reader.getVariable(LineReader.DISABLE_HISTORY), null)
      assertEquals(terminal.getAttributes.toString, attributes)
      writer.write("-callback-remainder\n/help\n".getBytes(UTF_8))
      writer.flush()
      assertEquals(runZIO(input.discardRemainder *> input.readLine()), Some("/help"))
      assert(!output.toString(UTF_8).contains("partial-private"))
      assert(!output.toString(UTF_8).contains("callback-remainder"))
    } finally {
      writer.close()
      terminal.close()
    }
  }

  test("auth failure and explicit cancellation restore prompt settings and never render exception details") {
    for (fail <- List(true, false)) {
      val output = new ByteArrayOutputStream()
      val terminal = new DumbTerminal(
        new ByteArrayInputStream((if fail then redirect + "\n" else "cancel\n").getBytes(UTF_8)),
        output
      )
      val reader = LineReaderBuilder.builder().terminal(terminal).build()
      val input = new TerminalLoginInput(terminal, Some(reader))
      val attributes = terminal.getAttributes.toString
      val auth = new FakeAuth {
        override def completeBrowser(login: OpenAiCodexBrowserLogin, value: String) =
          ZIO.fail(new RuntimeException(value + " token=secret"))
      }
      try {
        runZIO(input.login(LoginMethod.Browser, new SubscriptionLogin(auth)))
        assertEquals(terminal.getAttributes.toString, attributes)
        assertEquals(reader.getVariable(LineReader.DISABLE_HISTORY), null)
        assertEquals(reader.getHistory.size(), 0)
        assert(!output.toString(UTF_8).contains("private-code"))
        assert(!output.toString(UTF_8).contains("token=secret"))
      } finally terminal.close()
    }
  }

  test("plain client preserves piped /help and /exit behavior without invoking auth or an agent") {
    val output = new ByteArrayOutputStream()
    val terminal = new DumbTerminal(new ByteArrayInputStream("/help\n/exit\n".getBytes(UTF_8)), output)
    try {
      runZIO(new BasicTerminalRepl(application, new SubscriptionLogin(new FakeAuth)).runWithTerminal(terminal))
      assertEquals("yallmo> ".r.findAllIn(output.toString(UTF_8)).length, 2)
    } finally terminal.close()
  }

  test("ordinary reader preserves final EOF text, tabs and prompts beyond the private limit") {
    for (line <- List("/help", "/log\tdebug", "x" * 20000)) {
      val terminal = new DumbTerminal(new ByteArrayInputStream(line.getBytes(UTF_8)), new ByteArrayOutputStream())
      val input = new TerminalLoginInput(terminal)
      try {
        assertEquals(runZIO(input.readLine()), Some(line))
        assertEquals(runZIO(input.readLine()), None)
      } finally terminal.close()
    }
  }

  test("private redirect still rejects input beyond its limit without exposing it as ordinary input") {
    val terminal = new DumbTerminal(
      new ByteArrayInputStream((("x" * 16385) + "\n/help\n").getBytes(UTF_8)),
      new ByteArrayOutputStream()
    )
    val input = new TerminalLoginInput(terminal)
    try {
      assert(runZIO(input.privateInput(input.readRedirect).either).isLeft)
      assertEquals(runZIO(input.discardRemainder *> input.readLine()), Some("/help"))
    } finally terminal.close()
  }

  test("plain client dispatches unterminated help, tab-separated log command and long prompt") {
    val prompt = "x" * 20000
    for (commands <- List("/help", "/log\tdebug\n" + prompt)) {
      val observed = scala.collection.mutable.ArrayBuffer.empty[(String, LogLevel)]
      val output = new ByteArrayOutputStream()
      val terminal = new DumbTerminal(new ByteArrayInputStream(commands.getBytes(UTF_8)), output)
      try {
        runZIO(
          new BasicTerminalRepl(
            application((text, level) => observed += ((text, level))),
            new SubscriptionLogin(new FakeAuth)
          )
            .runWithTerminal(terminal)
            .timeoutFail(new Exception("test timeout"))(3.seconds)
        )
        if commands == "/help" then {
          assertEquals(observed.toList, Nil)
          assertEquals("yallmo> ".r.findAllIn(output.toString(UTF_8)).length, 2)
        } else assertEquals(observed.toList, List((prompt, LogLevel.Debug)))
      } finally terminal.close()
    }
  }

  test("plain browser success uses private input and returns to ordinary commands") {
    val pipe = new PipedInputStream(65536)
    val writer = new PipedOutputStream(pipe)
    val output = new ByteArrayOutputStream()
    val terminal = new DumbTerminal(pipe, output)
    val auth = new FakeAuth {
      override def completeBrowser(login: OpenAiCodexBrowserLogin, value: String) =
        super.completeBrowser(login, value) *> ZIO.attempt {
          writer.write("/help\n/exit\n".getBytes(UTF_8))
          writer.flush()
        }
    }
    try {
      writer.write(("/login\n" + redirect + "\n").getBytes(UTF_8))
      writer.flush()
      runZIO(
        new BasicTerminalRepl(application, new SubscriptionLogin(auth))
          .runWithTerminal(terminal)
          .timeoutFail(new Exception("test timeout"))(3.seconds)
      )
      assertEquals(auth.completed, Some(redirect))
      assert(!output.toString(UTF_8).contains("private-code"))
      assert(output.toString(UTF_8).contains("credentials saved privately"))
    } finally {
      writer.close()
      terminal.close()
    }
  }

  private def application: ReplApplication[Any] = application((_, _) => fail("Agent must not run"))

  private def application(observe: (String, LogLevel) => Unit): ReplApplication[Any] = {
    val agent = new Agent[Any] {
      val id = AgentId("unused")
      def turn(
          request: fmgp.yallmo.model.llm.AgentTurnRequest
      ): IO[YallmoError, fmgp.yallmo.model.llm.AgentTurnResponse] =
        ZIO.dieMessage("Agent must not run for login commands")
    }
    val orchestrator = runZIO(OrchestratorLive.inMemory(Chunk(agent)))
    new ReplApplication(
      new ReplSession[Any](
        new AgentRunner[Any](Map.empty, Map.empty),
        new AgentInstance[Any] {
          val id = AgentId("unused")
          def next(task: AgentTask, context: StaticContext[Any], runContext: RunContext): IO[YallmoError, AgentTurn] =
            FiberRef.currentLogLevel.get.map { level =>
              observe(task.prompt, level)
              AgentTurn(Some(Message.Assistant("ok")), AgentDecision.FinalAnswer("ok"))
            }
        },
        StaticContext[Any]("test"),
        afterSubmit = (_, turn) => ZIO.succeed(turn)
      ),
      ReplToolAvailability(Chunk.empty, Chunk.empty),
      ReplOrchestratorLive.make(orchestrator, agent, agent)
    )
  }

  private def runZIO[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
