package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.util.control.NonFatal

/** Mount-only interop in an otherwise empty host. Vite's bridge returns a sanitized, inert DOM fragment. If the bridge
  * is unavailable, fail safely to the original Markdown as text, never unsanitized HTML.
  */
private[web] object MarkdownView {
  @js.native
  @JSGlobal("yallmoMarkdown")
  private object Browser extends js.Object {
    def render(source: String): dom.DocumentFragment = js.native
  }

  def apply(source: String): HtmlElement = div(
    cls := "sdlc-markdown",
    onMountCallback { context =>
      try { val _ = context.thisNode.ref.appendChild(Browser.render(source)) }
      catch {
        case NonFatal(_) =>
          context.thisNode.ref.textContent = source
          context.thisNode.ref.classList.add("sdlc-markdown-fallback")
      }
    }
  )
}
