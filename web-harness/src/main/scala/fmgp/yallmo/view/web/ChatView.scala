package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*
import fmgp.yallmo.clientharness.{KeyInput, KeyboardControls}
import fmgp.yallmo.model.AgentId
import org.scalajs.dom
import scala.scalajs.js

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
  * | Conversations    Reload |   Server observations (pending publication)    |
  * |                         |                                                  |
  * | Scrollable session list |--------------------------------------------------|
  * |                         | Recovery notice (only when needed)               |
  * |                         | Editable message composer                        |
  * |                         | Status / warning  Dictation / Stop  Discard Send |
  * +-------------------------+--------------------------------------------------+
  * }}}
  *
  * On narrow screens the sidebar collapses behind App's Menu button. Transient notifications appear at the top-right
  * below the toolbar; they are separate from inline recovery guidance.
  *
  * All components observe the same application-root [[ChatModel]]. Sending clears the composer but leaves it editable
  * for the next draft; only real server observations enter the transcript, before turn completion. History follows new
  * content only while near the bottom. Mount-local state controls scrolling and optional-name input, not connection,
  * draft or pending-turn ownership.
  */
private[web] object ChatView {

  /** New/draft actions explicitly open Chat; the application decides where existing-session clicks navigate. */
  def sidebar(
      model: ChatModel,
      openChat: () => Unit,
      selectedSession: Signal[Option[String]],
      selectSession: String => Unit
  ): HtmlElement = {
    val state = model.state.signal
    val name = Var("")
    div(
      cls := "conversations",
      button(
        typ := "button",
        "New conversation",
        idAttr := "new-conversation",
        disabled <-- state.map(s => !s.enabled || s.pending.nonEmpty || s.voiceBusy),
        onClick --> (_ => { model.newConversation(name.now()); openChat() })
      ),
      detailsTag(
        summaryTag("Name (optional)"),
        input(
          aria.label := "Optional conversation name",
          value <-- name.signal,
          onInput.mapToValue --> name.writer,
          placeholder := "Automatic name",
          disabled <-- state.map(s => s.pending.nonEmpty)
        )
      ),
      button(
        typ := "button",
        "Unsent draft",
        hidden <-- state.map(s => s.active.isEmpty || s.conversations("").draft.isEmpty),

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
              cls <-- selectedSession.map(selected => if selected.contains(id) then "session selected" else "session"),

              aria.current <-- selectedSession.map(selected => if selected.contains(id) then "true" else "false"),
              span(cls := "session-name", id),
              span(cls := "muted", child.text <-- summary.map(s => s"${s.messageCount} messages")),
              onClick --> (_ => selectSession(id))
            )
          )
        }
      )
    )
  }

  /** Shared-toolbar agent selection, populated from configured agent IDs. */
  def agentSelect(model: ChatModel, busy: Signal[Boolean] = Val(false)): HtmlElement = {
    val state = model.state.signal
    val choices = state.map(s => (s.agents, s.current.agent.filter(s.agents.contains))).distinct
    select(
      cls := "agent-select",
      idAttr := "configured-agent",
      aria.label := "Configured agent",
      title := "Changing agent resets provider continuation, not history.",
      disabled <-- state.combineWith(busy).map { case (s, running) =>
        running || s.currentPending.nonEmpty
      },
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
    KeyboardControls.shouldSubmit(KeyInput(key, shift, ctrl, meta, alt, composing))

  private def voiceControls(model: ChatModel, voice: VoiceModel, target: () => VoiceDraftTarget): HtmlElement = {
    val state = voice.state.signal
    div(
      cls := "voice-controls",
      hidden <-- state.map(!_.available),
      button(
        typ := "button",
        cls := "voice-record",
        aria.label <-- state.map(_.phase match {
          case VoicePhase.Idle         => "Start dictation"
          case VoicePhase.Preparing    => "Requesting microphone"
          case VoicePhase.Recording    => "Stop and transcribe"
          case VoicePhase.Stopping     => "Finishing recording"
          case VoicePhase.Uploading    => "Uploading recording"
          case VoicePhase.Transcribing => "Transcribing recording"
        }),
        child.text <-- state.map(_.phase match {
          case VoicePhase.Idle         => "🎤 Dictation"
          case VoicePhase.Preparing    => "Requesting microphone…"
          case VoicePhase.Recording    => "Stop"
          case VoicePhase.Stopping     => "Finishing recording…"
          case VoicePhase.Uploading    => "Uploading…"
          case VoicePhase.Transcribing => "Transcribing…"
        }),
        title := (if voice.supported then "Record up to 30 seconds; Space: stop and transcribe · Esc: discard"
                  else "Microphone capture requires HTTPS or localhost and AudioWorklet support"),
        disabled <-- state.combineWith(model.state.signal).map { (v, c) =>
          !voice.supported || !v.available || c.pending.nonEmpty ||
          (v.busy && v.phase != VoicePhase.Recording)
        },
        // Prevent mouse focus from moving the textarea cursor before it is captured.
        onMouseDown.preventDefault --> (_ => ()),
        onClick --> (_ =>
          if voice.state.now().phase == VoicePhase.Recording then voice.stop() else voice.start(target())
        )
      ),
      span(
        cls <-- state.map(s =>
          if s.phase == VoicePhase.Recording && s.seconds >= 25 then "voice-timer voice-limit" else "voice-timer"
        ),
        role := "timer",
        aria.label := "Recording time, maximum 30 seconds",
        hidden <-- state.map(_.phase != VoicePhase.Recording),
        child.text <-- state.map(s => f"00:${s.seconds}%02d / 00:30")
      ),
      button(
        typ := "button",
        hidden <-- state.map(!_.busy),
        "Discard",
        aria.label <-- state.map(s =>
          if s.phase == VoicePhase.Uploading || s.phase == VoicePhase.Transcribing then "Discard result"
          else "Discard recording"
        ),
        title := "Discard locally; already submitted agent work is not cancelled",
        onClick --> (_ => voice.cancel())
      )
    )
  }

  /** Routed transcript and composer; `client` is reused by persisted-message attachment download controls. */
  def apply(model: ChatModel, client: OrchestratorWSClient, speech: MessageSpeech, voice: VoiceModel): HtmlElement = {
    val state = model.state.signal
    val current = state.map(_.current)
    val follow = Var(true)
    val history =
      current.map(c => (c.snapshot.toList.flatMap(_.messages.toList) ++ c.serverPending).distinctBy(_.id)).distinct
    val pendingIds = current.map(_.serverPending.map(_.id).toSet).distinct
    val visibility: js.Function1[dom.Event, Unit] = _ =>
      model.visibilityChanged(dom.document.visibilityState != "hidden")
    var composer = Option.empty[dom.HTMLTextAreaElement]
    var cursor = Option.empty[(String, Long, Int, Int)]
    def remember(element: dom.HTMLTextAreaElement): Unit = {
      val s = model.state.now()
      if element.value == s.current.draft then
        cursor = Some((s.active, s.current.draftVersion, element.selectionStart, element.selectionEnd))
    }
    def target(): VoiceDraftTarget = {
      composer.filter(_ == dom.document.activeElement).foreach(remember)
      val s = model.state.now()
      model.voiceTarget(cursor.collect {
        case (key, revision, start, end) if key == s.active && revision == s.current.draftVersion => (start, end)
      })
    }
    sectionTag(
      cls := "conversation-layout chat-main",
      onMountCallback { _ =>
        dom.document.addEventListener("visibilitychange", visibility)
        model.enter(dom.document.visibilityState != "hidden")
      },
      onUnmountCallback { _ =>
        model.leave()
        dom.document.removeEventListener("visibilitychange", visibility)
        voice.cancel(false)
      },
      state.map(_.active).distinct.changes --> (_ => voice.cancel(false)),
      model.voiceInsertions.events --> { insertion =>
        composer.foreach { element =>
          val _ = dom.window.requestAnimationFrame { _ =>
            if element.isConnected && model.state.now().active == insertion.conversation &&
              element.value == insertion.text && dom.document.activeElement == element
            then element.setSelectionRange(insertion.cursor, insertion.cursor)
          }
        }
      },
      div(
        cls := "conversation-header chat-heading",
        h2(
          child.text <-- state.map(s => if s.active.isEmpty then "New conversation" else s"Conversation: ${s.active}")
        ),
        button(
          typ := "button",
          "Reload history",
          idAttr := "reload-history",
          disabled <-- state.map(_.currentPending.nonEmpty),
          onClick --> (_ => model.reload())
        )
      ),
      div(
        cls := "chat-history",
        role := "log",
        aria.label := "Server-observed conversation messages",
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
          hidden <-- history.map(_.nonEmpty),
          "No server-observed messages to display."
        ),
        div(
          cls := "messages",
          children <-- history.split(_.id) { (id, initial, message) =>
            App.messageCard(initial, message, client, speech, pendingIds.map(_.contains(id)))
          }
        )
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
            idAttr := "chat-composer",
            rows := 3,
            value <-- current.map(_.draft),
            placeholder := "Write a message… (Enter to send · Shift+Enter for newline)",
            onMountCallback(context => composer = Some(context.thisNode.ref)),
            onUnmountCallback(_ => composer = None),
            onInput --> { event =>
              val element = event.target.asInstanceOf[dom.HTMLTextAreaElement]
              model.draft(element.value)
              remember(element)
            },
            onSelect --> (event => remember(event.target.asInstanceOf[dom.HTMLTextAreaElement])),
            onBlur --> (event => remember(event.target.asInstanceOf[dom.HTMLTextAreaElement])),
            onKeyUp --> (event => remember(event.target.asInstanceOf[dom.HTMLTextAreaElement])),
            onClick --> (event => remember(event.target.asInstanceOf[dom.HTMLTextAreaElement])),
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
          div(
            cls := "composer-feedback",
            hidden <-- state.map(s => s.pending.isEmpty && !s.needsAgent),
            span(
              cls := "muted",
              role := "status",
              aria.live := "polite",
              hidden <-- state.map(s => s.pending.isEmpty),
              child.text <-- state.map { s =>
                if s.currentPending.nonEmpty then "Waiting for the terminal result…"
                else if s.pending.nonEmpty then "Waiting for another conversation…"
                else ""
              }
            ),
            span(
              cls := "agent-warning",
              role := "status",
              hidden <-- state.map(!_.needsAgent),
              "Choose an agent to send."
            )
          ),
          div(
            cls := "composer-buttons",
            voiceControls(model, voice, () => target()),
            button(typ := "button", "Send", disabled <-- state.map(!_.canSend), onClick --> (_ => model.send()))
          )
        )
      )
    )
  }
}
