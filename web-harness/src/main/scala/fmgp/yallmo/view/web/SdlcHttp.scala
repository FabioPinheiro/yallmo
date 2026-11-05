package fmgp.yallmo.view.web

import org.scalajs.dom
import scala.concurrent.{ExecutionContext, Future}

private[web] enum SdlcCollection(val title: String, val endpoint: String) {
  case Tasks extends SdlcCollection("Tasks", "/api/sdlc/tasks")
  case Intents extends SdlcCollection("Intents", "/api/sdlc/intents")
}

/** Same-origin, read-only HTTP. Deliberately independent of WebSocket initialization and domain/core codecs. */
private[web] final class SdlcHttp(get: String => Future[String])(using ExecutionContext) {
  def load(collection: SdlcCollection): Future[List[SdlcRecord]] =
    get(collection.endpoint).flatMap { body =>
      SdlcRecord.decode(body) match {
        case Right(records) => Future.successful(records)
        case Left(error)    => Future.failed(new RuntimeException(s"${collection.endpoint}: $error"))
      }
    }
}

private[web] object SdlcHttp {
  def browser(using ExecutionContext): SdlcHttp = new SdlcHttp(endpoint =>
    dom
      .fetch(
        endpoint,
        new dom.RequestInit {
          method = dom.HttpMethod.GET
          cache = dom.RequestCache.`no-store`
        }
      )
      .toFuture
      .flatMap { response =>
        if response.ok then response.text().toFuture
        else
          Future.failed(new RuntimeException(s"GET $endpoint failed: HTTP ${response.status} ${response.statusText}"))
      }
  )
}
