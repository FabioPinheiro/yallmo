package fmgp.yallmo.orchestration

import fmgp.yallmo.model.{MessagePayload, YallmoError}
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
        saved <- Ref.make(runContext)
        turn <- runner
          .execution(agent, staticContext, budget)
          .run(
            task,
            runContext.append(MessagePayload.User(task.prompt)),
            saved.set
          )
        transcript <- saved.get
        _ <- ZIO.dieMessage("Runner did not save its final transcript").unless(transcript == turn.runContext)
      } yield turn
  }
}
