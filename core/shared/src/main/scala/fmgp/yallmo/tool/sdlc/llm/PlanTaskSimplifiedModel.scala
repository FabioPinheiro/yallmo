package fmgp.yallmo.tool.sdlc.llm

import fmgp.yallmo.tool.sdlc.{PlanTask, PlanTaskId, PlanTaskRelationship, PlanTaskStep, TaskState}
import java.time.Instant
import zio.json.JsonCodec

/** Full planning content exchanged through Set and ReadTask, without storage timestamps. The title is plain text;
  * descriptions are Markdown; steps retain their supplied order and do not determine task state.
  *
  * This is a replacement snapshot, not a patch: omitted defaulted fields become empty/open/false. Echo expectedRevision
  * from the last read when updating. Zero is accepted only for new records (empty ID).
  */
final case class PlanTaskSimplifiedModel(
    id: PlanTaskId,
    title: String,
    description: String = "",
    state: TaskState = TaskState.Open,
    refs: List[(PlanTaskRelationship, PlanTaskId)] = Nil,
    contributesTo: List[String] = Nil,
    assumptions: List[String] = Nil,
    archived: Boolean = false,
    expectedRevision: Long = 0L,
    steps: List[PlanTaskStep] = Nil
) derives JsonCodec {

  /** Generates an escaped title heading, Markdown description, and ordered checklist without storing the export. */
  def toMarkdown: String = {
    val escapedTitle = title.flatMap(c => if "\\`*_{}[]<>!|&#~".contains(c) then s"\\$c" else c.toString)
    val narrative = if description.isEmpty then s"# $escapedTitle" else s"# $escapedTitle\n\n$description"
    if steps.isEmpty then narrative
    else s"$narrative\n\n## Steps\n\n${steps.map(_.toMarkdown).mkString("\n\n")}"
  }

  /** Constructs a stored snapshot with explicit runtime-supplied metadata.
    *
    * For an existing task, the caller must preserve its ID and createdAt and supply the new updatedAt. This conversion
    * performs no allocation, clock access, or project validation.
    */
  def toPlanTask(createdAt: Instant, updatedAt: Instant): PlanTask =
    PlanTask(
      id = id,
      title = title,
      description = description,
      createdAt = createdAt,
      updatedAt = updatedAt,
      state = state,
      refs = refs,
      contributesTo = contributesTo,
      assumptions = assumptions,
      archived = archived,
      revision = expectedRevision,
      steps = steps
    )
}
