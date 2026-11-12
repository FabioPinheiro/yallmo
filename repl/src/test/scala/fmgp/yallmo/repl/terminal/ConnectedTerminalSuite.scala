package fmgp.yallmo.repl.terminal

import fmgp.yallmo.tool.FunctionKey

import fmgp.yallmo.clientharness.ControlAction

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.SessionId
import fmgp.yallmo.repl.*
import fmgp.yallmo.repl.voice.AudioRecorder
import fmgp.yallmo.server.OrchestratorClient
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PipedInputStream, PipedOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import org.jline.reader.LineReader
import org.jline.terminal.Size
import org.jline.terminal.impl.DumbTerminal
import zio.*

final class ConnectedTerminalSuite extends FunSuite {
  private val user = AgentId("user-human")
  private val snapshot = MessageSnapshot(
    MessageId.sha256("wire"),
    user,
    None,
    java.time.Instant.EPOCH,
    "tool\u009b",
    "hello\u001b[31mRED\u001b[0m\n\u0007",
    Chunk(BlobId.fresh()),
    Some(FunctionKey.unsafe("name\r\u001b"))
  )

  test("snapshot rendering keeps wire role, author, content, tool and blob references with controls sanitized") {
    val text = TerminalMessageRenderer.renderSnapshot(snapshot)
    assertEquals(text, s"tool[user-human] tool[name]> helloRED\n blob[${snapshot.blobIds.head}]")
    assert(!text.codePoints().anyMatch(code => Character.isISOControl(code) && code != '\n' && code != '\t'))
  }

  test("snapshot bodies preserve LF and tabs, normalize carriage returns and strip ANSI, controls and format escapes") {
    val content =
      "a\u001b[31m\nb\u001b[0m\tc\r\nd\re\u001b[2J\u0000\u0007\u0008\u000b\u007f\u0085\u009b\u009d\u200b\u202e\u2028\u2029"
    val message = snapshot.copy(content = content, blobIds = Chunk.empty)
    assertEquals(TerminalMessageRenderer.renderSnapshot(message), "tool[user-human] tool[name]> a\nb\tc\nd\ne")
    assertEquals(message.content, content)
    assertEquals(message.id, snapshot.id)
  }

  test("read-only menus/status never advertise voice, providers, login, submission or activation") {
    val normal = TerminalMenus.hintForKeyMap(LineReader.VICMD, true)
    val insert = TerminalMenus.hintForKeyMap(LineReader.VIINS, true)
    val agents = TerminalMenus.hintForKeyMap(TerminalMenus.AgentsKeyMap, true)
    assert(normal.contains("READ-ONLY")); assert(normal.contains("preference"))
    assert(insert.contains("/history")); assert(agents.contains("capability unknown"))
    List(normal, insert, agents).foreach(text =>
      List("voice", "provider", "login", "submit", "activate").foreach(word => assert(!text.contains(word)))
    )
  }

