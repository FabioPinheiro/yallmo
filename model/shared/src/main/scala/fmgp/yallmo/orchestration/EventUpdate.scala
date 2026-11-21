package fmgp.yallmo.orchestration

import fmgp.yallmo.model.{AgentId, BlobId, Message, MessageId, MessagePayload}
import fmgp.yallmo.tool.FunctionKey
import java.time.Instant
import zio.Chunk

/** Subscription selection follows canonical ancestry, not exclusive ownership of immutable messages. */
enum EventScope {
  case All
  case Session(sessionId: SessionId)
}

/** Observation provenance only. A persisted object is not necessarily on this session's canonical head. */
final case class EventOrigin(sessionId: SessionId, turnId: Option[String] = None)

/** Process-local canonical session snapshot. `revision` orders live outcomes; it is distinct from the durable
  * info/archive revision in `meta` and is not a durable replay cursor.
  */
final case class EventSession(
    id: SessionId,
    agentIds: Seq[AgentId],
    messageCount: Long,
    head: Option[MessageId],
    revision: Long,
    error: Option[String] = None,
    meta: Option[SessionMetadata] = None,
    info: Option[SessionInfo] = None
)

object EventSession {
  def from(session: Session, revision: Long): EventSession =
    EventSession(
      session.id,
      session.agentIds,
      session.messages.size.toLong,
      session.messages.lastOption.map(_.id),
      revision,
      meta = session.meta,
      info = session.info
    )
}

/** Public projection retained by bounded subscriber queues, never raw provider continuation payloads. */
final case class EventMessage(
    id: MessageId,
    agentId: AgentId,
    parentId: Option[MessageId],
    timestamp: Instant,
    role: String,
    content: String,
    blobIds: Chunk[BlobId],
    toolName: Option[FunctionKey]
)

object EventMessage {
  def from(message: Message): EventMessage = EventMessage(
    message.id,
    message.agentId,
    message.parentId,
    message.timestamp,
    message.payload.role,
    message.payload.contentStr,
    message.payload.blobIds,
    message.payload match {
      case MessagePayload.ToolCall(call)     => Some(call.name)
      case MessagePayload.ToolOutput(output) => Some(output.name)
      case _                                 => None
    }
  )
}

/** Only public message observations enter live fan-out. */
enum EventUpdate {
  case Pending(operationId: String, messages: Seq[EventMessage])
  case Confirmed(operationId: String, messages: Seq[EventMessage], sessions: Seq[EventSession])
  case Uncertain(operationId: String)
}

final case class SequencedEventUpdate(sequence: Long, change: EventUpdate, origin: Option[EventOrigin] = None)
