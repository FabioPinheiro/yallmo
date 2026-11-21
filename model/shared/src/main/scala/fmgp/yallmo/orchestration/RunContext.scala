package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*

/** Temporary payload snapshot for one turn's provider rendering, not an independent transcript owner. Ordinary
  * execution derives a fresh snapshot from ManagedHistory each turn. Invocation uses only its temporary input.
  */
final case class RunContext(messages: Seq[MessagePayload] = Vector.empty) {
  def append(message: MessagePayload): RunContext = copy(messages = messages :+ message)

  def appendAll(newMessages: Seq[MessagePayload]): RunContext =
    copy(messages = messages ++ newMessages)
}
