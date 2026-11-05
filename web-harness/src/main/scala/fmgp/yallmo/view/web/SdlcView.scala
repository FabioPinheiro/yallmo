package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*
import scala.concurrent.ExecutionContext.Implicits.global

private[web] object SdlcView {
  def apply(refreshes: Signal[Unit]): HtmlElement = {
    val http = SdlcHttp.browser
    val model = new SdlcModel(http.load)
    sectionTag(
      cls := "page sdlc-page",
      onMountCallback(_ => model.mount()),
      onUnmountCallback(_ => model.unmount()),
      refreshes.changes --> (_ => model.refresh()),
      div(
        cls := "sdlc-heading",
        h2("SDLC"),
        button(
          typ := "button",
          "Refresh",
          aria.label := "Refresh SDLC tasks and intents",
          onClick --> (_ => model.refresh())
        )
      ),
      p(cls := "muted", "Read-only stored tasks and intents. Expand a card to inspect its complete JSON object."),
      collection(SdlcCollection.Tasks, model.state.signal.map(_.tasks)),
      collection(SdlcCollection.Intents, model.state.signal.map(_.intents))
    )
  }

  private def collection(kind: SdlcCollection, state: Signal[SdlcLoadState]): HtmlElement =
    sectionTag(
      cls := "sdlc-section",
      aria.label := kind.title,
      h3(kind.title),
      div(
        aria.live := "polite",
        child <-- state.map {
          case SdlcLoadState.Loading => p(cls := "muted", role := "status", s"Loading ${kind.title.toLowerCase}…")
          case SdlcLoadState.Failed(message) =>
            p(
              cls := "error",
              role := "alert",
              s"Could not load ${kind.title.toLowerCase}: $message. Use Refresh to retry."
            )
          case SdlcLoadState.Ready(Nil)     => p(cls := "muted", s"No ${kind.title.toLowerCase}.")
          case SdlcLoadState.Ready(records) =>
            div(cls := "sdlc-cards", records.zipWithIndex.map { (record, index) => card(kind, record, index) })
        }
      )
    )

  private def card(kind: SdlcCollection, record: SdlcRecord, index: Int): HtmlElement = {
    val expanded = Var(false)
    val bodyId = s"sdlc-${kind.toString.toLowerCase}-json-$index"
    articleTag(
      cls := "sdlc-card",
      button(
        typ := "button",
        cls := "sdlc-card-toggle",
        aria.expanded <-- expanded.signal,
        aria.controls := bodyId,
        aria.label <-- expanded.signal.map(open =>
          s"${if open then "Collapse" else "Expand"} ${record.id}: ${record.title}"
        ),
        code(cls := "sdlc-card-id", record.id),
        span(cls := "sdlc-card-title", record.title),
        onClick --> (_ => expanded.update(!_))
      ),
      div(
        idAttr := bodyId,
        hidden <-- expanded.signal.map(!_),
        child <-- expanded.signal.map(open => if open then pre(cls := "sdlc-json", record.prettyJson) else emptyNode)
      )
    )
  }
}
