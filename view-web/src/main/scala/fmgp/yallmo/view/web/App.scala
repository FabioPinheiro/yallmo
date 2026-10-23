package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*
import fmgp.yallmo.view.ViewProtocol.*
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
    val refresh = new EventBus[Unit]
    val refreshes = refresh.events.startWith(())
    val route = Var(ViewRoute.parse(dom.window.location.hash))
    div(
      cls := "app",
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
          button(typ := "button", "Refresh", onClick.mapTo(()) --> refresh.writer)
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
          case ViewRoute.Sessions(id) => sessionPage(id, refreshes)
          case ViewRoute.Messages     =>
            listPage("All messages", "No messages.", () => ApiClient.messages(), refreshes)(messageList)
          case ViewRoute.Graph =>
            listPage("Conversation graph", "No messages.", () => ApiClient.messages(), refreshes) { messages =>
              div(
                p(
                  cls := "muted",
                  "Expand a node to inspect its message. Shared history appears once. Up to 32 levels are shown."
                ),
                child <-- messages.map(conversationTree)
              )
            }
          case ViewRoute.Agents =>
            listPage(
              "Agents",
              "No registered agents. This lists the runtime registry, not historical message authors.",
              () => ApiClient.agents(),
              refreshes
            ) { ids =>
              ul(cls := "id-list", children <-- ids.split(identity) { (id, _, _) => li(code(id)) })
            }
          case ViewRoute.Blobs =>
            listPage("Blobs", "No stored blobs.", () => ApiClient.blobs(), refreshes) { ids =>
              ul(
                cls := "id-list",
                children <-- ids.split(identity) { (id, _, _) =>
                  li(a(href := ApiClient.blobUrl(id), download := id, id))
                }
              )
            }
        }
      )
    )
  }

  private def sessionPage(id: Option[String], refreshes: Signal[Unit]): HtmlElement = {
    val sessions = refreshes.flatMapSwitch(_ => load(ApiClient.sessions()))
    val details: Signal[LoadState[Option[SessionView]]] = refreshes.flatMapSwitch { _ =>
      id match {
        case Some(value) =>
          load(ApiClient.session(value)).map {
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
          children <-- values(sessions).split(_.id) { (sessionId, _, summary) =>
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
              h2(session.id),
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
        messageList(details.map {
          case LoadState.Ready(Some(session)) => session.messages
          case _                              => Nil
        })
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

  private def messageList(messages: Signal[List[MessageView]]): HtmlElement =
    div(
      cls := "messages",
      children <-- messages.split(_.id) { (_, _, message) => messageCard(message) }
    )

  private def messageCard(message: Signal[MessageView]): HtmlElement =
    articleTag(
      cls := "message",
      div(
        cls := "message-meta",
        span(cls := "role", child.text <-- message.map(_.role)),
        strong(child.text <-- message.map(_.agentId)),
        span(child.text <-- message.map(_.toolName.getOrElse(""))),
        timeTag(child.text <-- message.map(_.timestamp))
      ),
      child <-- message.map(messageBody),
      div(
        cls := "attachments",
        children <-- message.map(_.blobIds).split(identity) { (id, _, _) =>
          a(href := ApiClient.blobUrl(id), download := id, s"Download attachment $id")
        }
      ),
      detailsTag(
        summaryTag("Message identity"),
        code(child.text <-- message.map(value => s"ID: ${value.id}")),
        code(child.text <-- message.map(value => s"Parent: ${value.parentId.getOrElse("(root)")}"))
      )
    )

  private def messageBody(message: MessageView): HtmlElement =
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
  private def conversationTree(messages: List[MessageView]): HtmlElement = {
    val ids = messages.map(_.id).toSet
    val children = messages.groupBy(_.parentId)
    def branch(message: MessageView, depth: Int): HtmlElement =
      li(
        detailsTag(
          cls := "graph-node",
          summaryTag(
            span(cls := "role", message.role),
            span(s" ${message.agentId} · Message details"),
            code(message.id),
            small(s"Parent: ${message.parentId.getOrElse("(root)")}")
          ),
          messageCard(Val(message))
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
