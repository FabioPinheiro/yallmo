package fmgp.yallmo.orchestration.storage

import fmgp.yallmo.model.{MessageData, MessageId}
import zio.json.*
import zio.json.ast

/** Message JSONL contains references, not blob content. */
private[storage] object StorageJson {
  private final case class RawEnvelope(id: String, kind: String, value: ast.Json) derives JsonCodec

  def envelopeJson(artifact: DecodedArtifact): Either[String, String] =
    artifact match
      case DecodedArtifact.Msg(stored) =>
        stored.value.toJson.fromJson[ast.Json].left.map(_.toString).map { value =>
          RawEnvelope(MessageId.value(stored.id), ArtifactKind.Msg.value, value).toJson
        }

  def decodeEnvelope(json: String): Either[String, DecodedArtifact] =
    for {
      envelope <- json.fromJson[RawEnvelope]
      id <- MessageId.from(envelope.id)
      kind <- ArtifactKind.from(envelope.kind)
      artifact <- kind match
        case ArtifactKind.Msg =>
          if envelope.value.asObject.flatMap(_.get("payload")).flatMap(_.asObject).exists(_.contains("AudioInput")) then
            Left(
              "legacy AudioInput payload is unsupported; stop the REPL and recover into a new directory with " +
                "sbt \"core/runMain fmgp.yallmo.orchestration.storage.RecoverLegacyAudio <source> <new-destination>\". " +
                "Redacted recordings become unavailable-audio notices; the source is unchanged."
            )
          else envelope.value.toJson.fromJson[MessageData].map(value => DecodedArtifact.Msg(StoredArtifact(id, value)))
    } yield artifact
}
