package fmgp.yallmo.orchestration

import fmgp.yallmo.model.{Message, MessageId, YallmoError}
import fmgp.yallmo.model.som.*
import zio.*
import zio.json.JsonCodec

/** Opt-in preparation for a program's next managed turn, not an automatic cleanup policy.
  *
  * Trusted code selects an exclusive base, inclusive current head, destination and bounded plain-text evidence. All IDs
  * must belong to the inspected active snapshot; none are sent to or selected by System One. Evidence must be
  * explicitly curated: never pass serialized messages, provider reasoning/continuations, blob bytes or credentials. The
  * host requires both official confidence and selected probability to meet minConfidence (Laya extensions are ignored).
  * Invalid/uncertain answers and typed provider failures KEEP; cancellation and real rebase failures propagate.
  */
final case class SystemOneHistoryDecision(model: String, minConfidence: Double = 0.9) {
  import SystemOneHistoryDecision.*

  /** Call inside nextManaged, then pass this fresh RunContext AND the same history capability to the delegate's
    * nextManaged. Advisory state/answers are never appended. A changed source stops continuation rather than applying
    * an obsolete recommendation. No candidate means no advisory request.
    */
  def prepare(history: ManagedHistory)(
      select: Chunk[Message] => Option[(MessageId, MessageId, Option[MessageId], String)]
  ): ZIO[SOMProvider, YallmoError, RunContext] =
    for {
      _ <- ZIO
        .fail(YallmoError.InvalidConfig("History decision threshold must be finite and in (0.5, 1]"))
        .unless(minConfidence.isFinite && minConfidence > 0.5 && minConfidence <= 1.0)
      messages <- history.snapshot
      _ <- select(messages) match {
        case Some((base, head, onto, evidence)) if validCandidate(messages, base, head, onto) =>
          for {
            rebase <- decision
              .decide(Evidence(evidence))
              .map(answer => permitsRebase(answer.rewrite))
              .catchAll(_ => ZIO.succeed(false))
            current <- history.snapshot
            _ <- ZIO
              .fail(YallmoError.InvalidAgentDecision("History changed during advisory decision"))
              .unless(current.lastOption.map(_.id) == Some(head))
            _ <- history.rebase(base, head, onto).when(rebase)
          } yield ()
        case _ => ZIO.unit
      }
      fresh <- history.messages // Also refreshes AgentRunner's program work fence.
    } yield RunContext(fresh.map(_.payload))

  private val decision = SystemOneDecisionAgent[Evidence, Questions](
    model,
    _ =>
      Questions(
        SystemOneQuestion.Choice(
          "Should the host apply its preselected context rebase? Keep if the evidence is insufficient or uncertain.",
          Map("keep" -> "Keep the current context unchanged", "rebase" -> "Apply the host's preselected context rebase")
        )
      )
  )

  private def permitsRebase(answer: SystemOneAnswer.Choice): Boolean = {
    val probabilities = answer.probabilities
    def valid(value: Double): Boolean = value.isFinite && value >= 0.0 && value <= 1.0
    answer.choice == "rebase" && probabilities.keySet == Set("keep", "rebase") &&
    probabilities.values.forall(valid) && valid(answer.confidence) &&
    math.abs(probabilities.values.sum - 1.0) <= 1e-6 &&
    probabilities("rebase") > probabilities("keep") &&
    probabilities("rebase") >= minConfidence && answer.confidence >= minConfidence
  }
}

object SystemOneHistoryDecision {
  private final case class Evidence(context: String) derives JsonCodec
  private final case class Questions(rewrite: SystemOneQuestion.Choice) derives JsonCodec, SOSchema

  private def validCandidate(
      messages: Chunk[Message],
      base: MessageId,
      head: MessageId,
      onto: Option[MessageId]
  ): Boolean =
    messages.lastOption.exists(_.id == head) && messages.exists(_.id == base) &&
      onto.forall(id => messages.exists(_.id == id))
}
