package fmgp.yallmo.orchestration

import fmgp.yallmo.model.YallmoError
import zio.*
import fmgp.yallmo.orchestration.{RunContext, StaticContext}

/** An effectful, ordered transform of one agent run's dynamic context.
  *
  * Extensions can inspect the agent's [[StaticContext]] to load memory, add request metadata, redact sensitive data, or
  * apply deterministic pre-retrieval. They can return only a new [[RunContext]], so they cannot add capabilities or
  * otherwise change the static context after the run begins.
  */
trait ContextExtension[R] {
  def key: String

  def apply(
      staticContext: StaticContext[R],
      runContext: RunContext
  ): ZIO[R, YallmoError, RunContext]
}
