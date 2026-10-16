package fmgp.yallmo.provider.openai

import fmgp.yallmo.model.YallmoError

import zio.*
import zio.http.*

import java.net.{URI, URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Browser/device login and refresh for OpenAI's ChatGPT subscription service. */
final class OpenAiCodexAuth private[openai] (client: Client, secrets: OpenAiCodexSecrets) {
  import OpenAiCodexAuth.*

  // Construction stays synchronous for compatibility; permits and cancellation are managed by ZIO.
  private val refreshLock = Unsafe.unsafe { unsafe ?=> Semaphore.unsafe.make(1) }

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
    (for {
      interval <- ZIO
        .fromEither(boundedInterval(device.interval.toMillis.toDouble / 1000))
        .mapError(YallmoError.AuthenticationFailure.apply)
      authorization <- pollForAuthorization(device, interval)
      credentials <- exchangeAuthorizationCode(authorization, DeviceRedirectUri)
      _ <- secrets.save(credentials)
    } yield credentials).timeoutFail(loginTimedOut)(DeviceLoginTimeout)

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

  def completeBrowserLogin(login: OpenAiCodexBrowserLogin, redirectUrl: String): Task[OpenAiCodexCredentials] =
    (for {
      authorization <- ZIO
        .fromEither(parseBrowserRedirect(login, redirectUrl))
        .mapError(YallmoError.AuthenticationFailure.apply)
      credentials <- exchangeAuthorizationCode(authorization, BrowserRedirectUri)
      _ <- secrets.save(credentials)
    } yield credentials).timeoutFail(loginTimedOut)(DeviceLoginTimeout)

  def validCredentials: Task[OpenAiCodexCredentials] =
    refreshLock.withPermit {
      // A previous waiter may have rotated the refresh token; always reload inside the lock.
      for {
        credentials <- secrets.load
        now <- Clock.currentTime(TimeUnit.MILLISECONDS)
        refreshed <-
          if credentials.expiresAtMillis > now + RefreshSkew.toMillis then ZIO.succeed(credentials)
          else refresh(credentials).tap(secrets.save)
      } yield refreshed
    }

  private def pollForAuthorization(
      device: OpenAiCodexDeviceCode,
      interval: Duration
  ): Task[OpenAiCodexAuthorization] =
    authenticationResponse(
      Request
        .post(
          DeviceTokenUrl,
          Body.fromString(
            ujson.write(ujson.Obj("device_auth_id" -> device.deviceAuthId, "user_code" -> device.userCode))
          )
        )
        .addHeader("Content-Type", "application/json")
    ).flatMap { case (status, body) =>
      ZIO.fromEither(classifyDevicePoll(status, body)).mapError(YallmoError.AuthenticationFailure.apply).flatMap {
        case DevicePollResult.Authorized(authorization) => ZIO.succeed(authorization)
        case DevicePollResult.Pending                   => ZIO.sleep(interval) *> pollForAuthorization(device, interval)
        case DevicePollResult.SlowDown                  =>
          val slower = interval + 5.seconds
          ZIO.sleep(slower) *> pollForAuthorization(device, slower)
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
    authenticationResponse(request).flatMap { case (status, body) =>
      if status >= 200 && status < 300 then ZIO.succeed(body)
      else ZIO.fail(YallmoError.HttpFailure(s"OpenAI Codex authentication returned HTTP $status"))
    }

  private def authenticationResponse(request: Request): Task[(Int, String)] =
    Client
      .batched(request)
      .provideEnvironment(ZEnvironment(client))
      .flatMap(response => response.body.asString.map(response.status.code -> _))
      // Transport errors can contain request/callback data. Never propagate their messages or causes.
      .mapError(_ => YallmoError.AuthenticationFailure("OpenAI Codex authentication request failed"))
      .timeoutFail(YallmoError.AuthenticationFailure("OpenAI Codex authentication request timed out"))(AuthHttpTimeout)

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
) {
  override def toString: String = "OpenAiCodexDeviceCode(<redacted>)"
}

final case class OpenAiCodexBrowserLogin(url: String, state: String, codeVerifier: String) {
  override def toString: String = "OpenAiCodexBrowserLogin(<redacted>)"
}

private[openai] final case class OpenAiCodexAuthorization(authorizationCode: String, codeVerifier: String) {
  override def toString: String = "OpenAiCodexAuthorization(<redacted>)"
}

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
  private[openai] val AuthHttpTimeout: Duration = 30.seconds
  private[openai] val MaxDeviceInterval: Duration = 60.seconds
  private[openai] def loginTimedOut: YallmoError.AuthenticationFailure =
    YallmoError.AuthenticationFailure("OpenAI subscription login timed out")

  private[openai] enum DevicePollResult {
    case Pending, SlowDown
    case Authorized(value: OpenAiCodexAuthorization)
  }

  def live(client: Client): OpenAiCodexAuth = new OpenAiCodexAuth(client, OpenAiCodexSecrets.live)

  private[openai] def parseDeviceCode(body: String): Either[String, OpenAiCodexDeviceCode] =
    parseObject(body).flatMap { json =>
      for {
        deviceAuthId <- stringField(json, "device_auth_id")
        userCode <- stringField(json, "user_code")
        seconds <- json
          .get("interval")
          .flatMap(value => value.numOpt.orElse(value.strOpt.flatMap(_.trim.toDoubleOption)))
          .toRight("OpenAI Codex response is missing interval")
        interval <- boundedInterval(seconds)
      } yield OpenAiCodexDeviceCode(deviceAuthId, userCode, interval)
    }

  private def boundedInterval(seconds: Double): Either[String, Duration] =
    Either.cond(
      seconds.isFinite && seconds >= 0 && seconds <= MaxDeviceInterval.toSeconds,
      math.max(1000L, math.ceil(seconds * 1000).toLong).millis,
      "OpenAI Codex device interval must be finite and between 0 and 60 seconds"
    )

  private[openai] def classifyDevicePoll(status: Int, body: String): Either[String, DevicePollResult] =
    if status >= 200 && status < 300 then parseAuthorization(body).map(DevicePollResult.Authorized.apply)
    else {
      val errorCode = parseObject(body).toOption.flatMap(_.get("error")).flatMap { error =>
        error.strOpt.orElse(error.objOpt.flatMap(_.get("code")).flatMap(_.strOpt))
      }
      if errorCode.contains("slow_down") then Right(DevicePollResult.SlowDown)
      else if status == 403 || status == 404 || errorCode.contains("deviceauth_authorization_pending") then
        Right(DevicePollResult.Pending)
      else Left(s"OpenAI Codex device authentication returned HTTP $status")
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

  private[openai] def parseCredentials(body: String): Either[String, OpenAiCodexCredentials] =
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
      } yield OpenAiCodexCredentials(access, refresh, now + millis.toLong)
    }

  private def parseObject(body: String): Either[String, collection.Map[String, ujson.Value]] =
    scala.util.Try(ujson.read(body).obj).toEither.left.map(_ => "Invalid OpenAI Codex authentication response")

  private def stringField(json: collection.Map[String, ujson.Value], name: String): Either[String, String] =
    json
      .get(name)
      .flatMap(_.strOpt)
      .filter(value => value.trim.nonEmpty && !value.exists(c => c == '\r' || c == '\n'))
      .toRight(s"OpenAI Codex response is missing or has invalid $name")
}
