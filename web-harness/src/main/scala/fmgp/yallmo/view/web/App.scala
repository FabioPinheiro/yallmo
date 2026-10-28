package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*
import fmgp.yallmo.clientharness.ControlAction
import fmgp.yallmo.model.{AgentId, BlobId, MessageId}
import fmgp.yallmo.server.protocol.YallmoProtocol.{MessageSnapshot, SessionSnapshot}
import org.scalajs.dom
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.control.NonFatal

object App {
  private enum LoadState[+A] {
    case Loading
    case Ready(value: A)
    case Failed(message: String)
  }

  def main(args: Array[String]): Unit = {
    val _ = args
    renderOnDomContentLoaded(dom.document.getElementById("app"), application())
  }

  private def load[A](request: => Future[A]): Signal[LoadState[A]] =
    EventStream
      .fromFuture(
        request.map(value => LoadState.Ready(value): LoadState[A]).recover { case NonFatal(error) =>
          LoadState.Failed(Option(error.getMessage).getOrElse("Request failed"))
        }
      )
      .startWith(LoadState.Loading)

  private def application(): HtmlElement = {
    val notifications = new UiNotifications
    val speech = new MessageSpeech(MessageSpeech.browserPlayback, notifications.publish)
    val disconnected = new EventBus[Throwable]
    val client = new OrchestratorWSClient(OrchestratorWSClient.defaultEndpoint, onDisconnect = disconnected.emit)
    val chat = new ChatModel(client, notifications.publish)
    val selectedAgent = chat.state.signal.map(_.current.agent).distinct
    val scalamode = new ScalamodeModel(client.invokeAgent, () => chat.state.now().current.agent)
    val voice = new VoiceModel(
      chat,
      VoiceRecorder.browser,
      VoiceTransfer.browser(client),
      notifications.publish,
      () => speech.stop()
    )
    val humanName = Var("fabio")
    val connection = Var("Connecting…")
    val connecting = Var(false)
    val sidebarOpen = Var(false)
    val refresh = new EventBus[Unit]
    val refreshes = refresh.events.startWith(())
    var claimedUser = OrchestratorWSClient.claimedUser("fabio")
    var intentionalClose = false
    var mounted = false
    def close(): Unit = {
      voice.disable()
      scalamode.disable()
      intentionalClose = true
      client.close()
      intentionalClose = false
    }
    def connect(): Unit = {
      val requestedUser = OrchestratorWSClient.claimedUser(humanName.now())
      if requestedUser.nonEmpty && !connecting
          .now() && chat.state.now().pending.isEmpty && !chat.state.now().creating && !chat.state
          .now()
          .voiceBusy && !scalamode.state.now().pending
      then {
        connecting.set(true)
        connection.set("Connecting…")
        if requestedUser != claimedUser then {
          chat.disable()
          close()
          claimedUser = requestedUser
        }
        client.connect(claimedUser).onComplete {
          case scala.util.Success(info) if mounted =>
            connecting.set(false)
            chat.configure(info)
            scalamode.configure(info)
            voice.configure(info)
            chat.reload()
            refresh.emit(())
            connection.set(if OrchestratorWSClient.canChat(info) then
              s"Trusted operator · ${AgentId.value(info.identity.id)}"
            else "Read only")
          case scala.util.Failure(error) if mounted =>
            connecting.set(false)
            voice.disable()
            chat.disable()
            scalamode.disable()
            connection.set(s"Disconnected: ${error.getMessage}")
            notifications.publish(UiNotice(s"Connection failed: ${error.getMessage}", "error"))
          case _ => ()
        }
      }
    }
    val route = Var(ViewRoute.parse(dom.window.location.hash))
    def openChat(): Unit = {
      route.set(ViewRoute.Chat)
      dom.window.location.hash = ViewRoute.Chat.href
      sidebarOpen.set(false)
    }
    def unavailable(): Unit =
      notifications.publish(UiNotice("This control is not available right now.", "warn"))
    def focus(selector: String): Unit = if !WebKeyboard.focus(selector) then unavailable()
    def activate(selector: String): Unit = if !WebKeyboard.activate(selector) then unavailable()
    def focusChat(selector: String): Unit = {
      openChat()
      // Hash routing mounts the composer before focus is applied; never steal focus after leaving Chat.
      val _ = dom.window.requestAnimationFrame { _ =>
        if mounted && ViewRoute.parse(dom.window.location.hash) == ViewRoute.Chat then focus(selector)
      }
    }
    val keyboard = new WebKeyboard(
      ViewRoute.tabs.map { tab =>
        WebCommand(
          tab.command,
          () => {
            dom.window.location.hash = tab.href
            sidebarOpen.set(false)
          }
        )
      } ++ List(
        WebCommand(
          ControlAction.Edit,
          () => if route.now() == ViewRoute.Scalamode then focus("#scalamode-code") else focusChat("#chat-composer")
        ),
        WebCommand(
          ControlAction.Agents,
          () => focus("#configured-agent")
        ),
        WebCommand(
          ControlAction.Conversations,
          () => {
            sidebarOpen.set(true)
            val _ = dom.window.requestAnimationFrame(_ => focus(".conversation-list button"))
          }
        ),
        WebCommand(
          ControlAction.NewConversation,
          () => {
            sidebarOpen.set(true)
            val _ = dom.window.requestAnimationFrame(_ => activate("#new-conversation"))
          }
        ),
        WebCommand(
          ControlAction.Reload,
          () => {
            if route.now() == ViewRoute.Chat then activate("#reload-history") else refresh.emit(())
          }
        ),
        WebCommand(ControlAction.ReloadConversations, () => chat.refresh()),
        WebCommand(ControlAction.Username, () => focus("#username")),
        WebCommand(ControlAction.Reconnect, () => activate("#reconnect")),
        WebCommand(ControlAction.Dictation, () => focusChat(".voice-record")),
        WebCommand(ControlAction.StopSpeech, () => speech.stop()),
        WebCommand(ControlAction.Content, () => focus("#content"))
      ),
      () => sidebarOpen.set(false),
      voice
    )
    div(
      cls := "app",
      voice.state.signal
        .map(s => s.phase == VoicePhase.Preparing || s.phase == VoicePhase.Recording || s.phase == VoicePhase.Stopping)
        .distinct --> speech.setCaptureActive,
      disconnected.events --> { error =>
        if mounted && !intentionalClose then {
          voice.disable()
          chat.disable()
          scalamode.disable()
          connection.set(s"Disconnected: ${error.getMessage}")
          if !connecting.now() then notifications.publish(UiNotice(s"Disconnected: ${error.getMessage}", "error"))
        }
      },
      onMountCallback { _ => mounted = true; connect() },
      onUnmountCallback { _ => mounted = false; speech.stop(); close() },
      windowEvents(_.onBeforeUnload) --> (_ => { speech.stop(); close() }),
      windowEvents(_.onPageHide) --> (_ => { speech.stop(); voice.cancel(false) }),
      windowEvents(_.onHashChange).map(_ => ViewRoute.parse(dom.window.location.hash)) --> route.writer,
      button(
        cls := "skip-link",
        typ := "button",
        "Skip to content",
        onClick --> (_ => dom.document.getElementById("content").asInstanceOf[dom.HTMLElement].focus())
      ),
      asideTag(
        cls <-- sidebarOpen.signal.map(if _ then "app-sidebar expanded" else "app-sidebar"),
        div(
          cls := "sidebar-brand",
          h1("YALLMO"),
          button(
            typ := "button",
            cls := "sidebar-toggle",
            "Menu",
            aria.label := "Toggle navigation and conversations",
            aria.expanded <-- sidebarOpen.signal,
            aria.controls := "sidebar-content",
            onClick --> (_ => sidebarOpen.update(!_))
          )
        ),
        div(
          idAttr := "sidebar-content",
          cls := "sidebar-content",
          navTag(
            cls := "tabs",
            aria.label := "Views",
            ViewRoute.tabs.map { tab =>
              a(
                href := tab.href,
                tab.title,
                cls <-- route.signal.map(current => if current.title == tab.title then "tab selected" else "tab"),
                aria.current <-- route.signal.map(current => if current.title == tab.title then "page" else "false"),
                onClick --> (_ => sidebarOpen.set(false))
              )
            }
          ),
          ChatView.sidebar(chat, () => openChat())
        )
      ),
      div(
        cls := "main-column",
        div(
          cls := "toolbar",
          input(
            cls := "username",
            idAttr := "username",
            aria.label := "Username",
            title := "Claimed author name (not authentication). Reconnect to apply.",
            value <-- humanName.signal,
            onInput.mapToValue --> humanName.writer
          ),
          span(cls := "connection-status", role := "status", child.text <-- connection.signal),
          ChatView.agentSelect(chat, scalamode.state.signal.map(_.pending)),
          button(
            typ := "button",
            "Reconnect",
            idAttr := "reconnect",
            disabled <-- humanName.signal
              .combineWith(connecting.signal, chat.state.signal, scalamode.state.signal)
              .map { case (name, busy, state, scalaState) =>
                OrchestratorWSClient
                  .claimedUser(name)
                  .isEmpty || busy || state.pending.nonEmpty || state.creating || state.voiceBusy || scalaState.pending
              },
            title <-- humanName.signal.map(name =>
              if OrchestratorWSClient.claimedUser(name).isEmpty then "Enter a username to reconnect."
              else "Apply username and refresh connection state."
            ),
            onClick --> (_ => connect())
          ),
          keyboard.view()
        ),
        div(
          cls := "content-area",
          notifications.view(),
          mainTag(
            idAttr := "content",
            tabIndex := -1,
            child <-- route.signal.distinct.map {
              case ViewRoute.Chat         => ChatView(chat, client, speech, voice)
              case ViewRoute.Scalamode    => ScalamodeView(scalamode, selectedAgent)
              case ViewRoute.Sessions(id) => sessionPage(id, refreshes, client, speech)
              case ViewRoute.Messages     =>
                listPage("All messages", "No messages.", () => client.messages(), refreshes)(messages =>
                  messageList(messages, client, speech)
                )
              case ViewRoute.Graph =>
                listPage("Conversation graph", "No messages.", () => client.messages(), refreshes) { messages =>
                  div(
                    p(
                      cls := "muted",
                      "Expand a node to inspect its message. Shared history appears once. Up to 32 levels are shown."
                    ),
                    child <-- messages.map(messages => conversationTree(messages, client, speech))
                  )
                }
              case ViewRoute.Agents =>
                listPage(
                  "Agents",
                  "No registered agents. This lists the runtime registry, not historical message authors.",
                  () => client.agents(),
                  refreshes
                ) { ids =>
                  ul(cls := "id-list", children <-- ids.split(identity) { (id, _, _) => li(code(id)) })
                }
              case ViewRoute.Blobs =>
                listPage("Blobs", "No stored blobs.", () => client.blobs(), refreshes) { ids =>
                  div(
                    p(cls := "muted", "Stored files. Download a blob to save it to your device."),
                    ul(
                      cls := "blob-list",
                      aria.label := "Stored blobs",
                      children <-- ids.split(identity) { (id, _, _) =>
                        li(
                          cls := "blob-row",
                          div(
                            cls := "blob-identity",
                            span(cls := "muted", "Blob ID"),
                            code(id.value)
                          ),
                          downloadButton(id, "Download", client)
                        )
                      }
                    )
                  )
                }
            }
          )
        )
      )
    )
  }

