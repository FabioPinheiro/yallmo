package fmgp.yallmo.view.web

import scala.scalajs.js.URIUtils.{decodeURIComponent, encodeURIComponent}
import scala.util.Try

/** Hash navigation needs no server-side SPA fallback and supports refresh, bookmarks and back/forward. */
object SessionRoute {
  def href(id: String): String = s"#/sessions/${encodeURIComponent(id)}"

  def parse(hash: String): Option[String] =
    Option
      .when(hash.startsWith("#/sessions/"))(hash.stripPrefix("#/sessions/"))
      .filter(_.nonEmpty)
      .flatMap(value => Try(decodeURIComponent(value)).toOption)
}
