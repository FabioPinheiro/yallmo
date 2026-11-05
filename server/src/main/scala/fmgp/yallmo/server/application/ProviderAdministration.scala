package fmgp.yallmo.server.application

import fmgp.yallmo.orchestration.config.{OAuthCredentialsConfig, ProviderId}
import fmgp.yallmo.provider.openai.{OpenAiCodexAuth, OpenAiCodexBrowserLogin}
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import java.util.UUID
import zio.*

/** Server-owned provider administration for trusted operators. Pending PKCE material never leaves the server. Attempts
  * are connection-bound, single-use, bounded and scoped to this application's lifetime. Completion saves credentials
  * privately and activates them in the runtime when the supplied application supports it.
  */
final class ProviderAdministration private (
    catalog: ProviderCatalog,
    auth: OpenAiCodexAuth.OAuthFlow,
    pending: Ref.Synchronized[Map[String, ProviderAdministration.Attempt]],
    commitLock: Semaphore,
    activate: (ProviderId, OAuthCredentialsConfig) => UIO[Boolean]
) {
  import ProviderAdministration.*

  def list: IO[JsonRpc.Error, ProvidersList.Result] =
    catalog.list
      .mapError(_ => unavailable)
      .map(entries =>
        Chunk.fromIterable(
          entries.map(entry =>
            ProviderSummary(
              ProviderId.value(entry.id),
              entry.providerType match {
                case ProviderCatalog.ProviderType.OAI     => ProviderKind.OAI
                case ProviderCatalog.ProviderType.ChatGPT => ProviderKind.ChatGPT
              },
              entry.credentials match {
                case ProviderCatalog.CredentialStatus.NotApplicable => ProviderCredentialStatus.NotApplicable
                case ProviderCatalog.CredentialStatus.LoggedOut     => ProviderCredentialStatus.LoggedOut
                case ProviderCatalog.CredentialStatus.Configured    => ProviderCredentialStatus.Configured
              }
            )
          )
        )
      )

  /** Admission is serialized with connection closure by the transport. Replaces that connection's earlier attempt. */
  def start(owner: UUID, providerId: String): IO[JsonRpc.Error, ProvidersLoginStart.Result] =
    for {
      id <- ZIO.fromEither(ProviderId.from(providerId)).mapError(_ => JsonRpc.Error.InvalidParams)
      _ <- catalog.validateLoginTarget(id).mapError(targetError)
      result <- pending.modifyZIO { current =>
        for {
          now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          live = current.filter { case (_, attempt) => attempt.expiresAtMillis > now && attempt.owner != owner }
          _ <- ZIO
            .fail(JsonRpc.Error(ErrorCode.Busy, "Too many pending provider logins"))
            .when(live.size >= MaxAttempts)
          login <- auth.startBrowserLogin.orElseFail(authenticationFailed)
          attemptId <- ZIO.succeed(UUID.randomUUID().toString)
          expires = now + OpenAiCodexAuth.BrowserLoginTimeout.toMillis
          attempt = Attempt(owner, id, login, expires)
        } yield (ProvidersLoginStart.Result(attemptId, login.url, expires), live.updated(attemptId, attempt))
      }
    } yield result

  /** Consumes the attempt before exchange, including failed/denied authorization. Never retries token exchange.
    * Cancellation/disconnect may interrupt authentication, but cannot interrupt private persistence and live activation
    * after authentication succeeds. A saved-only fallback reports restartRequired rather than claiming activation.
    */
  def complete(owner: UUID, request: ProvidersLoginComplete.Params): IO[JsonRpc.Error, ProvidersLoginComplete.Result] =
    ZIO.uninterruptibleMask { restore =>
      for {
        _ <- ZIO
          .fail(JsonRpc.Error.InvalidParams)
          .unless(
            request.attemptId.length <= 64 && request.redirectUrl.nonEmpty &&
              request.redirectUrl.length <= ProvidersLoginComplete.MaxRedirectChars
          )
        attempt <- pending
          .modify { current =>
            current.get(request.attemptId).filter(_.owner == owner) match {
              case Some(value) => (Some(value), current - request.attemptId)
              case None        => (None, current)
            }
          }
          .someOrFail(unknownAttempt)
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        _ <- ZIO.fail(unknownAttempt).unless(attempt.expiresAtMillis > now)
        _ <- restore(catalog.validateLoginTarget(attempt.providerId)).mapError(targetError)
        credentials <- restore(auth.completeBrowserLogin(attempt.login, request.redirectUrl))
          .orElseFail(authenticationFailed)
        active <- commitLock.withPermit {
          for {
            _ <- catalog.updateChatGptCredentials(attempt.providerId, credentials).mapError(targetError)
            active <- activate(attempt.providerId, credentials)
          } yield active
        }
      } yield ProvidersLoginComplete.Result(restartRequired = !active)
    }

  def cancel(owner: UUID, attemptId: String): UIO[Empty] =
    pending
      .update(current => if current.get(attemptId).exists(_.owner == owner) then current - attemptId else current)
      .as(Empty())

  def disconnect(owner: UUID): UIO[Unit] =
    pending.update(_.filterNot { case (_, attempt) => attempt.owner == owner })

  private[application] def expire: UIO[Unit] =
    Clock
      .currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      .flatMap(now => pending.update(_.filter { case (_, attempt) => attempt.expiresAtMillis > now }))
}

object ProviderAdministration {
  private val MaxAttempts = 64
  private final case class Attempt(
      owner: UUID,
      providerId: ProviderId,
      login: OpenAiCodexBrowserLogin,
      expiresAtMillis: Long
  ) {
    override def toString: String = "ProviderLoginAttempt(<redacted>)"
  }
  private val unavailable = JsonRpc.Error(ErrorCode.Rejected, "Provider configuration unavailable")
  private val authenticationFailed = JsonRpc.Error(ErrorCode.Rejected, "OpenAI login failed; start a new login")
  private val unknownAttempt =
    JsonRpc.Error(ErrorCode.NotFound, "Login attempt expired or unavailable; start a new login")

  private def targetError(error: ProviderCatalog.CredentialError): JsonRpc.Error = error match {
    case ProviderCatalog.CredentialError.MissingProvider => JsonRpc.Error(ErrorCode.NotFound, "Provider not found")
    case ProviderCatalog.CredentialError.NotChatGPT      =>
      JsonRpc.Error(ErrorCode.Rejected, "Provider does not support ChatGPT login")
    case _ => unavailable
  }

  /** Activation runs only after a successful save and shares its commit lock, preventing concurrent logins from
    * publishing credentials in a different order on disk and in memory. False means saved but restart is required.
    */
  def make(
      catalog: ProviderCatalog,
      auth: OpenAiCodexAuth.OAuthFlow,
      activate: (ProviderId, OAuthCredentialsConfig) => UIO[Boolean] = (_, _) => ZIO.succeed(false)
  ): ZIO[Scope, Nothing, ProviderAdministration] =
    for {
      pending <- Ref.Synchronized.make(Map.empty[String, Attempt])
      commitLock <- Semaphore.make(1)
      service = new ProviderAdministration(catalog, auth, pending, commitLock, activate)
      _ <- (ZIO.sleep(1.minute) *> service.expire).forever.forkScoped
      _ <- ZIO.addFinalizer(pending.set(Map.empty))
    } yield service
}
