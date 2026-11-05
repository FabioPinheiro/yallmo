package fmgp.yallmo.tool.sdlc

import fmgp.yallmo.tool.sdlc.llm.PlanTaskSimplifiedModel
import java.time.Instant
import zio.json.JsonCodec

/** Complete task snapshot. The runtime owns timestamps and revision; IDs remain stable across updates. A zero revision
  * is a legacy decode default, inferred from journal history before the record is exposed.
  *
  * Relationships contain IDs, not embedded records. Subtask order is preferred order, not a prerequisite dependency.
  * Decoding establishes JSON shape only; project-level reference validation belongs to the tool implementation.
  */
final case class PlanTask(
    id: String,
    text: String,
    createdAt: Instant,
    updatedAt: Instant,
    state: TaskState = TaskState.Open,
    subtasks: List[String] = Nil,
    dependsOn: List[String] = Nil,
    leftoverOf: List[String] = Nil,
    contributesTo: List[String] = Nil,
    assumptions: List[String] = Nil,
    archived: Boolean = false,
    revision: Long = 0L
) derives JsonCodec {

  /** Removes runtime-maintained metadata without changing planning fields. */
  def toSimplifiedModel: PlanTaskSimplifiedModel =
    PlanTaskSimplifiedModel(
      id = id,
      text = text,
      state = state,
      subtasks = subtasks,
      dependsOn = dependsOn,
      leftoverOf = leftoverOf,
      contributesTo = contributesTo,
      assumptions = assumptions,
      archived = archived,
      expectedRevision = revision
    )
}
