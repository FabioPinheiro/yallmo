package fmgp.yallmo.orchestration

import zio.*
import fmgp.yallmo.model.*

/** Temporary payload snapshot for one turn's extensions and provider rendering, not an independent transcript owner.
  * Ordinary execution derives a fresh snapshot from ManagedHistory each turn. Invocation uses only its temporary input.
  */
final case class RunContext(messages: Chunk[MessagePayload] = Chunk.empty) {
  def append(message: MessagePayload): RunContext = copy(messages = messages :+ message)

  def appendAll(newMessages: Chunk[MessagePayload]): RunContext =
    copy(messages = messages ++ newMessages)
}
