package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.Var
import fmgp.yallmo.model.{AgentId, MessageId}
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

private[web] final case class GraphBranchPending(
    parent: MessageId,
    agent: AgentId,
    draft: String,
    selectionRevision: Long,
    draftRevision: Long
)
private[web] final case class GraphBranchState(
    parent: Option[MessageId] = None,
    draft: String = "",
    pending: Option[GraphBranchPending] = None,
    enabled: Boolean = false,
    reviewRequired: Boolean = false,
    notice: String = "",
    messages: List[MessageSnapshot] = Nil,
    adoptedHead: Option[MessageId] = None
)

/** Application-owned unnamed branch state. Capture each request once; route changes and edits never retarget it.
  * Disconnect invalidates callbacks, not backend execution. Unknown outcomes require explicit review before a new Send.
  */
private[web] final class GraphBranchModel(
    submit: BranchesSubmit.Params => Future[BranchOutcome],
    selectedAgent: () => Option[AgentId],
    otherBusy: () => Boolean,
    connectionVersion: () => Long
)(using ExecutionContext) {
  val state = Var(GraphBranchState())
  private var actor = Option.empty[AgentId]
  private var agents = List.empty[AgentId]
  private var epoch = 0L
  private var selectionRevision = 0L
  private var draftRevision = 0L
  private var adoptionRevision = 0L
  private var consumedAdoptionRevision = 0L

  /** Renderer adoption is an explicit terminal-result action, never a side effect of resnapshot/remount. */
  def consumeAdoptedHead(): Option[MessageId] = {
    val result = Option.when(adoptionRevision != consumedAdoptionRevision)(state.now().adoptedHead).flatten
    consumedAdoptionRevision = adoptionRevision
    result
  }

  def configure(info: Initialize.Result): Unit = {
    if state.now().pending.nonEmpty then disable()
    epoch += 1
    actor = info.identity.websiteAgent
    agents = info.configuredAgentIds.toList.flatMap(_.toList)
    state.update(
      _.copy(enabled =
        info.identity.role == Role.Operator && info.methods.contains(BranchesSubmit.method) && actor.nonEmpty
      )
    )
  }
  def disable(): Unit = {
    epoch += 1
    actor = None
    state.update(s =>
      s.copy(
        enabled = false,
        pending = None,
        draft = s.pending.filter(p => p.draftRevision == draftRevision && s.draft.isEmpty).fold(s.draft)(_.draft),
        reviewRequired = s.reviewRequired || s.pending.nonEmpty,
        notice = if s.pending.nonEmpty then
          "Branch outcome unknown after disconnect. Refresh and inspect saved messages; do not repeat blindly."
        else s.notice
      )
    )
  }
  def select(parent: Option[MessageId]): Unit = {
    selectionRevision += 1
    state.update(_.copy(parent = parent, adoptedHead = None))
  }
  def draft(value: String): Unit = if value != state.now().draft then {
    draftRevision += 1
    state.update(_.copy(draft = value))
  }
  def reviewed(): Unit = state.update(
    _.copy(
      reviewRequired = false,
      notice = ""
    )
  )

  def blockedReason: Option[String] = {
    val s = state.now()
    if !s.enabled then Some("Connect as an operator to a server supporting unnamed branches.")
    else if s.pending.nonEmpty then Some("Waiting for branch execution; leaving Graph does not cancel it.")
    else if otherBusy() then Some("Wait for other chat/tool work before sending.")
    else if s.reviewRequired then Some("Refresh and inspect saved messages before allowing a new submission.")
    else if s.parent.isEmpty then Some("Select a parent message.")
    else if !selectedAgent().exists(agents.contains) then Some("Choose a configured agent in the toolbar.")
    else if s.draft.trim.isEmpty then Some("Write a message before sending.")
    else None
  }

  def send(): Unit = blockedReason match {
    case Some(reason) => state.update(_.copy(notice = reason))
    case None         =>
      val s = state.now()
      val pending = GraphBranchPending(s.parent.get, selectedAgent().get, s.draft, selectionRevision, draftRevision)
      epoch += 1
      val current = epoch
      val connection = connectionVersion()
      state.update(_.copy(draft = "", pending = Some(pending), notice = "", adoptedHead = None))
      submit(BranchesSubmit.Params(pending.parent, pending.agent, BranchesSubmit.Task(pending.draft), actor.get))
        .onComplete { result =>
          if current == epoch then {
            if connection != connectionVersion() then disable()
            else
              result match {
                case Success(value: BranchOutcome.Succeeded)
                    if value.parentId == pending.parent &&
                      value.newMessages.exists(m =>
                        m.id == value.promptId && m.parentId
                          .contains(pending.parent) && m.role == "user" && m.agentId == actor.get
                      ) &&
                      value.newMessages.exists(_.id == value.head) =>
                  val adopt = selectionRevision == pending.selectionRevision
                  if adopt then adoptionRevision += 1
                  state.update(current =>
                    current.copy(
                      pending = None,
                      parent = if adopt then Some(value.head) else current.parent,
                      adoptedHead = Option.when(adopt)(value.head),
                      messages = (current.messages ++ value.newMessages.toList).distinctBy(_.id),
                      notice = ""
                    )
                  )
                case Success(value: BranchOutcome.Failed) if value.parentId == pending.parent =>
                  failed(
                    pending,
                    value.error.message,
                    value.acceptance != PromptAcceptance.NotAccepted,
                    value.newMessages.toList
                  )
                case Failure(error: OrchestratorWSClient.RpcException)
                    if BranchesSubmit.isKnownRejection(error.error.code) =>
                  failed(pending, s"Branch rejected: ${error.getMessage}", false, Nil)
                case _ =>
                  failed(
                    pending,
                    "Branch outcome unknown. Refresh and inspect saved messages; do not repeat blindly.",
                    true,
                    Nil
                  )
              }
          }
        }
  }

  private def failed(
      pending: GraphBranchPending,
      notice: String,
      review: Boolean,
      messages: List[MessageSnapshot]
  ): Unit =
    state.update(current =>
      current.copy(
        pending = None,
        reviewRequired = review,
        notice = notice,
        draft =
          if draftRevision == pending.draftRevision && current.draft.isEmpty then pending.draft else current.draft,
        messages = (current.messages ++ messages).distinctBy(_.id),
        adoptedHead = None
      )
    )
}
