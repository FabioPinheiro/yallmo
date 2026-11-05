package fmgp.yallmo.view.web

import com.raquo.laminar.api.L.{EventBus, Var}
import fmgp.yallmo.model.AgentId
import fmgp.yallmo.orchestration.{AgentTask, SessionId, SessionSummary}
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/** Browser-local state for one conversation, kept separately from the server's persisted transcript.
  *
  * Sending captures the draft in [[ChatPendingMessage]] and clears the composer, which remains editable for the next
  * message. A completed turn updates the snapshot without replacing that next draft. These values survive tab
  * navigation through [[ChatModel]], but are not persisted across page reloads.
  *
  * @param snapshot
  *   Last confirmed server snapshot, including the message head used for the next submission's concurrency check.
  *   Absent before the first submit result or successful history read; never contains the temporary pending card.
  * @param draft
  *   Editable, unsent composer text. On failure, submitted text is restored only when this is still empty.
  * @param draftVersion
  *   Increments on draft edits so asynchronous dictation cannot replace a stale selection, even if text is restored.
  * @param agent
  *   Assistant selected for the next turn, independent of historical message authors. Must belong to the configured
  *   agent catalog before sending.
  * @param resetContinuation
  *   Whether the next turn should discard prior opaque provider continuation, not visible history. Set on resume or
  *   agent selection; cleared when submitting and restored after a failed turn.
  * @param loading
  *   Whether a history read is outstanding for this conversation; blocks sending, not draft editing.
  * @param reloadRequired
  *   Recovery gate after a failed read or turn. A successful history reload clears it; this is not a server run status
  *   or proof that an uncertain turn has stopped.
  * @param notice
  *   Persistent inline recovery or validation guidance; empty when there is nothing to display. Separate from the
  *   short-lived notifications published by the model.
  */
private[web] final case class ChatConversation(
    snapshot: Option[SessionSnapshot] = None,
    name: Option[SessionId] = None,
    draft: String = "",
    draftVersion: Long = 0L,
    agent: Option[AgentId] = None,
    resetContinuation: Boolean = true,
    loading: Boolean = false,
    reloadRequired: Boolean = false,
    notice: String = ""
)

/** Captured user input displayed immediately while turn submission is outstanding.
  *
  * This is a presentation-only preview, not a persisted message, delivery acknowledgement, or retry queue. Completion
  * removes it alongside the authoritative snapshot update; failures remove it and preserve draft/recovery guidance.
  *
  * @param conversation
  *   Owning conversation's map key. The empty string identifies the initial unsaved conversation until the first submit
  *   returns a session ID.
  * @param author
  *   Claimed human identity from connection initialization, captured at Send time; not an authenticated identity.
  * @param prompt
  *   Exact submitted text, independent of subsequent edits to the now-cleared composer.
  */
private[web] final case class ChatPendingMessage(conversation: String, author: AgentId, prompt: String)

/** Application-root chat state shared by the sidebar, toolbar and routed conversation view.
  *
  * Conversation keys are server session IDs, except `""`, which always exists for the initial unsaved conversation. The
  * active key must exist in the map. Only one submission may be outstanding in this browser client, even when the user
  * browses another conversation. This is UI state, not a wire or persistence model.
  *
  * @param active
  *   Key of the conversation currently shown and edited; switching it does not redirect an outstanding turn's result.
  * @param conversations
  *   Retained per-conversation snapshots, drafts and preferences. The first submit result transfers the unsaved draft
  *   and pending preview to the returned session ID, then resets the unsaved entry.
  * @param sessions
  *   Last successfully fetched server summaries used by the conversation sidebar, not the source of transcript data.
  * @param agents
  *   Executable assistant IDs advertised by initialization; unlike the general registry, excludes human/presence IDs.
  * @param enabled
  *   Whether the initialized connection advertises the RPC methods needed to submit turns. A capability/UI gate, not
  *   authentication or authorization implemented by this model.
  * @param pending
  *   The client's single outstanding submitted-input preview, possibly owned by a conversation other than `active`.
  * @param voiceBusy
  *   Blocks Send and preparing a new conversation during explicit dictation; draft editing remains enabled.
  * @param listError
  *   Latest session-list read error displayed in the sidebar; cleared by a successful list refresh.
  */