  List(false, true).foreach { executable =>
    test(s"connected byte input never creates browser/recorder/provider widgets even when canSubmit=$executable") {
      run(ZIO.scoped {
        for {
          app <- ConnectedReplApplication.make(if executable then operatorReads else reads, user)
          _ <- ZIO.succeed { assertEquals(app.canSubmit, executable); assert(app.remoteConnection) }
          output = new ByteArrayOutputStream
          terminal <- ZIO.acquireRelease(ZIO.attempt {
            val terminal = new DumbTerminal(
              "connected-test",
              "xterm",
              new ByteArrayInputStream("i/exit\n".getBytes(UTF_8)),
              output,
              UTF_8
            )
            terminal.setSize(new Size(120, 35))
            terminal
          })(t => ZIO.attempt(t.close()).ignore)
          reader = new StatusLineReader(terminal, new java.util.HashMap[String, Object]())
          recorder = new AudioRecorder {
            def record(seconds: Int) = ZIO.dieMessage("connected mode used local recorder")
            def recordUntil(termination: UIO[fmgp.yallmo.repl.voice.VoiceRecordingTermination]) =
              ZIO.dieMessage("connected mode used local recorder")
          }
          client = new TerminalRepl(
            app,
            reader,
            recorder,
            throw new AssertionError("connected mode constructed browser audio")
          )
          _ <- ZIO.succeed {
            assertEquals(Option(reader.getKeyMaps.get(LineReader.VICMD).getBound("p")), None)
            assert(!reader.getKeyMaps.containsKey(TerminalMenus.ProviderKeyMap))
            assert(
              !reader.getWidgets.containsKey(TerminalControlBindings.widgetName(LineReader.VICMD, ControlAction.Speak))
            )
            assert(!reader.getWidgets.containsKey(s"${TerminalProviderMode.KeyMapName}-enter"))
          }
          _ <- client.run
        } yield {
          assertEquals(reader.getHistory.size(), 1)
          assertEquals(reader.getHistory.get(0), "/exit")
          assert(
            !reader.getWidgets.containsKey(TerminalControlBindings.widgetName(LineReader.VICMD, ControlAction.Speak))
          )
          assert(!output.toString(UTF_8).contains("Browser audio setup"))
        }
      })
    }

  }

