package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*
import fmgp.yallmo.model.AgentId
import org.scalajs.dom

/** Laminar chat components placed in the application shell by [[App]].
  *
  * Desktop layout (App owns the shell, branding, navigation, identity controls and notifications):
  * {{{
  * +-------------------------+--------------------------------------------------+
  * | YALLMO                  | Username | Connection | Agent v | Reconnect      |
  * | Navigation links        |                         agentSelect(model)       |
  * |-------------------------+--------------------------------------------------+
  * | sidebar(model, ...)     | apply(model, client)                             |
  * |                         | Conversation title               Reload history |
  * | New conversation        |--------------------------------------------------|
  * | Name (optional)         | Scrollable history                               |
  * | Unsent draft (if any)   |   Persisted message/tool cards                   |
  * | Conversations    Reload |   Pending user card (not yet confirmed saved)    |
  * |                         |                                                  |
  * | Scrollable session list |--------------------------------------------------|
  * |                         | Recovery notice (only when needed)               |
  * |                         | Editable message composer                        |
  * |                         | Shortcut hint      Waiting / agent warning  Send |
  * +-------------------------+--------------------------------------------------+
  * }}}
  *
  * On narrow screens the sidebar collapses behind App's Menu button. Transient notifications appear at the top-right
  * below the toolbar; they are separate from inline recovery guidance.
  *
  * All components observe the same application-root [[ChatModel]]. Sending clears the composer but leaves it editable
  * for the next draft; the captured pending card is replaced by authoritative server messages on completion. History
  * follows new content only while near the bottom. Mount-local state controls scrolling and optional-name input, not
  * connection, draft or pending-turn ownership.
  */
