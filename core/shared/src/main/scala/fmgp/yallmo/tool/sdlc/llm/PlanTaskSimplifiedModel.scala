package fmgp.yallmo.tool.sdlc.llm

import fmgp.yallmo.tool.sdlc.{PlanTask, TaskState}
import java.time.Instant
import zio.json.JsonCodec

/** Full planning content exchanged through Set and ReadTask, without storage timestamps.
  *
  * This is a replacement snapshot, not a patch: omitted defaulted fields become empty/open/false. Echo expectedRevision
  * from the last read when updating. Zero is accepted only for new records (empty ID).
  */
final case class PlanTaskSimplifiedModel(
    id: String,
    text: String,
    state: TaskState = TaskState.Open,
    subtasks: List[String] = Nil,
    dependsOn: List[String] = Nil,
    leftoverOf: List[String] = Nil,
    contributesTo: List[String] = Nil,
    assumptions: List[String] = Nil,
    archived: Boolean = false,
    expectedRevision: Long = 0L
) derives JsonCodec {

  /** Constructs a stored snapshot with explicit runtime-supplied metadata.
    *
    * For an existing task, the caller must preserve its ID and createdAt and supply the new updatedAt. This conversion
    * performs no allocation, clock access, or project validation.
    */
  def toPlanTask(createdAt: Instant, updatedAt: Instant): PlanTask =
    PlanTask(
      id = id,
      text = text,
      createdAt = createdAt,
      updatedAt = updatedAt,
      state = state,
      subtasks = subtasks,
      dependsOn = dependsOn,
      leftoverOf = leftoverOf,
      contributesTo = contributesTo,
      assumptions = assumptions,
      archived = archived,
      revision = expectedRevision
    )
}
