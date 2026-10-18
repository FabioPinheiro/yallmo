package fmgp.yallmo.repl.terminal

import fmgp.yallmo.repl.*
import fmgp.yallmo.orchestration.config.ProviderId
import org.jline.reader.LineReader
import org.jline.terminal.{Attributes, Terminal}
import org.jline.terminal.Attributes.LocalFlag
import org.jline.utils.NonBlockingReader
import zio.*

/** One terminal reader, shared with ordinary input. Secret input bypasses the line editor entirely: no widgets,
  * completion, history expansion, buffer insertion, or persistent history can see the callback.
  */
private[terminal] final class TerminalProviderLoginInput(terminal: Terminal, lineReader: Option[LineReader] = None)
    extends LoginInteraction {
  private var skipLf = false
  private var discardPendingLine = false
  private var inputStopped = false
  private var activePrivateReads = List.empty[Promise[Nothing, Unit]]

  // A timeout may return before its losing reader completes an atomic bounded read. Join its bookkeeping before
  // deciding whether a fragment needs draining; otherwise the guard can restore ECHO before the fragment is recorded.
  private def awaitPrivateReads: UIO[Unit] = ZIO.suspendSucceed {
    val reads = synchronized(activePrivateReads)
    ZIO.foreachDiscard(reads)(_.await)
  }

  private def checkInput: Task[Unit] = ZIO.suspendSucceed {
    if inputStopped then ZIO.fail(new java.io.IOException("Terminal input stopped after private cleanup failed."))
    else ZIO.unit
  }

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
    val clear = ZIO.succeed {
      if mask && !finished && text.length > 0 then discardPendingLine = true
      text.setLength(0)
    }
    if !mask then (checkInput *> loop).ensuring(clear)
    else
      ZIO.acquireReleaseWith(Promise.make[Nothing, Unit].tap { done =>
        ZIO.succeed { synchronized { activePrivateReads = done :: activePrivateReads } }
      }) { done =>
        clear *> ZIO.succeed { synchronized { activePrivateReads = activePrivateReads.filterNot(_ eq done) } } *>
          done.succeed(()).unit
      }(_ => checkInput *> loop)
  }

  def discardRemainder: Task[Unit] = checkInput *> ZIO.suspendSucceed {
    if discardPendingLine then privateInput(ZIO.unit) else ZIO.unit
  }

  /** Runs in the guard's uninterruptible release, before any attributes/history can be restored. No second reader,
    * buffer, or input-size limit is involved. A broken reader cannot establish a safe boundary: stop input and keep
    * echo off, including when an enclosing guard subsequently releases.
    */
  private def drainPendingLine: UIO[Unit] = ZIO.suspendSucceed {
    def loop: Task[Unit] = ZIO.attemptBlocking(terminal.reader().read(50L)).flatMap {
      case -1                                      => ZIO.succeed { discardPendingLine = false }
      case value if value == '\n' || value == '\r' =>
        ZIO.succeed { skipLf = value == '\r'; discardPendingLine = false }
      case _ => ZIO.yieldNow *> loop
    }
    if !discardPendingLine || inputStopped then ZIO.unit
    else
      (show("Discarding unfinished login input; press Enter to return to the REPL.").ignore *> loop)
        .catchAllCause { _ =>
          ZIO.succeed { inputStopped = true; discardPendingLine = false } *>
            show("Terminal cleanup failed; input stopped. Once back at the shell, restore echo with: stty echo").ignore
        }
  }

  /** Restore exact settings after draining on every exit, including interruption. Only a failed cleanup leaves ECHO
    * disabled and input stopped, since restoring it without a line boundary could expose the secret suffix.
    */
  def privateInput[A](effect: Task[A]): Task[A] =
    ZIO
      .acquireReleaseWith(checkInput *> ZIO.attempt {
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
        awaitPrivateReads *> drainPendingLine *> ZIO.attempt {
          if inputStopped then attributes.setLocalFlag(LocalFlag.ECHO, false)
          try terminal.setAttributes(attributes)
          finally lineReader.foreach(_.setVariable(LineReader.DISABLE_HISTORY, history.orNull))
        }.ignore
      }(_ => effect.exit)
      .flatMap(exit => checkInput *> ZIO.suspendSucceed(exit))

  /** Runs profile-scoped login with private terminal input. */
  def profileLogin(
      id: ProviderId,
      method: LoginMethod,
      service: Option[ProviderLoginService]
  ): UIO[ProviderLoginService.Result] =
    privateInput {
      for {
        redirect <- Promise.make[Throwable, Option[String]]
        // The service races redirect acquisition with cancellation. One reader owns both phases, so the
        // cancellation branch can never steal callback characters from readRedirect.
        interaction = new LoginInteraction {
          def show(message: String): Task[Unit] = TerminalProviderLoginInput.this.show(message)
          def readRedirect: Task[Option[String]] = show("Redirect URL (hidden):") *> redirect.await
          def awaitCancellation: Task[Unit] = method match {
            case LoginMethod.Browser =>
              TerminalProviderLoginInput.this.readLine(mask = true).exit.flatMap { exit =>
                redirect.done(exit) *> ZIO.suspendSucceed(exit).flatMap {
                  case Some(value) if value.trim.nonEmpty && value.trim != "cancel" =>
                    TerminalProviderLoginInput.this.awaitCancellation
                  case _ => ZIO.unit
                }
              }
          }
        }
        result <- service.fold[UIO[ProviderLoginService.Result]](
          ZIO.succeed(ProviderLoginService.Result.Failed(ProviderLoginService.Error.AuthenticationFailed))
        )(_.run(id, method, interaction))
      } yield result
    }.catchAll(_ => ZIO.succeed(ProviderLoginService.Result.Failed(ProviderLoginService.Error.AuthenticationFailed)))

}
