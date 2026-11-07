package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*
import fmgp.yallmo.clientharness.ControlAction
import fmgp.yallmo.model.{AgentId, BlobId, UserId}
import fmgp.yallmo.server.protocol.YallmoProtocol.MessageSnapshot
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
    val providers = new ProvidersModel(
      () => client.providers(),
      client.startProviderLogin,
      client.completeProviderLogin,
      client.cancelProviderLogin
    )
    val chat = new ChatModel(client, notifications.publish)
    val selectedAgent = chat.state.signal.map(_.current.agent).distinct
    val scalamode = new ScalamodeModel(client.invokeAgent, () => chat.state.now().current.agent)
    val branch = new GraphBranchModel(
      client.submitBranch,
      () => chat.state.now().current.agent,
      () => chat.state.now().pending.nonEmpty || chat.state.now().voiceBusy || scalamode.state.now().pending,
      () => client.connectionVersion
    )
    val branchBusy = branch.state.signal.map(_.pending.nonEmpty).distinct
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
    val connected = Var(false)
    val sidebarOpen = Var(false)
    val route = Var(ViewRoute.parse(dom.window.location.hash))
    val refresh = new EventBus[Unit]
    val refreshes = refresh.events.startWith(())
    var claimedUser = OrchestratorWSClient.claimedUser("fabio")
    var intentionalClose = false
    var mounted = false
    def close(): Unit = {
      connected.set(false)
      voice.disable()
      providers.disable()
      scalamode.disable()
      branch.disable()
      intentionalClose = true
      client.close()
      intentionalClose = false
    }
    def connect(): Unit = {
      val requestedUser = OrchestratorWSClient.claimedUser(humanName.now())
      if requestedUser.nonEmpty && !connecting
          .now() && chat.state.now().pending.isEmpty && !chat.state
          .now()
          .voiceBusy && !scalamode.state.now().pending && branch.state.now().pending.isEmpty && !providers.state
          .now()
          .busy
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
            val graphOwnsInventory = route.now() == ViewRoute.Graph
            chat.configure(info, refreshSessions = !graphOwnsInventory)
            scalamode.configure(info)
            branch.configure(info)
            voice.configure(info)
            providers.configure(info)
            refresh.emit(())
            connected.set(true)
            connection.set(if OrchestratorWSClient.canChat(info) then
              s"Trusted operator · ${UserId.value(info.identity.id)}"
            else "Read only")
          case scala.util.Failure(error) if mounted =>
            connected.set(false)
            connecting.set(false)
            voice.disable()
            chat.disable()
            providers.disable()
            scalamode.disable()
            branch.disable()
            connection.set(s"Disconnected: ${error.getMessage}")
            notifications.publish(UiNotice(s"Connection failed: ${error.getMessage}", "error"))
          case _ => ()
        }
      }
    }
    def openChat(): Unit = {
      route.set(ViewRoute.Chat)
      dom.window.location.hash = ViewRoute.Chat.href
      sidebarOpen.set(false)
    }
    val graphSession = Var(Option.empty[String])
    val graphNavigation = new EventBus[String]
    val sessionNavigation = new SidebarSessionNavigation(
      () => route.now(),
      chat.resume,
      () => openChat(),
      id => { graphSession.set(Some(id)); graphNavigation.emit(id) },
      () => sidebarOpen.set(false)
    )
    val sidebarSession = route.signal.combineWith(chat.state.signal.map(_.active), graphSession.signal).map {
      case (ViewRoute.Graph, _, selected) => selected
      case (_, active, _)                 => Option.when(active.nonEmpty)(active)
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
          () =>
            route.now() match {
              case ViewRoute.Scalamode => focus("#scalamode-code")
              case ViewRoute.Graph     => focus("#graph-composer")
              case _                   => focusChat("#chat-composer")
            }
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
      branchBusy --> (busy => { chat.setBranchBusy(busy); scalamode.setBranchBusy(busy) }),
      voice.state.signal
        .map(s => s.phase == VoicePhase.Preparing || s.phase == VoicePhase.Recording || s.phase == VoicePhase.Stopping)
        .distinct --> speech.setCaptureActive,
      disconnected.events --> { error =>
        if mounted && !intentionalClose then {
          connected.set(false)
          voice.disable()
          chat.disable()
          providers.disable()
          scalamode.disable()
          branch.disable()
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
          ChatView.sidebar(chat, () => openChat(), sidebarSession, sessionNavigation.select)
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
          ChatView.agentSelect(chat, scalamode.state.signal.map(_.pending).combineWith(branchBusy).map(_ || _)),
          button(
            typ := "button",
            "Reconnect",
            idAttr := "reconnect",
            disabled <-- humanName.signal
              .combineWith(
                connecting.signal,
                chat.state.signal,
                scalamode.state.signal,
                providers.state.signal,
                branchBusy
              )
              .map { case (name, busy, state, scalaState, providerState, branchPending) =>
                OrchestratorWSClient
                  .claimedUser(name)
                  .isEmpty || busy || state.pending.nonEmpty || state.voiceBusy || scalaState.pending || providerState.busy || branchPending
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
              case ViewRoute.Chat      => ChatView(chat, client, speech, voice)
              case ViewRoute.Scalamode => ScalamodeView(scalamode, selectedAgent)
              case ViewRoute.Providers => ProvidersView(providers, refreshes)
              case ViewRoute.Sdlc      => SdlcView(refreshes)
              case ViewRoute.Messages  =>
                listPage("All messages", "No messages.", () => client.messages(), refreshes)(messages =>
                  messageList(messages, client, speech)
                )
              case ViewRoute.Graph =>
                ConversationGraphView(
                  client,
                  speech,
                  refreshes,
                  graphNavigation.events,
                  graphSession.set,
                  branch,
                  selectedAgent,
                  connected.signal,
                  chat.updateSummaries,
                  chat.state.signal
                    .map(s => s.pending.nonEmpty || s.voiceBusy)
                    .combineWith(scalamode.state.signal.map(_.pending))
                    .map(_ || _)
                )
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
      children <-- messages.split(_.id) { (_, initial, message) => messageCard(initial, message, client, speech) }
    )

  private[web] def messageCard(
      initial: MessageSnapshot,
      message: Signal[MessageSnapshot],
      client: OrchestratorWSClient,
      speech: MessageSpeech,
      pending: Signal[Boolean] = Val(false)
  ): HtmlElement = {
    val collapsed = Var(MessageText.startsCollapsed(initial.role, initial.content))
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
        MessageBodyView.toggle(
          collapsed,
          span(cls := "role", child.text <-- message.map(_.role)),
          strong(child.text <-- message.map(value => AgentId.value(value.agentId))),
          span(child.text <-- message.map(_.toolName.fold("")(_.value)))
        ),
        timeTag(
          title := "UTC",
          child.text <-- message.map(value => MessageText.formatTimestamp(value.timestamp))
        ),
        detailsTag(
          cls := "message-identity",
          summaryTag("Message identity"),
          div(
            cls := "message-identity-panel",
            code(child.text <-- message.map(value => s"ID: ${value.id}")),
            code(child.text <-- message.map(value => s"Parent: ${value.parentId.getOrElse("(root)")}"))
          )
        ),
        span(role := "status", hidden <-- pending.map(!_), "Pending publication"),
        speech.buttonFor(message.map(_.content))
      ),
      div(
        hidden <-- collapsed.signal,
        child <-- message.map(messageBody),
        div(
          cls := "attachments",
          children <-- message.map(_.blobIds.toList).split(identity) { (id, _, _) =>
            downloadButton(id, s"Download attachment ${id.value}", client)
          }
        )
      )
    )
  }

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
            div(
              cls := "result-block",
              strong(cls := "result-label", field.name),
              MessageBodyView(field.text, markdown = false)
            )
          }
        )
      case None =>
        MessageBodyView(
          MessageText.format(message.role, message.content),
          markdown = message.role != "tool_call" && message.role != "tool_result"
        )
    }

}
