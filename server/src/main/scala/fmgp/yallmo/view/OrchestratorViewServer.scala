package fmgp.yallmo.view

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.view.ViewProtocol.*
import zio.*
import zio.http.*
import zio.json.*

/** Read-only HTTP adapter over the application's existing orchestrator. Construction starts nothing.
  *
  * Call [[start]] inside the application's scope. The listener binds loopback only: transcripts and attachments can
  * contain secrets. There is no remote authentication or public-hosting mode.
  */
final class OrchestratorViewServer(view: OrchestratorLive[?]) {
  val routes: Routes[Any, Nothing] = Routes(
    RoutePattern.any -> handler { (_: Path, request: Request) => respond(request) }
  )

  /** Installs the server in the caller's scope and returns the actual port (zero requests an ephemeral test port). */
  def start(port: Int = 8080): ZIO[Scope, Throwable, Int] =
    for {
      _ <- ZIO.fail(new IllegalArgumentException("Port must be between 0 and 65535")).unless(port >= 0 && port <= 65535)
      server <- Server
        .defaultWith(
          _.binding("127.0.0.1", port)
            .disableRequestStreaming(1024)
            .maxHeaderSize(8192)
            .gracefulShutdownTimeout(1.second)
        )
        .build
      bound <- Server.install(routes).provideEnvironment(server)
    } yield bound

