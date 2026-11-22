package fmgp.yallmo.webharness

import com.raquo.laminar.api.L.*
import fmgp.yallmo.server.protocol.YallmoProtocol.SessionsRename
import org.scalajs.dom

/** Explicit, touch-friendly title editing shared by Chat and graph session inspectors. */
private[webharness] object ConversationTitleEditor {
  private final case class State(
      target: Option[ConversationTitleTarget],
      draft: Option[ConversationTitleDraft],
      enabled: Boolean,
      blocked: Boolean
  )

  def apply(
      model: ConversationTitleModel,
      target: Signal[Option[ConversationTitleTarget]],
      blocked: Signal[Boolean] = Val(false),
      headingPrefix: String = "Conversation: ",
      emptyTitle: String = "New conversation"
  ): HtmlElement = {
    val state = target.combineWith(model.drafts.signal, model.enabled.signal, blocked).map {
      case (selected, drafts, enabled, blocked) =>
        State(selected, selected.flatMap(t => drafts.get(t.id)), enabled, blocked)
    }
    val draft = state.map(_.draft)
    val busy = draft.map(_.exists(_.saving))
    val unavailable = state.map(s => !s.enabled || s.blocked || s.draft.exists(_.saving))
    var currentTarget = Option.empty[ConversationTitleTarget]
    def withTarget(action: ConversationTitleTarget => Unit): Unit = currentTarget.foreach(action)
    val titleInput = input(
      idAttr := "conversation-title-input",
      typ := "text",
      aria.label := "Conversation title",
      maxLength := SessionsRename.MaxTitleLength,
      value <-- draft.map(_.fold("")(_.value)),
      disabled <-- unavailable,
      onInput.mapToValue --> (value => withTarget(t => model.change(t.id, value)))
    )
    val editButton = button(
      typ := "button",
      idAttr := "rename-conversation",
      cls := "conversation-title-edit",
      aria.label := "Edit conversation title",
      title := "Edit conversation title",
      span(aria.hidden := true, "✎"),
      hidden <-- state.map(s => !s.enabled || s.target.isEmpty),
      disabled <-- state.map(_.blocked),
      onClick --> (_ => withTarget(model.begin))
    )
    div(
      cls := "conversation-title",
      target --> (value => currentTarget = value),
      onMountBind { context =>
        var previous = Option.empty[(String, Boolean)]
        state.map(s => (s.target.map(_.id.value).getOrElse(""), s.draft.nonEmpty)).distinct --> { case (id, editing) =>
          val restore = previous.contains((id, true)) && !editing
          previous = Some((id, editing))
          if editing || restore then {
            val _ = dom.window.requestAnimationFrame { _ =>
              val stillEditing = currentTarget.exists(t => model.drafts.now().contains(t.id))
              if context.thisNode.ref.isConnected && currentTarget.exists(_.id.value == id) && stillEditing == editing
              then
                if editing then { titleInput.ref.focus(); titleInput.ref.select() }
                else if !editButton.ref.hasAttribute("hidden") && !editButton.ref.disabled then editButton.ref.focus()
            }
          }
        }
      },
      div(
        cls := "conversation-title-display",
        hidden <-- draft.map(_.nonEmpty),
        h2(
          child.text <-- target.map(
            _.fold(emptyTitle)(t => headingPrefix + t.title.map(_.trim).filter(_.nonEmpty).getOrElse(t.id.value))
          )
        ),
        editButton
      ),
      form(
        cls := "conversation-title-form",
        hidden <-- draft.map(_.isEmpty),
        aria.busy <-- busy,
        onSubmit.preventDefault --> (_ => withTarget(t => model.save(t.id))),
        onKeyDown --> { event =>
          if event.key == "Escape" && !event.isComposing then {
            event.preventDefault()
            event.stopPropagation()
            withTarget(t => model.cancel(t.id))
          } else if event.key == "Enter" &&
            (event.isComposing || event.keyCode == 229 || event.shiftKey || event.ctrlKey || event.metaKey || event.altKey || event.repeat)
          then event.preventDefault()
        },
        label(forId := "conversation-title-input", "Conversation title"),
        titleInput,
        div(
          cls := "conversation-title-actions",
          button(
            typ := "submit",
            child.text <-- busy.map(if _ then "Saving…" else "Save"),
            disabled <-- state.map(s =>
              !s.enabled || s.blocked || s.draft.forall(d => d.saving || d.needsRefresh || d.value.trim.isEmpty)
            )
          ),
          button(typ := "button", "Cancel", disabled <-- busy, onClick --> (_ => withTarget(t => model.cancel(t.id)))),
          button(
            typ := "button",
            "Reset title",
            disabled <-- state.map(s =>
              !s.enabled || s.blocked || s.draft.exists(d => d.saving || d.needsRefresh) ||
                s.target.flatMap(_.title).isEmpty
            ),
            onClick --> (_ => withTarget(t => model.save(t.id, reset = true)))
          ),
          button(
            typ := "button",
            "Reload latest title",
            hidden <-- draft.map(!_.exists(_.needsRefresh)),
            disabled <-- unavailable,
            onClick --> (_ => withTarget(t => model.refresh(t.id)))
          )
        ),
        p(cls := "muted", "Enter: save · Esc: cancel. Reset title restores the session ID; messages are unchanged."),
        p(
          cls := "error",
          role := "status",
          aria.live := "polite",
          hidden <-- draft.map(_.forall(_.error.isEmpty)),
          child.text <-- draft.map(_.fold("")(_.error))
        )
      )
    )
  }
}
