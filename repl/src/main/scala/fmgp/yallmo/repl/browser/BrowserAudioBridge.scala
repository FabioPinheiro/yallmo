package fmgp.yallmo.repl.browser

import fmgp.yallmo.repl.voice.AudioRecorder
import zio.*
import zio.http.*
import zio.json.*

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

/** Own this in the REPL scope, initializing lazily on the first browser action. Startup defects become typed failures.
  * Neither construction nor operations print the credential-bearing setup URL; the terminal decides when to display it.
  */
final class BrowserAudioBridge private (
    val setupUrl: String,
    private val session: BrowserAudioSession
) {
  val recorder: AudioRecorder = session.recorder

  /** Completes on browser speech completion, not enqueue. Interruption cancels the browser operation on its next poll.
    */
  def speak(text: String): IO[BrowserAudioError, Unit] = session.speak(text)

  def status: UIO[BrowserAudioStatus] = session.status
}

object BrowserAudioBridge {
  import BrowserProtocol.*

  private lazy val processToken: String = {
    val bytes = new Array[Byte](32)
    new SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  private[browser] val defaultConfig: Server.Config = Server.Config.default.binding("0.0.0.0", 5180)

  /** Bind all interfaces for Docker publishing; the browser URL and Host/Origin boundary stay loopback. */
  def make: ZIO[Scope, BrowserAudioError, BrowserAudioBridge] =
    make(defaultConfig.address.getPort)

  /** Uses the configured port without changing the Docker-compatible all-interface binding. */
  def make(port: Int): ZIO[Scope, BrowserAudioError, BrowserAudioBridge] =
    if port < 1 || port > 65535 then ZIO.fail(failure("startup"))
    else start(defaultConfig.binding("0.0.0.0", port))

  private[repl] def start(config: Server.Config): ZIO[Scope, BrowserAudioError, BrowserAudioBridge] =
    ZIO
      .acquireReleaseExit(Scope.make)((scope, exit) => scope.close(exit))
      .flatMap { scope =>
        initialize(config).provideEnvironment(ZEnvironment[Scope](scope)).onExit {
          case Exit.Failure(cause) => scope.close(Exit.failCause(cause))
          case Exit.Success(_)     => ZIO.unit
        }
      }
      .catchAllCause(cause =>
        if cause.isInterrupted then ZIO.refailCause(cause.map(_ => failure("startup")))
        else
          ZIO.logWarning(s"Browser audio service failed to start port=${config.address.getPort}") *>
            ZIO.fail(failure("startup"))
      )

  private def initialize(config: Server.Config): ZIO[Scope, Throwable, BrowserAudioBridge] =
    for {
      portRef <- Ref.make(0)
      _ <- ZIO.addFinalizer(
        portRef.get.flatMap(port => ZIO.when(port > 0)(ZIO.logInfo(s"Browser audio service stopped port=$port")).unit)
      )
      session <- BrowserAudioSession.make
      server <- Server
        .defaultWith(_ =>
          config
            .disableRequestStreaming(MaxUploadBytes)
            .maxHeaderSize(8192)
            .idleTimeout(10.seconds)
            .gracefulShutdownTimeout(1.second)
            .logWarningOnFatalError(false)
        )
        .build
      routes = Routes(RoutePattern.any -> handler { (_: Path, request: Request) =>
        portRef.get.flatMap(port => serve(request, port, processToken, session))
      })
      port <- Server.install(routes).provideEnvironment(server)
      _ <- portRef.set(port)
      _ <- ZIO.logInfo(s"Browser audio service started bind=${config.address.getHostString} port=$port")
    } yield new BrowserAudioBridge(s"http://127.0.0.1:$port/#token=$processToken", session)

  private def serve(request: Request, port: Int, token: String, session: BrowserAudioSession): UIO[Response] = {
    val path = request.path.encode
    val action = for {
      _ <- verifySite(request, port)
      response <-
        if path.startsWith("/api/") then authenticate(request, token) *> api(request, path, session)
        else if request.method == Method.GET then asset(path)
        else ZIO.fail(failure("not_found"))
    } yield response
    action
      .catchAll(error => ZIO.succeed(errorResponse(error)))
      .catchAllCause(_ => ZIO.succeed(errorResponse(failure("internal"))))
      .map(
        _.addHeaders(
          Headers(
            Header.Custom("Cache-Control", "no-store"),
            Header.Custom("Referrer-Policy", "no-referrer"),
            Header.Custom("X-Content-Type-Options", "nosniff"),
            Header.Custom("X-Frame-Options", "DENY"),
            Header.Custom("Permissions-Policy", "microphone=(self)"),
            Header.Custom(
              "Content-Security-Policy",
              "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; worker-src 'self'; media-src 'self' blob:; base-uri 'none'; frame-ancestors 'none'"
            )
          )
        )
      )
  }

  private def header(request: Request, name: String): IO[BrowserAudioError, Option[String]] = {
    val values = request.headers.iterator.filter(_.headerName.equalsIgnoreCase(name)).map(_.renderedValue).toList
    if values.size > 1 then ZIO.fail(failure("bad_request")) else ZIO.succeed(values.headOption)
  }

  private def verifySite(request: Request, port: Int): IO[BrowserAudioError, Unit] = for {
    host <- header(request, "Host")
    origin <- header(request, "Origin")
    encoding <- header(request, "Content-Encoding")
    expected = s"127.0.0.1:$port"
    _ <- ZIO
      .fail(failure("forbidden"))
      .unless(
        port != 0 && host.contains(expected) && origin.forall(_ == s"http://$expected")
      )
    _ <- ZIO.fail(failure("bad_request")).unless(encoding.isEmpty)
  } yield ()

  private def authenticate(request: Request, token: String): IO[BrowserAudioError, Unit] =
    header(request, "Authorization").flatMap { authorization =>
      val valid =
        authorization.exists(value => MessageDigest.isEqual(value.getBytes(UTF_8), s"Bearer $token".getBytes(UTF_8)))
      ZIO.fail(failure("unauthorized")).unless(valid).unit
    }

  private def client(request: Request): IO[BrowserAudioError, String] =
    header(request, "X-Browser-Client").someOrFail(failure("wrong_client"))

  private def api(request: Request, path: String, session: BrowserAudioSession): IO[BrowserAudioError, Response] =
    (request.method, path) match {
      case (Method.POST, "/api/connect") =>
        decode[Connect](request)
          .flatMap(input => session.connect(input.clientId))
          .map(value => Response.json(value.toJson))
      case (Method.GET, "/api/poll") =>
        client(request).flatMap(session.poll).map(value => Response.json(value.toJson))
      case (Method.POST, "/api/event") =>
        for {
          id <- client(request)
          event <- decode[Event](request)
          _ <- session.event(id, event)
        } yield Response.json("{}")
      case (Method.POST, "/api/disconnect") =>
        client(request).flatMap(session.disconnect).as(Response.json("{}"))
      case (Method.POST, upload) if upload.startsWith("/api/audio/") =>
        for {
          id <- client(request)
          operationId = upload.stripPrefix("/api/audio/")
          _ <- session.checkUpload(id, operationId)
          bytes <- boundedBody(request, MaxUploadBytes).onError(_ =>
            session.event(id, Event(operationId, "error")).ignore
          )
          _ <- session.upload(id, operationId, bytes)
        } yield Response.json("{}")
      case _ => ZIO.fail(failure("not_found"))
    }

  private def decode[A: JsonDecoder](request: Request): IO[BrowserAudioError, A] =
    boundedBody(request, 4096).flatMap(bytes =>
      ZIO.fromEither(new String(bytes.toArray, UTF_8).fromJson[A]).mapError(_ => failure("bad_request"))
    )

  private def boundedBody(request: Request, limit: Int): IO[BrowserAudioError, Chunk[Byte]] =
    request.body.asStream
      .take(limit.toLong + 1)
      .runCollect
      .mapError(_ => failure("bad_request"))
      .timeoutFail(failure("timeout"))(4.seconds)
      .flatMap(bytes => if bytes.size <= limit then ZIO.succeed(bytes) else ZIO.fail(failure("too_large")))

  private def asset(path: String): IO[BrowserAudioError, Response] = {
    val name = if path == "/" then "index.html" else path.stripPrefix("/")
    if !name.matches("[A-Za-z0-9_-]+\\.(html|js|css)") then ZIO.fail(failure("not_found"))
    else
      ZIO
        .attemptBlocking {
          val stream = getClass.getResourceAsStream(s"/browser-STT-TTS/$name")
          if stream == null then None
          else
            try Some(Chunk.fromArray(stream.readNBytes(256 * 1024 + 1)))
            finally stream.close()
        }
        .mapError(_ => failure("not_found"))
        .flatMap {
          case Some(bytes) if bytes.size <= 256 * 1024 =>
            val contentType = if name.endsWith(".html") then "text/html; charset=utf-8"
            else if name.endsWith(".css") then "text/css; charset=utf-8"
            else "text/javascript; charset=utf-8"
            ZIO.succeed(Response(body = Body.fromChunk(bytes)).addHeader("Content-Type", contentType))
          case _ => ZIO.fail(failure("not_found"))
        }
  }

  private def errorResponse(error: BrowserAudioError): Response = {
    val status = error.code match {
      case "unauthorized"                                 => Status.Unauthorized
      case "forbidden" | "wrong_client"                   => Status.Forbidden
      case "client_conflict" | "stale_operation" | "busy" => Status.Conflict
      case "not_found"                                    => Status.NotFound
      case "too_large"                                    => Status.RequestEntityTooLarge
      case "timeout"                                      => Status.RequestTimeout
      case "closed"                                       => Status.ServiceUnavailable
      case "internal"                                     => Status.InternalServerError
      case _                                              => Status.BadRequest
    }
    Response.json(Error(error.code).toJson).status(status)
  }
}
