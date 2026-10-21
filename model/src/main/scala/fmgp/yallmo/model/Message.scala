package fmgp.yallmo.model

import fmgp.yallmo.model.llm.ProviderContinuation
import zio.Chunk
import zio.json.{EncoderOps, JsonCodec}

import java.time.Instant
import scala.util.Try

/** A full SHA-256 address used by persisted artifacts. */
opaque type MessageId = String
object MessageId {
  private val Pattern = "sha256:[0-9a-f]{64}".r

  def from(value: MessageData): MessageId = unsafe(s"sha256:${Utils.sha256(value.toJson)}")
  def from(value: String): Either[String, MessageId] =
    Either.cond(
      Pattern.matches(value),
      value,
      "artifact id must be sha256: followed by 64 lowercase hexadecimal characters"
    )

  /** Creates a content address from the SHA-256 of UTF-8 text. */
  def sha256(value: String): MessageId = unsafe(s"sha256:${Utils.sha256(value)}")
  def unsafe(value: String): MessageId = value

  def value(id: MessageId): String = id

  given JsonCodec[MessageId] = JsonCodec.string.transformOrFail(MessageId.from, MessageId.value)
}

given JsonCodec[Instant] = JsonCodec.string.transformOrFail(
  value => Try(Instant.parse(value)).toEither.left.map(_.getMessage),
  _.toString
)

final case class MessageData(
    agentId: AgentId,
    parentId: Option[MessageId],
    timestamp: Instant,
    payload: MessagePayload
) derives JsonCodec

final case class Message(id: MessageId, data: MessageData) {
  def agentId: AgentId = data.agentId
  def parentId: Option[MessageId] = data.parentId
  def timestamp: Instant = data.timestamp
  def payload: MessagePayload = data.payload

}

object Message {
  def apply(data: MessageData): Message =
    new Message(MessageId.from(data), data)

  export MessagePayload.{
    Assistant,
    Context,
    FunctionCallOutput,
    FunctionCallRequest as FunctionCall,
    Instructions,
    RagResult,
    Reasoning,
    ToolResult,
    User
  }
}

/** Ordered, provider-neutral context exchanged with a language model. */
sealed trait MessagePayload derives JsonCodec {
  def role: String
  def contentStr: String

  /** Binary content is referenced, never embedded in a message. */
  def blobIds: Chunk[BlobId] = Chunk.empty
}

/** The container format of audio supplied to a multimodal model request. */
enum AudioFormat(val wireName: String) {
  case Wav extends AudioFormat("wav")
}

object AudioFormat {
  given JsonCodec[AudioFormat] = JsonCodec.string.transformOrFail(
    value => AudioFormat.values.find(_.wireName == value).toRight("Unsupported audio format"),
    _.wireName
  )
}

object MessagePayload {

  /** Audio content stored separately from the immutable message. */
  final case class AudioAttachment(
      blobId: BlobId,
      format: AudioFormat,
      description: Option[String] = None
  ) extends MessagePayload derives JsonCodec {
    override def role: String = "user"
    override def blobIds: Chunk[BlobId] = Chunk(blobId)
    override def contentStr: String =
      s"AudioAttachment(blobId=${blobId.value}, format=${format.wireName}${description.fold("")(value =>
          s", description=$value"
        )})"
    override def toString: String = contentStr
  }

  final case class User(text: String) extends MessagePayload derives JsonCodec {
    override def role: String = "user"
    override def contentStr: String = text
  }

  final case class Assistant(text: String) extends MessagePayload derives JsonCodec {
    override def role: String = "assistant"
    override def contentStr: String = text
  }

  final case class Context(label: String, text: String) extends MessagePayload derives JsonCodec {
    override def role: String = "context"
    override def contentStr: String = s"$label:\n$text"
  }

  final case class RagResult(retrieverKey: String, documents: Chunk[RagDocument]) extends MessagePayload
      derives JsonCodec {
    override def role: String = "context"
    override def contentStr: String =
      documents
        .map(document => s"source=${document.source}\n${document.text}")
        .mkString(s"RAG result from $retrieverKey:\n", "\n\n", "")
  }

  final case class ToolResult(toolKey: String, text: String) extends MessagePayload derives JsonCodec {
    override def role: String = "context"
    override def contentStr: String = s"tool result from $toolKey:\n$text"
  }

  final case class Reasoning(summary: Option[String], continuation: ProviderContinuation) extends MessagePayload
      derives JsonCodec {
    override def role: String = "reasoning"
    override def contentStr: String = summary.getOrElse("")
  }

  final case class FunctionCallRequest(call: fmgp.yallmo.model.FunctionCall) extends MessagePayload derives JsonCodec {
    override def role: String = "tool_call"
    override def contentStr: String = call.argumentsJson
  }

  final case class FunctionCallOutput(output: fmgp.yallmo.model.FunctionCallOutput) extends MessagePayload
      derives JsonCodec {
    override def role: String = "tool_result"
    override def contentStr: String = output.output
  }

  /** The immutable system instruction for this request; it must be the final input item. */
  final case class Instructions(text: String) extends MessagePayload derives JsonCodec {
    override def role: String = "system"
    override def contentStr: String = text
  }
}
