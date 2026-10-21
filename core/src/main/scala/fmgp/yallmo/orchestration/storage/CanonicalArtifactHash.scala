package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.{Message, MessageData, MessageId}

/** Canonical content addresses for immutable stored messages. */
private[storage] object CanonicalArtifactHash {
  def store(value: MessageData): StoredArtifact[MessageData] =
    StoredArtifact(Message(value).id, value)

  def verify(artifact: DecodedArtifact): Either[(MessageId, MessageId), Unit] =
    artifact match
      case DecodedArtifact.Msg(stored) =>
        val computed = Message(stored.value).id
        Either.cond(computed == stored.id, (), (stored.id, computed))
}
