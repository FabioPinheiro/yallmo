package fmgp.yallmo.webharness

import com.raquo.laminar.api.L.Var
import fmgp.yallmo.orchestration.SessionId
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/** Metadata-only target: graph editing never constructs or selects a Chat conversation. */
private[webharness] final case class ConversationTitleTarget(id: SessionId, title: Option[String], revision: Long)

/** A retained title draft captures its revision independently of subsequent live observations. */
private[webharness] final case class ConversationTitleDraft(
    value: String,
    expectedRevision: Long,
    saving: Boolean = false,
    needsRefresh: Boolean = false,
    error: String = ""
)

/** Application-owned title editing shared by Chat and Graph. No view owns the mutation's lifetime. */
private[webharness] final class ConversationTitleModel(
    client: OrchestratorWSClient,
    updated: SessionSnapshot => Unit,
    notify: UiNotice => Unit
)(using ExecutionContext) {
  val enabled = Var(false)
  val drafts = Var(Map.empty[SessionId, ConversationTitleDraft])
  private var connected = false
  private var generation = -1L

  def configure(info: Initialize.Result): Unit = {
    if generation != client.connectionVersion then disable()
    generation = client.connectionVersion
    connected = true
    enabled.set(info.identity.role == Role.Operator && info.methods.contains(SessionsRename.method))
  }

  def disable(): Unit = {
    connected = false
    enabled.set(false)
    drafts.update(
      _.view
        .mapValues(draft =>
          if draft.saving then
            draft.copy(
              saving = false,
              needsRefresh = true,
              error = "The rename outcome is unknown. Reload the latest title before saving again."
            )
          else draft
        )
        .toMap
    )
  }

  private def edit(id: SessionId)(change: ConversationTitleDraft => ConversationTitleDraft): Unit =
    drafts.update(values => values.get(id).fold(values)(draft => values.updated(id, change(draft))))

  def begin(target: ConversationTitleTarget): Unit = if enabled.now() then
    drafts.update(values =>
      if values.contains(target.id) then values
      else values.updated(target.id, ConversationTitleDraft(target.title.getOrElse(""), target.revision))
    )

  def change(id: SessionId, value: String): Unit =
    edit(id)(draft => if draft.saving then draft else draft.copy(value = value))

  def cancel(id: SessionId): Unit = if !drafts.now().get(id).exists(_.saving) then drafts.update(_ - id)

  def save(id: SessionId, reset: Boolean = false): Unit =
    drafts.now().get(id).filter(draft => enabled.now() && !draft.saving && !draft.needsRefresh).foreach { draft =>
      val title = Option.unless(reset)(draft.value.trim)
      if title.exists(value =>
          value.isEmpty || value.length > SessionsRename.MaxTitleLength ||
            value.exists(char => Character.isISOControl(char) || char == '\u2028' || char == '\u2029')
        )
      then
        edit(id)(
          _.copy(error =
            s"Use a single-line title of 1–${SessionsRename.MaxTitleLength} characters, or choose Reset title."
          )
        )
      else {
        val connection = client.connectionVersion
        edit(id)(_.copy(saving = true, error = ""))
        client.renameSession(SessionsRename.Params(id, draft.expectedRevision, title)).onComplete {
          case Success(snapshot) if connected && connection == client.connectionVersion =>
            updated(snapshot)
            drafts.update(_ - id)
            notify(UiNotice(if reset then "Conversation title reset." else "Conversation title updated."))
          case Failure(error) if connected && connection == client.connectionVersion =>
            val knownRejection = error match {
              case remote: OrchestratorWSClient.RpcException =>
                Set(ErrorCode.Forbidden, ErrorCode.Unauthorized, ErrorCode.NotFound, -32601, -32602).contains(
                  remote.error.code
                )
              case _ => false
            }
            edit(id)(
              _.copy(
                saving = false,
                needsRefresh = !knownRejection,
                error = if knownRejection then error.getMessage
                else
                  "The title changed or the save outcome is unknown. Reload the latest title, then review your draft before saving."
              )
            )
          case _ => ()
        }
      }
    }

  /** Explicit reconciliation keeps the typed value and never retries a mutation. */
  def refresh(id: SessionId): Unit =
    drafts.now().get(id).filter(draft => enabled.now() && !draft.saving).foreach { _ =>
      val connection = client.connectionVersion
      edit(id)(_.copy(saving = true))
      client.session(id.value).onComplete {
        case Success(snapshot) if connected && connection == client.connectionVersion =>
          updated(snapshot)
          edit(id)(
            _.copy(
              saving = false,
              needsRefresh = false,
              expectedRevision = snapshot.meta.fold(0L)(_.revision),
              error =
                s"Latest title: ${SessionPresentation.displayName(id.value, snapshot.info)}. Your draft is retained; review it before saving."
            )
          )
        case Failure(error) if connected && connection == client.connectionVersion =>
          edit(id)(_.copy(saving = false, error = error.getMessage))
        case _ => ()
      }
    }
}
