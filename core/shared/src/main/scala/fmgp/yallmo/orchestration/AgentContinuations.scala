package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.ProviderContinuation
import zio.*

/** Request-only continuation filtering; stored managed history is never rewritten. */
object AgentContinuations {

  /** Filter only provider request history; keep the runner's append-only transcript and capability policy intact. */
  def withCurrentContinuations[R](
      selected: AgentInstance[R],
      start: Int,
      excluded: Option[Set[ProviderContinuation]] = None
  ): AgentInstance[R] =
    if start <= 0 && excluded.forall(_.isEmpty) then selected
    else
      new AgentInstance[R] {
        override val id: AgentId = selected.id
        override val capabilities = selected.capabilities
        override def configuredContext(context: StaticContext[R]): ZIO[R, YallmoError, StaticContext[R]] =
          selected.configuredContext(context)
        override def nextManaged(
            task: AgentTask,
            context: StaticContext[R],
            current: RunContext,
            history: ManagedHistory
        ): ZIO[R, YallmoError, AgentTurn] =
          for {
            old <- excluded match {
              case Some(values) => ZIO.succeed(values)
              case None => history.initialMessages.map(initial => continuations(initial.take(start).map(_.payload)))
            }
            turn <- selected.nextManaged(task, context, withoutContinuations(current, old), history)
          } yield turn

        override def next(
            task: AgentTask,
            context: StaticContext[R],
            current: RunContext
        ): ZIO[R, YallmoError, AgentTurn] =
          selected.next(task, context, excluded.fold(requestSnapshot(current))(withoutContinuations(current, _)))

        private def requestSnapshot(current: RunContext): RunContext = {
          val messages = current.messages.zipWithIndex.collect {
            case (message, index) if index >= start || !message.isInstanceOf[MessagePayload.Reasoning] => message
          }
          current.copy(messages = messages)
        }
      }

  def continuations(messages: Chunk[MessagePayload]): Set[ProviderContinuation] =
    messages.collect { case MessagePayload.Reasoning(_, continuation) => continuation }.toSet

  private def withoutContinuations(current: RunContext, excluded: Set[ProviderContinuation]): RunContext =
    current.copy(messages = current.messages.filter {
      case MessagePayload.Reasoning(_, continuation) => !excluded(continuation)
      case _                                         => true
    })
}
