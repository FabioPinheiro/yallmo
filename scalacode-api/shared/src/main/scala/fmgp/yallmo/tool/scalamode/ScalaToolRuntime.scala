package fmgp.yallmo.tool.scalamode

import zio.*

/** Trusted runner helpers, not marked safe or exposed as snippet capabilities. Transport bootstrap and file logging are
  * platform-specific; pure evaluation works on JVM and Scala.js.
  */
object ScalaToolRuntime extends ScalaToolRuntimePlatform {
  val LogFile: String = "runtime.jsonl"
  val ExecutionStartedFile: String = "execution-started"
  val ExecutionMarkerProperty: String = "yallmo.scalamode.executionMarker"

  /** Overload resolution preserves the inferred snippet type: plain values are evaluated, effects are executed. Only
    * effects with no environment requirement are executed, without runtime type tests or casts.
    */
  def evaluate(value: => Any): Task[Any] = markExecutionStarted *> ZIO.attempt(value)

  @scala.annotation.targetName("evaluateEffect")
  def evaluate[E, A](effect: => ZIO[Any, E, A]): ZIO[Any, E, A] =
    markExecutionStarted *> ZIO.suspendSucceed(effect)

  /** Pure services need no bootstrap input or connection. Evaluation overload selection stays in the thin generated
    * callback, where the submitted result's type is known; these runners never erase or inspect an effect.
    */
  def runPure[E, A](evaluate: Unit => ZIO[Any, E, A]): ZIO[Any, E | Throwable, Unit] =
    ZIO.suspendSucceed(evaluate(())).flatMap(value => Console.printLine(value))

}
