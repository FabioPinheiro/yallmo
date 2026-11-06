package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*
import fmgp.yallmo.model.{AgentId, MessageId}
import fmgp.yallmo.orchestration.SessionSummary
import fmgp.yallmo.server.protocol.YallmoProtocol.MessageSnapshot
import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.scalajs.js.timers.{SetIntervalHandle, clearInterval, setInterval}
import scala.util.control.NonFatal

/** The JS bridge owns its empty host; each inspector body has a separate, explicitly unmounted Laminar root. */
private[web] object ConversationGraphView {
  @js.native
  private trait GraphMessage extends js.Object {
    val id: String = js.native
    val parentId: String | Null = js.native
    val timestamp: String = js.native
    val role: String = js.native
    val content: String = js.native
    val author: String = js.native
  }

  @js.native
  private trait SessionNode extends js.Object {
    val id: String = js.native
    val head: String | Null = js.native
    val error: String | Null = js.native
  }

  @js.native
  private trait GraphHandle extends js.Object {
    def update(messages: js.Array[GraphMessage], sessions: js.Array[SessionNode]): Unit = js.native
    def navigateSession(id: String): Unit = js.native
    def appendMessages(messages: js.Array[GraphMessage], head: js.UndefOr[String]): Unit = js.native
    def destroy(): Unit = js.native
  }

  @js.native
  @JSGlobal("yallmoConversationGraph")
  private object Browser extends js.Object {
    def create(
        host: dom.Element,
        inspect: js.Function2[String, dom.Element, js.Function0[Unit]],
        selected: js.Function1[js.UndefOr[String], Unit],
        liveChanged: js.Function1[Boolean, Unit],
        messageSelected: js.Function1[js.UndefOr[String], Unit]
    ): GraphHandle = js.native
  }

  def apply(
      client: OrchestratorWSClient,
      speech: MessageSpeech,
      refreshes: Signal[Unit],
      navigation: EventStream[String],
      selected: Option[String] => Unit,
      branch: GraphBranchModel,
      agent: Signal[Option[AgentId]],
      connected: Signal[Boolean],
      updateSummaries: List[SessionSummary] => Unit,
      busy: Signal[Boolean]
  ): HtmlElement = {
    var ready = false
    var visible = !dom.document.hidden
    var interval = Option.empty[SetIntervalHandle]
    val model = new ConversationGraphModel(
      () => client.messages(),
      () => client.sessions(),
      client.session,
      () => client.connectionVersion,
      () => ready,
      () => visible,
      updateSummaries
    )
    val visibilityChanged: js.Function1[dom.Event, Unit] = _ => {
      visible = !dom.document.hidden
      model.poll()
    }
    var graph = Option.empty[GraphHandle]
    var artifacts = Map.empty[String, MessageSnapshot]
    val bridgeError = Var(Option.empty[String])
    def inspect(id: String, host: dom.Element): js.Function0[Unit] = artifacts.get(id) match {
      case Some(message) =>
        val root = render(host, App.messageCard(message, Val(message), client, speech))
        () => root.unmount()
      case None =>
        host.textContent = s"Message unavailable: $id"
        () => ()
    }
    def mergeBranches(): Unit = if model.state.now().snapshot.nonEmpty then {
      val state = branch.state.now()
      artifacts ++= state.messages.map(message => MessageId.value(message.id) -> message)
      graph.foreach(
        _.appendMessages(
          js.Array(state.messages.map(messageNode)*),
          state.adoptedHead.fold[js.UndefOr[String]](js.undefined)(MessageId.value)
        )
      )
    }
    val host = div(
      cls := "graph-host",
      onMountCallback { context =>
        model.enable()
        try {
          graph = Some(
            Browser.create(
              context.thisNode.ref,
              (id, body) => inspect(id, body),
              id => selected(id.toOption),
              live => {
                interval.foreach(clearInterval)
                interval = None
                model.setLive(live)
                if live then interval = Some(setInterval(2000)(model.poll()))
              },
              id => branch.select(id.toOption.flatMap(value => MessageId.from(value).toOption))
            )
          )
          bridgeError.set(None)
          visible = !dom.document.hidden
          dom.document.addEventListener("visibilitychange", visibilityChanged)
          interval = Some(setInterval(2000)(model.poll()))
          model.poll()
        } catch {
          case NonFatal(error) => bridgeError.set(Some(s"Graph could not start: ${error.getMessage}"))
        }
      },
      onUnmountCallback { _ =>
        model.disable()
        interval.foreach(clearInterval)
        interval = None
        dom.document.removeEventListener("visibilitychange", visibilityChanged)
        graph.foreach(_.destroy())
        graph = None
        selected(None)
        branch.select(None)
      },
      // A successful explicit reconnect may supersede a snapshot from the old connection; timer ticks never do.
      connected.distinct --> { value => ready = value; if value then model.refresh() },
      branch.state.signal.map(_.messages).distinct --> (_ => mergeBranches()),
      branch.state.signal.map(_.reviewRequired).distinct.changes.filter(identity) --> (_ => model.refresh()),
      navigation --> (id => graph.foreach(_.navigateSession(id))),
      refreshes.changes --> (_ => model.refresh()),
      model.state.signal.map(_.snapshot).distinct --> { snapshot =>
        snapshot.foreach { value =>
          val messages = (value.messages ++ branch.state.now().messages).distinctBy(_.id)
          artifacts ++= messages.map(message => MessageId.value(message.id) -> message)
          graph.foreach(
            _.update(
              js.Array(messages.map(messageNode)*),
              js.Array(value.sessions.map(sessionNode)*)
            )
          )
        }
      }
    )
    sectionTag(
      cls := "conversation-layout graph-page",
      child <-- model.state.signal.map(_.error).combineWith(bridgeError.signal).map { case (loadError, startupError) =>
        loadError.orElse(startupError).fold[HtmlElement](span())(message => p(cls := "error", role := "alert", message))
      },
      host,
      GraphBranchView(branch, agent, busy)
    )
  }

  private def messageNode(message: MessageSnapshot): GraphMessage =
    js.Dynamic
      .literal(
        id = MessageId.value(message.id),
        parentId = message.parentId.fold[String | Null](null)(MessageId.value),
        timestamp = message.timestamp.toString,
        role = message.role,
        content = message.content,
        author = AgentId.value(message.agentId)
      )
      .asInstanceOf[GraphMessage]

  private def sessionNode(session: GraphSession): SessionNode =
    js.Dynamic
      .literal(
        id = session.id,
        head = session.head.orNull,
        error = session.error.orNull
      )
      .asInstanceOf[SessionNode]
}