private[web] object ChatView {

  /** Conversation actions and session list; `openChat` switches to Chat and closes mobile navigation after selection.
    */
  def sidebar(model: ChatModel, openChat: () => Unit): HtmlElement = {
    val state = model.state.signal
    val name = Var("")
    div(
      cls := "conversations",
      button(
        typ := "button",
        "New conversation",
        disabled <-- state.map(s => !s.enabled || s.creating || s.pending.nonEmpty),
        onClick --> (_ => { model.newConversation(name.now()); openChat() })
      ),
      detailsTag(
        summaryTag("Name (optional)"),
        input(
          aria.label := "Optional conversation name",
          value <-- name.signal,
          onInput.mapToValue --> name.writer,
          placeholder := "Automatic name",
          disabled <-- state.map(s => s.creating || s.pending.nonEmpty)
        )
      ),
      button(
        typ := "button",
        "Unsent draft",
        hidden <-- state.map(s => s.active.isEmpty || s.conversations("").draft.isEmpty),
        disabled <-- state.map(_.creating),
        onClick --> (_ => { model.resume(""); openChat() })
      ),
      div(
        cls := "conversation-heading",
        h2("Conversations"),
        button(typ := "button", "Reload", aria.label := "Reload conversations", onClick --> (_ => model.refresh()))
      ),
      p(
        cls := "error",
        role := "alert",
        hidden <-- state.map(_.listError.isEmpty),
        child.text <-- state.map(_.listError)
      ),
      ul(
        cls := "session-list conversation-list",
        children <-- state.map(_.sessions).split(_.id.value) { (id, _, summary) =>
          li(
            button(
              typ := "button",
              cls <-- state.map(s => if s.active == id then "session selected" else "session"),
              disabled <-- state.map(_.creating),
              aria.current <-- state.map(s => if s.active == id then "true" else "false"),
              span(cls := "session-name", id),
              span(cls := "muted", child.text <-- summary.map(s => s"${s.messageCount} messages")),
              onClick --> (_ => { model.resume(id); openChat() })
            )
          )
        }
      )
    )
  }

  /** Shared-toolbar selector for the current conversation's next assistant, populated from configured agent IDs. */
  def agentSelect(model: ChatModel): HtmlElement = {
    val state = model.state.signal
    val choices = state.map(s => (s.agents, s.current.agent.filter(s.agents.contains))).distinct
    select(
      cls := "agent-select",
      aria.label := "Configured agent",
      title := "Changing agent resets provider continuation, not history.",
      disabled <-- state.map(s => s.creating || s.currentPending.nonEmpty),
      onChange.mapToValue --> model.selectAgent,
      // Options must exist before restoring the value, including after catalog changes.
      children <-- choices.map { case (agents, _) =>
        option(value := "", "Choose an agent") :: agents
          .map(id => option(value := AgentId.value(id), AgentId.value(id)))
      },
      value <-- choices.map { case (_, agent) => agent.map(AgentId.value).getOrElse("") }
    )
  }

  private[web] def shouldSend(
      key: String,
      shift: Boolean,
      ctrl: Boolean,
      meta: Boolean,
      alt: Boolean,
      composing: Boolean
  ): Boolean =
    key == "Enter" && !shift && !ctrl && !meta && !alt && !composing

  /** Routed transcript and composer; `client` is reused by persisted-message attachment download controls. */
  def apply(model: ChatModel, client: OrchestratorWSClient): HtmlElement = {
    val state = model.state.signal
    val current = state.map(_.current)
    val follow = Var(true)
    val history = state.map(s => (s.current.snapshot.toList.flatMap(_.messages.toList), s.currentPending)).distinct
    val messages = history.map(_._1).distinct
    val pending = history.map(_._2).distinct
    sectionTag(
      cls := "chat-main",
      div(
        cls := "chat-heading",
        h2(
          child.text <-- state.map(s => if s.active.isEmpty then "New conversation" else s"Conversation: ${s.active}")
        ),
        button(
          typ := "button",
          "Reload history",
          disabled <-- state.map(s => s.active.isEmpty || s.creating || s.currentPending.nonEmpty),
          onClick --> (_ => model.reload())
        )
      ),
      div(
        cls := "chat-history",
        role := "log",
        aria.label := "Conversation messages, including pending input",
        onScroll --> { event =>
          val element = event.target.asInstanceOf[dom.HTMLElement]
          follow.set(element.scrollHeight - element.scrollTop - element.clientHeight < 100)
        },
        onMountBind { context =>
          history --> { _ =>
            if follow.now() then {
              val _ = dom.window.requestAnimationFrame { _ =>
                if follow.now() && context.thisNode.ref.isConnected then
                  context.thisNode.ref.scrollTop = context.thisNode.ref.scrollHeight
              }
            }
          }
        },
        p(cls := "muted", hidden <-- current.map(!_.loading), role := "status", "Loading history…"),
        p(
          cls := "muted",
          hidden <-- history.map { case (messages, pending) => messages.nonEmpty || pending.nonEmpty },
          "No persisted messages to display."
        ),
        App.messageList(messages, client),
        child <-- pending.map {
          case Some(message) =>
            div(
              cls := "messages",
              articleTag(
                cls := "message message-user",
                div(
                  cls := "message-meta",
                  span(cls := "role", "user"),
                  strong(AgentId.value(message.author)),
                  span(role := "status", "Pending — not yet confirmed saved")
                ),
                pre(message.prompt)
              )
            )
          case None => emptyNode
        }
      ),
      div(
        cls := "composer",
        p(
          cls := "chat-notice",
          role := "status",
          aria.live := "polite",
          hidden <-- current.map(_.notice.isEmpty),
          child.text <-- current.map(_.notice)
        ),
        label(
          "Message",
          textArea(
            rows := 3,
            value <-- current.map(_.draft),
            placeholder := "Write a message…",
            onInput.mapToValue --> model.draft,
            onKeyDown --> { event =>
              if shouldSend(
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
          )
        ),
        div(
          cls := "composer-actions",
          small(cls := "muted", "Enter to send · Shift+Enter for newline"),
          span(
            cls := "muted",
            role := "status",
            aria.live := "polite",
            hidden <-- state.map(s => !s.creating && s.pending.isEmpty),
            child.text <-- state.map { s =>
              if s.creating then "Creating conversation…"
              else if s.currentPending.nonEmpty then "Waiting for the terminal result…"
              else if s.pending.nonEmpty then "Waiting for another conversation…"
              else ""
            }
          ),
          span(
            cls := "agent-warning",
            role := "status",
            hidden <-- state.map(!_.needsAgent),
            "Choose an agent to send."
          ),
          button(typ := "button", "Send", disabled <-- state.map(!_.canSend), onClick --> (_ => model.send()))
        )
      )
    )
  }
}
