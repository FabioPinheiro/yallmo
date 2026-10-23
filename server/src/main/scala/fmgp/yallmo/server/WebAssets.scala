package fmgp.yallmo.server

import zio.*
import zio.http.{Body, Request, Response, Status}

/** Static infrastructure only. Unknown application/API paths never fall back to SPA HTML. */
private[server] object WebAssets {
  def response(request: Request): UIO[Response] = {
    val resource = request.path.encode.split("/", -1).toList match {
      case List("", "") | List("", "index.html") => Some("index.html")
      case "" :: "assets" :: rest if rest.nonEmpty && rest.forall(_.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) =>
        Some(("assets" :: rest).mkString("/"))
      case _ => None
    }
    resource match {
      case None       => ZIO.succeed(Response.text("Not found").status(Status.NotFound))
      case Some(name) =>
        ZIO
          .attemptBlocking {
            val stream = getClass.getResourceAsStream(s"/view-web/$name")
            if stream == null then None
            else
              try Some(Chunk.fromArray(stream.readAllBytes()))
              finally stream.close()
          }
          .map {
            case Some(bytes) =>
              val mime =
                if name.endsWith(".html") then "text/html; charset=utf-8"
                else if name.endsWith(".js") then "text/javascript; charset=utf-8"
                else if name.endsWith(".css") then "text/css; charset=utf-8"
                else "application/octet-stream"
              Response(body = Body.fromChunk(bytes)).addHeader("Content-Type", mime)
            case None if name == "index.html" =>
              Response.text("SPA not packaged; use Vite or run server/bundle").status(Status.ServiceUnavailable)
            case None => Response.text("Not found").status(Status.NotFound)
          }
          .catchAllCause { cause =>
            ZIO
              .logErrorCause("SPA asset read failed", cause)
              .as(Response.text("Unable to read asset").status(Status.InternalServerError))
          }
    }
  }

  def secure(request: Request, response: Response): Response = {
    val host = request.headers.iterator
      .find(_.headerName.equalsIgnoreCase("Host"))
      .map(_.renderedValue)
      .filter(_.matches("(localhost|127\\.0\\.0\\.1):[0-9]{1,5}"))
    response
      .addHeader("Cache-Control", "no-store")
      .addHeader("X-Content-Type-Options", "nosniff")
      .addHeader("Referrer-Policy", "no-referrer")
      .addHeader("X-Frame-Options", "DENY")
      .addHeader(
        "Content-Security-Policy",
        s"default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'${host
            .fold("")(value => s" ws://$value")}; base-uri 'none'; frame-ancestors 'none'"
      )
  }
}
