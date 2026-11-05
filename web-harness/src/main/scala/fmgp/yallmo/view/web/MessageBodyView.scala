package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*

/** Shared body for persisted/pending message cards and tool results on every route. */
private[web] object MessageBodyView {
  def apply(text: String, markdown: Boolean = true): HtmlElement = {
    val body = if markdown then MarkdownView(text) else MarkdownView.blocks(text)
    if MessageText.isLong(text) then
      detailsTag(cls := "message-body", summaryTag(s"Show text (${text.length} characters)"), body)
    else body
  }
}
