package fmgp.yallmo.repl.terminal

import fmgp.yallmo.repl.*
import fmgp.yallmo.repl.grammar.{CompletionRequest, ReplGrammar}
import fmgp.yallmo.provider.Transcriber
import fmgp.yallmo.repl.voice.AudioRecorder
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
final class LineTerminalRepl[R] private (
    application: ReplApplication[R],
    reader: LineReader,
    recorder: AudioRecorder,
    transcriber: Transcriber,
    login: SubscriptionLogin,
) {
  private val loginInput = new TerminalLoginInput(reader.getTerminal, Some(reader))
  private val prompt = "yallmo> "
  private val terminalStatus = new TerminalStatus(reader, application.activeAgent)
  private val voiceInsert = new VoiceInsert(reader, recorder, transcriber, terminalStatus)

  terminalStatus.install()
  voiceInsert.install()
  installQuitBinding()

  def run: ZIO[R, Nothing, Unit] =
    (for
      state <- Ref.make(application.initialState)
      previousReadWasInterrupt <- Ref.make(false)
      _ <- loop(state, previousReadWasInterrupt)
    yield ()).ensuring(saveHistory *> terminalStatus.close)

  private def loop(state: Ref[ReplState], previousReadWasInterrupt: Ref[Boolean]): ZIO[R, Nothing, Unit] =
    readLine.flatMap {
      case LineTerminalRepl.ReadResult.End       => Console.printLine("Bye.").orDie
      case LineTerminalRepl.ReadResult.Interrupt =>
        previousReadWasInterrupt.getAndSet(true).flatMap { wasInterrupted =>
          if LineTerminalRepl.shouldExitAfterInterrupt(wasInterrupted) then Console.printLine("Bye.").orDie
          else loop(state, previousReadWasInterrupt)
        }
      case LineTerminalRepl.ReadResult.Line(line) =>
        previousReadWasInterrupt.set(false) *>
          state.get.flatMap(application.submit(_, line)).flatMap(handleTransition).flatMap {
            case (nextState, exitsClient) =>
              state.set(nextState) *>
                (if exitsClient then ZIO.unit else loop(state, previousReadWasInterrupt))
          }
    }

  /** Renders semantic application events and applies their resulting session state. */
  private def handleTransition(transition: ReplTransition): ZIO[R, Nothing, (ReplState, Boolean)] =
    ZIO.foldLeft(transition.events)((transition.state, false)) { case ((currentState, exitsClient), event) =>
      if exitsClient then ZIO.succeed(currentState -> true)
      else
        event match {
          case ReplEvent.LoginRequested(method) => loginInput.login(method, login).as(currentState -> false)
          case ReplEvent.Help(_)                =>
            (TerminalEventRenderer.render(event) *>
              Console.printLine("Voice insert: Esc, then v. Space stops recording; Esc cancels.").orDie)
              .as(currentState -> false)
          case other => TerminalEventRenderer.render(other).as(currentState -> (other == ReplEvent.ExitRequested))
        }
    }

  private def readLine: UIO[LineTerminalRepl.ReadResult] =
    (loginInput.discardRemainder *> ZIO
      .attemptBlocking(LineTerminalRepl.ReadResult.Line(reader.readLine(prompt))))
      .catchSome {
        case _: UserInterruptException => ZIO.succeed(LineTerminalRepl.ReadResult.Interrupt)
        case _: EndOfFileException     => ZIO.succeed(LineTerminalRepl.ReadResult.End)
      }
      .catchAll(_ =>
        Console
          .printLineError(
            "Terminal input failed."
          )
          .orDie
          .as(LineTerminalRepl.ReadResult.End)
      )

  private def saveHistory: UIO[Unit] =
    ZIO.attemptBlocking(reader.getHistory.save()).ignore

  /** Binds Vi command-mode `q` to the existing typed `/exit` command rather than exiting the JVM from the widget. */
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

object LineTerminalRepl {
  private[repl] enum ReadResult {
    case Line(value: String)
    case Interrupt
    case End
  }

  /** Returns true only when the immediately preceding read was also interrupted. */
  private[repl] def shouldExitAfterInterrupt(previousReadWasInterrupt: Boolean): Boolean = previousReadWasInterrupt

  private val historyPath: Path =
    Paths.get(java.lang.System.getProperty("user.home"), ".yallmo", "yallmo-history")

  /** Runs a system terminal in line mode. `fallbackToPlain` is reserved for `--ui=auto`; explicit line mode fails when
    * JLine reports a dumb terminal.
    */
  def runSystem[R](
      application: ReplApplication[R],
      fallbackToPlain: Boolean,
      recorder: AudioRecorder,
      transcriber: Transcriber,
      login: SubscriptionLogin
  ): ZIO[R, Throwable, Unit] =
    ZIO.acquireReleaseWith(ZIO.attemptBlocking(TerminalBuilder.builder().system(true).build()))(closeTerminal) {
      terminal =>
        if terminal.getType.startsWith(Terminal.TYPE_DUMB) then
          if fallbackToPlain then
            Console.printLine("Interactive line editing is unavailable; using plain terminal mode.").orDie *>
              new BasicTerminalRepl(application, login).runWithTerminal(terminal)
          else ZIO.fail(IllegalStateException("Line UI requires an interactive terminal; use --ui=plain instead."))
        else
          ZIO
            .attemptBlocking(new LineTerminalRepl(application, buildReader(terminal), recorder, transcriber, login))
            .flatMap(_.run)
    }

  private def closeTerminal(terminal: Terminal): UIO[Unit] =
    ZIO.attemptBlocking(terminal.close()).ignore

  private def buildReader(terminal: Terminal): LineReader = {
    Option(historyPath.getParent).foreach(parent => Files.createDirectories(parent))
    // Equivalent to LineReaderBuilder's defaults, with a bounded status resize hook.
    val variables = new java.util.HashMap[String, Object]()
    // Keep Escape disambiguation responsive in Vi mode.
    variables.put(LineReader.AMBIGUOUS_BINDING, java.lang.Long.valueOf(50L))
    variables.put(LineReader.HISTORY_FILE, historyPath)
    val reader = new StatusLineReader(terminal, variables)
    reader.setHistory(new org.jline.reader.impl.history.DefaultHistory())
    reader.setCompleter(new ReplCompleter)

    // Start each prompt in Vi insert mode; Esc switches to the standard JLine Vi command map.
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
