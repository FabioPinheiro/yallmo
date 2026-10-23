package fmgp.yallmo.view.web

import fmgp.yallmo.view.ViewProtocol.*
import org.scalajs.dom
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js.URIUtils.encodeURIComponent
import zio.json.*

/** Relative same-origin URLs work with the Vite proxy and the packaged JVM server alike. */
object ApiClient {
  def sessions()(using ExecutionContext): Future[List[SessionSummaryView]] =
    get[List[SessionSummaryView]]("/api/sessions")

  def session(id: String)(using ExecutionContext): Future[SessionView] =
    get[SessionView](s"/api/sessions/${encodeURIComponent(id)}")

  def messages()(using ExecutionContext): Future[List[MessageView]] = get[List[MessageView]]("/api/messages")

  def agents()(using ExecutionContext): Future[List[String]] = get[List[String]]("/api/agents")

  def blobs()(using ExecutionContext): Future[List[String]] = get[List[String]]("/api/blobs")

  def blobUrl(id: String): String = s"/api/blobs/${encodeURIComponent(id)}"

  private def get[A: JsonDecoder](path: String)(using ExecutionContext): Future[A] =
    dom.fetch(path).toFuture.flatMap { response =>
      response.text().toFuture.flatMap { body =>
        if !response.ok then
          val message = body.fromJson[ApiError].map(_.message).getOrElse(s"Request failed (HTTP ${response.status})")
          Future.failed(new RuntimeException(message))
        else
          body.fromJson[A] match {
            case Right(value) => Future.successful(value)
            case Left(_)      => Future.failed(new RuntimeException("The server returned an invalid response"))
          }
      }
    }
}
