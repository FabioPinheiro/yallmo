package fmgp.yallmo.repl

import fmgp.yallmo.orchestration.config.{OAuthCredentialsConfig, ProviderId}
import fmgp.yallmo.provider.openai.OpenAiCodexAuth
import zio.*

/** Profile-scoped login. OAuth is storage-free; only the selected catalog can persist returned credentials.
  *
  * Cancellation and the timeout cover authentication, not commit. After authentication wins, the latest-target check
  * and private atomic save finish without interruption. TODO Saved means persisted for restart, not active runtime
  * login. Redirect input must remain in the supplied terminal-private interaction, never in application commands or
  * logs.
  */
final class ProviderLoginService(
    catalog: ReplProviderCatalog,
    auth: OpenAiCodexAuth.OAuthFlow,
    loginTimeout: Duration = OpenAiCodexAuth.BrowserLoginTimeout
) {
  import ProviderLoginService.*

  def run(id: ProviderId, method: LoginMethod, interaction: LoginInteraction): UIO[Result] =
    ZIO
      .uninterruptibleMask { restore =>
        for {
          target <- restore(catalog.validateLoginTarget(id)).mapError(Error.Catalog(_))
          outcome <- restore(
            (interaction.show(
              s"ChatGPT subscription login for profile '${target.displayId}'. Saved credentials require restart; this does not switch the active agent."
            ) *> authenticate(method, interaction))
              .raceFirst(interaction.awaitCancellation.as(None))
              .timeout(loginTimeout)
          ).orElseFail(Error.AuthenticationFailed)
          result <- outcome match {
            case None                    => ZIO.succeed(Result.TimedOut)
            case Some(None)              => ZIO.succeed(Result.Cancelled)
            case Some(Some(credentials)) =>
              catalog.updateChatGptCredentials(id, credentials).mapError(Error.Catalog(_)).as(Result.Saved)
          }
        } yield result
      }
      .catchAllCause { cause =>
        if cause.isInterruptedOnly then ZIO.refailCause(cause.stripFailures)
        else ZIO.succeed(Result.Failed(cause.failureOption.getOrElse(Error.AuthenticationFailed)))
      }

  private def authenticate(
      method: LoginMethod,
      interaction: LoginInteraction
  ): Task[Option[OAuthCredentialsConfig]] = method match {
    case LoginMethod.Browser =>
      for {
        login <- auth.startBrowserLogin
        _ <- interaction.show(s"Open this login URL in your browser:\n${login.url}")
        _ <- interaction.show(
          "After approval, paste the FULL http://localhost:1455/auth/callback URL, including state.\n" +
            "The localhost page may fail to load; copy its address anyway. Do not share it.\n" +
            "Empty input, cancel, Ctrl+C or EOF cancels. During authorization exchange, Enter cancels."
        )
        redirect <- interaction.readRedirect
        credentials <- redirect.map(_.trim).filter(value => value.nonEmpty && value != "cancel") match {
          case None        => ZIO.none
          case Some(value) => auth.completeBrowserLogin(login, value).map(Some(_))
        }
      } yield credentials
  }
}

object ProviderLoginService {

  /** Fixed categories only: never contains provider/transport exceptions, tokens, or callback URLs. */
  enum Error {
    case AuthenticationFailed
    case Catalog(error: ReplProviderCatalog.CredentialError)
  }

  enum Result {
    case Saved, Cancelled, TimedOut
    case Failed(error: Error)
  }
}
