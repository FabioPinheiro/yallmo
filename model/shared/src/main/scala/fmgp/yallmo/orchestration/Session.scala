package fmgp.yallmo.orchestration

import java.time.Instant
import zio.json.JsonCodec
import fmgp.yallmo.model.{AgentId, Message, MessageId}

/** Stable identifier for one ordered message thread. */
opaque type SessionId = String

object SessionId {
  private val NamePattern = "[A-Za-z0-9][A-Za-z0-9._-]*".r

  given JsonCodec[SessionId] = JsonCodec.string.transformOrFail(from, _.value)

  def from(value: String): Either[String, SessionId] =
    Either.cond(
      NamePattern.matches(value) && value != "." && value != "..",
      value,
      "session name must be one safe nonblank path segment"
    )

  def unsafe(value: String): SessionId = value

  extension (id: SessionId) def value: String = id

}

/** Storage-owned audit metadata. Unknown legacy dates remain absent. `updatedAt` and `revision` track info/archive
  * changes only, not message appends, participant additions or execution activity. Archiving does not block execution.
  */
final case class SessionMetadata(
    createdAt: Option[Instant] = None,
    updatedAt: Option[Instant] = None,
    archivedAt: Option[Instant] = None,
    revision: Long = 0L
) derives JsonCodec

/** Caller-supplied session description. A supplied object replaces the complete previous info; absent fields clear
  * their values. `parentSessionId` is validated at creation and is immutable thereafter; lineage is not authority.
  * `status` is descriptive text only, not an archive flag or execution state.
  */
final case class SessionInfo(
    title: Option[String] = None,
    parentSessionId: Option[SessionId] = None,
    goals: Option[String] = None,
    status: Option[String] = None
) derives JsonCodec

/** An ordered, durable sequence of message artifacts and the agents allowed to contribute to it. `meta` is assigned
  * only by Storage; both optional fields may be absent on legacy records.
  */
final case class Session(
    id: SessionId,
    agentIds: Seq[AgentId],
    messages: Seq[Message],
    meta: Option[SessionMetadata] = None,
    info: Option[SessionInfo] = None
) {
  def ref: SessionRef = SessionRef(agentIds, messages.lastOption.map(_.id), meta, info)
}

/** Persisted session state without loading history. All mutations preserve fields they do not own. */
final case class SessionRef(
    agentIds: Seq[AgentId],
    head: Option[MessageId],
    meta: Option[SessionMetadata] = None,
    info: Option[SessionInfo] = None
) derives JsonCodec
