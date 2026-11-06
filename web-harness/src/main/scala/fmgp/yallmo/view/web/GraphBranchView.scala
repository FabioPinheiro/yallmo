package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*
import fmgp.yallmo.model.{AgentId, MessageId}

private[web] object GraphBranchView {
  def apply(model: GraphBranchModel, agent: Signal[Option[AgentId]], busy: Signal[Boolean]): HtmlElement = {
    val blocked = model.state.signal.combineWith(agent, busy).map(_ => model.blockedReason)
    div(
      cls := "composer graph-composer",
      div(
        cls := "graph-composer-parent",
        hidden <-- model.state.signal.map(_.parent.isEmpty),
        child.text <-- model.state.signal
          .map(_.parent.fold("")(id => s"Reply from ${MessageId.value(id).takeRight(12)}")),
        title <-- model.state.signal.map(_.parent.fold("")(MessageId.value))
      ),
      textArea(
        idAttr := "graph-composer",
        aria.label := "Unnamed branch message",
        placeholder := "Message…",
        rows := 3,
        value <-- model.state.signal.map(_.draft),
        onInput.mapToValue --> model.draft,
        onKeyDown --> { event =>
          if !event.repeat && ChatView.shouldSend(
              event.key,
              event.shiftKey,
              event.ctrlKey,
              event.metaKey,
              event.altKey,
              event.isComposing || event.keyCode == 229
            )
          then {
            event.preventDefault()
            model.send()
          }
        }
      ),
      div(
        cls := "composer-actions",
        div(
          cls := "composer-buttons",
          button(
            typ := "button",
            "Send",
            title <-- blocked.map(_.getOrElse("")),
            disabled <-- blocked.map(_.nonEmpty),
            onClick --> (_ => model.send())
          )
        )
      ),
      p(
        cls := "muted",
        role := "status",
        hidden <-- model.state.signal.map(s => s.pending.isEmpty && s.notice.isEmpty),
        child.text <-- model.state.signal.map(s => if s.pending.nonEmpty then "Generating…" else s.notice)
      ),
      button(
        typ := "button",
        "I reviewed saved messages; allow a new submission",
        hidden <-- model.state.signal.map(!_.reviewRequired),
        onClick --> (_ => model.reviewed())
      )
    )
  }
}
