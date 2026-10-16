package fmgp.yallmo.repl

import fmgp.yallmo.provider.openai.*
import fmgp.yallmo.repl.grammar.LoginMethod
import zio.*
import zio.http.Client

/** Terminal-local interaction only: redirects must never be submitted to ReplApplication. */
trait LoginInteraction {
  def show(message: String): Task[Unit]
  def readRedirect: Task[Option[String]]
  def awaitCancellation: Task[Unit]
}

enum LoginResult {
  case Saved, Cancelled, Failed, TimedOut
}

/** Injectable OAuth boundary; the live implementation delegates all OAuth and storage to OpenAiCodexAuth. */
trait SubscriptionLoginAuth {
  def startBrowser: Task[OpenAiCodexBrowserLogin]
  def completeBrowser(login: OpenAiCodexBrowserLogin, redirect: String): Task[Unit]
  def startDevice: Task[OpenAiCodexDeviceCode]
  def completeDevice(device: OpenAiCodexDeviceCode): Task[Unit]
}

final class SubscriptionLogin(
    auth: SubscriptionLoginAuth,
    private val loginTimeout: Duration = OpenAiCodexAuth.DeviceLoginTimeout
) {
  def run(method: LoginMethod, interaction: LoginInteraction): UIO[LoginResult] = {
    def cancellable(effect: Task[Unit]): Task[LoginResult] =
      effect.as(LoginResult.Saved).raceFirst(interaction.awaitCancellation.as(LoginResult.Cancelled))

    val login = for {
      _ <- interaction.show(
        "ChatGPT subscription login (not an API key). This does not switch the selected generation provider."
      )
      result <- method match {
        case LoginMethod.Browser =>
          for {
            login <- auth.startBrowser
            _ <- interaction.show(s"Open this login URL in your browser:\n${login.url}")
            _ <- interaction.show(
              "After approval, paste the FULL http://localhost:1455/auth/callback URL, including state.\n" +
                "The localhost page may fail to load; copy its address anyway. Do not share it.\n" +
                "Empty input, cancel, Ctrl+C or EOF cancels. During authorization exchange, Enter cancels."
            )
            redirect <- interaction.readRedirect
            result <- redirect.filter(value => value.trim.nonEmpty && value.trim != "cancel") match {
              case None        => ZIO.succeed(LoginResult.Cancelled)
              case Some(value) => cancellable(auth.completeBrowser(login, value.trim))
            }
          } yield result
        case LoginMethod.Device =>
          for {
            device <- auth.startDevice
            // Never display deviceAuthId, exchanged codes or credentials.
            _ <- interaction.show(s"Open ${device.verificationUri} and enter pairing code: ${device.userCode}")
            _ <- interaction.show("Waiting for browser approval (up to 15 minutes). Enter, Ctrl+C or EOF cancels.")
            result <- cancellable(auth.completeDevice(device))
          } yield result
      }
    } yield result

    login
      .timeout(loginTimeout)
      .map(_.getOrElse(LoginResult.TimedOut))
      // External exceptions may include callback URLs or tokens. Never render or log their details/causes.
      .catchAllCause(cause =>
        if cause.isInterruptedOnly then ZIO.refailCause(cause.stripFailures)
        else ZIO.succeed(LoginResult.Failed)
      )
  }
}

object SubscriptionLogin {
  val live: ZLayer[Client, Nothing, SubscriptionLogin] = ZLayer.fromFunction { (client: Client) =>
    val auth = OpenAiCodexAuth.live(client)
    new SubscriptionLogin(new SubscriptionLoginAuth {
      def startBrowser = auth.startBrowserLogin
      def completeBrowser(login: OpenAiCodexBrowserLogin, redirect: String) =
        auth.completeBrowserLogin(login, redirect).unit
      def startDevice = auth.startDeviceLogin
      def completeDevice(device: OpenAiCodexDeviceCode) = auth.completeDeviceLogin(device).unit
    })
  }
}