  private def sessionPage(
      id: Option[String],
      refreshes: Signal[Unit],
      client: OrchestratorWSClient,
      speech: MessageSpeech
  ): HtmlElement = {
    val sessions = refreshes.flatMapSwitch(_ => load(client.sessions()))
    val details: Signal[LoadState[Option[SessionSnapshot]]] = refreshes.flatMapSwitch { _ =>
      id match {
        case Some(value) =>
          load(client.session(value)).map {
            case LoadState.Loading         => LoadState.Loading
            case LoadState.Ready(session)  => LoadState.Ready(Option(session))
            case LoadState.Failed(message) => LoadState.Failed(message)
          }
        case None => Val(LoadState.Ready(None))
      }
    }
    div(
      cls := "workspace",
      asideTag(
        h2("Sessions"),
        status(sessions),
        p(
          cls := "muted",
          child.text <-- sessions.map {
            case LoadState.Ready(Nil) => "No sessions yet."
            case _                    => ""
          }
        ),
        ul(
          cls := "session-list",
          children <-- values(sessions).split(_.id.value) { (sessionId, _, summary) =>
            li(
              a(
                href := SessionRoute.href(sessionId),
                cls := (if id.contains(sessionId) then "session selected" else "session"),
                span(cls := "session-name", sessionId),
                span(
                  cls := "muted",
                  child.text <-- summary.map(value =>
                    s"${value.messageCount} messages · ${value.agentIds.mkString(", ")}"
                  )
                )
              )
            )
          }
        )
      ),
      sectionTag(
        cls := "transcript",
        status(details),
        child <-- details.map {
          case LoadState.Ready(Some(session)) =>
            div(
              h2(session.id.value),
              p(cls := "muted", s"Participants: ${session.agentIds.mkString(", ")}"),
              if session.messages.isEmpty then p("This session has no messages.") else emptyNode
            )
          case LoadState.Ready(None) =>
            div(
              cls := "empty-state",
              h2("Your sessions, at a glance"),
              p("Choose a session to inspect its transcript.")
            )
          case _ => emptyNode
        },
        messageList(
          details.map {
            case LoadState.Ready(Some(session)) => session.messages.toList
            case _                              => Nil
          },
          client,
          speech
        )
      )
    )
  }

