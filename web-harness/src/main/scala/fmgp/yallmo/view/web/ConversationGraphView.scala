package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.*
import fmgp.yallmo.model.{AgentId, MessageId}
import fmgp.yallmo.orchestration.SessionSummary
import fmgp.yallmo.server.protocol.YallmoProtocol.MessageSnapshot
import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.util.control.NonFatal

/** The JS bridge owns its empty host; each inspector body has a separate, explicitly unmounted Laminar root. */
private[web] object ConversationGraphView {
  @js.native
  private trait MessageNode extends js.Object {
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
    def update(messages: js.Array[MessageNode], sessions: js.Array[SessionNode]): Unit = js.native
    def navigateSession(id: String): Unit = js.native
    def appendMessages(messages: js.Array[MessageNode], head: js.UndefOr[String]): Unit = js.native
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

  /** Session navigation is an explicit selection even when its pointer is not available until bootstrap. */
  def navigateSession(branch: GraphBranchModel, id: String, navigate: String => Unit): Unit = {
    branch.select(None)
    navigate(id)
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
    val model = new ConversationGraphModel(
      client.subscribeEvents,
      () => client.connectionVersion,
      () => ready,
      () => visible,
      updateSummaries
    )
    val visibilityChanged: js.Function1[dom.Event, Unit] = _ => {
      visible = !dom.document.hidden
      model.eligibilityChanged()
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
    def mergeBranches(): Unit = {
      val state = branch.state.now()
      artifacts ++= state.messages.map(message => MessageId.value(message.id) -> message)
      val adopt = if graph.nonEmpty then branch.consumeAdoptedHead() else None
      graph.foreach(
        _.appendMessages(
          js.Array(state.messages.map(messageNode)*),
          adopt.fold[js.UndefOr[String]](js.undefined)(MessageId.value)
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
              live => model.setLive(live),
              id =>
                branch.select(id.toOption.flatMap(value => MessageId.from(value).toOption).filter { messageId =>
                  model.isConfirmed(messageId) || branch.state.now().messages.exists(_.id == messageId)
                })
            )
          )
          bridgeError.set(None)
          visible = !dom.document.hidden
          dom.document.addEventListener("visibilitychange", visibilityChanged)
          mergeBranches()
          model.eligibilityChanged()
        } catch {
          case NonFatal(error) => bridgeError.set(Some(s"Graph could not start: ${error.getMessage}"))
        }
      },
      onUnmountCallback { _ =>
        model.disable()
        dom.document.removeEventListener("visibilitychange", visibilityChanged)
        graph.foreach(_.destroy())
        graph = None
        selected(None)
        branch.select(None)
      },
      // Eligibility owns the subscription; reconnect never replays authoring requests.
      connected.distinct --> { value => ready = value; model.eligibilityChanged() },
      branch.state.signal.map(_.messages).distinct --> (_ => mergeBranches()),
      branch.state.signal.map(_.reviewRequired).distinct.changes.filter(identity) --> (_ => model.refresh()),
      navigation --> (id => navigateSession(branch, id, session => graph.foreach(_.navigateSession(session)))),
      refreshes.changes --> (_ => model.refresh()),
      model.state.signal.map(_.snapshot).distinct --> { snapshot =>
        snapshot.foreach { value =>
          val messages = (value.messages ++ branch.state.now().messages).distinctBy(_.id)
          artifacts = messages.map(message => MessageId.value(message.id) -> message).toMap
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

  private def messageNode(message: MessageSnapshot): MessageNode =
    js.Dynamic
      .literal(
        id = MessageId.value(message.id),
        parentId = message.parentId.fold[String | Null](null)(MessageId.value),
        timestamp = message.timestamp.toString,
        role = message.role,
        content = message.content,
        author = AgentId.value(message.agentId)
      )
      .asInstanceOf[MessageNode]

  private def sessionNode(session: GraphSessionNode): SessionNode =
    js.Dynamic
      .literal(
        id = session.id,
        head = session.head.orNull,
        error = session.error.orNull
      )
      .asInstanceOf[SessionNode]
}
