package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*
import fmgp.yallmo.tool.{FunctionKey, FunctionToolDefinition, ScalamodeApi, ScalamodeServiceApi}

/** Only renders documentation captured in the selected function definition. Never discovers host APIs or executes code.
  */
private[web] object ScalamodeApiView {
  def apply(function: FunctionToolDefinition): HtmlElement =
    function.scalamode match {
      case Some(api)                                      => available(api)
      case None if function.name == FunctionKey.scalamode =>
        p(
          cls := "muted",
          "Structured Scalamode service metadata is unavailable for this binding. See its description above."
        )
      case None => span()
    }

  private def available(api: ScalamodeApi): HtmlElement =
    sectionTag(
      cls := "scalamode-api",
      h5("Available Scalamode services"),
      p(
        cls := "muted",
        "Only services bound to this agent's effective Scalamode function are listed. This is documentation, not a playground."
      ),
      if api.services.isEmpty then
        p("No additional ScalamodeService is bound. Only the restricted built-in Scala API is available.")
      else
        div(
          cls := "scalamode-service-list",
          api.services.map(service)
        )
      ,
      detailsTag(
        summaryTag("Built-in Scala API (tool / ZIO)"),
        pre(code(api.builtins))
      )
    )

  private def service(api: ScalamodeServiceApi): HtmlElement =
    detailsTag(
      cls := "scalamode-service-api",
      summaryTag(code(s"${api.parameter}: ${api.apiTypes.mkString(" & ")}")),
      p(cls := "agent-function-description", api.description),
      api.scalaApi match {
        case Some(source) =>
          div(
            strong("Scala interfaces and callable methods"),
            pre(code(source))
          )
        case None =>
          p(
            cls := "muted",
            "The binding author has not supplied Scala interface source. API types and documentation are shown above."
          )
      }
    )
}
