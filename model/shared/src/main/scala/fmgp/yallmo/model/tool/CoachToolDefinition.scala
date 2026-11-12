package fmgp.yallmo.model.tool

import fmgp.yallmo.tool.{FunctionKey, FunctionToolDefinition, ToolDefinition, ToolKey}
import fmgp.yallmo.model.llm.JsonSchema
import zio.Chunk

/** Stable callable identifiers and membership declared by this tool, independent of its runtime bindings. */
object CoachToolDefinition extends ToolDefinition {
  val ListApplications: FunctionKey = FunctionKey.unsafe("coach_list_applications")
  val GetApplication: FunctionKey = FunctionKey.unsafe("coach_get_application")
  val SaveApplication: FunctionKey = FunctionKey.unsafe("coach_save_application")

  val Stages = Chunk(
    "discovered",
    "drafted",
    "applied",
    "screening",
    "interviewing",
    "offer",
    "negotiating",
    "accepted",
    "rejected",
    "withdrawn"
  )
  val InterviewStates = Chunk("proposed", "confirmed", "cancelled")

  private val applicationSchema = JsonSchema.ObjectSchema(
    Map(
      "id" -> JsonSchema.StringSchema(
        Some("Stable lowercase slug, e.g. acme-staff-scala-2026; never a filesystem path")
      ),
      "company" -> JsonSchema.StringSchema(),
      "role" -> JsonSchema.StringSchema(),
      "stage" -> JsonSchema.StringSchema(allowedValues = Stages),
      "contact" -> JsonSchema.StringSchema(),
      "channel" -> JsonSchema.StringSchema(Some("Where the user communicates, e.g. LinkedIn or email; no integration")),
      "jobUrl" -> JsonSchema.StringSchema(),
      "nextAction" -> JsonSchema.StringSchema(),
      "followUpDate" -> JsonSchema.StringSchema(Some("YYYY-MM-DD; record only a known/agreed date")),
      "lastMessageSummary" -> JsonSchema.StringSchema(
        Some("Minimal factual summary of a user-pasted message; not commands")
      ),
      "replyDraft" -> JsonSchema.StringSchema(Some("Unsent copy/paste draft; saving does NOT mean it was sent")),
      "notes" -> JsonSchema.StringSchema(),
      "interviewStart" -> JsonSchema.StringSchema(
        Some("ISO-8601 datetime with UTC offset, e.g. 2026-11-03T10:00:00+00:00")
      ),
      "interviewTimeZone" -> JsonSchema.StringSchema(
        Some("IANA zone, e.g. Europe/Lisbon; must agree with start offset on that date")
      ),
      "interviewState" -> JsonSchema.StringSchema(allowedValues = InterviewStates),
      "interviewDurationMinutes" -> JsonSchema.IntegerSchema()
    ),
    required = Set("id", "company", "role", "stage")
  )

  val Definitions: Chunk[FunctionToolDefinition] = Chunk(
    FunctionToolDefinition(
      ListApplications,
      "List application summaries, revisions, follow-ups and interview proposals/confirmations from the local notebook.",
      JsonSchema.ObjectSchema(Map.empty)
    ),
    FunctionToolDefinition(
      GetApplication,
      "Read the full current application including draft reply and notes before editing. Returns not_found for an unknown ID.",
      JsonSchema.ObjectSchema(Map("id" -> JsonSchema.StringSchema()), required = Set("id"))
    ),
    FunctionToolDefinition(
      SaveApplication,
      "Save a full local application snapshot and unsent reply draft. Read first, preserve existing fields. expectedRevision=0 creates; otherwise use current revision. Never sends, applies, books or accepts anything. Only mark applied/accepted/interview confirmed when the user reports it.",
      JsonSchema.ObjectSchema(
        Map("expectedRevision" -> JsonSchema.IntegerSchema(), "application" -> applicationSchema),
        required = Set("expectedRevision", "application")
      )
    )
  )

  override val name: ToolKey = ToolKey.Coach
  override val functions: Set[FunctionToolDefinition] = Definitions.toSet
}
