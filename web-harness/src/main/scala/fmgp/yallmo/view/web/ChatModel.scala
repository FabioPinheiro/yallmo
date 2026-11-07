package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.{EventBus, Var}
import fmgp.yallmo.model.{AgentId, MessageId}
import fmgp.yallmo.orchestration.{AgentTask, SessionId, SessionSummary}
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/** Retained composer and canonical transcript. Captured input is bookkeeping, never a transcript message. */
private[web] final case class ChatConversation(
    snapshot: Option[SessionSnapshot] = None,
    creating: Boolean = true,
    newSubmitted: Boolean = false,
    nameCollision: Boolean = false,
    terminalHead: Option[MessageId] = None,
    name: Option[SessionId] = None,
    draft: String = "",
    draftVersion: Long = 0L,
    agent: Option[AgentId] = None,
    resetContinuation: Boolean = true,
    loading: Boolean = false,
    reloadRequired: Boolean = false,
    reviewRequired: Boolean = false,
    feedReady: Boolean = false,
    serverPending: List[MessageSnapshot] = Nil,
    notice: String = ""
)

/** One captured submission, independent of subsequent draft edits and route changes. */
private[web] final case class ChatPendingTurn(conversation: String, author: AgentId, prompt: String)

private[web] final case class ChatState(
    active: String = "",
    conversations: Map[String, ChatConversation] = Map("" -> ChatConversation()),
    sessions: List[SessionSummary] = Nil,
    agents: List[AgentId] = Nil,
    enabled: Boolean = false,
    pending: Option[ChatPendingTurn] = None,
    voiceBusy: Boolean = false,
    branchBusy: Boolean = false,
    listError: String = ""
) {

  /** Selected conversation; relies on the invariant that `active` is present in `conversations`. */
  def current: ChatConversation = conversations(active)

  /** Submission controls for the selected conversation, not transcript presentation. */
  def currentPending: Option[ChatPendingTurn] = pending.filter(_.conversation == active)

  /** Whether a nonblank draft needs a valid configured agent, used for the inline Send warning. */
  def needsAgent: Boolean = current.draft.trim.nonEmpty && !current.agent.exists(agents.contains)

  /** First reason Send cannot proceed, shared by the button gate and rejected-keyboard-send notifications. */
  def sendBlockedReason: Option[String] =
    if !enabled then Some("Reconnect to a chat-enabled server before sending.")
    else if branchBusy then Some("Wait for the unnamed branch execution before sending.")
    else if voiceBusy then Some("Finish or discard dictation before sending.")
    else if pending.nonEmpty then Some("Wait for the pending turn to finish before sending.")
    else if current.nameCollision then
      Some("That conversation name already exists. Explicitly resume it or choose another name.")
    else if current.terminalHead.nonEmpty then Some("Wait for canonical history to reconcile the completed turn.")
    else if !current.feedReady then Some("Wait for the live session subscription before sending.")
    else if current.loading then Some("Wait for history to load before sending.")
    else if current.reviewRequired || current.reloadRequired || (!current.creating && current.snapshot.isEmpty) then
      Some("Reload history and review the transcript before sending again.")
    else if current.draft.trim.isEmpty then Some("Write a message before sending.")
    else if needsAgent then Some("Choose an agent before sending.")
    else None

  /** Sending is allowed only when connection, pending-work, history, draft and agent checks all pass. */
  def canSend: Boolean = sendBlockedReason.isEmpty
}

