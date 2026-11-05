package fmgp.yallmo.tool.sdlc

import zio.json.JsonCodec

/** Stable task identifier encoded as a JSON string. The empty sentinel requests allocation only in a new task snapshot;
  * storage rejects it for persisted IDs and relationship targets.
  */
opaque type PlanTaskId = String

object PlanTaskId {
  val New: PlanTaskId = ""

  /** Validates an allocated ID; an empty creation sentinel is not an allocated ID. */
  def from(value: String): Either[String, PlanTaskId] =
    Either.cond(value.matches("T-[1-9][0-9]{0,9}"), value, "Use a task ID such as T-1")

  /** For runtime allocation and trusted callers; untrusted allocated IDs should use from. */
  def unsafe(value: String): PlanTaskId = value

  extension (id: PlanTaskId) {
    def value: String = id
    def isEmpty: Boolean = id.length == 0
    def nonEmpty: Boolean = id.length != 0
  }

  given Ordering[PlanTaskId] = Ordering.String
  given JsonCodec[PlanTaskId] = JsonCodec.string.transformOrFail(
    value => if value.isEmpty then Right(New) else from(value),
    _.value
  )
}
