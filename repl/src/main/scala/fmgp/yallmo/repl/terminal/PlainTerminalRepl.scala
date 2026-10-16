package fmgp.yallmo.repl.terminal

import fmgp.yallmo.repl.*
import org.jline.terminal.{Terminal, TerminalBuilder}
import zio.*

/** Plain terminal client for redirected streams and terminals without a line editor. Uses one nonblocking input reader
  * for commands and private login prompts; no persistent command history is installed.
  */
final class BasicTerminalRepl[R](application: ReplApplication[R], login: SubscriptionLogin) {
  def run: ZIO[R, Nothing, Unit] =
    ZIO
      .acquireReleaseWith(
        ZIO.attemptBlocking(TerminalBuilder.builder().system(true).dumb(true).build())
      )(terminal => ZIO.attemptBlocking(terminal.close()).ignore)(runWithTerminal)
      .catchAll(_ => Console.printLineError("Terminal input failed.").ignore)

  private[terminal] def runWithTerminal(terminal: Terminal): ZIO[R, Nothing, Unit] = {
    val input = new TerminalLoginInput(terminal)
    def loop(state: ReplState): ZIO[R, Nothing, Unit] =
      (input.discardRemainder *> ZIO.attempt {
        terminal.writer().print("yallmo> ")
        terminal.flush()
      } *> input.readLine()).either.flatMap {
        case Left(_) | Right(None) => Console.printLine("Bye.").ignore
        case Right(Some(line))     =>
          for {
            transition <- application.submit(state, line)
            _ <- ZIO.foreachDiscard(transition.events) {
              case ReplEvent.LoginRequested(method) =>
                input
                  .show(
                    "Plain mode cannot guarantee terminal echo is disabled; prefer /login device on an unsupported TTY."
                  )
                  .ignore *>
                  input.login(method, login)
              case other => TerminalEventRenderer.render(other)
            }
            _ <- if transition.exitsClient then ZIO.unit else loop(transition.state)
          } yield ()
      }
    loop(application.initialState)
  }
}
