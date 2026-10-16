package fmgp.yallmo.repl.terminal

import fmgp.yallmo.repl.*
import fmgp.yallmo.repl.grammar.LoginMethod
import org.jline.reader.LineReader
import org.jline.terminal.{Attributes, Terminal}
import org.jline.terminal.Attributes.LocalFlag
import org.jline.utils.NonBlockingReader
import zio.*

/** One terminal reader, shared with ordinary input. Secret input bypasses the line editor entirely: no widgets,
  * completion, history expansion, buffer insertion, or persistent history can see the callback.
  */
private[terminal] final class TerminalLoginInput(terminal: Terminal, lineReader: Option[LineReader] = None)
    extends LoginInteraction {
  private var skipLf = false
  private var discardPendingLine = false

  def show(message: String): Task[Unit] = ZIO.attempt {
    terminal.writer().println(message)
    terminal.flush()
  }

  def readRedirect: Task[Option[String]] =
    show("Redirect URL (hidden):") *> readLine(mask = true)

  def awaitCancellation: Task[Unit] = readLine(mask = true).unit

  /** Each bounded read and its bookkeeping are atomic with respect to fiber interruption. Otherwise a race winner could
    * consume (say) the leading slash of the next line without recording a pending fragment to discard.
    */
  def readLine(mask: Boolean = false): Task[Option[String]] = ZIO.suspendSucceed {
    val text = new java.lang.StringBuilder()
    var finished = false
    def step: Task[Option[Option[String]]] = ZIO.uninterruptible {
      ZIO.attemptBlocking(terminal.reader().read(50L)).flatMap { value =>
        ZIO.attempt {
          value match {
            case NonBlockingReader.READ_EXPIRED => None
            case '\n' if skipLf                 => skipLf = false; None
            case -1                             =>
              finished = true
              Some(if !mask && text.length > 0 then Some(text.toString) else None)
            case 3 | 4 if mask                           => finished = true; Some(None)
            case value if value == '\n' || value == '\r' =>
              skipLf = value == '\r'
              finished = true
              if mask then { terminal.writer().println(); terminal.flush() }
              Some(Some(text.toString))
            case 8 | 127 if mask =>
              skipLf = false
              if text.length > 0 then {
                text.setLength(text.length - 1)
                if mask then { terminal.writer().print("\b \b"); terminal.flush() }
              }
              None
            case value if mask && value < 32 => skipLf = false; None
            case value                       =>
              skipLf = false
              if mask && text.length >= 16384 then throw new java.io.IOException("Terminal input limit exceeded")
              text.append(value.toChar)
              if mask then { terminal.writer().print('*'); terminal.flush() }
              None
          }
        }
      }
    }
    def loop: Task[Option[String]] = step.flatMap {
      case Some(result) => ZIO.succeed(result)
      case None         => ZIO.yieldNow *> loop
    }
    loop.ensuring(ZIO.succeed {
      if mask && !finished && text.length > 0 then discardPendingLine = true
      text.setLength(0)
    })
  }

  def discardRemainder: Task[Unit] = ZIO.suspendSucceed {
    if !discardPendingLine then ZIO.unit
    else {
      discardPendingLine = false
      show("Discarding unfinished login input; press Enter to return to the REPL.") *>
        privateInput(readLine(mask = true).unit)
    }
  }

  /** Restore actual terminal attributes and the exact prior history setting on every exit. */
  def privateInput[A](effect: Task[A]): Task[A] =
    ZIO.acquireReleaseWith(ZIO.attempt {
      val attributes = new Attributes(terminal.getAttributes)
      val history = lineReader.map(_.getVariable(LineReader.DISABLE_HISTORY))
      try {
        val raw = new Attributes(attributes)
        raw.setLocalFlag(LocalFlag.ICANON, false)
        raw.setLocalFlag(LocalFlag.ECHO, false)
        raw.setLocalFlag(LocalFlag.ISIG, false)
        terminal.setAttributes(raw)
        lineReader.foreach(_.setVariable(LineReader.DISABLE_HISTORY, true))
        (attributes, history)
      } catch {
        case error: Throwable =>
          terminal.setAttributes(attributes)
          lineReader.foreach(_.setVariable(LineReader.DISABLE_HISTORY, history.orNull))
          throw error
      }
    }) { case (attributes, history) =>
      ZIO.attempt {
        terminal.setAttributes(attributes)
        lineReader.foreach(_.setVariable(LineReader.DISABLE_HISTORY, history.orNull))
      }.ignore
    }(_ => effect)

  def login(method: LoginMethod, service: SubscriptionLogin): UIO[Unit] =
    privateInput(service.run(method, this)).catchAll(_ => ZIO.succeed(LoginResult.Failed)).flatMap { result =>
      val message = result match {
        case LoginResult.Saved     => "ChatGPT subscription credentials saved privately to the project .env file."
        case LoginResult.Cancelled => "Subscription login cancelled."
        case LoginResult.TimedOut  => "Subscription login timed out; please try again."
        case LoginResult.Failed    => "Subscription login failed; please try again."
      }
      show(message).ignore
    }
}