/** App-owned drafts and terminal bookkeeping; mounted Chat alone owns the scoped event feed. No authoring replay. */
private[web] final class ChatModel(client: OrchestratorWSClient, notify: UiNotice => Unit = _ => ())(using
    ExecutionContext
) {
  val state = Var(ChatState())
  val voiceInsertions = new EventBus[VoiceInsertion]
  private var initializedIdentity = Option.empty[AgentId]
  private var listVersion = 0L
  private var liveSummaries = Map.empty[SessionId, SessionSummary]
  private var mounted = false
  private var visible = true
  private var connected = false
  private var inventoryEnabled = true
  private var feed = Option.empty[ChatSessionFeed]
  private var feedOwner = ""

  private def retire(): Unit = {
    val previous = feed
    feed = None
    previous.foreach(_.stop())
    state.update(s =>
      s.copy(conversations = s.conversations.map { case (key, c) =>
        key -> c.copy(
          feedReady = false,
          serverPending = Nil,
          loading = false,
          reloadRequired = c.reloadRequired || c.snapshot.nonEmpty
        )
      })
    )
  }

  def enter(visibleNow: Boolean = true): Unit = {
    mounted = true
    visible = visibleNow
    inventoryEnabled = true
    if connected then { refreshList(); attach() }
  }
  def leave(): Unit = { mounted = false; inventoryEnabled = false; listVersion += 1; retire() }
  def visibilityChanged(value: Boolean): Unit = if visible != value then {
    visible = value
    if visible then attach() else { listVersion += 1; retire() }
  }

  private def attach(reviewed: Boolean = false): Unit = if mounted && visible && connected && feed.isEmpty then {
    val s = state.now()
    val key = s.active
    val target = s.current.snapshot
      .map(_.id)
      .orElse(Option.when(key.nonEmpty)(SessionId.unsafe(key)))
      .orElse(s.current.name)
      .getOrElse(SessionId.unsafe(java.util.UUID.randomUUID().toString))
    edit(key)(_.copy(name = Some(target)))
    feedOwner = key
    val terminalAtSubscribe = s.current.terminalHead
    val next = new ChatSessionFeed(
      target,
      (scope, callback) => client.subscribeEvents(callback, scope),
      value => {
        edit(feedOwner) { c =>
          val collision = c.creating && !c.newSubmitted && value.snapshot.nonEmpty
          val converged = c.terminalHead.exists(head => value.snapshot.exists(_.messages.exists(_.id == head))) ||
            (value.baseline && !value.loading && value.error.isEmpty && value.snapshot.nonEmpty && terminalAtSubscribe == c.terminalHead)
          c.copy(
            snapshot = if collision then c.snapshot else value.snapshot.orElse(c.snapshot),
            creating = c.creating && !(c.newSubmitted && value.snapshot.nonEmpty),
            nameCollision = collision,
            terminalHead = if converged then None else c.terminalHead,
            serverPending = if collision then Nil else value.pending,
            loading = value.loading,
            feedReady = !value.loading && value.error.isEmpty,
            reloadRequired =
              if value.error.nonEmpty then true else if c.loading && !value.loading then false else c.reloadRequired,
            reviewRequired =
              if reviewed && c.loading && !value.loading && value.error.isEmpty then false else c.reviewRequired,
            notice = if collision then
              "That conversation name already exists. Explicitly resume it or choose another name. Your New draft is retained."
            else value.error.getOrElse(c.notice)
          )
        }
        value.snapshot.foreach { snapshot =>
          val summary = SessionSummary(snapshot.id, snapshot.messages.size.toLong, snapshot.agentIds)
          liveSummaries += snapshot.id -> summary
          state.update(s =>
            s.copy(sessions = (s.sessions.filterNot(_.id == snapshot.id) :+ summary).sortBy(_.id.value))
          )
        }
      }
    )
    feed = Some(next)
    next.start()
  }

  private def edit(id: String)(f: ChatConversation => ChatConversation): Unit =
    state.update(s => s.copy(conversations = s.conversations.updated(id, f(s.conversations(id)))))

  // Graph owns inventory while mounted, including when Live is paused. Defer Chat reads until it returns.
  def configure(info: Initialize.Result, refreshSessions: Boolean = true): Unit = {
    val wasConnected = connected
    connected = true
    if wasConnected then retire()
    inventoryEnabled = refreshSessions && mounted
    initializedIdentity = info.identity.chatAgent
    state.update(
      _.copy(
        enabled = OrchestratorWSClient.canChat(info) && initializedIdentity.nonEmpty,
        agents = info.configuredAgentIds.toList.flatMap(_.toList)
      )
    )
    if inventoryEnabled then refreshList()
    if !refreshSessions then retire()
    attach()
  }

  def disable(): Unit = {
    connected = false
    listVersion += 1
    retire()
    state.update(_.copy(enabled = false))
  }

  def refresh(): Unit = {
    refreshList()
    reload()
  }

  /** Inventory-only update from the graph snapshot; never reload an active transcript or change its draft/continuation.
    */
  def updateSummaries(summaries: List[SessionSummary]): Unit = {
    listVersion += 1
    liveSummaries = Map.empty
    state.update(_.copy(sessions = summaries, listError = ""))
  }

  private def refreshList(): Unit = if inventoryEnabled && mounted && visible && connected then {
    listVersion += 1
    val version = listVersion
    liveSummaries = Map.empty
    client.sessions().onComplete {
      case Success(sessions) if version == listVersion =>
        val inventory = (sessions.map(s => s.id -> s).toMap ++ liveSummaries).values.toList.sortBy(_.id.value)
        state.update(_.copy(sessions = inventory, listError = ""))
      case Failure(error) if version == listVersion => state.update(_.copy(listError = error.getMessage))
      case _                                        => ()
    }
  }

  def draft(text: String): Unit = edit(state.now().active)(c =>
    if text == c.draft then c else c.copy(draft = text, draftVersion = c.draftVersion + 1)
  )

  def voiceTarget(selection: Option[(Int, Int)]): VoiceDraftTarget = {
    val s = state.now()
    VoiceDraftTarget(s.active, s.current.draft, s.current.draftVersion, selection)
  }

  def setBranchBusy(busy: Boolean): Unit = state.update(_.copy(branchBusy = busy))

  def setVoiceBusy(busy: Boolean): Unit = state.update(_.copy(voiceBusy = busy))

  /** Insert like typing when the captured range is still valid; otherwise append to the latest originating draft. A
    * version check also detects edits which happen to restore the original text. Never submit or redirect a result.
    */
  def insertTranscript(target: VoiceDraftTarget, text: String): Unit =
    state.now().conversations.get(target.conversation).foreach { c =>
      val range = target.selection.filter { (start, end) =>
        c.draftVersion == target.version && c.draft == target.draft && start >= 0 && end >= start && end <= c.draft.length
      }
      val (updated, cursor) = range match {
        case Some((start, end)) => (c.draft.take(start) + text + c.draft.drop(end), start + text.length)
        case None               =>
          val separator =
            if c.draft.nonEmpty && text.nonEmpty && !c.draft.last.isWhitespace && !text.head.isWhitespace then " "
            else ""
          val value = c.draft + separator + text
          (value, value.length)
      }
      edit(target.conversation)(_.copy(draft = updated, draftVersion = c.draftVersion + 1))
      voiceInsertions.emit(VoiceInsertion(target.conversation, updated, cursor))
    }

  def selectAgent(value: String): Unit = {
    val s = state.now()
    if s.currentPending.isEmpty && !s.branchBusy then
      edit(s.active)(_.copy(agent = s.agents.find(AgentId.value(_) == value), resetContinuation = true))
  }

  def resume(id: String): Unit = {
    state.update(s =>
      s.copy(
        active = id,
        conversations = s.conversations.updated(
          id,
          s.conversations
            .getOrElse(id, ChatConversation(creating = id.isEmpty))
            .copy(resetContinuation = true, creating = id.isEmpty, nameCollision = false)
        )
      )
    )
    retire()
    attach()
  }

  /** Explicit read-only resubscription; successful baseline is required before authoring. */
  def reload(): Unit = if mounted && connected then { retire(); attach(reviewed = true) }

  /** New conversation is a local draft. Submission is the only remote authoring operation. */
  def newConversation(name: String): Unit = {
    val s = state.now()
    if s.enabled && s.pending.isEmpty && !s.voiceBusy then {
      val parsed = if name.trim.isEmpty then Right(None) else SessionId.from(name.trim).map(Some(_))
      parsed match {
        case Left(error) => notify(UiNotice(error, "warn"))
        case Right(id)   =>
          state.update(current =>
            current.copy(
              active = "",
              conversations = current.conversations.updated("", ChatConversation(agent = s.current.agent, name = id))
            )
          )
          retire()
          attach()
      }
    }
  }

  def send(): Unit = {
    val s = state.now()
    s.sendBlockedReason match {
      case Some(reason) => notify(UiNotice(reason, "warn"))
      case None         =>
        // Give the captured draft a stable key before authoring; the empty-key composer remains reusable.
        val key = if s.active.isEmpty then s.current.name.get.value else s.active
        if feedOwner == s.active then feedOwner = key
        state.update { current =>
          val conversations = current.conversations.updated(
            key,
            s.current.copy(draft = "", notice = "", newSubmitted = s.current.creating)
          )
          current.copy(
            active = key,
            pending = Some(ChatPendingTurn(key, initializedIdentity.get, s.current.draft)),
            conversations = if key == s.active then conversations else conversations.updated("", ChatConversation())
          )
        }
        submit(key)
    }
  }

  private def submit(id: String): Unit = {
    val s = state.now()
    val conversation = s.conversations(id)
    val params = TurnSubmit.Params(
      if conversation.creating then TurnSubmit.Target.New(conversation.name)
      else TurnSubmit.Target.Existing(conversation.snapshot.map(_.id).getOrElse(SessionId.unsafe(id))),
      conversation.agent.get,
      AgentTask(s.pending.get.prompt),
      s.pending.get.author,
      conversation.snapshot.flatMap(_.messages.lastOption.map(_.id)),
      conversation.resetContinuation
    )
    edit(id)(_.copy(resetContinuation = false, loading = false, notice = ""))
    client.submitTurn(params).onComplete { result =>
      // Terminal replies resolve execution bookkeeping, never overwrite a newer event-owned transcript.
      val conversation = state.now().conversations(id)
      val recoveryDraft = if conversation.draft.isEmpty then params.task.prompt else conversation.draft
      val outcome = result match {
        case Success(TurnOutcome.Succeeded(snapshot, _, _)) =>
          val head = snapshot.messages.lastOption
            .map(_.id)
            .filterNot(id => conversation.snapshot.exists(_.messages.exists(_.id == id)))
          conversation.copy(creating = false, terminalHead = head)
        case Success(TurnOutcome.Failed(acceptance, _, error)) =>
          notify(UiNotice(s"Turn failed: ${error.message}. Draft retained; reload history before resending.", "error"))
          conversation.copy(
            draft = recoveryDraft,
            reloadRequired = true,
            reviewRequired = true,
            resetContinuation = true,
            notice = if conversation.snapshot.nonEmpty then
              s"${error.message} (prompt: $acceptance). Draft retained. Reload history before manually sending again."
            else
              s"${error.message} (prompt: $acceptance). Draft retained. Inspect the session list and resume any saved conversation before manually resending."
          )
        case Failure(error: OrchestratorWSClient.RpcException) if TurnSubmit.isKnownRejection(error.error.code) =>
          notify(UiNotice(s"Turn rejected: ${error.getMessage}. Reload history before resending.", "error"))
          conversation.copy(
            draft = recoveryDraft,
            reloadRequired = conversation.snapshot.nonEmpty,
            creating = params.target.isInstanceOf[TurnSubmit.Target.New],
            newSubmitted = false,
            nameCollision = params.target.isInstanceOf[TurnSubmit.Target.New] && conversation.snapshot.nonEmpty,
            reviewRequired = true,
            resetContinuation = true,
            notice = s"${error.getMessage}. Review the request before manually sending again."
          )
        case Failure(error) =>
          notify(
            UiNotice(
              s"Turn result unknown: ${error.getMessage}. It may still be running; reconnect and reload history.",
              "error"
            )
          )
          conversation.copy(
            draft = recoveryDraft,
            reloadRequired = true,
            reviewRequired = true,
            resetContinuation = true,
            notice =
              s"Result unknown: ${error.getMessage}. The turn may still be running. Inspect the session list and resume the saved conversation before manually resending; do not duplicate a running turn."
          )
      }
      val succeeded = result.toOption.exists(_.isInstanceOf[TurnOutcome.Succeeded])
      val reconcilePublication = succeeded && (outcome.terminalHead.nonEmpty || outcome.serverPending.nonEmpty)
      val updated = outcome.copy(
        feedReady = outcome.feedReady && succeeded && !reconcilePublication,
        serverPending = if succeeded then outcome.serverPending else Nil,
        reloadRequired = outcome.reloadRequired || !outcome.feedReady || reconcilePublication
      )
      if !succeeded && feedOwner == id then retire()
      state.update(s => s.copy(pending = None, conversations = s.conversations.updated(id, updated)))
      // Reconcile lagging heads and stored-but-unpublished observations, even when a failed CAS kept the old head.
      // This is one fresh read baseline, never authoring replay or terminal transcript replacement.
      if reconcilePublication && state.now().active == id && mounted && visible && connected then {
        retire()
        attach()
      }
      refreshList()
    }
  }
}
