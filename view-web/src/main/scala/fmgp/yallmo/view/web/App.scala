package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*
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
    val client = new OrchestratorWSClient(OrchestratorWSClient.defaultEndpoint)
    val refresh = new EventBus[Unit]
    val refreshes = refresh.events.startWith(())
    val route = Var(ViewRoute.parse(dom.window.location.hash))
    div(
      cls := "app",
      onMountCallback(_ => { val _ = client.connect() }),
      onUnmountCallback(_ => client.close()),
      windowEvents(_.onBeforeUnload) --> (_ => client.close()),
      windowEvents(_.onHashChange).map(_ => ViewRoute.parse(dom.window.location.hash)) --> route.writer,
      button(
        cls := "skip-link",
        typ := "button",
        "Skip to content",
        onClick --> (_ => dom.document.getElementById("content").asInstanceOf[dom.HTMLElement].focus())
      ),
      headerTag(
        div(p(cls := "eyebrow", "YALLMO"), h1("Orchestrator view")),
        div(
          cls := "header-actions",
          span(cls := "read-only", "Read only"),
          button(typ := "button", "Refresh", onClick --> (_ => client.connect().onComplete(_ => refresh.emit(()))))
        )
      ),
      navTag(
        cls := "tabs",
        aria.label := "Views",
        ViewRoute.tabs.map { tab =>
          a(
            href := tab.href,
            tab.title,
            cls <-- route.signal.map(current => if current.title == tab.title then "tab selected" else "tab"),
            aria.current <-- route.signal.map(current => if current.title == tab.title then "page" else "false")
          )
        }
      ),
      mainTag(
        idAttr := "content",
        tabIndex := -1,
        child <-- route.signal.map {
          case ViewRoute.Sessions(id) => sessionPage(id, refreshes, client)
          case ViewRoute.Messages     =>
            listPage("All messages", "No messages.", () => client.messages(), refreshes)(messages =>
              messageList(messages, client)
            )
          case ViewRoute.Graph =>
            listPage("Conversation graph", "No messages.", () => client.messages(), refreshes) { messages =>
              div(
                p(
                  cls := "muted",
                  "Expand a node to inspect its message. Shared history appears once. Up to 32 levels are shown."
                ),
                child <-- messages.map(messages => conversationTree(messages, client))
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
              ul(
                cls := "id-list",
                children <-- ids.split(identity) { (id, _, _) =>
                  li(downloadLink(id, id.value, client))
                }
              )
            }
        }
      )
    )
  }

  private def sessionPage(id: Option[String], refreshes: Signal[Unit], client: OrchestratorWSClient): HtmlElement = {
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
          client
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

  private def messageList(messages: Signal[List[MessageSnapshot]], client: OrchestratorWSClient): HtmlElement =
    div(
      cls := "messages",
      children <-- messages.split(_.id) { (_, _, message) => messageCard(message, client) }
    )

  private def messageCard(message: Signal[MessageSnapshot], client: OrchestratorWSClient): HtmlElement =
    articleTag(
      cls := "message",
      div(
        cls := "message-meta",
        span(cls := "role", child.text <-- message.map(_.role)),
        strong(child.text <-- message.map(value => AgentId.value(value.agentId))),
        span(child.text <-- message.map(_.toolName.getOrElse(""))),
        timeTag(child.text <-- message.map(_.timestamp.toString))
      ),
      child <-- message.map(messageBody),
      div(
        cls := "attachments",
        children <-- message.map(_.blobIds.toList).split(identity) { (id, _, _) =>
          downloadLink(id, s"Download attachment ${id.value}", client)
        }
      ),
      detailsTag(
        summaryTag("Message identity"),
        code(child.text <-- message.map(value => s"ID: ${value.id}")),
        code(child.text <-- message.map(value => s"Parent: ${value.parentId.getOrElse("(root)")}"))
      )
    )

  private def downloadLink(id: BlobId, label: String, client: OrchestratorWSClient): HtmlElement = {
    val busy = Var(false)
    val error = Var("")
    span(
      a(
        href := "#",
        label,
        aria.disabled <-- busy.signal,
        onClick.preventDefault --> { _ =>
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
      child.text <-- busy.signal.map(if _ then " Downloading…" else ""),
      span(cls := "error", role := "alert", child.text <-- error.signal)
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
  private def conversationTree(messages: List[MessageSnapshot], client: OrchestratorWSClient): HtmlElement = {
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
          messageCard(Val(message), client)
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