private[web] final case class ChatState(
    active: String = "",
    conversations: Map[String, ChatConversation] = Map("" -> ChatConversation()),
    sessions: List[SessionSummary] = Nil,
    agents: List[AgentId] = Nil,
    enabled: Boolean = false,
    pending: Option[ChatPendingMessage] = None,
    voiceBusy: Boolean = false,
    listError: String = ""
) {

  /** Selected conversation; relies on the invariant that `active` is present in `conversations`. */
  def current: ChatConversation = conversations(active)

  /** Preview to render in the current transcript; another conversation's pending input stays hidden. */
  def currentPending: Option[ChatPendingMessage] = pending.filter(_.conversation == active)

  /** Whether a nonblank draft needs a valid configured agent, used for the inline Send warning. */
  def needsAgent: Boolean = current.draft.trim.nonEmpty && !current.agent.exists(agents.contains)

  /** First reason Send cannot proceed, shared by the button gate and rejected-keyboard-send notifications. */
  def sendBlockedReason: Option[String] =
    if !enabled then Some("Reconnect to a chat-enabled server before sending.")
    else if voiceBusy then Some("Finish or discard dictation before sending.")
    else if pending.nonEmpty then Some("Wait for the pending turn to finish before sending.")
    else if current.loading then Some("Wait for history to load before sending.")
    else if current.reloadRequired || (active.nonEmpty && current.snapshot.isEmpty) then
      Some("Reload history and review the transcript before sending again.")
    else if current.draft.trim.isEmpty then Some("Write a message before sending.")
    else if needsAgent then Some("Choose an agent before sending.")
    else None

  /** Sending is allowed only when connection, pending-work, history, draft and agent checks all pass. */
  def canSend: Boolean = sendBlockedReason.isEmpty
}

/** Application-owned controller for [[ChatState]] and the existing JSON-RPC client.
  *
  * Lives outside routed views so drafts and pending input survive navigation. Reads use version checks to ignore stale
  * responses; turn callbacks update their original conversation, not whichever is currently selected. Send captures
  * input once and submits once with an explicit new/existing target. Terminal snapshots replace the temporary preview;
  * no message is persisted by the browser and no request is automatically retried or replayed.
  *
  * @param client
  *   Shared application-root WebSocket adapter; connection lifecycle is managed by App.
  * @param notify
  *   Publishes transient user feedback; recovery instructions also remain in the affected conversation's notice.
  */