  private def listPage[A](
      title: String,
      empty: String,
      fetch: () => Future[List[A]],
      refreshes: Signal[Unit]
  )(render: Signal[List[A]] => HtmlElement): HtmlElement = {
    val state = refreshes.flatMapSwitch(_ => load(fetch()))
    sectionTag(
      cls := "page",
      h2(title),
      status(state),
      p(
        cls := "muted",
        child.text <-- state.map {
          case LoadState.Ready(Nil) => empty
          case _                    => ""
        }
      ),
      render(values(state))
    )
  }

  private def values[A](state: Signal[LoadState[List[A]]]): Signal[List[A]] =
    state.map {
      case LoadState.Ready(items) => items
      case _                      => Nil
    }

  private def status[A](state: Signal[LoadState[A]]): HtmlElement =
    div(
      aria.live := "polite",
      child <-- state.map {
        case LoadState.Loading         => p(cls := "muted", role := "status", "Loading…")
        case LoadState.Failed(message) => p(cls := "error", role := "alert", message)
        case _                         => emptyNode
      }
    )

  private[web] def messageList(
      messages: Signal[List[MessageSnapshot]],
      client: OrchestratorWSClient,
      speech: MessageSpeech
  ): HtmlElement =
    div(
      cls := "messages",
      children <-- messages.split(_.id) { (_, _, message) => messageCard(message, client, speech) }
    )

