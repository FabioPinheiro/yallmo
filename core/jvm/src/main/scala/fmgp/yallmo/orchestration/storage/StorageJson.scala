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
      // Decoder errors may quote untrusted input; report schema stages without exposing stored content.
      envelope <- json.fromJson[RawEnvelope].left.map(_ => "invalid envelope JSON; expected id, kind and value fields")
      id <- MessageId.from(envelope.id)
      kind <- ArtifactKind.from(envelope.kind).left.map(_ => "unsupported artifact kind; expected 'msg'")
      artifact <- kind match
        case ArtifactKind.Msg =>
          if envelope.value.asObject.flatMap(_.get("payload")).flatMap(_.asObject).exists(_.contains("AudioInput")) then
            Left(
              "legacy AudioInput payload is unsupported; stop the REPL and recover into a new directory with " +
                "sbt \"core/runMain fmgp.yallmo.orchestration.storage.RecoverLegacyAudio <source> <new-destination>\". " +
                "Redacted recordings become unavailable-audio notices; the source is unchanged."
            )
          else if envelope.value.asObject.flatMap(_.get("payload")).flatMap(_.asObject).exists(_.contains("ToolResult"))
          then
            Left(
              "legacy ToolResult payload is unsupported; tool executions now require ToolCall/ToolOutput " +
                "pairs. Archive the existing storage and start in a new directory, or migrate a copy offline, " +
                "recomputing message IDs, descendant parent IDs and session references. " +
                "No automatic migration is performed."
            )
          else
            envelope.value.toJson
              .fromJson[MessageData]
              .left
              .map(_ =>
                s"message ${MessageId.value(id)}: invalid message data; check agentId, parentId, timestamp and payload schema"
              )
              .map(value => DecodedArtifact.Msg(StoredArtifact(id, value)))
    } yield artifact
}
