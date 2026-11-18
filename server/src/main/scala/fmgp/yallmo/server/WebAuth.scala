package fmgp.yallmo.server

import fmgp.yallmo.model.{User, UserId}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.{Arrays, Base64, HexFormat}
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import scala.collection.mutable
import zio.*
import zio.http.*
import zio.json.*

/** Process-local, bounded sessions. Restarting the host revokes every cookie. No credentials enter RPC or logs. */
private[server] final class WebAuth(users: Map[UserId, User], lifetime: Duration = 8.hours) {
  import WebAuth.*

  private val random = new SecureRandom()
  private val passwordHashes = users.collect {
    case (id, User(userId, hash)) if id == userId =>
      val parts = hash.split("\\$")
      val salt = if parts(2) == "yallmo" then parts(2).getBytes(UTF_8) else HexFormat.of().parseHex(parts(2))
      UserId.value(id) -> PasswordHash(salt, HexFormat.of().parseHex(parts(3)))
  }
  private val sessions = mutable.Map.empty[String, Session]
  private var nextLogin = 0L

  private def verify(login: Login): UIO[Boolean] =
    if login.password.length > 1024 then ZIO.succeed(false)
    else
      ZIO
        .attemptBlocking {
          // Always do the same KDF, including for unknown users.
          val stored = passwordHashes.getOrElse(login.username, DummyPasswordHash)
          val derived = derivePassword(login.password, stored.salt)
          try {
            val matchesHash = MessageDigest.isEqual(stored.hash, derived)
            passwordHashes.contains(login.username) && matchesHash
          } finally Arrays.fill(derived, 0.toByte)
        }
        .orElseSucceed(false)

  def session(request: Request): UIO[Option[Session]] =
    Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).map { now =>
      synchronized {
        sessions.filterInPlace((_, session) => session.expires > now)
        token(request).flatMap(sessions.get)
      }
    }

  def valid(session: Session): UIO[Boolean] =
    Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).map { now =>
      synchronized(sessions.valuesIterator.exists(_ eq session)) && session.expires > now
    }

  def untilRevoked(session: Session): UIO[Unit] =
    Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS).flatMap { now =>
      session.revoked.await.raceFirst(ZIO.sleep(Duration.fromMillis((session.expires - now).max(0))))
    }

  def endpoint(request: Request): UIO[Response] = {
    val path = request.path.encode
    if !sameOrigin(request, required = request.method != Method.GET) then ZIO.succeed(Response.status(Status.Forbidden))
    else if path == "/auth/session" && request.method == Method.GET then
      session(request).map(_.fold(Response.status(Status.Unauthorized))(identityResponse))
    else if Set("/auth/login", "/auth/logout").contains(path) && request.method == Method.POST then
      if header(request, "X-Yallmo-CSRF") != Some("1") then ZIO.succeed(Response.status(Status.Forbidden))
      else if path == "/auth/logout" then
        ZIO
          .succeed(synchronized(token(request).flatMap(sessions.remove)))
          .flatMap(previous => ZIO.foreachDiscard(previous)(_.revoked.succeed(())))
          .as(Response.status(Status.NoContent).addHeader("Set-Cookie", cookie(request, "", expired = true)))
      else login(request)
    else ZIO.succeed(Response.status(Status.MethodNotAllowed))
  }

  private def login(request: Request): UIO[Response] = for {
    now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
    admitted <- ZIO.succeed(synchronized {
      if now < nextLogin then false
      else { nextLogin = now + 200; true }
    })
    response <-
      if !admitted then ZIO.succeed(Response.status(Status.TooManyRequests).addHeader("Retry-After", "1"))
      else
        request.body.asString.orElseSucceed("").flatMap { body =>
          val supplied = Option.when(body.length <= 8192)(body.fromJson[Login].toOption).flatten
          val verified = supplied match {
            case Some(login) => verify(login).map(valid => Option.when(valid)(login))
            case None        => ZIO.none
          }
          verified.flatMap {
            case None        => ZIO.sleep(300.millis).as(Response.status(Status.Unauthorized))
            case Some(login) =>
              for {
                createdAt <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
                revoked <- Promise.make[Nothing, Unit]
                bytes <- ZIO.succeed { val bytes = new Array[Byte](32); random.nextBytes(bytes); bytes }
                key = Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
                created = new Session(UserId(login.username), createdAt + lifetime.toMillis, revoked)
                previous <- ZIO.succeed(synchronized {
                  sessions.filterInPlace((_, session) => session.expires > createdAt)
                  val previous = token(request).flatMap(sessions.remove)
                  if sessions.size < 1024 then sessions.put(key, created)
                  previous
                })
                _ <- ZIO.foreachDiscard(previous)(_.revoked.succeed(()))
                retained <- valid(created)
              } yield
                if retained then identityResponse(created).addHeader("Set-Cookie", cookie(request, key))
                else Response.status(Status.ServiceUnavailable)
          }
        }
  } yield response

  private def identityResponse(session: Session): Response =
    Response.json(Map("username" -> UserId.value(session.user)).toJson)

  private def cookie(request: Request, value: String, expired: Boolean = false): String =
    s"$CookieName=$value; Path=/; HttpOnly; SameSite=Strict; Max-Age=${if expired then 0 else lifetime.toSeconds}${
        if header(request, "Origin").exists(_.startsWith("https://")) then "; Secure" else ""
      }"
}

private[server] object WebAuth {
  private val CookieName = "yallmo-session"
  private final case class PasswordHash(salt: Array[Byte], hash: Array[Byte])
  private val DummyPasswordHash = PasswordHash(new Array[Byte](16), new Array[Byte](32))

  private def derivePassword(password: String, salt: Array[Byte]): Array[Byte] = {
    val chars = password.toCharArray
    try {
      val spec = new PBEKeySpec(chars, salt, 600000, 256)
      try SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded
      finally spec.clearPassword()
    } finally Arrays.fill(chars, '\u0000')
  }
  private final case class Login(username: String, password: String) derives JsonDecoder

  final class Session(val user: UserId, val expires: Long, val revoked: Promise[Nothing, Unit])

  private def header(request: Request, name: String): Option[String] = {
    val values = request.headers.iterator.filter(_.headerName.equalsIgnoreCase(name)).map(_.renderedValue).toList
    if values.size == 1 then values.headOption else None
  }

  private def token(request: Request): Option[String] = {
    val tokens = request.headers.iterator
      .filter(_.headerName.equalsIgnoreCase("Cookie"))
      .flatMap { header =>
        header.renderedValue
          .split(";")
          .iterator
          .map(_.trim)
          .filter(_.startsWith(s"$CookieName="))
          .map(_.drop(CookieName.length + 1))
      }
      .toList
    tokens match {
      case value :: Nil if value.matches("[A-Za-z0-9_-]{43}") => Some(value)
      case _                                                  => None
    }
  }

  /** Match the browser-visible authority, not forwarded headers. Vite/reverse proxies must preserve Host and Origin.
    * Mutation and WS requests require Origin; safe non-browser HTTP reads can omit it. No CORS is enabled.
    */
  def sameOrigin(request: Request, required: Boolean): Boolean = {
    val origins = request.headers.iterator.count(_.headerName.equalsIgnoreCase("Origin"))
    val host = header(request, "Host").filter(_.matches("[A-Za-z0-9.\\-\\[\\]:]+"))
    val origin = header(request, "Origin")
    !header(request, "Sec-Fetch-Site").contains("cross-site") &&
    (if origins == 0 then !required
     else origin.exists(value => host.exists(host => value == s"http://$host" || value == s"https://$host")))
  }
}
