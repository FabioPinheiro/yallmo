package fmgp.yallmo.repl.voice

import fmgp.yallmo.model.MessagePayload.AudioAttachment
import fmgp.yallmo.model.{AudioFormat, BlobId}
import zio.Chunk

/** Capture result before ingestion. Bytes never become part of the message payload.
  *
  * TODO support for streams
  */
final case class CapturedAudio(input: AudioAttachment, bytes: Chunk[Byte]) {
  def format: AudioFormat = input.format
  def sizeBytes: Long = bytes.size.toLong
  override def toString: String = s"CapturedAudio(input=$input, sizeBytes=$sizeBytes)"
}
object CapturedAudio {
  def apply(bytes: Chunk[Byte], format: AudioFormat): CapturedAudio =
    CapturedAudio(AudioAttachment(BlobId.fresh(), format), bytes)
}
