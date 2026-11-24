package fmgp.yallmo.webharness

import com.raquo.laminar.api.L.*
import fmgp.yallmo.model.AgentId
import fmgp.yallmo.server.protocol.YallmoProtocol.{AgentsSkills, ToolsList}
import fmgp.yallmo.server.protocol.YallmoProtocol.given
import fmgp.yallmo.tool.FunctionToolDefinition
import scala.concurrent.Future
import zio.json.*

private[webharness] object AgentToolsView {
  def apply(
      id: String,
      configured: Signal[Boolean],
      fetch: AgentId => Future[ToolsList.Result],
      fetchSkills: AgentId => Future[AgentsSkills.Result]
  ): HtmlElement = {
    val skills = new AgentSkillsModel(() => fetchSkills(AgentId(id)))
    val model = new AgentToolsModel(() => fetch(AgentId(id)))
    val state = model.state.signal
    val skillState = skills.state.signal
    val toolsPanelId = s"agent-tools-${scala.scalajs.js.URIUtils.encodeURIComponent(id)}"
    val skillsPanelId = s"agent-skills-${scala.scalajs.js.URIUtils.encodeURIComponent(id)}"
    val toolsLabel = state.map { s =>
      if s.loading then "Loading tools…"
      else if s.tools.nonEmpty then "Collapse tools"
      else if s.error.nonEmpty then "Retry tools"
      else "Inspect tools"
    }
    val skillsLabel = skillState.map { s =>
      if s.loading then "Loading skills…"
      else if s.value.nonEmpty then "Collapse skills"
      else if s.error.nonEmpty then "Retry skills"
      else "Inspect skills"
    }
    li(
      cls := "agent-tools-card",
      configured.distinct --> (known => if !known then { model.reset(); skills.reset() }),
      onUnmountCallback { _ => model.reset(); skills.reset() },
      div(
        cls := "agent-tools-heading",
        h3(code(id)),
        div(
          cls := "agent-inspection-actions",
          button(
            typ := "button",
            disabled <-- state.map(_.loading).combineWith(configured).map((loading, known) => loading || !known),
            aria.label <-- toolsLabel.map(label => s"$label for $id"),
            aria.expanded <-- model.expanded.signal,
            aria.controls := toolsPanelId,
            child.text <-- toolsLabel,
            onClick --> (_ => model.toggle())
          ),
          button(
            typ := "button",
            disabled <-- skillState.map(_.loading).combineWith(configured).map((loading, known) => loading || !known),
            aria.label <-- skillsLabel.map(label => s"$label for $id"),
            aria.expanded <-- skills.expanded.signal,
            aria.controls := skillsPanelId,
            child.text <-- skillsLabel,
            onClick --> (_ => skills.toggle())
          )
        )
      ),
      p(
        cls := "muted",
        hidden <-- configured,
        "Presence or registered-only agent: no configured capability context to inspect."
      ),
      sectionTag(
        idAttr := skillsPanelId,
        cls := "agent-skills",
        hidden <-- skills.expanded.signal.combineWith(configured).map((expanded, known) => !expanded || !known),
        h4(
          child.text <-- skills.state.signal.map(s =>
            s.value.fold("Available skills")(value => s"Available skills · ${value.skills.size}")
          )
        ),
        p(
          cls := "muted",
          role := "status",
          child.text <-- skills.state.signal.map(s =>
            if s.loading then "Loading skills…"
            else if s.value.exists(_.skills.isEmpty) then "No skills enabled for this agent."
            else ""
          )
        ),
        child <-- skills.state.signal.map(_.error).map {
          case Some(error) => p(cls := "error", role := "alert", error)
          case None        => emptyNode
        },
        ul(
          cls := "agent-skill-names",
          children <-- skills.state.signal.map(_.value.toList.flatMap(_.skills.toList)).split(_.name) { (name, _, _) =>
            li(a(href := SkillLocation.href(name), name))
          }
        )
      ),
      sectionTag(
        idAttr := toolsPanelId,
        cls := "agent-tools-panel",
        hidden <-- model.expanded.signal.combineWith(configured).map((expanded, known) => !expanded || !known),
        p(
          cls := "muted",
          role := "status",
          child.text <-- state.map(s =>
            if s.loading then "Loading effective function schemas…"
            else
              s.tools.fold("")(tools =>
                if tools.isEmpty then "No effective functions. This agent exposes no callable tools."
                else {
                  val functions = tools.flatMap(_.functions).map(_.name).distinct.size
                  s"${tools.size} tool${if tools.size == 1 then "" else "s"} · $functions effective function${
                      if functions == 1 then "" else "s"
                    }"
                }
              )
          )
        ),
        child <-- state.map(_.error).map {
          case Some(message) => p(cls := "error", role := "alert", message)
          case None          => emptyNode
        },
        ul(
          cls := "agent-tool-list",
          children <-- state.map(_.tools.toList.flatMap(_.toList)).split(_.name) { (_, initial, _) =>
            li(
              cls := "agent-tool",
              h4(span(cls := "muted", "Tool "), code(initial.name.value)),
              ul(
                cls := "agent-function-list",
                initial.functions.map(functionView)
              )
            )
          }
        )
      )
    )
  }

  private def functionView(function: FunctionToolDefinition): HtmlElement =
    li(
      cls := "agent-function",
      detailsTag(
        summaryTag(span(cls := "muted", "Function "), code(function.name.value)),
        p(cls := "agent-function-description", function.description),
        ScalamodeApiView(function),
        detailsTag(
          summaryTag("Input schema / raw function JSON"),
          pre(code(function.toJsonPretty))
        )
      )
    )
}
