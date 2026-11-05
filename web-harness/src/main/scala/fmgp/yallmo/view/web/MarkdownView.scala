package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.util.control.NonFatal

/** Mount-owned interop in an otherwise empty host. Vite returns sanitized Markdown and asynchronously enhances Mermaid
  * fences as inert images. Unmount cancels late updates. A missing bridge falls back to text, never unsanitized HTML.
  */
private[web] object MarkdownView {
  @js.native
  @JSGlobal("yallmoMarkdown")
  private object Browser extends js.Object {
    def render(source: String): dom.DocumentFragment = js.native
    def renderBlocks(source: String): dom.DocumentFragment = js.native
    def enhance(host: dom.Element): js.Function0[Unit] = js.native
  }

  def apply(source: String): HtmlElement = component(source, fencedOnly = false)

  /** Render code fences but preserve all other tool/log output as literal, whitespace-preserving text. */
  def blocks(source: String): HtmlElement = component(source, fencedOnly = true)

  private def component(source: String, fencedOnly: Boolean): HtmlElement = {
    var cancelEnhancement: js.Function0[Unit] = () => ()
    div(
      cls := "markdown-view",
      onMountCallback { context =>
        val host = context.thisNode.ref
        host.textContent = ""
        host.classList.remove("markdown-fallback")
        try {
          val fragment = if fencedOnly then Browser.renderBlocks(source) else Browser.render(source)
          val _ = host.appendChild(fragment)
          cancelEnhancement = Browser.enhance(host)
        } catch {
          case NonFatal(_) =>
            host.textContent = source
            host.classList.add("markdown-fallback")
        }
      },
      onUnmountCallback { _ => cancelEnhancement() }
    )
  }
}