  private def messageCard(
      message: Signal[MessageSnapshot],
      client: OrchestratorWSClient,
      speech: MessageSpeech
  ): HtmlElement =
    articleTag(
      cls <-- message.map(value =>
        value.role match {
          case "user"      => "message message-user"
          case "assistant" => "message message-assistant"
          case _           => "message"
        }
      ),
      div(
        cls := "message-meta",
        span(cls := "role", child.text <-- message.map(_.role)),
        strong(child.text <-- message.map(value => AgentId.value(value.agentId))),
        span(child.text <-- message.map(_.toolName.getOrElse(""))),
        timeTag(child.text <-- message.map(_.timestamp.toString)),
        speech.buttonFor(message.map(_.content))
      ),
      child <-- message.map(messageBody),
      div(
        cls := "attachments",
        children <-- message.map(_.blobIds.toList).split(identity) { (id, _, _) =>
          downloadButton(id, s"Download attachment ${id.value}", client)
        }
      ),
      detailsTag(
        summaryTag("Message identity"),
        code(child.text <-- message.map(value => s"ID: ${value.id}")),
        code(child.text <-- message.map(value => s"Parent: ${value.parentId.getOrElse("(root)")}"))
      )
    )

  private def downloadButton(id: BlobId, label: String, client: OrchestratorWSClient): HtmlElement = {
    val busy = Var(false)
    val error = Var("")
    div(
      cls := "download-control",
      button(
        typ := "button",
        cls := "blob-download",
        aria.label <-- busy.signal.map(if _ then s"Downloading blob ${id.value}" else s"Download blob ${id.value}"),
        disabled <-- busy.signal,
        child.text <-- busy.signal.map(if _ then "Downloading…" else label),
        onClick --> { _ =>
          if !busy.now() then {
            busy.set(true)
            error.set("")
            client.download(id).onComplete { result =>
              busy.set(false)
              result.failed.foreach(cause => error.set(Option(cause.getMessage).getOrElse("Download failed")))
            }
          }
        }
      ),
      span(
        cls := "muted download-status",
        role := "status",
        child.text <-- busy.signal.map(if _ then "Preparing your download…" else "")
      ),
      child <-- error.signal.map { message =>
        if message.nonEmpty then p(cls := "error download-error", role := "alert", message)
        else emptyNode
      }
    )
  }

