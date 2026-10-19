package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.{AudioInput, Message, MessageData, MessageId}

/** Canonical content addresses for immutable stored messages. */
private[storage] object CanonicalArtifactHash {
  def store(value: MessageData): StoredArtifact[MessageData] =
    // Hash the live input first, then retain only the shared metadata-only projection.
    StoredArtifact(Message(value).id, value.redacted)

  def verify(artifact: DecodedArtifact): Either[(MessageId, MessageId), Unit] =
    artifact match
      case DecodedArtifact.Msg(stored) if stored.value.payload.isInstanceOf[AudioInput] =>
        // Audio identity includes original bytes that are deliberately absent from retained records.
        Right(())
      case DecodedArtifact.Msg(stored) =>
        val computed = Message(stored.value).id
        Either.cond(computed == stored.id, (), (stored.id, computed))
}
