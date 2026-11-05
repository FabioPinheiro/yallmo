package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

private[web] object SdlcView {
  def apply(refreshes: Signal[Unit]): HtmlElement = new Page(new SdlcModel(SdlcHttp.browser.load)).view(refreshes)

  private def records(state: SdlcLoadState): List[SdlcRecord] = state match {
    case SdlcLoadState.Ready(values) => values
    case _                           => Nil
  }

  private def cardId(kind: SdlcCollection, id: String): String = s"sdlc-${kind.toString.toLowerCase}-$id"

  private final class Page(model: SdlcModel) {
    private val query = Var("")
    private val includeArchived = Var(false)
    private val taskState = Var("all")
    private val expanded = Var(Set.empty[String])
    private var mounted = false
    private var navigationFrame: Option[Int] = None

    private def index(state: SdlcState): Map[String, (SdlcCollection, SdlcRecord)] =
      (records(state.tasks).map(record => record.id -> (SdlcCollection.Tasks, record)) ++
        records(state.intents).map(record => record.id -> (SdlcCollection.Intents, record))).toMap

    def view(refreshes: Signal[Unit]): HtmlElement = sectionTag(
      cls := "page sdlc-page",
      onMountCallback { _ => mounted = true; model.mount() },
      onUnmountCallback { _ =>
        mounted = false
        navigationFrame.foreach(dom.window.cancelAnimationFrame)
        model.unmount()
      },
      refreshes.changes --> (_ => model.refresh()),
      div(
        cls := "sdlc-heading",
        div(h1("SDLC"), p(cls := "muted", "Plans, outcomes, and the steps that connect them.")),
        button(
          typ := "button",
          "Refresh",
          aria.label := "Refresh SDLC tasks and intents",
          onClick --> (_ => model.refresh())
        )
      ),
      p(
        cls := "sdlc-readonly muted",
        "Read-only shared plans · Changes are made by your agents. Step completion does not change task state."
      ),
      div(
        cls := "sdlc-toolbar",
        label(
          cls := "sdlc-search",
          span("Search plans"),
          input(
            typ := "search",
            placeholder := "Search text, steps, or IDs…",
            value <-- query.signal,
            onInput.mapToValue --> query.writer
          )
        ),
        label(
          span("Task state"),
          select(
            value <-- taskState.signal,
            onChange.mapToValue --> taskState.writer,
            option(value := "all", "All states"),
            option(value := "open", "Open"),
            option(value := "doing", "Doing"),
            option(value := "done", "Done"),
            option(value := "dropped", "Dropped")
          )
        ),
        label(
          cls := "sdlc-archive-filter",
          input(
            typ := "checkbox",
            checked <-- includeArchived.signal,
            onChange.mapToChecked --> includeArchived.writer
          ),
          span("Include archived")
        )
      ),
      collection(SdlcCollection.Tasks, model.state.signal.map(_.tasks)),
      collection(SdlcCollection.Intents, model.state.signal.map(_.intents))
    )

    private def collection(kind: SdlcCollection, state: Signal[SdlcLoadState]): HtmlElement = {
      val loaded = state.map(records)
      val visible = loaded.combineWith(query.signal, includeArchived.signal, taskState.signal).map {
        case (values, search, archived, status) =>
          SdlcRecord.visible(values, search, archived, if kind == SdlcCollection.Tasks then status else "all")
      }
      sectionTag(
        cls := "sdlc-section",
        aria.label := kind.title,
        div(
          cls := "sdlc-section-heading",
          h2(kind.title),
          span(
            cls := "muted",
            child.text <-- visible.combineWith(loaded).map { case (shown, all) => s"${shown.size} of ${all.size}" }
          )
        ),
        div(
          aria.live := "polite",
          child <-- state.combineWith(visible).map {
            case (SdlcLoadState.Loading, _) =>
              p(cls := "muted", role := "status", s"Loading ${kind.title.toLowerCase}…")
            case (SdlcLoadState.Failed(message), _) =>
              p(
                cls := "error",
                role := "alert",
                s"Could not load ${kind.title.toLowerCase}: $message. Use Refresh to retry."
              )
            case (SdlcLoadState.Ready(Nil), _) => p(cls := "sdlc-empty muted", s"No ${kind.title.toLowerCase} yet.")
            case (SdlcLoadState.Ready(_), Nil) =>
              p(
                cls := "sdlc-empty muted",
                "No plans match these filters. Try another search or include archived records."
              )
            case _ => emptyNode
          }
        ),
        div(
          cls := "sdlc-cards",
          children <-- visible.split(_.id) { (id, _, record) => card(kind, id, record) }
        )
      )
    }

    private def card(kind: SdlcCollection, id: String, record: Signal[SdlcRecord]): HtmlElement = {
      val key = cardId(kind, id)
      val open = expanded.signal.map(_.contains(key)).distinct
      val jsonOpen = Var(false)
      val copyNotice = Var("")
      articleTag(
        idAttr := key,
        cls := "sdlc-card",
        tabIndex := -1,
        div(
          cls := "sdlc-card-header",
          cls("is-open") <-- open,
          button(
            typ := "button",
            cls := "sdlc-card-toggle",
            aria.expanded <-- open,
            aria.controls := s"$key-body",
            code(cls := "sdlc-card-id", id),
            child <-- record.map(value => value.state.fold[HtmlElement](span())(stateBadge)),
            child <-- record.map(value =>
              if value.archived then span(cls := "sdlc-badge sdlc-badge-archived", "Archived") else emptyNode
            ),
            child <-- record.map(stepCount),
            span(cls := "sdlc-separator", aria.hidden := true, "−"),
            span(cls := "sdlc-card-title", child.text <-- record.map(_.title)),
            span(cls := "sdlc-chevron", aria.hidden := true, child.text <-- open.map(if _ then "−" else "+")),
            onClick --> (_ => expanded.update(keys => if keys.contains(key) then keys - key else keys + key))
          ),
          div(
            cls := "sdlc-card-actions",
            exportControl(record, copyNotice),
            button(
              typ := "button",
              cls := "sdlc-json-toggle",
              "Raw JSON",
              aria.expanded <-- jsonOpen.signal,
              aria.controls := s"$key-json",
              aria.label <-- jsonOpen.signal.map(show => s"${if show then "Hide" else "Show"} raw JSON for $id"),
              onClick --> (_ => jsonOpen.update(!_))
            )
          )
        ),
        p(cls := "sdlc-copy-notice muted", role := "status", aria.live := "polite", child.text <-- copyNotice.signal),
        div(
          idAttr := s"$key-json",
          cls := "sdlc-json-panel",
          hidden <-- jsonOpen.signal.map(!_),
          child <-- jsonOpen.signal.combineWith(record).map { case (show, value) =>
            if show then pre(cls := "sdlc-json", value.prettyJson) else emptyNode
          }
        ),
        div(
          idAttr := s"$key-body",
          hidden <-- open.map(!_),
          child <-- open.combineWith(record).map { case (isOpen, value) => if isOpen then body(value) else emptyNode }
        )
      )
    }

    private def body(record: SdlcRecord): HtmlElement = div(
      cls := "sdlc-card-body",
      if record.description.isEmpty then emptyNode else MarkdownView(record.description),
      if record.steps.isEmpty then emptyNode
      else
        sectionTag(
          cls := "sdlc-steps",
          h3(s"Steps · ${record.completedSteps}/${record.steps.size} complete"),
          ol(record.steps.map { step =>
            li(
              cls := (if step.done then "sdlc-step is-done" else "sdlc-step"),
              span(
                cls := "sdlc-step-check",
                role := "img",
                aria.label := (if step.done then "Done" else "Not done"),
                if step.done then "✓" else "○"
              ),
              div(
                strong(step.title),
                if step.description.trim.isEmpty then emptyNode else MarkdownView(step.description)
              )
            )
          })
        )
      ,
      if record.relationships.isEmpty then emptyNode
      else
        sectionTag(
          cls := "sdlc-relationships",
          h3("Relationships"),
          record.relationships.map(_._1).distinct.map { relationship =>
            div(
              cls := "sdlc-ref-row",
              span(cls := "sdlc-ref-label", SdlcRecord.relationshipLabel(relationship)),
              record.relationships.collect { case (`relationship`, target) => relationshipLink(target) }
            )
          }
        )
      ,
      if record.assumptions.isEmpty then emptyNode
      else
        sectionTag(
          cls := "sdlc-assumptions",
          h3("Assumptions"),
          ul(record.assumptions.map(assumption => li(assumption)))
        )
    )

    private def relationshipLink(targetId: String): HtmlElement = button(
      typ := "button",
      cls := "sdlc-ref",
      targetId,
      disabled <-- model.state.signal.map(state => !index(state).contains(targetId)),
      title <-- model.state.signal.map(state =>
        index(state).get(targetId).fold(s"$targetId is not available in the loaded records") { case (_, target) =>
          s"Open $targetId: ${target.title}"
        }
      ),
      onClick --> (_ => navigate(targetId))
    )

    private def navigate(id: String): Unit = index(model.state.now()).get(id).foreach { case (kind, record) =>
      query.set("")
      taskState.set("all")
      if record.archived then includeArchived.set(true)
      val key = cardId(kind, id)
      expanded.update(_ + key)
      navigationFrame.foreach(dom.window.cancelAnimationFrame)
      navigationFrame = Some(dom.window.requestAnimationFrame { _ =>
        navigationFrame = None
        if mounted then
          Option(dom.document.getElementById(key)).foreach { element =>
            element.asInstanceOf[dom.HTMLElement].focus()
            element.scrollIntoView()
          }
      })
    }
  }

  private def stateBadge(state: String): HtmlElement = {
    val known = Set("open", "doing", "done", "dropped")
    span(cls := s"sdlc-badge sdlc-state-${if known.contains(state) then state else "unknown"}", state.capitalize)
  }

  private def stepCount(record: SdlcRecord): HtmlElement =
    if record.steps.isEmpty then span()
    else
      span(
        cls := "sdlc-step-count",
        span(cls := "sdlc-separator", aria.hidden := true, "−"),
        s"${record.completedSteps}/${record.steps.size} steps"
      )

  private def exportControl(record: Signal[SdlcRecord], notice: Var[String]): HtmlElement = {
    val copying = Var(false)
    var mounted = false
    button(
      cls := "sdlc-copy",
      onMountCallback(_ => mounted = true),
      onUnmountCallback(_ => mounted = false),
      typ := "button",
      "Copy Markdown",
      disabled <-- copying.signal,
      onClick.compose(_.withCurrentValueOf(record).map(_._2)) --> { current =>
        copying.set(true)
        notice.set("")
        try
          dom.window.navigator.clipboard.writeText(current.markdown).toFuture.onComplete { result =>
            if mounted then {
              copying.set(false)
              notice.set(result match {
                case Success(_) => "Markdown copied."
                case Failure(_) => "Copy unavailable. Check clipboard permissions and try again."
              })
            }
          }
        catch {
          case NonFatal(_) =>
            copying.set(false)
            notice.set("Copy unavailable. Check clipboard permissions and try again.")
        }
      }
    )
  }
}
