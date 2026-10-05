package fmgp.yallmo.provider.openai

import fmgp.yallmo.*
import fmgp.yallmo.model.YallmoError

import zio.*
import zio.http.*

import java.net.{URI, URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Device-code login and refresh protocol for OpenAI's ChatGPT Codex subscription service. */
final class OpenAiCodexAuth private[openai] (client: Client, secrets: OpenAiCodexSecrets) {
  import OpenAiCodexAuth.*

  def credentialsPath: java.nio.file.Path = secrets.path

  def startDeviceLogin: Task[OpenAiCodexDeviceCode] =
    jsonRequest(
      Request
        .post(DeviceUserCodeUrl, Body.fromString(s"{\"client_id\":\"$ClientId\"}"))
        .addHeader("Content-Type", "application/json")
    ).flatMap { body =>
      ZIO.fromEither(parseDeviceCode(body)).mapError(YallmoError.AuthenticationFailure.apply)
    }

  def completeDeviceLogin(device: OpenAiCodexDeviceCode): Task[OpenAiCodexCredentials] =
    for {
      deadline <- Clock.currentTime(TimeUnit.MILLISECONDS).map(_ + DeviceLoginTimeout.toMillis)
      authorization <- pollForAuthorization(device, deadline, device.interval)
      credentials <- exchangeAuthorizationCode(authorization, DeviceRedirectUri)
      _ <- secrets.save(credentials)
    } yield credentials

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
          "originator" -> "asar"
        ).toList.map { case (key, value) => s"${encode(key)}=${encode(value)}" }.mkString("&")
        OpenAiCodexBrowserLogin(s"$AuthorizeUrl?$query", state, verifier)
      }
      .mapError(error =>
        YallmoError.AuthenticationFailure(s"Unable to start OpenAI browser login: ${error.getMessage}")
      )

  def completeBrowserLogin(login: OpenAiCodexBrowserLogin, redirectUrl: String): Task[OpenAiCodexCredentials] =
    for {
      authorization <- ZIO
        .fromEither(parseBrowserRedirect(login, redirectUrl))
        .mapError(YallmoError.AuthenticationFailure.apply)
      credentials <- exchangeAuthorizationCode(authorization, BrowserRedirectUri)
      _ <- secrets.save(credentials)
    } yield credentials

  def validCredentials: Task[OpenAiCodexCredentials] =
    for {
      credentials <- secrets.load
      now <- Clock.currentTime(TimeUnit.MILLISECONDS)
      refreshed <-
        if credentials.expiresAtMillis - now > RefreshSkew.toMillis then ZIO.succeed(credentials)
        else refresh(credentials).tap(secrets.save)
    } yield refreshed

  private def pollForAuthorization(
      device: OpenAiCodexDeviceCode,
      deadlineMillis: Long,
      interval: Duration
  ): Task[OpenAiCodexAuthorization] =
    Clock.currentTime(TimeUnit.MILLISECONDS).flatMap { now =>
      if now >= deadlineMillis then ZIO.fail(YallmoError.AuthenticationFailure("OpenAI Codex device login timed out"))
      else
        jsonRequest(
          Request
            .post(
              DeviceTokenUrl,
              Body.fromString(s"{\"device_auth_id\":\"${device.deviceAuthId}\",\"user_code\":\"${device.userCode}\"}")
            )
            .addHeader("Content-Type", "application/json")
        ).either.flatMap {
          case Right(body) =>
            ZIO.fromEither(parseAuthorization(body)).mapError(YallmoError.AuthenticationFailure.apply)
          case Left(_: YallmoError.HttpFailure) =>
            ZIO.sleep(interval) *> pollForAuthorization(device, deadlineMillis, interval)
          case Left(error) => ZIO.fail(error)
        }
    }

  private def exchangeAuthorizationCode(
      authorization: OpenAiCodexAuthorization,
      redirectUri: String
  ): Task[OpenAiCodexCredentials] =
    formRequest(
      Map(
        "grant_type" -> "authorization_code",
        "client_id" -> ClientId,
        "code" -> authorization.authorizationCode,
        "code_verifier" -> authorization.codeVerifier,
        "redirect_uri" -> redirectUri
      )
    ).flatMap(body => ZIO.fromEither(parseCredentials(body)).mapError(YallmoError.AuthenticationFailure.apply))

  private def refresh(credentials: OpenAiCodexCredentials): Task[OpenAiCodexCredentials] =
    formRequest(
      Map(
        "grant_type" -> "refresh_token",
        "client_id" -> ClientId,
        "refresh_token" -> credentials.refreshToken
      )
    ).flatMap(body => ZIO.fromEither(parseCredentials(body)).mapError(YallmoError.AuthenticationFailure.apply))

  private def formRequest(values: Map[String, String]): Task[String] =
    val body = values.toList.map { case (key, value) => s"${encode(key)}=${encode(value)}" }.mkString("&")
    jsonRequest(
      Request.post(TokenUrl, Body.fromString(body)).addHeader("Content-Type", "application/x-www-form-urlencoded")
    )

  private def jsonRequest(request: Request): Task[String] =
    Client
      .batched(request)
      .provideEnvironment(ZEnvironment(client))
      .flatMap { response =>
        response.body.asString.flatMap { body =>
          if response.status.isSuccess then ZIO.succeed(body)
          else ZIO.fail(YallmoError.HttpFailure(s"OpenAI Codex authentication returned HTTP ${response.status.code}"))
        }
      }

  private def randomUrlSafe(size: Int): String = {
    val bytes = new Array[Byte](size)
    new SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }

  private def encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}

