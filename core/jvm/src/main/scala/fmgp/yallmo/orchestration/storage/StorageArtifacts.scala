package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.{MessageData, MessageId}

/** The concrete artifact types stored by [[StorageFS]]. */
private[storage] enum ArtifactKind(val value: String) {
  case Msg extends ArtifactKind("msg")
}

private[storage] object ArtifactKind {
  def from(value: String): Either[String, ArtifactKind] =
    ArtifactKind.values.find(_.value == value).toRight(s"unsupported artifact kind '$value'")
}

private[storage] final case class StoredArtifact[A](id: MessageId, value: A)

private[storage] enum DecodedArtifact {
  case Msg(stored: StoredArtifact[MessageData])

  def id: MessageId = this match
    case Msg(stored) => stored.id
}
