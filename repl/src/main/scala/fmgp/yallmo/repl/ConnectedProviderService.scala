package fmgp.yallmo.repl

import fmgp.yallmo.orchestration.config.ProviderId
import fmgp.yallmo.provider.openai.OpenAiCodexAuth
import fmgp.yallmo.server.OrchestratorClient
import fmgp.yallmo.server.application.ProviderCatalog
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import zio.*

/** Connected provider UI boundary. All configuration, PKCE, exchange, persistence and activation remain on the server.
  * No login mutation is retried. Once completion may have been sent, cancellation/lost replies are explicitly
  * uncertain, not a claim that the server rolled back. The shared connection, selected agent and session are never
  * replaced.
  */
final class ConnectedProviderService(
    client: OrchestratorClient,
    loginTimeout: Duration = OpenAiCodexAuth.BrowserLoginTimeout
) extends ProviderLoginService.Login {
  import ProviderLoginService.{Error, Result}

  override def inputFailure: Result = Result.Uncertain

  def list: IO[ProviderCatalog.Error, Vector[ProviderCatalog.Entry]] =
    client.listProviders
      .mapError(_ => ProviderCatalog.Error.ReadFailed)
      .map(_.map { entry =>
        ProviderCatalog.Entry(
          ProviderId.unsafe(entry.id),
          entry.id,
          entry.providerType match {
            case ProviderKind.OAI     => ProviderCatalog.ProviderType.OAI
            case ProviderKind.ChatGPT => ProviderCatalog.ProviderType.ChatGPT
          },
          "", // Endpoints are deliberately not part of the remote display contract.
          entry.credentials match {
            case ProviderCredentialStatus.NotApplicable => ProviderCatalog.CredentialStatus.NotApplicable
            case ProviderCredentialStatus.LoggedOut     => ProviderCatalog.CredentialStatus.LoggedOut
            case ProviderCredentialStatus.Configured    => ProviderCatalog.CredentialStatus.Configured
          }
        )
      }.toVector)

  def run(id: ProviderId, method: LoginMethod, interaction: LoginInteraction): UIO[Result] = ZIO.scoped {
    for {
      attempt <- Ref.make(Option.empty[String])
      completing <- Ref.make(false)
      _ <- ZIO.addFinalizer(
        attempt.get.flatMap(value =>
          ZIO.foreachDiscard(value)(id =>
            client.cancelProviderLogin(ProvidersLoginCancel.Params(id)).interruptible.timeout(2.seconds).ignore
          )
        )
      )
      workflow =
        for {
          started <- client
            .startProviderLogin(ProvidersLoginStart.Params(ProviderId.value(id)))
            .orElseFail(new IllegalStateException("Unable to start server login"))
          _ <- attempt.set(Some(started.attemptId))
          _ <- ZIO
            .fail(new IllegalStateException("Invalid server login URL"))
            .unless(
              started.authorizationUrl.startsWith("https://auth.openai.com/oauth/authorize?") &&
                started.authorizationUrl.forall(char =>
                  !Character.isISOControl(char) &&
                    !Set(Character.FORMAT.toInt, Character.LINE_SEPARATOR.toInt, Character.PARAGRAPH_SEPARATOR.toInt)
                      .contains(Character.getType(char))
                )
            )
          _ <- interaction.show("Open this OpenAI login URL in your browser:\n" + started.authorizationUrl)
          _ <- interaction.show(
            "After approval, paste the FULL http://localhost:1455/auth/callback URL, including code and state.\n" +
              "The localhost page may fail to load; copy its address anyway. Keep this URL private.\n" +
              "Empty input, cancel, Ctrl+C or EOF cancels before exchange. During saving, Enter abandons the wait; the server may still save."
          )
          redirect <- interaction.readRedirect
          result <- redirect.map(_.trim).filter(value => value.nonEmpty && value != "cancel") match {
            case None                                                                  => ZIO.succeed(Result.Cancelled)
            case Some(value) if value.length > ProvidersLoginComplete.MaxRedirectChars =>
              ZIO.succeed(Result.Failed(Error.AuthenticationFailed))
            case Some(value) =>
              method match {
                case LoginMethod.Browser =>
                  completing.set(true) *> client
                    .completeProviderLogin(ProvidersLoginComplete.Params(started.attemptId, value))
                    .fold(
                      _ => Result.Uncertain,
                      result => if result.restartRequired then Result.Saved else Result.Activated
                    )
              }
          }
        } yield result
      cancelled = completing.get.map(sent => if sent then Result.Uncertain else Result.Cancelled)
      result <- workflow
        .raceFirst(interaction.awaitCancellation *> cancelled)
        .timeout(loginTimeout)
        .flatMap {
          case Some(result) => ZIO.succeed(result)
          case None         => completing.get.map(sent => if sent then Result.Uncertain else Result.TimedOut)
        }
        .catchAllCause { cause =>
          if cause.isInterruptedOnly then ZIO.refailCause(cause.stripFailures)
          else completing.get.map(sent => if sent then Result.Uncertain else Result.Failed(Error.AuthenticationFailed))
        }
    } yield result
  }
}