final case class OpenAiCodexDeviceCode(
    deviceAuthId: String,
    userCode: String,
    interval: Duration,
    verificationUri: String = OpenAiCodexAuth.DeviceVerificationUri
)

final case class OpenAiCodexBrowserLogin(url: String, state: String, codeVerifier: String)

private[openai] final case class OpenAiCodexAuthorization(authorizationCode: String, codeVerifier: String)

object OpenAiCodexAuth {
  val ClientId = "app_EMoamEEZ73f0CkXaXp7hrann"
  val AuthorizeUrl = "https://auth.openai.com/oauth/authorize"
  val TokenUrl = "https://auth.openai.com/oauth/token"
  val DeviceUserCodeUrl = "https://auth.openai.com/api/accounts/deviceauth/usercode"
  val DeviceTokenUrl = "https://auth.openai.com/api/accounts/deviceauth/token"
  val DeviceVerificationUri = "https://auth.openai.com/codex/device"
  val DeviceRedirectUri = "https://auth.openai.com/deviceauth/callback"
  val BrowserRedirectUri = "http://localhost:1455/auth/callback"
  val BrowserScope = "openid profile email offline_access"
  val DeviceLoginTimeout: Duration = 15.minutes
  val RefreshSkew: Duration = 1.minute

  def live(client: Client): OpenAiCodexAuth = new OpenAiCodexAuth(client, OpenAiCodexSecrets.live)

  private[openai] def parseDeviceCode(body: String): Either[String, OpenAiCodexDeviceCode] =
    parseObject(body).flatMap { json =>
      for {
        deviceAuthId <- stringField(json, "device_auth_id")
        userCode <- stringField(json, "user_code")
        interval <- deviceCodeInterval(json)
      } yield OpenAiCodexDeviceCode(deviceAuthId, userCode, interval.toLong.seconds)
    }

  private[openai] def parseAuthorization(body: String): Either[String, OpenAiCodexAuthorization] =
    parseObject(body).flatMap { json =>
      for {
        code <- stringField(json, "authorization_code")
        verifier <- stringField(json, "code_verifier")
      } yield OpenAiCodexAuthorization(code, verifier)
    }

  private[openai] def parseBrowserRedirect(
      login: OpenAiCodexBrowserLogin,
      redirectUrl: String
  ): Either[String, OpenAiCodexAuthorization] =
    scala.util
      .Try {
        val uri = URI.create(redirectUrl.trim)
        val parameters = Option(uri.getRawQuery).toList
          .flatMap(_.split("&").toList)
          .flatMap(_.split("=", 2).toList match {
            case key :: value :: Nil =>
              Some(URLDecoder.decode(key, StandardCharsets.UTF_8) -> URLDecoder.decode(value, StandardCharsets.UTF_8))
            case _ => None
          })
          .toMap
        val code =
          parameters.getOrElse("code", throw IllegalArgumentException("Redirect URL has no authorization code"))
        val state = parameters.getOrElse("state", throw IllegalArgumentException("Redirect URL has no state"))
        if state != login.state then
          throw IllegalArgumentException("Redirect URL state does not match the login request")
        OpenAiCodexAuthorization(code, login.codeVerifier)
      }
      .toEither
      .left
      .map(error => Option(error.getMessage).getOrElse("Invalid OpenAI redirect URL"))

  private[openai] def parseCredentials(body: String): Either[String, OpenAiCodexCredentials] =
    parseObject(body).flatMap { json =>
      for {
        access <- stringField(json, "access_token")
        refresh <- stringField(json, "refresh_token")
        expiresIn <- numberField(json, "expires_in")
        _ <- Either.cond(expiresIn > 0, (), "OpenAI Codex token expiry must be positive")
      } yield OpenAiCodexCredentials(access, refresh, java.lang.System.currentTimeMillis() + expiresIn.toLong * 1000L)
    }

  private def parseObject(body: String): Either[String, collection.Map[String, ujson.Value]] =
    scala.util.Try(ujson.read(body).obj).toEither.left.map(_ => "Invalid OpenAI Codex authentication response")

  private def stringField(json: collection.Map[String, ujson.Value], name: String): Either[String, String] =
    json.get(name).flatMap(_.strOpt).filter(_.nonEmpty).toRight(s"OpenAI Codex response is missing $name")

  private def deviceCodeInterval(json: collection.Map[String, ujson.Value]): Either[String, Double] =
    json
      .get("interval")
      .flatMap(value => value.numOpt.orElse(value.strOpt.flatMap(_.trim.toDoubleOption)))
      .filter(_ >= 0)
      .toRight("OpenAI Codex response is missing a non-negative interval")

  private def numberField(json: collection.Map[String, ujson.Value], name: String): Either[String, Double] =
    json.get(name).flatMap(_.numOpt).toRight(s"OpenAI Codex response is missing $name")
}
