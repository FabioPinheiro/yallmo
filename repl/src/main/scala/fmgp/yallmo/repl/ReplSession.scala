package fmgp.yallmo.repl

import zio.*
import java.nio.file.Path
import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.LlmProviderPayloadDebug
import fmgp.yallmo.repl.debug.ReplDebugSnapshot
import fmgp.yallmo.orchestration.{AgentBudget, AgentResult, AgentTask, RunContext, StaticContext}

/** The immutable state retained by one interactive REPL session. */
final case class ReplState(
    runContext: RunContext = RunContext(),
    logLevel: LogLevel = LogLevel.Error,
    sessionId: Option[String] = None,
    lastProviderRequest: Option[Path] = None
)

/** The result of accepting one user prompt in a REPL session. */
final case class ReplTurn(
    state: ReplState,
    result: AgentResult,
    newMessages: Chunk[MessagePayload]
)

/** Adapts the bounded agent runner to a multi-prompt interactive session.
  *
  * Each submitted prompt starts from the previous [[RunContext]], while the [[StaticContext]] remains fixed for the
  * lifetime of this session.
  */
final class ReplSession[R](
    runner: AgentRunner[R],
    agent: AgentInstance[R],
    staticContext: StaticContext[R],
    budget: AgentBudget = AgentBudget.default,
    afterSubmit: (ReplState, ReplTurn) => IO[YallmoError, ReplTurn] = (_, turn) => ZIO.succeed(turn)
) {

  /** Captures the current transcript without executing the agent. */
  def debugSnapshot(state: ReplState): ReplDebugSnapshot =
    ReplDebugSnapshot(state.runContext.messages)

  def submit(state: ReplState, prompt: String): ZIO[R, YallmoError, ReplTurn] =
    LlmProviderPayloadDebug
      .withRequestCapture(runner.runWithContext(agent, AgentTask(prompt), staticContext, state.runContext, budget))
      .map { case (agentRun, requestCapture) =>
        // A run may append several messages (user prompt, assistant turns, tool calls/results, RAG results).
        // Keep only this run's append-only transcript delta; the preceding messages already belong to `state`.
        val newMessages = agentRun.runContext.messages.drop(state.runContext.messages.size)
        ReplTurn(
          ReplState(
            agentRun.runContext,
            state.logLevel,
            state.sessionId,
            requestCapture.orElse(state.lastProviderRequest)
          ),
          agentRun.result,
          newMessages
        )
      }
      .flatMap(afterSubmit(state, _))
}
