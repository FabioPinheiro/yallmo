package fmgp.yallmo.tool.sdlc.llm

import fmgp.yallmo.tool.sdlc.PlanIntent
import java.time.Instant
import zio.json.JsonCodec

/** Outcome content visible to agents and users, without storage timestamps.
  *
  * This is a replacement snapshot, not a patch: omitted lists and archive visibility use their defaults. Echo
  * expectedRevision from the last read when updating. Zero is accepted only for new records (empty ID).
  */
final case class PlanIntentSimplifiedModel(
    id: String,
    text: String,
    dependsOn: List[String] = Nil,
    archived: Boolean = false,
    expectedRevision: Long = 0L
) derives JsonCodec {

  /** Constructs a stored snapshot with explicit runtime-supplied metadata.
    *
    * For an existing intent, preserve its ID and createdAt and supply the new updatedAt. This conversion performs no
    * allocation, clock access, or project validation.
    */
  def toPlanIntent(createdAt: Instant, updatedAt: Instant): PlanIntent =
    PlanIntent(
      id = id,
      text = text,
      createdAt = createdAt,
      updatedAt = updatedAt,
      dependsOn = dependsOn,
      archived = archived,
      revision = expectedRevision
    )
}
