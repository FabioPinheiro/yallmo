package fmgp.yallmo.provider.openai

import fmgp.yallmo.orchestration.config.OAuthCredentialsConfig
import fmgp.yallmo.model.YallmoError

import zio.*
import zio.http.*

import java.net.{URI, URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

final case class OpenAiCodexBrowserLogin(url: String, state: String, codeVerifier: String) {
  override def toString: String = "OpenAiCodexBrowserLogin(<redacted>)"
}

private[openai] final case class OpenAiCodexAuthorization(authorizationCode: String, codeVerifier: String) {
  override def toString: String = "OpenAiCodexAuthorization(<redacted>)"
}

object OpenAiCodexAuth {

  /** Storage-free OAuth login. Completion returns credentials for the caller to persist in its own profile store.
    *
    * Propose to move this is onw life. If this is the genetic OAuthFlow. Its Utiliti.
    */
  trait OAuthFlow {
    def startBrowserLogin: Task[OpenAiCodexBrowserLogin]
    def completeBrowserLogin(login: OpenAiCodexBrowserLogin, redirectUrl: String): Task[OAuthCredentialsConfig]
  }

  /** Uses only the supplied HTTP client; no credential store is constructed or consulted. */
  def oauthFlow(client: Client): OAuthFlow = new OAuthFlowLive(client)

  /** Obtains current credentials, refreshing expired tokens before returning them. */
  trait CredentialAccess {
    def validCredentials: Task[OAuthCredentialsConfig]
  }

  /** Magic value: fixed OAuth application identifier assigned by OpenAI for Codex, not derived from this code. Public
    * application metadata, not an API key or client secret.
    */
  val ClientId = "app_EMoamEEZ73f0CkXaXp7hrann"
  val AuthorizeUrl = "https://auth.openai.com/oauth/authorize"
  val TokenUrl = "https://auth.openai.com/oauth/token"
  val BrowserRedirectUri = "http://localhost:1455/auth/callback"
  val BrowserScope = "openid profile email offline_access"
  val BrowserLoginTimeout: Duration = 15.minutes
  val RefreshSkew: Duration = 1.minute
  private[openai] val AuthHttpTimeout: Duration = 30.seconds
  private[openai] def loginTimedOut: YallmoError.AuthenticationFailure =
    YallmoError.AuthenticationFailure("OpenAI subscription login timed out")

  /** Creates profile-local credential access, capturing the HTTP client from the ZIO environment at construction. No
    * files or environment variables are read/written.
    *
    * Refresh is deferred until inference, serialized per connection, and retained only in memory. Durable profile
    * persistence and REPL login integration remain separate application operations.
    */
  private[openai] def inMemory(
      initial: Option[OAuthCredentialsConfig]
  ): ZIO[Client, Nothing, CredentialAccess] =
    for {
      client <- ZIO.service[Client]
      credentials <- Ref.make(initial)
      lock <- Semaphore.make(1)
    } yield new CredentialAccess {
      override def validCredentials: Task[OAuthCredentialsConfig] =
        lock.withPermit(
          currentCredentials(
            client,
            credentials.get.someOrFail(YallmoError.AuthenticationFailure("ChatGPT profile is logged out")),
            value => credentials.set(Some(value))
          )
        )
    }

  private def currentCredentials(
      client: Client,
      load: Task[OAuthCredentialsConfig],
      save: OAuthCredentialsConfig => Task[Unit]
  ): Task[OAuthCredentialsConfig] =
    // A previous waiter may have rotated the refresh token; always reload inside the caller's lock.
    for {
      credentials <- load
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      refreshed <-
        if credentials.expiresAtMillis > now + RefreshSkew.toMillis then ZIO.succeed(credentials)
        else
          formRequest(
            client,
            Map(
              "grant_type" -> "refresh_token",
              "client_id" -> ClientId,
              "refresh_token" -> credentials.refreshToken
            )
          ).flatMap(body => ZIO.fromEither(parseCredentials(body)).mapError(YallmoError.AuthenticationFailure.apply))
            .tap(save)
    } yield refreshed

  private def formRequest(client: Client, values: Map[String, String]): Task[String] =
    val body = values.toList.map { case (key, value) => s"${encode(key)}=${encode(value)}" }.mkString("&")
    jsonRequest(
      client,
      Request.post(TokenUrl, Body.fromString(body)).addHeader("Content-Type", "application/x-www-form-urlencoded")
    )

  private def jsonRequest(client: Client, request: Request): Task[String] =
    authenticationResponse(client, request).flatMap { case (status, body) =>
      if status >= 200 && status < 300 then ZIO.succeed(body)
      else ZIO.fail(YallmoError.HttpFailure(s"OpenAI Codex authentication returned HTTP $status"))
    }

  private def authenticationResponse(client: Client, request: Request): Task[(Int, String)] =
    Client
      .batched(request)
      .provideEnvironment(ZEnvironment(client))
      .flatMap(response => response.body.asString.map(response.status.code -> _))
      // Transport errors can contain request/callback data. Never propagate their messages or causes.
      .mapError(_ => YallmoError.AuthenticationFailure("OpenAI Codex authentication request failed"))
      .timeoutFail(YallmoError.AuthenticationFailure("OpenAI Codex authentication request timed out"))(AuthHttpTimeout)

  private def encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

  private[openai] def parseBrowserRedirect(
      login: OpenAiCodexBrowserLogin,
      redirectUrl: String
  ): Either[String, OpenAiCodexAuthorization] = {
    // URI/decoder exception messages echo the input, so all syntax failures use a fixed message.
    val parsed = scala.util
      .Try {
        val uri = URI.create(redirectUrl.trim)
        require(
          uri.getScheme == "http" && uri.getHost == "localhost" && uri.getPort == 1455 &&
            uri.getRawPath == "/auth/callback" && uri.getRawUserInfo == null && uri.getRawFragment == null
        )
        val pairs = Option(uri.getRawQuery).toList.flatMap(_.split("&", -1).toList).map { pair =>
          val parts = pair.split("=", 2)
          require(parts.length == 2)
          URLDecoder.decode(parts(0), StandardCharsets.UTF_8) -> URLDecoder.decode(parts(1), StandardCharsets.UTF_8)
        }
        require(pairs.map(_._1).distinct.size == pairs.size)
        pairs.toMap
      }
      .toEither
      .left
      .map(_ => "Invalid OpenAI browser redirect URL")
    parsed.flatMap { parameters =>
      for {
        state <- parameters.get("state").filter(_.nonEmpty).toRight("OpenAI browser redirect is missing state")
        _ <- Either.cond(login.state.nonEmpty && state == login.state, (), "OpenAI browser redirect state mismatch")
        _ <- Either.cond(!parameters.contains("error"), (), "OpenAI browser authorization was denied")
        code <- parameters.get("code").filter(_.trim.nonEmpty).toRight("OpenAI browser redirect is missing code")
      } yield OpenAiCodexAuthorization(code, login.codeVerifier)
    }
  }

  private[openai] def parseCredentials(body: String): Either[String, OAuthCredentialsConfig] =
    parseObject(body).flatMap { json =>
      for {
        access <- stringField(json, "access_token")
        refresh <- stringField(json, "refresh_token")
        expiresIn <- json.get("expires_in").flatMap(_.numOpt).toRight("OpenAI Codex response is missing expires_in")
        now = java.lang.System.currentTimeMillis()
        _ <- Either.cond(
          expiresIn.isFinite && expiresIn > 0,
          (),
          "OpenAI Codex token expiry must be finite and positive"
        )
        millis = (BigDecimal(expiresIn) * 1000).toBigInt
        _ <- Either.cond(
          millis > 0 && millis <= BigInt(Long.MaxValue - now),
          (),
          "OpenAI Codex token expiry is outside the supported range"
        )
      } yield OAuthCredentialsConfig(access, refresh, now + millis.toLong)
    }

  private def parseObject(body: String): Either[String, collection.Map[String, ujson.Value]] =
    scala.util.Try(ujson.read(body).obj).toEither.left.map(_ => "Invalid OpenAI Codex authentication response")

  private def stringField(json: collection.Map[String, ujson.Value], name: String): Either[String, String] =
    json
      .get(name)
      .flatMap(_.strOpt)
      .filter(value => value.trim.nonEmpty && !value.exists(c => c == '\r' || c == '\n'))
      .toRight(s"OpenAI Codex response is missing or has invalid $name")

  private final class OAuthFlowLive(client: Client) extends OAuthFlow {

    /** Starts the Pi-style browser OAuth flow. The terminal caller pastes the final localhost redirect URL. */
    def startBrowserLogin: Task[OpenAiCodexBrowserLogin] =
      ZIO
        .attempt {
          val verifier = randomUrlSafe(32)
          val state = randomUrlSafe(16)
          val challenge = Base64.getUrlEncoder.withoutPadding.encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.UTF_8))
          )
          val query = Map(
            "response_type" -> "code",
            "client_id" -> ClientId,
            "redirect_uri" -> BrowserRedirectUri,
            "scope" -> BrowserScope,
            "code_challenge" -> challenge,
            "code_challenge_method" -> "S256",
            "state" -> state,
            "id_token_add_organizations" -> "true",
            "codex_cli_simplified_flow" -> "true",
            "originator" -> "yallmo"
          ).toList.map { case (key, value) => s"${encode(key)}=${encode(value)}" }.mkString("&")
          OpenAiCodexBrowserLogin(s"$AuthorizeUrl?$query", state, verifier)
        }
        .mapError(_ => YallmoError.AuthenticationFailure("Unable to start OpenAI browser login"))

    def completeBrowserLogin(login: OpenAiCodexBrowserLogin, redirectUrl: String): Task[OAuthCredentialsConfig] =
      (for {
        authorization <- ZIO
          .fromEither(parseBrowserRedirect(login, redirectUrl))
          .mapError(YallmoError.AuthenticationFailure.apply)
        credentials <- exchangeAuthorizationCode(authorization, BrowserRedirectUri)
      } yield credentials).timeoutFail(loginTimedOut)(BrowserLoginTimeout)

    private def exchangeAuthorizationCode(
        authorization: OpenAiCodexAuthorization,
        redirectUri: String
    ): Task[OAuthCredentialsConfig] =
      formRequest(
        client,
        Map(
          "grant_type" -> "authorization_code",
          "client_id" -> ClientId,
          "code" -> authorization.authorizationCode,
          "code_verifier" -> authorization.codeVerifier,
          "redirect_uri" -> redirectUri
        )
      ).flatMap(body => ZIO.fromEither(parseCredentials(body)).mapError(YallmoError.AuthenticationFailure.apply))

    private def randomUrlSafe(size: Int): String = {
      val bytes = new Array[Byte](size)
      new SecureRandom().nextBytes(bytes)
      Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    }

  }
}
