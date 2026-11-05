package fmgp.yallmo.tool.sdlc

import fmgp.yallmo.tool.sdlc.llm.PlanIntentSimplifiedModel
import java.time.Instant
import zio.json.JsonCodec

/** Complete outcome snapshot with a Markdown narrative. Required tasks alone do not prove the outcome has been
  * achieved.
  *
  * The runtime owns timestamps and revision. Zero is a legacy decode default, inferred during journal replay. Decoding
  * does not validate the referenced tasks.
  */
final case class PlanIntent(
    id: String,
    text: String,
    createdAt: Instant,
    updatedAt: Instant,
    dependsOn: List[String] = Nil,
    archived: Boolean = false,
    revision: Long = 0L
) derives JsonCodec {

  /** Removes runtime-maintained metadata without changing planning fields. */
  def toSimplifiedModel: PlanIntentSimplifiedModel =
    PlanIntentSimplifiedModel(
      id = id,
      text = text,
      dependsOn = dependsOn,
      archived = archived,
      expectedRevision = revision
    )
}