  private def respond(request: Request): UIO[Response] = {
    val response =
      if !trustedSite(request) then ZIO.succeed(error(Status.Forbidden, "forbidden", "Loopback access only"))
      else if request.method != Method.GET then
        ZIO.succeed(error(Status.MethodNotAllowed, "read_only", "Only GET is supported").addHeader("Allow", "GET"))
      else
        request.path.encode.split("/", -1).toList match {
          case List("", "api", "messages") =>
            view.listMessages
              .map(messages => Response.json(messages.map(messageView).toList.toJson))
              .catchAll(domainError)
          case List("", "api", "agents") =>
            view.listAgentIds.map(ids => Response.json(ids.map(AgentId.value).toList.toJson)).catchAll(domainError)
          case List("", "api", "blobs") =>
            view.listBlobIds.map(ids => Response.json(ids.map(_.value).toList.toJson)).catchAll(domainError)
          case List("", "api", "sessions") =>
            view.listSessions
              .map { sessions =>
                Response.json(
                  sessions
                    .sortBy(_.id.value)
                    .map { session =>
                      SessionSummaryView(
                        session.id.value,
                        session.messageCount,
                        session.agentIds.map(AgentId.value).toList
                      )
                    }
                    .toList
                    .toJson
                )
              }
              .catchAll(domainError)
          case List("", "api", "sessions", id) =>
            SessionId.from(id) match {
              case Left(reason)     => ZIO.succeed(error(Status.BadRequest, "invalid_session_id", reason))
              case Right(sessionId) => view.loadSession(sessionId).map(sessionResponse).catchAll(domainError)
            }
          case List("", "api", "blobs", id) =>
            BlobId.from(id) match {
              case Left(reason)  => ZIO.succeed(error(Status.BadRequest, "invalid_blob_id", reason))
              case Right(blobId) =>
                view
                  .readBlob(blobId)
                  .map { blob =>
                    Response(body = Body.fromChunk(blob.bytes))
                      .addHeader("Content-Type", "application/octet-stream")
                      .addHeader("Content-Disposition", s"""attachment; filename="${blob.id.value}"""")
                  }
                  .catchAll(domainError)
            }
          case "" :: "api" :: _ => ZIO.succeed(error(Status.NotFound, "not_found", "Unknown API route"))
          case path             => asset(path)
        }
    response
      .catchAllCause { cause =>
        ZIO
          .logErrorCause("Orchestrator view request failed", cause)
          .as(
            error(Status.InternalServerError, "internal_error", "Unable to read orchestrator data")
          )
      }
      .map(
        _.addHeaders(
          Headers(
            Header.Custom("Cache-Control", "no-store"),
            Header.Custom("X-Content-Type-Options", "nosniff"),
            Header.Custom("Referrer-Policy", "no-referrer"),
            Header.Custom("X-Frame-Options", "DENY"),
            Header.Custom(
              "Content-Security-Policy",
              "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'"
            )
          )
        )
      )
  }

  private def sessionResponse(session: Session): Response =
    Response.json(
      SessionView(
        session.id.value,
        session.agentIds.map(AgentId.value).toList,
        session.messages.map(messageView).toList
      ).toJson
    )

  private def messageView(message: Message): MessageView =
    MessageView(
      MessageId.value(message.id),
      AgentId.value(message.agentId),
      message.parentId.map(MessageId.value),
      message.timestamp.toString,
      message.payload.role,
      message.payload.contentStr,
      message.payload.blobIds.map(_.value).toList,
      message.payload match {
        case MessagePayload.FunctionCallRequest(call)  => Some(call.name)
        case MessagePayload.FunctionCallOutput(output) => Some(output.name)
        case _                                         => None
      }
    )

  private def domainError(failure: YallmoError): UIO[Response] = failure match {
    case YallmoError.StorageFailed(underlying) => domainError(underlying)
    case _: YallmoError.SessionNotFound => ZIO.succeed(error(Status.NotFound, "session_not_found", "Session not found"))
    case _: YallmoError.BlobMissing     => ZIO.succeed(error(Status.NotFound, "blob_not_found", "Blob not found"))
    case other                          =>
      ZIO
        .logErrorCause("Orchestrator view storage read failed", Cause.fail(other))
        .as(
          error(Status.InternalServerError, "storage_error", "Unable to read orchestrator data")
        )
  }

  private def error(status: Status, code: String, message: String): Response =
    Response.json(ApiError(code, message).toJson).status(status)

  // Host validation prevents DNS rebinding; Origin validation also rejects cross-site local requests.
  // Vite's loopback development origins are allowed, but no CORS response headers are emitted.
  private def trustedSite(request: Request): Boolean = {
    def values(name: String): List[String] =
      request.headers.iterator.filter(_.headerName.equalsIgnoreCase(name)).map(_.renderedValue).toList

    values("Host") match {
      case List(host) if host.matches("(localhost|127\\.0\\.0\\.1):[0-9]{1,5}") =>
        values("Origin") match {
          case Nil          => true
          case List(origin) =>
            origin == s"http://$host" || origin == "http://localhost:5173" || origin == "http://127.0.0.1:5173"
          case _ => false
        }
      case _ => false
    }
  }

  private def asset(path: List[String]): UIO[Response] = {
    val name = path match {
      case List("", "") | List("", "index.html") => Some("index.html")
      case "" :: "assets" :: rest
          if rest.nonEmpty && rest.forall(segment => segment.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) =>
        Some(("assets" :: rest).mkString("/"))
      case _ => None
    }
    name match {
      case None           => ZIO.succeed(error(Status.NotFound, "not_found", "Asset not found"))
      case Some(resource) =>
        ZIO
          .attemptBlocking {
            val stream = getClass.getResourceAsStream(s"/view-web/$resource")
            if stream == null then None
            else
              try Some(Chunk.fromArray(stream.readAllBytes()))
              finally stream.close()
          }
          .map {
            case Some(bytes) =>
              val contentType =
                if resource.endsWith(".html") then "text/html; charset=utf-8"
                else if resource.endsWith(".js") then "text/javascript; charset=utf-8"
                else if resource.endsWith(".css") then "text/css; charset=utf-8"
                else "application/octet-stream"
              Response(body = Body.fromChunk(bytes)).addHeader("Content-Type", contentType)
            case None if resource == "index.html" =>
              error(Status.ServiceUnavailable, "spa_not_built", "SPA not packaged; use Vite or run server/bundle")
            case None => error(Status.NotFound, "not_found", "Asset not found")
          }
          .catchAll { cause =>
            ZIO
              .logErrorCause("Orchestrator view asset read failed", Cause.fail(cause))
              .as(
                error(Status.InternalServerError, "asset_error", "Unable to read asset")
              )
          }
    }
  }
}
