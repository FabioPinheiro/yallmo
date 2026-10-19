package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.{MessageData, MessageId}
import zio.json.*
import zio.json.ast

/** Metadata-only JSONL boundary. Decode projects before indexing/duplicate checks so live and redacted audio agree. */
private[storage] object StorageJson {
  private final case class RawEnvelope(id: String, kind: String, value: ast.Json) derives JsonCodec

  def envelopeJson(artifact: DecodedArtifact): Either[String, String] =
    artifact match
      case DecodedArtifact.Msg(stored) =>
        stored.value.redacted.toJson.fromJson[ast.Json].left.map(_.toString).map { value =>
          RawEnvelope(MessageId.value(stored.id), ArtifactKind.Msg.value, value).toJson
        }

  def decodeEnvelope(json: String): Either[String, DecodedArtifact] =
    for {
      envelope <- json.fromJson[RawEnvelope]
      id <- MessageId.from(envelope.id)
      kind <- ArtifactKind.from(envelope.kind)
      artifact <- kind match
        case ArtifactKind.Msg =>
          envelope.value.toJson
            .fromJson[MessageData]
            .map(value => DecodedArtifact.Msg(StoredArtifact(id, value.redacted)))
    } yield artifact
}
