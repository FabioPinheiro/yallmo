package fmgp.yallmo.repl.terminal

import fmgp.yallmo.repl.*
import fmgp.yallmo.repl.grammar.{CompletionRequest, ReplGrammar}
import fmgp.yallmo.repl.voice.AudioRecorder
import fmgp.yallmo.repl.browser.{BrowserAudioBridge, BrowserAudioError}
import org.jline.reader.{
  Candidate,
  Completer,
  EndOfFileException,
  LineReader,
  ParsedLine,
  Reference,
  UserInterruptException,
  Widget
}
import org.jline.terminal.{Terminal, TerminalBuilder}
import zio.*

import java.nio.file.{Files, Path, Paths}
import java.util.List

/** JLine-backed terminal client with editable input, persistent history, and grammar-driven Tab completion. */
final class TerminalRepl[R] private[terminal] (
    private var application: ReplApplication[R],
    reader: StatusLineReader,
    recorder: AudioRecorder,
    createBrowser: ZIO[Scope, BrowserAudioError, BrowserAudioBridge] = BrowserAudioBridge.make
) {
  private val loginInput = new TerminalProviderLoginInput(reader.getTerminal, Some(reader))
  private val terminalStatus = new TerminalVoiceModeStatus(reader, application.activeAgent)
  private val providerMenu = new TerminalProviderMode(reader)
  private val agentMenu = new TerminalAgentMode(reader)
  private val providerClient = application.providerCatalog.map(TerminalProviderMenu.client)
  private val menus = new TerminalMenus(
    reader,
    application.activeAgent,
    Some(() => providerMenu.open()),
    Some(() => agentMenu.open(application.agentChoices, application.activeAgent))
  )
  private val transcriber = application.transcriber
  terminalStatus.install()
  menus.install()
  installStartupMode()
  providerMenu.install()
  agentMenu.install()
  installQuitBinding()

  def run: ZIO[R, Nothing, Unit] =
    ZIO
      .scoped {
        for {
          state <- Ref.make(application.initialState)
          // Widgets must retain the configured file/console loggers across their unsafe runtime boundary.
          runtime <- ZIO.runtime[Any]
          scope <- ZIO.service[Scope]
          browser <- TerminalBrowserAudio.make(
            // A recording widget holds JLine's lock: never make capture wait for printAbove on another thread.
            url => ZIO.attemptBlocking(reader.printAbove(s"Browser audio setup: $url")).ignore.forkIn(scope).unit,
            createBrowser
          )
          _ <- ZIO.succeed {
            new VoiceInsert(reader, recorder, transcriber, terminalStatus, runtime = runtime).install()
            new VoiceInsert(
              reader,
              browser.recorder,
              transcriber,
              terminalStatus,
              "m",
              browser.recordingStarted,
              runtime
            )
              .install()
            installSpeechBinding(state, browser, scope, runtime)
          }
          previousReadWasInterrupt <- Ref.make(false)
          _ <- loop(state, previousReadWasInterrupt)
        } yield ()
      }
      .ensuring(ZIO.attemptBlocking {
        agentMenu.close(); providerMenu.close()
      }.ignore *> saveHistory *> terminalStatus.close)

  private def loop(state: Ref[ReplState], previousReadWasInterrupt: Ref[Boolean]): ZIO[R, Nothing, Unit] = {
    def submit(action: ReplState => ZIO[R, Nothing, ReplTransition]): ZIO[R, Nothing, Unit] =
      previousReadWasInterrupt.set(false) *>
        state.get.flatMap(action).flatMap(handleTransition).flatMap { case (nextState, exitsClient) =>
          state.set(nextState) *>
            (if exitsClient then ZIO.unit else loop(state, previousReadWasInterrupt))
        }

    readLine.flatMap {
      case TerminalRepl.ReadResult.End       => Console.printLine("Bye.").orDie
      case TerminalRepl.ReadResult.Interrupt =>
        previousReadWasInterrupt.getAndSet(true).flatMap { wasInterrupted =>
          if TerminalRepl.shouldExitAfterInterrupt(wasInterrupted) then Console.printLine("Bye.").orDie
          else loop(state, previousReadWasInterrupt)
        }
      case TerminalRepl.ReadResult.ProviderAction(action) =>
        previousReadWasInterrupt.set(false) *> handleProviderActions(action) *>
          loop(state, previousReadWasInterrupt)
      case TerminalRepl.ReadResult.AgentSelection(id) =>
        previousReadWasInterrupt.set(false) *> selectAgent(id, state) *> loop(state, previousReadWasInterrupt)
      case TerminalRepl.ReadResult.Line(line) => submit(application.submit(_, line))
    }
  }

  /** Switch only the runtime, retaining the conversation/session. Old opaque provider state is not replayable. */
  private def selectAgent(id: fmgp.yallmo.model.AgentId, state: Ref[ReplState]): UIO[Unit] =
    if application.activeAgent.exists(_.agentId == fmgp.yallmo.model.AgentId.value(id)) then
      ZIO.succeed(agentMenu.complete(Right(())))
    else
      application.selectAgent(id).either.flatMap {
        case Left(error) => ZIO.succeed(agentMenu.complete(Left(error.getMessage)))
        case Right(next) =>
          state.get.flatMap { previous =>
            ZIO.succeed {
              application = next
              terminalStatus.selectAgent(next.activeAgent)
              agentMenu.complete(Right(()))
            } *> state.set(previous.copy(continuationStart = previous.runContext.messages.size))
          }
      }

  /** Catalog effects run between readLine calls, never inside a widget or through agent submission. */
  private def handleProviderActions(
      action: TerminalProviderMenu.Action
  ): ZIO[R, Nothing, Unit] = {
    import TerminalProviderMenu.Action
    val execute = action match {
      case Action.Catalog(operation) =>
        providerClient
          .fold[IO[ReplProviderCatalog.Error, TerminalProviderMenu.Reply]](
            ZIO.fail(ReplProviderCatalog.Error.ReadFailed)
          )(_.execute(operation))
          .either
          .flatMap(result => ZIO.succeed(providerMenu.complete(result)))
      case Action.Login(id, method) =>
        loginInput
          .profileLogin(id, method, application.providerLogin)
          .flatMap(result => ZIO.succeed(providerMenu.completeLogin(result)))
      case Action.Close => ZIO.unit
    }
    execute *> ZIO.succeed(providerMenu.takeAction()).flatMap {
      case Some(next) => handleProviderActions(next)
      case None       => ZIO.unit
    }
  }

  /** Renders semantic application events and applies their resulting session state. */
  private def handleTransition(transition: ReplTransition): ZIO[R, Nothing, (ReplState, Boolean)] =
    ZIO.foldLeft(transition.events)((transition.state, false)) { case ((currentState, exitsClient), event) =>
      if exitsClient then ZIO.succeed(currentState -> true)
      else
        event match {
          case ReplEvent.Help(_) =>
            (TerminalEventRenderer.render(event) *>
              Console
                .printLine(
                  "NORMAL: Esc. p: PROVIDER (Up/Down, Enter: inspect/create; ChatGPT details: login); a: AGENTS; i: edit; v: JVM voice input; m: browser microphone; t: speak latest assistant; q: exit. Space stops recording; Esc cancels."
                )
                .orDie)
              .as(currentState -> false)
          case other =>
            TerminalEventRenderer
              .render(other)
              .as(currentState -> (other == ReplEvent.ExitRequested))
        }
    }

  private def readLine: UIO[TerminalRepl.ReadResult] =
    (loginInput.discardRemainder *> ZIO
      .attemptBlocking {
        val line = reader.readLine(TerminalPrompt.user(application.userId))
        agentMenu.takeSelection() match {
          case Some(id) => TerminalRepl.ReadResult.AgentSelection(id)
          case None     =>
            providerMenu.takeAction() match {
              case Some(action) => TerminalRepl.ReadResult.ProviderAction(action)
              case None         => TerminalRepl.ReadResult.Line(line)
            }
        }
      })
      .catchSome {
        case _: UserInterruptException => ZIO.succeed(TerminalRepl.ReadResult.Interrupt)
        case _: EndOfFileException     => ZIO.succeed(TerminalRepl.ReadResult.End)
      }
      .catchAll(_ =>
        Console
          .printLineError(
            "Terminal input failed."
          )
          .orDie
          .as(TerminalRepl.ReadResult.End)
      )

  private def saveHistory: UIO[Unit] =
    ZIO.attemptBlocking(reader.getHistory.save()).ignore

  /** Only the first prompt starts in NORMAL. Menu handoffs keep their restoration callbacks and later ordinary prompts
    * keep the existing INSERT default.
    */
  private def installStartupMode(): Unit = {
    var firstPrompt = true
    val existing = Option(reader.getWidgets.get(LineReader.CALLBACK_INIT))
    reader.getWidgets.put(
      LineReader.CALLBACK_INIT,
      new Widget {
        override def apply(): Boolean = {
          if firstPrompt then {
            firstPrompt = false
            reader.setKeyMap(LineReader.VICMD)
          }
          existing.forall(_.apply())
        }
      }
    )
  }

  /** Speech waits off the input thread; scope exit interrupts pending startup/speech. */
  private def installSpeechBinding(
      state: Ref[ReplState],
      browser: TerminalBrowserAudio,
      scope: Scope,
      runtime: Runtime[Any]
  ): Unit = {
    val name = "browser-speak"
    reader.getWidgets.put(
      name,
      new Widget {
        override def apply(): Boolean = {
          Unsafe.unsafe { unsafe ?=>
            val current = runtime.unsafe.run(state.get).getOrThrowFiberFailure()
            val speak = browser
              .speak(current)
              .catchAll(error => ZIO.attempt(reader.printAbove(s"Browser speech failed: ${error.getMessage}")).ignore)
            runtime.unsafe.run(speak.forkIn(scope)).getOrThrowFiberFailure()
          }
          true
        }
      }
    )
    reader.getKeyMaps.get(LineReader.VICMD).bind(new Reference(name), "t")
  }

  /** Binds NORMAL `q` to the existing typed `/exit` command rather than exiting the JVM from the widget. */
  private def installQuitBinding(): Unit = {
    val widgetName = "quit-repl"
    reader.getWidgets.put(
      widgetName,
      new Widget {
        override def apply(): Boolean = {
          reader.getBuffer.clear()
          reader.getBuffer.write("/exit")
          reader.callWidget(LineReader.ACCEPT_LINE)
          true
        }
      }
    )
    reader.getKeyMaps.get(LineReader.VICMD).bind(new Reference(widgetName), "q")
  }
}

