package fmgp.yallmo.webharness

import com.raquo.laminar.api.L.*
import fmgp.yallmo.model.AgentId
import fmgp.yallmo.server.protocol.YallmoProtocol.ScalamodeExecution

private[webharness] object ScalamodeView {
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
      label(
        "Execution",
        select(
          idAttr := "scalamode-execution",
          aria.label := "Scalamode execution",
          value <-- state.map(_.execution.wire),
          disabled <-- state.map(s => !s.enabled || s.pending || s.branchBusy),
          onChange.mapToValue --> (value => ScalamodeExecution.values.find(_.wire == value).foreach(model.execution)),
          option(value := "jvm", "JVM — selected agent binding"),
          option(
            value := "js-node",
            disabled <-- state.map(s => !s.jsNodeAvailable),
            "Scala.js / Node (retained linker)"
          )
        )
      ),
      p(cls := "muted", child.text <-- state.map(_.jsReason)),
      p(
        cls := "muted",
        hidden <-- state.map(_.execution != ScalamodeExecution.JsNode),
        "Selected-agent services, rooted permissions, original limits and approval are preserved. Custom functions must opt in to executor rebinding and provide reviewed Scala.js products. Server-scoped incremental linker; cold first link, fresh Node process on every Run. No runtime state is retained."
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
            p(
              cls := "muted",
              result.execution match {
                case ScalamodeExecution.JVM    => "JVM — selected agent binding"
                case ScalamodeExecution.JsNode => "Scala.js / Node — selected-agent services (retained linker)"
              }
            ),
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
