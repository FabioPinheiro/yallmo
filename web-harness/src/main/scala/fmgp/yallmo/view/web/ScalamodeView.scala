package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*
import fmgp.yallmo.model.AgentId

private[web] object ScalamodeView {
  def apply(model: ScalamodeModel, selectedAgent: Signal[Option[AgentId]]): HtmlElement = {
    val state = model.state.signal
    val blockedReason = state.combineWith(selectedAgent).map { case (s, agent) => s.blockedReason(agent) }
    div(
      cls := "page scalamode-page",
      h1("Scalamode"),
      p(
        cls := "muted",
        "Send a ToolCall to a configured agent and receive its ToolOutput. No LLM inference or chat session is needed. The server checks tool permissions."
      ),
      label(
        "Scala code",
        textArea(
          idAttr := "scalamode-code",
          cls := "scalamode-editor",
          aria.label := "Scala code",
          rows := 12,
          spellCheck := false,
          value <-- state.map(_.code),
          onInput.mapToValue --> model.code
        )
      ),
      div(
        cls := "composer-actions",
        button(
          idAttr := "scalamode-run",
          typ := "button",
          disabled <-- blockedReason.map(_.nonEmpty),
          child.text <-- state.map(s => if s.pending then "Running scalamode…" else "Run scalamode"),
          onClick --> (_ => model.run())
        )
      ),
      p(cls := "muted", role := "status", child.text <-- blockedReason.map(_.getOrElse(""))),
      p(cls := "error", role := "alert", hidden <-- state.map(_.notice.isEmpty), child.text <-- state.map(_.notice)),
      child <-- state.map(_.result).map {
        case None         => emptyNode
        case Some(result) =>
          sectionTag(
            cls := "message scalamode-result",
            h2(result.title),
            result.fields match {
              case Some(fields) =>
                div(
                  dl(
                    cls := "result-fields",
                    fields.metadata.map(field => div(cls := "result-field", dt(field.name), dd(field.text)))
                  ),
                  fields.blocks.map(field =>
                    div(cls := "result-block", strong(field.name), MessageBodyView(field.text, markdown = false))
                  )
                )
              case None => MessageBodyView(result.output, markdown = false)
            },
            if result.logs.isEmpty then emptyNode
            else
              detailsTag(
                summaryTag(s"Logs (${result.logs.size})"),
                MarkdownView.blocks(result.logs.mkString("\n"))
              )
            ,
            p(cls := "muted", s"Saved response: ${result.message.id}")
          )
      }
    )
  }
}
