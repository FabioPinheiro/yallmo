package fmgp.yallmo.orchestration

import zio.Chunk
import fmgp.yallmo.model.{AgentId, Message, MessageId}

/** Stable identifier for one ordered message thread. */
opaque type SessionId = String

object SessionId {
  private val NamePattern = "[A-Za-z0-9][A-Za-z0-9._-]*".r

  def from(value: String): Either[String, SessionId] =
    Either.cond(
      NamePattern.matches(value) && value != "." && value != "..",
      value,
      "session name must be one safe nonblank path segment"
    )

  def unsafe(value: String): SessionId = value

  extension (id: SessionId) def value: String = id

}

/** An ordered, durable sequence of message artifacts and the agents allowed to contribute to it. */
final case class Session(
    id: SessionId,
    agentIds: Chunk[AgentId],
    messages: Chunk[Message]
)

type SessionRef = (SessionId, MessageId)

//SessionRange
//SessionSub
