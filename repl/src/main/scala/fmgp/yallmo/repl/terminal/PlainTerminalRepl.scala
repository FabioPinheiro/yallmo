package fmgp.yallmo.repl.terminal

import fmgp.yallmo.repl.*
import zio.*

/** Plain terminal client used for redirected streams, CI, and terminals that cannot support a line editor.
  *
  * It owns only terminal I/O. Command execution and session transitions remain in [[ReplApplication]].
  */
final class BasicTerminalRepl[R](application: ReplApplication[R]) {
  private val prompt = "yallmo> "

  def run: ZIO[R, Nothing, Unit] =
    Ref.make(application.initialState).flatMap(loop)

  private def loop(state: Ref[ReplState]): ZIO[R, Nothing, Unit] =
    readLine.flatMap {
      case None       => Console.printLine("Bye.").orDie
      case Some(line) =>
        for {
          refState <- state.get
          transition <- application.submit(refState, line)
          _ <- state.set(transition.state)
          _ <- ZIO.foreachDiscard(transition.events)(render)
          _ <- if transition.exitsClient then ZIO.unit else loop(state)
        } yield ()
    }

  private def render(event: ReplEvent): UIO[Unit] =
    TerminalEventRenderer.render(event)

  private def readLine: UIO[Option[String]] =
    (Console.print(prompt).orDie *> Console.readLine.map(Some(_))).catchAll(_ => ZIO.succeed(None))
}
