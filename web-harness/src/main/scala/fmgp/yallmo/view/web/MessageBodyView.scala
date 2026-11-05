package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*

/** Shared body and header disclosure for message cards; no separate text disclosure row. */
private[web] object MessageBodyView {
  def apply(text: String, markdown: Boolean = true): HtmlElement =
    if markdown then MarkdownView(text) else MarkdownView.blocks(text)

  def toggle(collapsed: Var[Boolean], metadata: HtmlElement*): HtmlElement =
    button(
      typ := "button",
      cls := "message-toggle",
      aria.expanded <-- collapsed.signal.map(!_),
      aria.label <-- collapsed.signal.map(if _ then "Expand message" else "Collapse message"),
      onClick --> (_ => collapsed.update(!_)),
      span(cls := "message-chevron", child.text <-- collapsed.signal.map(if _ then "▸" else "▾")),
      metadata
    )
}
