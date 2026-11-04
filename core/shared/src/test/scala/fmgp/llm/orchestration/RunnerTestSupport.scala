package fmgp.yallmo.orchestration

import fmgp.yallmo.model.*
import zio.*

/** Internal unit-test harness. Production callers can only run loops through Orchestrator.submit. */
private[orchestration] object RunnerTestSupport {
  extension [R](runner: AgentRunner[R]) {
    def run(
        agent: AgentInstance[R],
        task: AgentTask,
        staticContext: StaticContext[R],
        budget: AgentBudget = AgentBudget.default
    ): ZIO[R, YallmoError, AgentResult] =
      runWithContext(agent, task, staticContext, RunContext(), budget).map(_.result)

    def runWithContext(
        agent: AgentInstance[R],
        task: AgentTask,
        staticContext: StaticContext[R],
        runContext: RunContext,
        budget: AgentBudget = AgentBudget.default
    ): ZIO[R, YallmoError, AgentTurnResult] =
      for {
        accepted = runContext.append(MessagePayload.User(task.prompt))
        initial <- ZIO.foldLeft(accepted.messages)(Chunk.empty[Message]) { (chain, payload) =>
          Clock.instant.map(time => chain :+ Message(MessageData(agent.id, chain.lastOption.map(_.id), time, payload)))
        }
        active <- Ref.Synchronized.make(initial)
        history = new ManagedHistory {
          def initialMessages: UIO[Chunk[Message]] = ZIO.succeed(initial)
          def messages: UIO[Chunk[Message]] = active.get
          def rebase(base: MessageId, head: MessageId, onto: Option[MessageId]): IO[YallmoError, Chunk[Message]] =
            ZIO.fail(YallmoError.InvalidMessage("Runner-only harness has no object store"))
          private[orchestration] def snapshot: UIO[Chunk[Message]] = active.get
          private def appendLocked(current: Chunk[Message], payloads: Chunk[MessagePayload]): UIO[Chunk[Message]] =
            ZIO.foldLeft(payloads)(current) { (chain, payload) =>
              Clock.instant.map(time =>
                chain :+ Message(MessageData(agent.id, chain.lastOption.map(_.id), time, payload))
              )
            }
          private[orchestration] def appendAt(
              payloads: Chunk[MessagePayload],
              expectedParent: Option[MessageId]
          ): IO[YallmoError, Option[MessageId]] = active.modifyZIO { current =>
            if current.lastOption.map(_.id) != expectedParent then
              ZIO.fail(YallmoError.InvalidAgentDecision("History changed during work"))
            else appendLocked(current, payloads).map(next => (next.lastOption.map(_.id), next))
          }
          private[orchestration] def append(payloads: Chunk[MessagePayload]): IO[YallmoError, Unit] =
            active.updateZIO(current => appendLocked(current, payloads))

        }
        turn <- runner
          .execution(agent, staticContext, budget)
          .run(
            task,
            history
          )
        transcript <- history.messages.map(messages => RunContext(messages.map(_.payload)))
        _ <- ZIO.dieMessage("Runner did not save its final transcript").unless(transcript == turn.runContext)
      } yield turn
  }
}
