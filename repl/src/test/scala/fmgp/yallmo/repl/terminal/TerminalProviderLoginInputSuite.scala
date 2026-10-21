package fmgp.yallmo.repl.terminal

import fmgp.yallmo.repl.voice.CapturedAudio
import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.config.ProviderId
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.provider.openai.*
import fmgp.yallmo.repl.voice.{AudioRecorder, VoiceRecordingTermination}
import fmgp.yallmo.repl.*
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PipedInputStream, PipedOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import munit.FunSuite
import org.jline.reader.{LineReader, LineReaderBuilder, Widget}
import org.jline.terminal.Size
import org.jline.terminal.impl.DumbTerminal
import org.jline.terminal.Attributes.LocalFlag
import org.jline.utils.NonBlockingReader
import java.util.concurrent.{CompletableFuture, CountDownLatch, LinkedBlockingDeque, TimeUnit}
import zio.*

final class TerminalProviderLoginInputSuite extends FunSuite {
  private val redirect = "http://localhost:1455/auth/callback?state=test&code=private-code"
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
    val input = new TerminalProviderLoginInput(terminal, Some(reader))
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
      val input = new TerminalProviderLoginInput(terminal)
      val attributes = terminal.getAttributes.toString
      try {
        val result = runZIO(input.privateInput(input.readRedirect))
        assert(result.isEmpty || result.contains(""))
        assertEquals(terminal.getAttributes.toString, attributes)
        assertEquals(runZIO(input.readLine()), Some("/help"))
      } finally terminal.close()
    }
    val eof = new DumbTerminal(new ByteArrayInputStream(redirect.getBytes(UTF_8)), new ByteArrayOutputStream())
    try assertEquals(runZIO(new TerminalProviderLoginInput(eof).readRedirect), None)
    finally eof.close()
  }

  test("timeout discards a partial callback remainder and restores terminal and absent history setting") {
    val pipe = new PipedInputStream(65536)
    val writer = new PipedOutputStream(pipe)
    val output = new ByteArrayOutputStream()
    val terminal = new DumbTerminal(pipe, output)
    val reader = LineReaderBuilder.builder().terminal(terminal).build()
    val input = new TerminalProviderLoginInput(terminal, Some(reader))
    val attributes = terminal.getAttributes.toString
    try {
      writer.write("partial-private".getBytes(UTF_8))
      writer.flush()
      runZIO {
        for {
          consumed <- Promise.make[Nothing, Unit]
          fiber <- input.privateInput(input.readRedirect.ensuring(consumed.succeed(()).unit).timeout(100.millis)).fork
          _ <- consumed.await
          _ <- ZIO.succeed {
            assertEquals(reader.getVariable(LineReader.DISABLE_HISTORY), java.lang.Boolean.TRUE)
            writer.write("-callback-remainder\n/help\n".getBytes(UTF_8))
            writer.flush()
          }
          result <- fiber.join
        } yield assertEquals(result, None)
      }
      assertEquals(reader.getVariable(LineReader.DISABLE_HISTORY), null)
      assertEquals(terminal.getAttributes.toString, attributes)
      assertEquals(runZIO(input.discardRemainder *> input.readLine()), Some("/help"))
      assert(!output.toString(UTF_8).contains("partial-private"))
      assert(!output.toString(UTF_8).contains("callback-remainder"))
    } finally {
      writer.close()
      terminal.close()
    }
  }

  test("private input failure and cancellation restore settings without rendering exception details") {
    for fail <- List(true, false) do {
      val output = new ByteArrayOutputStream()
      val terminal = new DumbTerminal(
        new ByteArrayInputStream((if fail then redirect + "\n" else "cancel\n").getBytes(UTF_8)),
        output
      )
      val reader = LineReaderBuilder.builder().terminal(terminal).build()
      val input = new TerminalProviderLoginInput(terminal, Some(reader))
      val attributes = terminal.getAttributes.toString
      try {
        val effect = if fail then
          input.readRedirect.flatMap(value => ZIO.fail(new RuntimeException(value.getOrElse("") + " token=secret")))
        else input.awaitCancellation
        assertEquals(runZIO(input.privateInput(effect).either).isLeft, fail)
        assertEquals(terminal.getAttributes.toString, attributes)
        assertEquals(reader.getVariable(LineReader.DISABLE_HISTORY), null)
        assertEquals(reader.getHistory.size(), 0)
        assert(!output.toString(UTF_8).contains("private-code"))
        assert(!output.toString(UTF_8).contains("token=secret"))
      } finally terminal.close()
    }
  }

  test("profile private adapter bounds startup cancellation with settings restored") {
    val directory = Files.createTempDirectory("profile-input-")
    val path = directory.resolve("synthetic.json")
    val fixture = """{"userName":"synthetic","providers":{"account":{"providerType":"ChatGPT"}}}"""
    Files.writeString(path, fixture)
    val pipe = new PipedInputStream(65536)
    val writer = new PipedOutputStream(pipe)
    val terminal = new DumbTerminal(pipe, new ByteArrayOutputStream())
    val reader = LineReaderBuilder.builder().terminal(terminal).build()
    reader.getBuffer.write("exact draft")
    reader.getBuffer.cursor(4)
    reader.getHistory.add("old history")
    val attributes = terminal.getAttributes.toString
    val flow = new OpenAiCodexAuth.OAuthFlow {
      def startBrowserLogin = ZIO.never
      def completeBrowserLogin(login: OpenAiCodexBrowserLogin, redirect: String) =
        ZIO.dieMessage("unexpected exchange")
    }
    val service = new ProviderLoginService(runZIO(ReplProviderCatalog.make(path)), flow, 150.millis)
    val input = new TerminalProviderLoginInput(terminal, Some(reader))
    try {
      writer.write("\u0003".getBytes(UTF_8))
      writer.flush()
      val result = runZIO(input.profileLogin(ProviderId.unsafe("account"), LoginMethod.Browser, Some(service)))
      assertEquals(result, ProviderLoginService.Result.Cancelled)
      assertEquals(terminal.getAttributes.toString, attributes)
      assertEquals(reader.getVariable(LineReader.DISABLE_HISTORY), null)
      assertEquals(reader.getBuffer.toString -> reader.getBuffer.cursor(), "exact draft" -> 4)
      assertEquals(reader.getHistory.size(), 1)
      assertEquals(Files.readString(path), fixture)
      writer.write("/help\n".getBytes(UTF_8))
      writer.flush()
      assertEquals(runZIO(input.discardRemainder *> input.readLine()), Some("/help"))
    } finally {
      writer.close()
      terminal.close()
      Files.deleteIfExists(path)
      Files.deleteIfExists(directory)
    }
  }

  test("profile timeout or auth failure drains suffix before catalog return with echo and history disabled") {
    for (failAuth, eof) <- List((false, false), (true, false), (true, true))
    do {
      val directory = Files.createTempDirectory("profile-echo-")
      val path = directory.resolve("synthetic.json")
      val config = """{"userName":"synthetic","providers":{"account":{"providerType":"ChatGPT"}}}"""
      Files.writeString(path, config)
      val fixture = new EchoInput
      val reader = LineReaderBuilder.builder().terminal(fixture.terminal).build()
      reader.setVariable(LineReader.DISABLE_HISTORY, "original-history-setting")
      reader.getBuffer.write("exact draft")
      reader.getBuffer.cursor(4)
      reader.getHistory.add("old history")
      val original = fixture.terminal.getAttributes.toString
      val input = new TerminalProviderLoginInput(fixture.terminal, Some(reader))
      val flow = new OpenAiCodexAuth.OAuthFlow {
        private def start[A]: Task[A] =
          if failAuth then
            ZIO.attemptBlocking(assert(fixture.prefixConsumed.await(5, TimeUnit.SECONDS))) *>
              ZIO.fail(new java.io.IOException("private auth failure"))
          else ZIO.never
        def startBrowserLogin = start[OpenAiCodexBrowserLogin]
        def completeBrowserLogin(login: OpenAiCodexBrowserLogin, redirect: String) = ZIO.never
      }
      val service = new ProviderLoginService(runZIO(ReplProviderCatalog.make(path)), flow, 1.second)
      try {
        fixture.send("partial-private")
        runZIO {
          for {
            fiber <- input
              .profileLogin(ProviderId.unsafe("account"), LoginMethod.Browser, Some(service))
              .ensuring(ZIO.succeed { fixture.cleanupOrReturned.complete("returned"); () })
              .fork
            _ <- ZIO.attemptBlocking(assert(fixture.prefixConsumed.await(5, TimeUnit.SECONDS)))
            phase <- ZIO.attemptBlocking(fixture.cleanupOrReturned.get(5, TimeUnit.SECONDS))
            echo <- ZIO.succeed(fixture.terminal.getAttributes.getLocalFlag(LocalFlag.ECHO))
            history <- ZIO.succeed(reader.getVariable(LineReader.DISABLE_HISTORY))
            _ <- ZIO.succeed { if eof then fixture.eof() else fixture.send("-private-suffix\n/help\n") }
            result <- fiber.join
            next <- input.discardRemainder *> input.readLine()
          } yield {
            assertEquals(echo, false, s"suffix arrived during $phase (browser): ${fixture.output.toString(UTF_8)}")
            assertEquals(history, java.lang.Boolean.TRUE)
            assertEquals(phase, "cleanup")
            assertEquals(
              result,
              if failAuth then ProviderLoginService.Result.Failed(ProviderLoginService.Error.AuthenticationFailed)
              else ProviderLoginService.Result.TimedOut
            )
            assertEquals(next, if eof then None else Some("/help"))
          }
        }
        assertEquals(fixture.terminal.getAttributes.toString, original)
        assertEquals(reader.getVariable(LineReader.DISABLE_HISTORY), "original-history-setting")
        assertEquals(reader.getBuffer.toString -> reader.getBuffer.cursor(), "exact draft" -> 4)
        assertEquals(reader.getHistory.size(), 1)
        assertEquals(Files.readString(path), config)
        assert(!fixture.output.toString(UTF_8).contains("private-suffix"))
      } finally {
        fixture.close()
        Files.deleteIfExists(path)
        Files.deleteIfExists(directory)
      }
    }
  }

  test("guard joins an interrupted reader's late atomic read before checking for a pending fragment") {
    val fixture = new EchoInput(holdEmptyRead = true)
    val reader = LineReaderBuilder.builder().terminal(fixture.terminal).build()
    val input = new TerminalProviderLoginInput(fixture.terminal, Some(reader))
    val original = fixture.terminal.getAttributes.toString
    try {
      runZIO {
        for {
          effectReturned <- Promise.make[Nothing, Unit]
          fiber <- input.privateInput {
            for {
              child <- (ZIO.succeed(fixture.send("partial-private")) *> input.readRedirect).fork
              _ <- ZIO.attemptBlocking(assert(fixture.emptyReadStarted.await(5, TimeUnit.SECONDS)))
              _ <- child.interruptFork
              _ <- effectReturned.succeed(())
            } yield ()
          }.fork
          _ <- effectReturned.await.timeoutFail(new Exception("reader did not start"))(5.seconds)
          result <- fiber.poll
          _ <- ZIO.succeed {
            assertEquals(result, None)
            assert(!fixture.terminal.getAttributes.getLocalFlag(LocalFlag.ECHO))
            assertEquals(reader.getVariable(LineReader.DISABLE_HISTORY), java.lang.Boolean.TRUE)
            fixture.releaseEmptyRead.countDown()
          }
          _ <- ZIO.attemptBlocking(assertEquals(fixture.cleanupOrReturned.get(5, TimeUnit.SECONDS), "cleanup"))
          _ <- ZIO.succeed(fixture.send("-private-suffix\n/help\n"))
          _ <- fiber.join
        } yield ()
      }
      assertEquals(fixture.terminal.getAttributes.toString, original)
      assertEquals(reader.getVariable(LineReader.DISABLE_HISTORY), null)
      assertEquals(runZIO(input.discardRemainder *> input.readLine()), Some("/help"))
      assert(!fixture.output.toString(UTF_8).contains("private-suffix"))
    } finally fixture.close()
  }

  test("nested private guards drain after read failure or external interruption, including EOF") {
    for (failure, eof) <- List(false -> false, false -> true, true -> false, true -> true) do {
      val fixture = new EchoInput
      val reader = LineReaderBuilder.builder().terminal(fixture.terminal).build()
      val input = new TerminalProviderLoginInput(fixture.terminal, Some(reader))
      val original = fixture.terminal.getAttributes.toString
      try {
        runZIO {
          for {
            fiber <- input
              .privateInput(
                input.privateInput(
                  ZIO.succeed(fixture.send("partial-private")) *>
                    input.readRedirect
                )
              )
              .fork
            _ <- ZIO.attemptBlocking(assert(fixture.prefixConsumed.await(5, TimeUnit.SECONDS)))
            _ <- if failure then ZIO.succeed(fixture.failRead()) else fiber.interruptFork
            _ <- ZIO.attemptBlocking(assertEquals(fixture.cleanupOrReturned.get(5, TimeUnit.SECONDS), "cleanup"))
            _ <- ZIO.succeed {
              assert(!fixture.terminal.getAttributes.getLocalFlag(LocalFlag.ECHO))
              assertEquals(reader.getVariable(LineReader.DISABLE_HISTORY), java.lang.Boolean.TRUE)
              // A second interruption must not release either guard while cleanup is waiting.
            }
            _ <- fiber.interruptFork.when(!failure)
            _ <- ZIO.succeed {
              if eof then fixture.eof() else fixture.send("-private-suffix\r\n/help\n")
            }
            result <- fiber.await
          } yield assert(result.isFailure)
        }
        assertEquals(fixture.terminal.getAttributes.toString, original)
        assertEquals(reader.getVariable(LineReader.DISABLE_HISTORY), null)
        assertEquals(runZIO(input.discardRemainder), ())
        assertEquals(runZIO(input.readLine()), if eof then None else Some("/help"))
        assertEquals("Discarding unfinished login input".r.findAllIn(fixture.output.toString(UTF_8)).length, 1)
        assert(!fixture.output.toString(UTF_8).contains("private-suffix"))
      } finally fixture.close()
    }
  }

  test("cleanup read failure stops input and nested guards cannot re-enable echo or leak reader errors") {
    val fixture = new EchoInput
    val reader = LineReaderBuilder.builder().terminal(fixture.terminal).build()
    reader.setVariable(LineReader.DISABLE_HISTORY, false)
    reader.getBuffer.write("exact draft")
    val original = fixture.terminal.getAttributes
    val input = new TerminalProviderLoginInput(fixture.terminal, Some(reader))
    try {
      val result = runZIO {
        for {
          fiber <- input
            .privateInput(
              input.privateInput(
                ZIO.succeed(fixture.send("partial-private")) *>
                  input.readRedirect
              )
            )
            .either
            .fork
          _ <- ZIO.attemptBlocking(assert(fixture.prefixConsumed.await(5, TimeUnit.SECONDS)))
          _ <- ZIO.succeed(fixture.failRead())
          _ <- ZIO.attemptBlocking(assertEquals(fixture.cleanupOrReturned.get(5, TimeUnit.SECONDS), "cleanup"))
          _ <- ZIO.succeed(fixture.failRead())
          result <- fiber.join
        } yield result
      }
      assertEquals(result.left.toOption.map(_.getMessage), Some("Terminal input stopped after private cleanup failed."))
      original.setLocalFlag(LocalFlag.ECHO, false)
      assertEquals(fixture.terminal.getAttributes.toString, original.toString)
      assertEquals(reader.getVariable(LineReader.DISABLE_HISTORY), java.lang.Boolean.FALSE)
      assertEquals(reader.getBuffer.toString, "exact draft")
      fixture.send("-private-suffix\n/help\n")
      for operation <- List(input.readLine().unit, input.discardRemainder, input.privateInput(ZIO.unit)) do
        assert(runZIO(operation.either).isLeft)
      assert(!fixture.terminal.getAttributes.getLocalFlag(LocalFlag.ECHO))
      assert(!fixture.output.toString(UTF_8).contains("private-suffix"))
      assert(!fixture.output.toString(UTF_8).contains("private read failure"))
      assert(fixture.output.toString(UTF_8).contains("stty echo"))
    } finally fixture.close()
  }

  test("ordinary reader preserves final EOF text, tabs and prompts beyond the private limit") {
    for (line <- List("/help", "/log\tdebug", "x" * 20000)) {
      val terminal = new DumbTerminal(new ByteArrayInputStream(line.getBytes(UTF_8)), new ByteArrayOutputStream())
      val input = new TerminalProviderLoginInput(terminal)
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
    val input = new TerminalProviderLoginInput(terminal)
    try {
      assert(runZIO(input.privateInput(input.readRedirect).either).isLeft)
      assertEquals(runZIO(input.discardRemainder *> input.readLine()), Some("/help"))
    } finally terminal.close()
  }

  private val unusedRecorder = new AudioRecorder {
    def record(seconds: Int): Task[CapturedAudio] = ZIO.dieMessage("Command input must not record audio")
    def recordUntil(termination: UIO[VoiceRecordingTermination]): Task[Option[CapturedAudio]] =
      ZIO.dieMessage("Command input must not record audio")
  }

  test("the first prompt starts in NORMAL; Esc does nothing and a single i begins editing") {
    val observed = scala.collection.mutable.ListBuffer.empty[String]
    val output = new ByteArrayOutputStream()
    val terminal = new DumbTerminal(
      "test",
      "xterm",
      new ByteArrayInputStream("\u001bxihello\n/exit\n".getBytes(UTF_8)),
      output,
      UTF_8
    )
    terminal.setSize(new Size(160, 24))
    val variables = new java.util.HashMap[String, Object]()
    variables.put(LineReader.AMBIGUOUS_BINDING, java.lang.Long.valueOf(50L))
    val reader = new StatusLineReader(terminal, variables)
    reader.getKeyMaps.put(LineReader.MAIN, reader.getKeyMaps.get(LineReader.VIINS))
    val client =
      new TerminalRepl(application((text, _) => observed += text), reader, unusedRecorder)
    val modes = scala.collection.mutable.ListBuffer.empty[String]
    val original = reader.getWidgets.get(LineReader.CALLBACK_INIT)
    reader.getWidgets.put(
      LineReader.CALLBACK_INIT,
      new Widget {
        override def apply(): Boolean = {
          val result = original.apply()
          modes += reader.getKeyMap
          result
        }
      }
    )
    try {
      runZIO(client.run.timeoutFail(new Exception("test timeout"))(3.seconds))
      assertEquals(modes.toList, List(LineReader.VICMD, LineReader.MAIN))
      assertEquals(observed.toList, List("hello"), "NORMAL input must not enter the draft before i")
      assert(output.toString(UTF_8).contains("[NORMAL]"))
      assertEquals(reader.getHistory.get(0), "hello")
      assertEquals(reader.getHistory.get(1), "/exit")
    } finally terminal.close()
  }

  test("startup NORMAL shortcuts work without first pressing Esc") {
    val terminal = new DumbTerminal(
      "test",
      "xterm",
      new ByteArrayInputStream("q".getBytes(UTF_8)),
      new ByteArrayOutputStream(),
      UTF_8
    )
    terminal.setSize(new Size(160, 24))
    val reader = new StatusLineReader(terminal, new java.util.HashMap[String, Object]())
    try {
      runZIO(
        new TerminalRepl(application, reader, unusedRecorder).run
          .timeoutFail(new Exception("test timeout"))(3.seconds)
      )
      assertEquals(reader.getHistory.size(), 1)
      assertEquals(reader.getHistory.get(0), "/exit")
    } finally terminal.close()
  }

  test("the terminal client handles help, rejects legacy login, and exits without invoking auth or an agent") {
    val commands = List("/help", "/login", "/login browser", "/exit")
    val output = new ByteArrayOutputStream()
    val terminal = new DumbTerminal(
      "test",
      "xterm",
      new ByteArrayInputStream(("i" + commands.mkString("\n") + "\n").getBytes(UTF_8)),
      output,
      UTF_8
    )
    val reader = new StatusLineReader(terminal, new java.util.HashMap[String, Object]())
    try {
      runZIO(
        new TerminalRepl(application, reader, unusedRecorder).run
          .timeoutFail(new Exception("test timeout"))(3.seconds)
      )
      val history = reader.getHistory.iterator()
      val lines = scala.collection.mutable.ListBuffer.empty[String]
      while history.hasNext do lines += history.next().line()
      assertEquals(lines.toList, commands)
      assert(!output.toString(UTF_8).contains("Redirect URL (hidden)"))
    } finally terminal.close()
  }

  test("unsupported terminals fail before reading commands, installing history or invoking an agent") {
    for terminalType <- List("dumb", "dumb-color") do {
      val output = new ByteArrayOutputStream()
      val terminal = new DumbTerminal(
        "test",
        terminalType,
        new ByteArrayInputStream("/help\n/exit\n".getBytes(UTF_8)),
        output,
        UTF_8
      )
      val attributes = terminal.getAttributes.toString
      try {
        val result = runZIO(
          TerminalRepl.runWithTerminal(application, terminal, unusedRecorder).either
        )
        assertEquals(
          result.left.toOption.map(_.getMessage),
          Some("The REPL requires an interactive terminal with line-editing support.")
        )
        assertEquals(output.size(), 0)
        assertEquals(terminal.getAttributes.toString, attributes)
      } finally terminal.close()
    }
  }

  test("line PROVIDER list cannot invoke legacy login and restores the exact draft") {
    val input = "idraft\u001bhpld\u001baiX\n/exit\n"
    val output = new ByteArrayOutputStream()
    val terminal = new DumbTerminal("test", "xterm", new ByteArrayInputStream(input.getBytes(UTF_8)), output, UTF_8)
    val variables = new java.util.HashMap[String, Object]()
    variables.put(LineReader.AMBIGUOUS_BINDING, java.lang.Long.valueOf(50L))
    val reader = new StatusLineReader(terminal, variables)
    reader.getKeyMaps.put(LineReader.MAIN, reader.getKeyMaps.get(LineReader.VIINS))
    val observed = scala.collection.mutable.ListBuffer.empty[String]
    val app = application((text, _) => observed += text)
    val recorder = new AudioRecorder {
      def record(seconds: Int): Task[CapturedAudio] = ZIO.dieMessage("Menu navigation must not record audio")
      def recordUntil(termination: UIO[VoiceRecordingTermination]): Task[Option[CapturedAudio]] =
        ZIO.dieMessage("Menu navigation must not record audio")
    }

    try {
      runZIO(
        new TerminalRepl(app, reader, recorder).run
          .timeoutFail(new Exception("test timeout"))(3.seconds)
      )
      assertEquals(observed.toList, List("draXft"))
      val history = reader.getHistory.iterator()
      val lines = scala.collection.mutable.ListBuffer.empty[String]
      while history.hasNext do lines += history.next().line()
      assertEquals(lines.toList, List("draXft", "/exit"))
      assert(!output.toString(UTF_8).contains("Redirect URL (hidden)"))
    } finally terminal.close()
  }

  /** Models the kernel's ECHO at byte arrival, not JLine's rendering of already-buffered input. */
  private final class EchoInput(holdEmptyRead: Boolean = false) extends AutoCloseable {
    val emptyReadStarted = new CountDownLatch(1)
    val releaseEmptyRead = new CountDownLatch(1)
    private val gate = new java.util.concurrent.atomic.AtomicBoolean(holdEmptyRead)
    val cleanupOrReturned = new CompletableFuture[String]()
    val prefixConsumed = new CountDownLatch(1)
    val output = new ByteArrayOutputStream() {
      override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = synchronized {
        super.write(bytes, offset, length)
        if toString(UTF_8).contains("Discarding unfinished login input") then cleanupOrReturned.complete("cleanup")
      }
    }
    private val pending = new LinkedBlockingDeque[Either[java.io.IOException, Int]]()
    private val source = new NonBlockingReader {
      private var ended = false
      protected def read(timeout: Long, peek: Boolean): Int = {
        if ended then return -1
        if pending.isEmpty then {
          prefixConsumed.countDown()
          if gate.compareAndSet(true, false) then {
            emptyReadStarted.countDown()
            if !releaseEmptyRead.await(5, TimeUnit.SECONDS) then throw new java.io.IOException("fixture gate expired")
          }
        }
        val value = if timeout <= 0 then pending.takeFirst() else pending.pollFirst(timeout, TimeUnit.MILLISECONDS)
        if value == null then NonBlockingReader.READ_EXPIRED
        else {
          if peek then pending.addFirst(value)
          val result = value.fold(throw _, identity)
          if result == -1 then ended = true
          result
        }
      }
      def readBuffered(buffer: Array[Char], offset: Int, length: Int, timeout: Long): Int = {
        val value = read(timeout, false)
        if value < 0 then value
        else { buffer(offset) = value.toChar; 1 }
      }
    }
    val terminal = new DumbTerminal(new ByteArrayInputStream(Array.emptyByteArray), output) {
      override def reader(): NonBlockingReader = source
    }
    private val initial = terminal.getAttributes
    initial.setLocalFlag(LocalFlag.ECHO, true)
    initial.setLocalFlag(LocalFlag.ICANON, true)
    initial.setLocalFlag(LocalFlag.ISIG, true)
    terminal.setAttributes(initial)

    def send(text: String): Unit = {
      if terminal.getAttributes.getLocalFlag(LocalFlag.ECHO) then output.write(text.getBytes(UTF_8))
      text.foreach(character => pending.addLast(Right(character.toInt)))
    }
    def eof(): Unit = { pending.addLast(Right(-1)); () }
    def failRead(): Unit = { pending.addLast(Left(new java.io.IOException("private read failure"))); () }
    def close(): Unit = { releaseEmptyRead.countDown(); eof(); terminal.close() }
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
    val user = new Agent[Any] {
      val id = AgentId("user")
      def turn(
          request: fmgp.yallmo.model.llm.AgentTurnRequest
      ): IO[YallmoError, fmgp.yallmo.model.llm.AgentTurnResponse] =
        ZIO.dieMessage("The user must not invoke a provider")
    }
    val instances = runZIO(RuntimeInstances.fromAgents(Chunk(user, agent)))
    val orchestrator = runZIO(OrchestratorLive.inMemory(instances))
    new ReplApplication(
      new ReplSession[Any](
        new AgentRunner[Any](Map.empty, Map.empty),
        Some(new AgentInstance[Any] {
          val id = AgentId("unused")
          def next(task: AgentTask, context: StaticContext[Any], runContext: RunContext): IO[YallmoError, AgentTurn] =
            FiberRef.currentLogLevel.get.map { level =>
              observe(task.prompt, level)
              AgentTurn(Some(Message.Assistant("ok")), AgentDecision.FinalAnswer("ok"))
            }
        }),
        StaticContext[Any]("test")
      ),
      ReplToolAvailability(Chunk.empty, Chunk.empty),
      ReplOrchestratorLive.make(orchestrator, user, Some(agent))
    )
  }

  private def runZIO[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
