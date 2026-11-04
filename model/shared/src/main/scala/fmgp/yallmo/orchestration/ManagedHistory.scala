package fmgp.yallmo.orchestration

import fmgp.yallmo.model.{Message, MessageId, MessagePayload, YallmoError}
import zio.{Chunk, IO, UIO}

/** Orchestrator-owned active history for one ordinary submission.
  *
  * Programs may inspect immutable identities before persistence completes. Reads are not durability barriers. No
  * storage or session-publication authority is exposed.
  */
trait ManagedHistory {
  def messages: UIO[Chunk[Message]]

  /** Immutable history at prompt acceptance for this submission, including the accepted prompt. Reading this origin
    * snapshot neither refreshes the runner's captured work head nor acts as a persistence barrier.
    */
  def initialMessages: UIO[Chunk[Message]]

  /** Replays the exclusive-base/inclusive-head suffix onto a message (or root), then activates that ancestry. Head must
    * be the current local head. Restoring the exact head restores the same immutable context; intervening rewrites do
    * not invalidate it. Empty ranges activate the destination. Required objects are awaited; session publication is
    * best-effort. Mutation expires when submission closes. Reads are not persistence barriers.
    *
    * After rewriting inside nextManaged, explicitly read messages and rebuild the request before returning output. This
    * is a trusted program contract, not proof that a provider consumed the refreshed context.
    *
    * Changed histories require complete independent tool exchanges in destination ancestry and selected source range;
    * no pairing across the cut, duplicate IDs or reassociation of original results is allowed. Tools never run during
    * rebase. All Reasoning entries are conservatively omitted from a changed active chain (including destination
    * ancestry), without altering stored originals. The return value is only the retained source suffix; messages gives
    * the actual full sanitized chain. Exact no-ops preserve all original payloads and IDs. Object-save failure before
    * activation leaves active history unchanged, but may leave an immutable saved prefix. Standalone
    * Orchestrator.rebase remains context-neutral and payload-preserving.
    */
  def rebase(base: MessageId, head: MessageId, onto: Option[MessageId]): IO[YallmoError, Chunk[Message]]

  // Non-refreshing read for runner/advisor work; only explicit program messages reads refresh model context.
  private[orchestration] def snapshot: UIO[Chunk[Message]]
  // Atomically compare the captured parent and return this append's resulting head, never a later unlocked read.
  // On mismatch, retain produced payloads under that original parent in journal/audit and fail continuation.
  private[orchestration] def appendAt(
      payloads: Chunk[MessagePayload],
      expectedParent: Option[MessageId]
  ): IO[YallmoError, Option[MessageId]]

  /** Appends only new payloads, allocating stable identities and scheduling ordered persistence without waiting for
    * disk. This is not a durability barrier.
    */
  private[orchestration] def append(payloads: Chunk[MessagePayload]): IO[YallmoError, Unit]
}
