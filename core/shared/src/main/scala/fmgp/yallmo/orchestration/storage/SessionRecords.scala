package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.{AgentId, YallmoError}
import fmgp.yallmo.orchestration.*
import java.time.Instant

/** Shared pure rules; each backend applies them inside its serialized session mutation boundary. */
private[storage] object SessionRecords {
  def created(agentIds: Seq[AgentId], info: Option[SessionInfo], now: Instant): SessionRef =
    SessionRef(agentIds, None, Some(SessionMetadata(createdAt = Some(now), updatedAt = Some(now))), info)

  /** A fork starts fresh audit metadata and source lineage, not the source's archive, status or description. */
  def forked(source: SessionId, previous: SessionRef, now: Instant): SessionRef =
    created(previous.agentIds, Some(SessionInfo(parentSessionId = Some(source))), now).copy(head = previous.head)

  def updateInfo(
      id: SessionId,
      previous: SessionRef,
      expectedRevision: Long,
      info: Option[SessionInfo],
      now: Instant
  ): Either[YallmoError, SessionRef] =
    for {
      _ <- checkRevision(id, previous, expectedRevision)
      _ <- Either.cond(
        info.forall(_.parentSessionId == previous.info.flatMap(_.parentSessionId)),
        (),
        YallmoError.InvalidSessionInfo(id, "parentSessionId cannot change after creation")
      )
    } yield info match {
      case Some(value) if !previous.info.contains(value) =>
        previous.copy(info = Some(value), meta = Some(changed(previous, now)))
      case _ => previous
    }

  def setArchived(
      id: SessionId,
      previous: SessionRef,
      expectedRevision: Long,
      archived: Boolean,
      now: Instant
  ): Either[YallmoError, SessionRef] =
    checkRevision(id, previous, expectedRevision).map { _ =>
      if previous.meta.flatMap(_.archivedAt).nonEmpty == archived then previous
      else previous.copy(meta = Some(changed(previous, now).copy(archivedAt = Option.when(archived)(now))))
    }

  private def changed(previous: SessionRef, now: Instant): SessionMetadata = {
    val meta = previous.meta.getOrElse(SessionMetadata())
    meta.copy(updatedAt = Some(now), revision = meta.revision + 1L)
  }

  private def checkRevision(
      id: SessionId,
      previous: SessionRef,
      expected: Long
  ): Either[YallmoError, Unit] = {
    val actual = previous.meta.fold(0L)(_.revision)
    Either.cond(actual == expected, (), YallmoError.SessionMetadataConflict(id, expected, actual))
  }
}