  test("connected provider menu logs in with hidden input and restores the exact draft without local services") {
    for restart <- List(false, true) do {
      val pipe = new PipedInputStream(65536)
      val writer = new PipedOutputStream(pipe)
      val output = new ByteArrayOutputStream
      val terminal = new DumbTerminal("connected-login", "xterm", pipe, output, UTF_8)
      terminal.setSize(new Size(160, 35))
      val historyFile = java.nio.file.Files.createTempFile("connected-login-history-", ".txt")
      val variables = new java.util.HashMap[String, Object]()
      variables.put(LineReader.HISTORY_FILE, historyFile)
      variables.put(LineReader.AMBIGUOUS_BINDING, java.lang.Long.valueOf(50L))
      val reader = new StatusLineReader(terminal, variables)
      reader.setHistory(new org.jline.reader.impl.history.DefaultHistory())
      reader.getKeyMaps.put(LineReader.MAIN, reader.getKeyMaps.get(LineReader.VIINS))
      val finished = new java.util.concurrent.atomic.AtomicBoolean(false)
      val restored = new java.util.concurrent.CopyOnWriteArrayList[(String, Int)]()
      val displays = new java.util.concurrent.CopyOnWriteArrayList[String]()
      val calls = new java.util.concurrent.CopyOnWriteArrayList[String]()
      val callback = "http://localhost:1455/auth/callback?state=synthetic&code=private-code"
      def send(text: String): Unit = { writer.write(text.getBytes(UTF_8)); writer.flush() }
      val client = new Reads {
        override def capabilities = ZIO.some(
          Initialize.Result(
            "test",
            Identity(
              UserId(AgentId.value(user).stripPrefix("user-")),
              Role.Operator,
              Chunk(user, UserId.websiteAgent(UserId(AgentId.value(user).stripPrefix("user-"))))
            ),
            List(
              TurnSubmit.method,
              ProvidersList.method,
              ProvidersLoginStart.method,
              ProvidersLoginComplete.method,
              ProvidersLoginCancel.method
            ),
            Nil,
            MaxBlobChunkBytes,
            Some(Chunk(AgentId("bob")))
          )
        )
        override def listProviders = ZIO.succeed {
          calls.add("list")
          Chunk(
            ProviderSummary(
              "account",
              ProviderKind.ChatGPT,
              if finished.get() then ProviderCredentialStatus.Configured else ProviderCredentialStatus.LoggedOut
            )
          )
        }
        override def startProviderLogin(params: ProvidersLoginStart.Params) = ZIO.succeed {
          assertEquals(params.providerId, "account")
          assert(!terminal.getAttributes.getLocalFlag(org.jline.terminal.Attributes.LocalFlag.ECHO))
          assertEquals(reader.getVariable(LineReader.DISABLE_HISTORY), java.lang.Boolean.TRUE)
          calls.add("start")
          send(callback + "\n")
          ProvidersLoginStart.Result(
            "attempt",
            "https://auth.openai.com/oauth/authorize?state=synthetic",
            Long.MaxValue
          )
        }
        override def completeProviderLogin(params: ProvidersLoginComplete.Params) = ZIO.succeed {
          assertEquals(params, ProvidersLoginComplete.Params("attempt", callback))
          calls.add("complete")
          ProvidersLoginComplete.Result(restart)
        }
        override def cancelProviderLogin(params: ProvidersLoginCancel.Params) = ZIO.succeed {
          calls.add("cancel")
          finished.set(true)
          Empty()
        }
      }
      try {
        val app = run(ConnectedReplApplication.make(client, user, Some(AgentId("bob"))))
        val attributes = terminal.getAttributes.toString
        val repl = new TerminalRepl(
          app,
          reader,
          None,
          throw new AssertionError("Connected login must not acquire a browser/audio server")
        )
        val previous = reader.getWidgets.get(LineReader.CALLBACK_INIT)
        reader.getWidgets.put(
          LineReader.CALLBACK_INIT,
          new org.jline.reader.Widget {
            def apply(): Boolean = {
              val result = previous.apply()
              if reader.getKeyMap == TerminalProviderMode.KeyMapName then {
                restored.add((reader.getBuffer.toString, reader.getBuffer.cursor()))
                displays.add(
                  reader
                    .getDisplayedBufferWithPrompts(new java.util.ArrayList[org.jline.utils.AttributedString]())
                    .toString
                )
                if finished.get() then send("\u001b\u001bq")
              }
              result
            }
          }
        )
        assert(Option(reader.getKeyMaps.get(LineReader.VICMD).getBound("p")).nonEmpty)
        send("idraft\u001bhp\nl")
        run(repl.run)
        import scala.jdk.CollectionConverters.*
        assert(restored.asScala.nonEmpty)
        assert(restored.asScala.forall(_ == (("draft", 3))))
        assertEquals(calls.asScala.count(_ == "start"), 1)
        assertEquals(calls.asScala.count(_ == "complete"), 1)
        assertEquals(calls.asScala.count(_ == "cancel"), 1)
        assert(displays.asScala.exists(_.contains(if restart then "Restart required" else "no restart or reselection")))
        assert(displays.asScala.forall(text => !text.contains("New provider") && !text.contains("Endpoint:")))
        assertEquals(reader.getHistory.size(), 1)
        assertEquals(reader.getHistory.get(0), "/exit")
        assert(!java.nio.file.Files.readString(historyFile).contains("private-code"))
        assert(!output.toString(UTF_8).contains("private-code"))
        assertEquals(terminal.getAttributes.toString, attributes)
        assertEquals(app.selectedAgentId, Some(AgentId("bob")))
        assertEquals(app.initialState.sessionId, None)
      } finally {
        writer.close(); terminal.close(); java.nio.file.Files.deleteIfExists(historyFile)
      }
    }
  }

  test("connected completions include only enabled commands and their arguments") {
    val parser = new org.jline.reader.impl.DefaultParser()
    def complete(text: String, connected: Boolean = true): Set[String] = {
      val completer = new TerminalRepl.ReplCompleter(connected)
      val candidates = new java.util.ArrayList[org.jline.reader.Candidate]()
      completer.complete(null, parser.parse(text, text.length), candidates)
      import scala.jdk.CollectionConverters.*
      candidates.asScala.map(_.value()).toSet
    }
    assertEquals(complete("/"), ConnectedReplApplication.Commands)
    List("/debug ", " /debug ", "\t/debug ").foreach { text =>
      assertEquals(complete(text), Set.empty[String])
      assertEquals(complete(text, connected = false), Set("print", "export", "request"))
    }
    List("/session ", " /session ", "\t/session ").foreach { text =>
      assertEquals(complete(text), Set("list", "resume"))
    }
    List("/log ", " /log ", "\t/log ").foreach { text =>
      assertEquals(complete(text), Set("trace", "debug", "info", "warning", "error", "fatal", "off"))
    }
    assertEquals(complete("/new"), Set.empty[String])
    val candidates = new java.util.ArrayList[org.jline.reader.Candidate]()
    new TerminalRepl.ReplCompleter(true, true).complete(null, parser.parse("/", 1), candidates)
    import scala.jdk.CollectionConverters.*
    assertEquals(candidates.asScala.map(_.value()).toSet, ConnectedReplApplication.Commands + "/new")
    val operatorHint = TerminalMenus.hintForKeyMap(LineReader.VIINS, true, true)
    assert(operatorHint.contains("submit")); assert(operatorHint.contains("/new"))
    assert(!operatorHint.contains("voice")); assert(!operatorHint.contains("provider"))
  }