object TerminalRepl {
  private[repl] enum ReadResult {
    case Line(value: String)
    case ProviderAction(value: TerminalProviderMenu.Action)
    case AgentSelection(id: fmgp.yallmo.model.AgentId)
    case Interrupt
    case End
  }

  /** Returns true only when the immediately preceding read was also interrupted. */
  private[repl] def shouldExitAfterInterrupt(previousReadWasInterrupt: Boolean): Boolean = previousReadWasInterrupt

  private val historyPath: Path =
    Paths.get(java.lang.System.getProperty("user.home"), ".yallmo", "yallmo-history")

  /** Runs the JLine client, restoring the system terminal on every exit. Unsupported terminals fail without fallback.
    */
  def runSystem[R](
      application: ReplApplication[R],
      recorder: AudioRecorder,
      browserAudioPort: Int = 5180
  ): ZIO[R, Throwable, Unit] =
    ZIO.acquireReleaseWith(ZIO.attemptBlocking(TerminalBuilder.builder().system(true).build()))(closeTerminal) {
      terminal => runWithTerminal(application, terminal, recorder, browserAudioPort)
    }

  private[terminal] def runWithTerminal[R](
      application: ReplApplication[R],
      terminal: Terminal,
      recorder: AudioRecorder,
      browserAudioPort: Int = 5180
  ): ZIO[R, Throwable, Unit] =
    if terminal.getType.startsWith(Terminal.TYPE_DUMB) then
      ZIO.fail(IllegalStateException("The REPL requires an interactive terminal with line-editing support."))
    else
      ZIO
        .attemptBlocking(
          new TerminalRepl(
            application,
            buildReader(terminal),
            recorder,
            BrowserAudioBridge.make(browserAudioPort)
          )
        )
        .flatMap(_.run)

