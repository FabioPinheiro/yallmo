package fmgp.yallmo.webharness

import com.raquo.laminar.api.L.*
import fmgp.yallmo.model.AgentId
import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}
import zio.json.*

private[webharness] object SkillsView {
  def apply(model: SkillsModel, agents: Signal[List[String]], refreshes: Signal[Unit]): HtmlElement = {
    val catalogState = model.catalog.state.signal
    div(
      cls := "page skills-page",
      onMountCallback { _ =>
        val selection = SkillLocation.parse(dom.window.location.hash)
        model.collapsed.set(selection.nonEmpty)
        model.navigate(selection)
      },
      refreshes --> (_ => model.reload()),
      windowEvents(_.onHashChange) --> (_ => model.navigate(SkillLocation.parse(dom.window.location.hash))),
      onUnmountCallback(_ => model.suspend()),
      div(
        cls := "skills-heading",
        div(
          h1("Skills"),
          p(
            cls := "muted",
            "Browse the server’s loaded skill catalog. Inspection does not run agents or load their context."
          )
        ),
        button(typ := "button", "Refresh", disabled <-- catalogState.map(_.loading), onClick --> (_ => model.reload()))
      ),
      sectionTag(
        cls := "skills-catalog",
        div(
          cls := "skills-section-heading",
          h2(child.text <-- model.collapsed.signal.combineWith(model.selected.signal).map {
            case (true, Some(name)) => s"Skill catalog · $name"
            case _                  => "Skill catalog"
          }),
          button(
            typ := "button",
            aria.expanded <-- model.collapsed.signal.map(!_),
            aria.controls := "skill-catalog-content",
            child.text <-- model.collapsed.signal.map(if _ then "Browse skills" else "Collapse catalog"),
            onClick --> (_ => model.collapsed.update(!_))
          )
        ),
        div(
          idAttr := "skill-catalog-content",
          hidden <-- model.collapsed.signal,
          div(
            cls := "skills-toolbar",
            label(
              cls := "skills-search",
              "Search skills",
              input(
                typ := "search",
                placeholder := "Name or description…",
                value <-- model.query.signal,
                onInput.mapToValue --> (value => { model.query.set(value); model.page.set(0) })
              )
            ),
            label(
              "Automatic discovery",
              select(
                value <-- model.discovery.signal,
                option(value := "all", "All skills"),
                option(value := "automatic", "Automatic discovery on"),
                option(value := "manual", "Automatic discovery off"),
                onChange.mapToValue --> (value => { model.discovery.set(value); model.page.set(0) })
              )
            ),
            label(
              "Available to",
              select(
                value <-- model.agent.signal.map(_.getOrElse("")),
                option(value := "", "Any agent"),
                children <-- agents.map(_.sorted).split(identity) { (id, _, _) => option(value := id, id) },
                onChange.mapToValue --> model.filterAgent
              )
            )
          ),
          inspectionStatus(catalogState, () => model.reload()),
          inspectionStatus(model.agentSkills.state.signal, () => model.agent.now().foreach(model.filterAgent)),
          p(
            cls := "muted",
            role := "status",
            child.text <-- catalogState.combineWith(model.filtered, model.agentSkills.state.signal).map {
              case (state, _, _) if state.value.exists(_.skills.isEmpty) => "No skills loaded on this server."
              case (state, entries, filter)
                  if state.value.nonEmpty && entries.isEmpty && !filter.loading && filter.error.isEmpty =>
                "No skills match these filters."
              case _ => ""
            }
          ),
          div(
            cls := "skills-table-wrap",
            table(
              cls := "skills-table",
              caption(cls := "sr-only", "Loaded skills. Select a name to read its details below."),
              thead(tr(th("Name"), th("Description"))),
              tbody(
                children <-- model.filtered
                  .combineWith(model.page.signal)
                  .map { (entries, page) =>
                    entries.slice(page * model.pageSize, (page + 1) * model.pageSize)
                  }
                  .split(_.name) { (name, _, entry) =>
                    tr(
                      cls <-- model.selected.signal
                        .map(selected => if selected.contains(name) then "is-selected" else ""),
                      td(
                        a(
                          href := SkillLocation.href(name),
                          aria.current <-- model.selected.signal
                            .map(selected => if selected.contains(name) then "true" else "false"),
                          name
                        )
                      ),
                      td(span(cls := "skill-catalog-description", child.text <-- entry.map(_.description)))
                    )
                  }
              )
            )
          ),
          div(
            cls := "skills-pagination",
            span(
              child.text <-- model.filtered.map(entries =>
                s"${entries.size} skill${if entries.size == 1 then "" else "s"}"
              )
            ),
            button(
              typ := "button",
              "Previous",
              disabled <-- model.page.signal.map(_ == 0),
              onClick --> (_ => model.page.update(_ - 1))
            ),
            span(child.text <-- model.page.signal.map(page => s"Page ${page + 1}")),
            button(
              typ := "button",
              "Next",
              disabled <-- model.filtered
                .combineWith(model.page.signal)
                .map((entries, page) => (page + 1) * model.pageSize >= entries.size),
              onClick --> (_ => model.page.update(_ + 1))
            )
          )
        ),
        child <-- catalogState.map(_.value.toList.flatMap(_.diagnostics.toList)).map { diagnostics =>
          if diagnostics.isEmpty then emptyNode
          else
            detailsTag(
              cls := "skills-diagnostics",
              summaryTag(s"Skill discovery warnings · ${diagnostics.size}"),
              ul(diagnostics.map(diagnostic => li(code(diagnostic.resource), ": ", diagnostic.message)))
            )
        }
      ),
      sectionTag(
        cls := "skill-detail",
        aria.label := "Selected skill details",
        child <-- model.selected.signal.distinct.map {
          case None =>
            p(cls := "muted", "Select a skill above to read its instructions, documents, metadata and availability.")
          case Some(name) => detailView(model, name)
        }
      ),
      p(
        cls := "muted skills-snapshot",
        "Loaded server snapshot. Refresh rereads this snapshot; filesystem changes require a server restart."
      )
    )
  }

  private def detailView(model: SkillsModel, name: String): HtmlElement = div(
    h2(code(name)),
    inspectionStatus(model.detail.state.signal, () => model.reload()),
    child <-- model.detail.state.signal
      .map(_.value.map(detail => (detail.skill, detail.sections, detail.metadata)))
      .distinct
      .map {
        case None                              => emptyNode
        case Some((skill, sections, metadata)) =>
          div(
            p(cls := "skill-description", skill.description),
            div(
              cls := "skill-facts",
              skill.author.map(author => span(s"Author: $author")),
              skill.version.map(version => span(s"Version: $version"))
            ),
            div(
              cls := "skill-tabs",
              role := "group",
              aria.label := "Skill detail section",
              SkillTab.values.toList.map { tab =>
                button(
                  typ := "button",
                  aria.pressed <-- model.tab.signal.map(value => (value == tab).toString),
                  tab.label,
                  onClick --> { _ =>
                    if tab == SkillTab.Instructions && model.document.now().nonEmpty then
                      dom.window.location.hash = SkillLocation.href(name)
                    else model.selectTab(tab)
                  }
                )
              }
            ),
            child <-- model.tab.signal.distinct.map {
              case SkillTab.Instructions => reader("SKILL.md · instruction body", model, name, sections.toSet)
              case SkillTab.Documents    => documents(model, name, sections.toList)
              case SkillTab.Metadata     =>
                div(
                  p(
                    cls := "muted",
                    "Application metadata — not sent to the model. Descriptive only; it does not grant permissions or change instructions."
                  ),
                  if metadata.fields.isEmpty then p("No application metadata supplied.")
                  else
                    div(
                      copyButton("Copy metadata", () => metadata.toJsonPretty),
                      pre(cls := "skill-source", code(metadata.toJsonPretty))
                    )
                )
              case SkillTab.Availability => availability(model)
            }
          )
      }
  )

  private def documents(model: SkillsModel, name: String, sections: List[String]): HtmlElement = div(
    p(
      cls := "muted",
      "Supporting Markdown documents from the loaded catalog. Scripts and binary assets are not included."
    ),
    ul(
      cls := "skill-document-list",
      sections.map { path =>
        li(
          a(
            href := SkillLocation.href(name, Some(path)),
            aria.current <-- model.document.signal
              .map(selected => if selected.contains(path) then "true" else "false"),
            path
          )
        )
      }
    ),
    if sections.isEmpty then p(cls := "muted", "No supporting Markdown documents.") else emptyNode,
    child <-- model.document.signal.distinct.map {
      case Some(path) => reader(path, model, name, sections.toSet)
      case None       => p(cls := "muted", "Select a document to read it below.")
    }
  )

  private def availability(model: SkillsModel): HtmlElement = div(
    p(
      cls := "muted",
      "Effective skill access for configured agents. Available does not mean currently loaded into a conversation; automatic discovery also requires an enabled skill reader."
    ),
    inspectionStatus(model.availability.state.signal, () => model.loadAvailability()),
    ul(
      cls := "skill-availability-list",
      children <-- model.availability.state.signal.map(_.value.toList.flatMap(_.agents.toList)).split(_.agentId) {
        (id, _, entry) =>
          li(
            a(href := ViewRoute.Agents.href, AgentId.value(id)),
            span(
              child.text <-- entry.map(value =>
                value.error match {
                  case Some(error) => s"Inspection unavailable: $error"
                  case None        => if value.available then "Available" else "Not enabled"
                }
              )
            )
          )
      }
    ),
    p(
      child.text <-- model.availability.state.signal.map(s =>
        if s.value.exists(_.agents.isEmpty) then "No configured agents." else ""
      )
    )
  )

  private def reader(title: String, model: SkillsModel, name: String, sections: Set[String]): HtmlElement = {
    val content = model.detail.state.signal
      .map(s => if s.loading || s.error.nonEmpty then None else s.value.map(_.content))
      .distinct
    div(
      cls := "skill-reader",
      div(
        cls := "skills-section-heading",
        h3(code(title)),
        div(
          cls := "skill-reader-actions",
          button(
            typ := "button",
            "Preview",
            aria.pressed <-- model.source.signal.map(value => (!value).toString),
            onClick --> (_ => model.source.set(false))
          ),
          button(
            typ := "button",
            "Source",
            aria.pressed <-- model.source.signal.map(_.toString),
            onClick --> (_ => model.source.set(true))
          ),
          child <-- content.map {
            case Some(value) => copyButton("Copy", () => value)
            case None        => emptyNode
          }
        )
      ),
      child <-- content.combineWith(model.source.signal).map {
        case (None, _)            => emptyNode
        case (Some(value), true)  => pre(cls := "skill-source", code(value))
        case (Some(value), false) =>
          MarkdownView.withLinks(
            value,
            link => SkillLocation.documentLink(name, model.document.now(), sections, link)
          )
      }
    )
  }

  private def inspectionStatus[A](state: Signal[InspectionState[A]], retry: () => Unit): HtmlElement = div(
    p(cls := "muted", role := "status", child.text <-- state.map(s => if s.loading then "Loading…" else "")),
    child <-- state.map(_.error).distinct.map {
      case None        => emptyNode
      case Some(error) =>
        div(
          p(cls := "error", role := "alert", error),
          button(typ := "button", "Retry", onClick --> (_ => retry()))
        )
    }
  )

  private def copyButton(label: String, text: () => String): HtmlElement = {
    val notice = Var("")
    var revision = 0L
    div(
      cls := "skill-copy",
      onUnmountCallback(_ => revision += 1),
      button(
        typ := "button",
        label,
        onClick --> { _ =>
          revision += 1
          val request = revision
          Try(dom.window.navigator.clipboard.writeText(text()).toFuture).fold(
            _ => notice.set("Copy unavailable. Check clipboard permissions."),
            _.onComplete { result =>
              if request == revision then
                notice.set(result match {
                  case Success(_) => "Copied."
                  case Failure(_) => "Copy unavailable. Check clipboard permissions."
                })
            }
          )
        }
      ),
      span(role := "status", child.text <-- notice.signal)
    )
  }
}