  private def messageBody(message: MessageSnapshot): HtmlElement =
    (if message.role == "tool_result" then ToolResultContent.parse(message.content) else None) match {
      case Some(result) =>
        div(
          cls := "tool-result",
          dl(
            cls := "result-fields",
            result.metadata.map { field =>
              div(cls := "result-field", dt(field.name), dd(field.text))
            }
          ),
          result.blocks.map { field =>
            div(cls := "result-block", strong(cls := "result-label", field.name), textBody(field.text))
          }
        )
      case None => textBody(MessageText.format(message.role, message.content))
    }

  // Text nodes only, including decoded tool output. Native details handles expansion without extra state.
  private def textBody(text: String): HtmlElement =
    if MessageText.isLong(text) then
      detailsTag(cls := "message-body", summaryTag(s"Show text (${text.length} characters)"), pre(text))
    else pre(text)

  /** Messages have one parent, so a small HTML/CSS forest is enough; no graph layout library. */
  private def conversationTree(
      messages: List[MessageSnapshot],
      client: OrchestratorWSClient,
      speech: MessageSpeech
  ): HtmlElement = {
    val ids = messages.map(_.id).toSet
    val children = messages.groupBy(_.parentId)
    def branch(message: MessageSnapshot, depth: Int): HtmlElement =
      li(
        detailsTag(
          cls := "graph-node",
          summaryTag(
            span(cls := "role", message.role),
            span(s" ${message.agentId} · Message details"),
            code(MessageId.value(message.id)),
            small(s"Parent: ${message.parentId.getOrElse("(root)")}")
          ),
          messageCard(Val(message), client, speech)
        ),
        if depth >= 32 then
          if children.contains(Some(message.id)) then
            p(cls := "muted", "Depth limit; see All messages for the remaining messages.")
          else emptyNode
        else ul(children.getOrElse(Some(message.id), Nil).map(child => branch(child, depth + 1)))
      )
    val roots = messages.filter(message => message.parentId.forall(parent => !ids.contains(parent)))
    div(cls := "graph-scroll", ul(cls := "graph-tree", roots.map(message => branch(message, 1))))
  }
}