  private def closeTerminal(terminal: Terminal): UIO[Unit] =
    ZIO.attemptBlocking(terminal.close()).ignore

  private def buildReader(terminal: Terminal): StatusLineReader = {
    Option(historyPath.getParent).foreach(parent => Files.createDirectories(parent))
    // Equivalent to LineReaderBuilder's defaults, with a bounded status resize hook.
    val variables = new java.util.HashMap[String, Object]()
    // Keep Escape disambiguation responsive in Vi mode.
    variables.put(LineReader.AMBIGUOUS_BINDING, java.lang.Long.valueOf(50L))
    variables.put(LineReader.HISTORY_FILE, historyPath)
    val reader = new StatusLineReader(terminal, variables)
    reader.setHistory(new org.jline.reader.impl.history.DefaultHistory())
    reader.setCompleter(new ReplCompleter)

    // Default subsequent prompts to INSERT; installStartupMode selects NORMAL for the first prompt only.
    reader.getKeyMaps.put(LineReader.MAIN, reader.getKeyMaps.get(LineReader.VIINS))
    reader
  }

  private final class ReplCompleter extends Completer {
    override def complete(reader: LineReader, line: ParsedLine, candidates: List[Candidate]): Unit = {
      val result = ReplGrammar.complete(CompletionRequest(line.line(), line.cursor()))
      result.candidates.foreach { candidate =>
        candidates.add(new Candidate(candidate.value, candidate.value, "REPL", candidate.description, null, null, true))
      }
    }
  }
}
