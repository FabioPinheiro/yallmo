package fmgp.yallmo.webharness

import com.raquo.laminar.api.L.*
import fmgp.yallmo.model.AgentId
import fmgp.yallmo.server.protocol.YallmoProtocol.ToolsList
import fmgp.yallmo.server.protocol.YallmoProtocol.given
import fmgp.yallmo.tool.FunctionToolDefinition
import scala.concurrent.Future
import zio.json.*

private[webharness] object AgentToolsView {
  def apply(id: String, configured: Signal[Boolean], fetch: AgentId => Future[ToolsList.Result]): HtmlElement = {
    val model = new AgentToolsModel(() => fetch(AgentId(id)))
    val state = model.state.signal
    li(
      cls := "agent-tools-card",
      onUnmountCallback(_ => model.reset()),
      div(
        cls := "agent-tools-heading",
        h3(code(id)),
        button(
          typ := "button",
          disabled <-- state.map(_.loading).combineWith(configured).map((loading, known) => loading || !known),
          aria.label := s"Inspect tools for $id",
          child.text <-- state.map(s =>
            if s.loading then "Loading tools…"
            else if s.tools.nonEmpty || s.error.nonEmpty then "Reload tools"
            else "Inspect tools"
          ),
          onClick --> (_ => model.reload())
        )
      ),
      p(
        cls := "muted",
        hidden <-- configured,
        "Presence or registered-only agent: no configured tool context to inspect."
      ),
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