private[web] final class ChatModel(client: OrchestratorWSClient, notify: UiNotice => Unit = _ => ())(using
    ExecutionContext
) {
  val state = Var(ChatState())
  val voiceInsertions = new EventBus[VoiceInsertion]
  private var initializedIdentity = Option.empty[AgentId]
  private var listVersion = 0L
  private var readVersions = Map.empty[String, Long]

  private def edit(id: String)(f: ChatConversation => ChatConversation): Unit =
    state.update(s => s.copy(conversations = s.conversations.updated(id, f(s.conversations(id)))))

  def configure(info: Initialize.Result): Unit = {
    initializedIdentity = info.identity.chatAgent
    state.update(
      _.copy(
        enabled = OrchestratorWSClient.canChat(info) && initializedIdentity.nonEmpty,
        agents = info.configuredAgentIds.toList.flatMap(_.toList)
      )
    )
    refreshList()
  }

  def disable(): Unit = {
    // Closing/replacing a connection invalidates its reads, not the retained drafts or pending turn.
    listVersion += 1
    readVersions = readVersions.map { case (id, version) => id -> (version + 1) }
    state.update(s =>
      s.copy(
        enabled = false,
        conversations = s.conversations.map { case (id, conversation) =>
          id -> conversation.copy(loading = false)
        }
      )
    )
  }

  def refresh(): Unit = {
    refreshList()
    if state.now().active.nonEmpty then reload()
  }

  private def refreshList(): Unit = {
    listVersion += 1
    val version = listVersion
    client.sessions().onComplete {
      case Success(sessions) if version == listVersion => state.update(_.copy(sessions = sessions, listError = ""))
      case Failure(error) if version == listVersion    => state.update(_.copy(listError = error.getMessage))
      case _                                           => ()
    }
  }

  def draft(text: String): Unit = edit(state.now().active)(c =>
    if text == c.draft then c else c.copy(draft = text, draftVersion = c.draftVersion + 1)
  )

  def voiceTarget(selection: Option[(Int, Int)]): VoiceDraftTarget = {
    val s = state.now()
    VoiceDraftTarget(s.active, s.current.draft, s.current.draftVersion, selection)
  }

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
    if s.currentPending.isEmpty then
      edit(s.active)(_.copy(agent = s.agents.find(AgentId.value(_) == value), resetContinuation = true))
  }

  def resume(id: String): Unit = {
    state.update(s =>
      s.copy(
        active = id,
        conversations =
          s.conversations.updated(id, s.conversations.getOrElse(id, ChatConversation()).copy(resetContinuation = true))
      )
    )
    reload()
  }

  def reload(): Unit = {
    val s = state.now()
    val id = s.active
    if id.nonEmpty && !s.pending.exists(_.conversation == id) then {
      val version = readVersions.getOrElse(id, 0L) + 1
      readVersions = readVersions.updated(id, version)
      edit(id)(_.copy(loading = true))
      client.session(id).onComplete {
        case Success(snapshot) if readVersions.get(id).contains(version) =>
          edit(id)(c =>
            c.copy(
              snapshot = Some(snapshot),
              loading = false,
              reloadRequired = false,
              notice = if c.reloadRequired then
                "History reloaded. Review the stored transcript before manually resending; do not duplicate a turn that may still be running."
              else c.notice
            )
          )
        case Failure(error) if readVersions.get(id).contains(version) =>
          notify(UiNotice(s"History reload failed: ${error.getMessage}", "error"))
          edit(id)(
            _.copy(
              loading = false,
              reloadRequired = true,
              notice =
                s"History reload failed: ${error.getMessage}. If a submitted turn lost its result, it may still be running. Reconnect and reload before manually resending."
            )
          )
        case _ => ()
      }
    }
  }

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
      }
    }
  }

  def send(): Unit = {
    val s = state.now()
    s.sendBlockedReason match {
      case Some(reason) => notify(UiNotice(reason, "warn"))
      case None         =>
        state.update(
          _.copy(
            pending = Some(ChatPendingMessage(s.active, initializedIdentity.get, s.current.draft)),
            conversations = s.conversations.updated(s.active, s.current.copy(draft = "", notice = ""))
          )
        )
        submit(s.active)
    }
  }

  private def submit(id: String): Unit = {
    val s = state.now()
    val conversation = s.conversations(id)
    val params = TurnSubmit.Params(
      conversation.snapshot.fold[TurnSubmit.Target](TurnSubmit.Target.New(conversation.name))(s =>
        TurnSubmit.Target.Existing(s.id)
      ),
      conversation.agent.get,
      AgentTask(s.pending.get.prompt),
      s.pending.get.author,
      conversation.snapshot.flatMap(_.messages.lastOption.map(_.id)),
      conversation.resetContinuation
    )
    // Invalidate any older read before admitting this turn.
    readVersions = readVersions.updated(id, readVersions.getOrElse(id, 0L) + 1)
    edit(id)(_.copy(resetContinuation = false, loading = false, notice = ""))
    client.submitTurn(params).onComplete { result =>
      // Replace the preview and authoritative snapshot in one state transition, without matching prompt text.
      val conversation = state.now().conversations(id)
      val recoveryDraft = if conversation.draft.isEmpty then params.task.prompt else conversation.draft
      val updated = result match {
        case Success(TurnOutcome.Succeeded(snapshot, _, _)) =>
          conversation.copy(
            snapshot = Some(snapshot),
            reloadRequired = false,
            notice = ""
          )
        case Success(TurnOutcome.Failed(acceptance, snapshot, error)) =>
          notify(UiNotice(s"Turn failed: ${error.message}. Draft retained; reload history before resending.", "error"))
          conversation.copy(
            snapshot = snapshot.orElse(conversation.snapshot),
            draft = recoveryDraft,
            reloadRequired = true,
            resetContinuation = true,
            notice = if snapshot.orElse(conversation.snapshot).nonEmpty then
              s"${error.message} (prompt: $acceptance). Draft retained. Reload history before manually sending again."
            else
              s"${error.message} (prompt: $acceptance). Draft retained. Inspect the session list and resume any saved conversation before manually resending."
          )
        case Failure(error: OrchestratorWSClient.RpcException) if TurnSubmit.isKnownRejection(error.error.code) =>
          notify(UiNotice(s"Turn rejected: ${error.getMessage}. Reload history before resending.", "error"))
          conversation.copy(
            draft = recoveryDraft,
            reloadRequired = conversation.snapshot.nonEmpty,
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
            resetContinuation = true,
            notice =
              s"Result unknown: ${error.getMessage}. The turn may still be running. Inspect the session list and resume the saved conversation before manually resending; do not duplicate a running turn."
          )
      }
      state.update { s =>
        val key = updated.snapshot.fold(id)(_.id.value)
        val conversations = if key == id then s.conversations.updated(id, updated)
        else s.conversations.updated(key, updated).updated(id, ChatConversation())
        s.copy(active = if s.active == id then key else s.active, pending = None, conversations = conversations)
      }
      refreshList()
    }
  }
}
