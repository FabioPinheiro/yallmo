package fmgp.yallmo.orchestration

import zio.*
import fmgp.yallmo.model.*

/** The immutable, evolving state of one agent run.
  *
  * Only the ordered message transcript changes from turn to turn. Use [[append]] or [[appendAll]] to obtain the next
  * value; no context object is mutated or shared between turns.
  */
// FIXME: Replace RunContext with fmgp.yallmo.model.Thread once runtime transcripts retain MsgArtifact metadata.
final case class RunContext(messages: Chunk[MessagePayload] = Chunk.empty) {
  def append(message: MessagePayload): RunContext = copy(messages = messages :+ message)

  def appendAll(newMessages: Chunk[MessagePayload]): RunContext =
    copy(messages = messages ++ newMessages)
}