  test("diagnostic completion exposes only request, and only with the negotiated permission") {
    val parser = new org.jline.reader.impl.DefaultParser()
    def complete(input: String, allowed: Boolean): Set[String] = {
      val candidates = new java.util.ArrayList[org.jline.reader.Candidate]()
      new TerminalRepl.ReplCompleter(true, true, allowed)
        .complete(null, parser.parse(input, input.length), candidates)
      import scala.jdk.CollectionConverters.*
      candidates.asScala.map(_.value()).toSet
    }
    assert(complete("/", true).contains("/debug"))
    assert(!complete("/", false).contains("/debug"))
    assertEquals(complete("/deb", true), Set("/debug"))
    for input <- List("/debug ", " /debug ", "\t/debug ", "/debug re") do {
      assertEquals(complete(input, true), Set("request"))
      assertEquals(complete(input, false), Set.empty[String])
    }
    assertEquals(complete("/debug p", true), Set.empty[String])
    assertEquals(complete("/debug e", true), Set.empty[String])
  }

  test("diagnostic rendering prints the document safely as data, never as a local file path") {
    val document = zio.json.ast.Json.Obj(
      "payload" -> zio.json.ast.Json.Obj("text" -> zio.json.ast.Json.Str("hello\u202e\u001b[2J")),
      "literal" -> zio.json.ast.Json.Str("/file-that-must-not-be-read")
    )
    val lines = TerminalEventRenderer.providerRequestLines(Some(document))
    assert(lines.head.contains("sensitive"))
    assert(lines.last.contains("payload")); assert(lines.last.contains("hello"))
    assert(lines.last.contains("/file-that-must-not-be-read"))
    assert(!lines.last.contains('\u202e')); assert(!lines.last.contains('\u001b'))
    assert(TerminalEventRenderer.providerRequestLines(None).head.contains("No provider request"))
  }

  private def operatorReads: OrchestratorClient = new Reads {
    override def capabilities = ZIO.some(
      Initialize.Result(
        "test",
        Identity(
          UserId(AgentId.value(user).stripPrefix("user-")),
          Role.Operator,
          Chunk(user, UserId.websiteAgent(UserId(AgentId.value(user).stripPrefix("user-"))))
        ),
        List(TurnSubmit.method),
        Nil,
        MaxBlobChunkBytes,
        Some(Chunk(AgentId("bob")))
      )
    )
  }
  private def reads: OrchestratorClient = new Reads
  private class Reads extends OrchestratorClient {
    def initialize(clientName: String) = ZIO.dieMessage("unexpected initialize")
    def listAgentIds = ZIO.succeed(Chunk(user))
    def listSessions = ZIO.succeed(Chunk.empty)
    def loadSession(id: SessionId) = ZIO.dieMessage("unexpected session read")
    def listMessages = ZIO.dieMessage("unexpected message read")
    def listBlobIds = ZIO.dieMessage("unexpected blob list")
    def readBlob(id: BlobId, offset: Long, maxBytes: Int) = ZIO.dieMessage("unexpected blob read")
    def close = ZIO.unit
  }
  private def run[A](effect: Task[A]): A = Unsafe.unsafe { unsafe ?=>
    Runtime.default.unsafe.run(effect.timeoutFail(new Exception("test timeout"))(10.seconds)).getOrThrowFiberFailure()
  }
}
