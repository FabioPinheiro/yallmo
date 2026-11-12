package fmgp.yallmo.model.tool

import fmgp.yallmo.tool.{FunctionKey, FunctionToolDefinition, ToolDefinition, ToolKey}
import fmgp.yallmo.model.llm.JsonSchema
import zio.Chunk

/** Stable callable identifiers and membership declared by this tool, independent of its runtime bindings. */
object SdlcToolDefinition extends ToolDefinition {
  val SetTasks: FunctionKey = FunctionKey.unsafe("sdlc_set")
  val ReadTask: FunctionKey = FunctionKey.unsafe("sdlc_read_task")
  val SetIntents: FunctionKey = FunctionKey.unsafe("sdlc_set_intents")
  val ReadIntent: FunctionKey = FunctionKey.unsafe("sdlc_read_intent")
  val List: FunctionKey = FunctionKey.unsafe("sdlc_list")

  private val stepSchema = JsonSchema.ObjectSchema(
    properties = Map(
      "done" -> JsonSchema.BooleanSchema(Some("Whether this checklist item is complete; does not change task state")),
      "title" -> JsonSchema.StringSchema(Some("Nonblank single-line plain-text summary; at most 60 characters")),
      "description" -> JsonSchema.StringSchema(Some("Optional Markdown details; at most 1000 characters"))
    ),
    required = Set("done", "title")
  )
  private val taskSchema = JsonSchema.ObjectSchema(
    properties = Map(
      "id" -> JsonSchema.StringSchema(Some("Existing stable task ID, or empty string to allocate a new ID")),
      "title" -> JsonSchema.StringSchema(Some("Nonblank single-line plain-text task title; at most 160 characters")),
      "description" -> JsonSchema.StringSchema(
        Some("Markdown work description and completion conditions; at most 8000 characters; defaults to empty")
      ),
      "steps" -> JsonSchema
        .ArraySchema(stepSchema, Some("Ordered local checklist, at most 100 items; not subtask IDs")),
      "state" -> JsonSchema.StringSchema(allowedValues = Chunk("open", "doing", "done", "dropped")),
      "refs" -> JsonSchema.ArraySchema(
        JsonSchema.ArraySchema(JsonSchema.StringSchema(), Some("Exactly two strings: [relationship, task ID]")),
        Some("Task references as [relationship, ID] tuples; relationship is subtask, dependsOn, or leftoverOf")
      ),
      "contributesTo" -> JsonSchema.ArraySchema(JsonSchema.StringSchema()),
      "assumptions" -> JsonSchema.ArraySchema(JsonSchema.StringSchema()),
      "archived" -> JsonSchema.BooleanSchema(),
      "expectedRevision" -> JsonSchema.IntegerSchema(
        Some("Echo the version returned by Read; 0 only when id is empty for creation")
      )
    ),
    required = Set("id", "title")
  )

  val SetDefinition: FunctionToolDefinition = FunctionToolDefinition(
    name = SetTasks,
    description =
      "Set up to 100 complete planning tasks in shared server storage. tasksJson is a JSON array, not an encoded string. Use id=\"\" to create; otherwise ReadTask first and preserve all fields. Omitted fields reset to defaults; other tasks remain unchanged. Returns simplified tasks with allocated IDs. Timestamps are runtime-owned. Echo expectedRevision from ReadTask when updating; stale revisions fail without writes. Creates are not retry-idempotent.",
    inputSchema = JsonSchema.ObjectSchema(
      Map("tasksJson" -> JsonSchema.ArraySchema(taskSchema)),
      required = Set("tasksJson")
    )
  )

  val ReadTaskDefinition: FunctionToolDefinition = FunctionToolDefinition(
    name = ReadTask,
    description =
      "Read up to 100 unique task IDs from shared server storage, including archived tasks. Returns simplified tasks in request order, without timestamps. Unknown IDs fail explicitly; an empty list returns an empty list.",
    inputSchema = JsonSchema.ObjectSchema(
      Map("taskIds" -> JsonSchema.ArraySchema(JsonSchema.StringSchema())),
      required = Set("taskIds")
    )
  )

  private val intentSchema = JsonSchema.ObjectSchema(
    properties = Map(
      "id" -> JsonSchema.StringSchema(Some("Existing stable intent ID, or empty string to allocate a new ID")),
      "text" -> JsonSchema.StringSchema(
        Some("Markdown desired outcome and success conditions; at most 8000 characters")
      ),
      "dependsOn" -> JsonSchema.ArraySchema(JsonSchema.StringSchema()),
      "archived" -> JsonSchema.BooleanSchema(),
      "expectedRevision" -> JsonSchema.IntegerSchema(
        Some("Echo the version returned by Read; 0 only when id is empty for creation")
      )
    ),
    required = Set("id", "text")
  )

  val SetIntentsDefinition: FunctionToolDefinition = FunctionToolDefinition(
    name = SetIntents,
    description =
      "Set up to 100 complete intent snapshots in shared server storage. intentsJson is an array, not an encoded string. Empty id creates an I-n ID; nonempty IDs must exist. Read before editing; omitted fields reset to defaults. dependsOn contains existing task IDs. Echo expectedRevision from the read result on updates; stale revisions fail. Timestamps are runtime-owned; creates are not retry-idempotent.",
    inputSchema = JsonSchema.ObjectSchema(
      Map("intentsJson" -> JsonSchema.ArraySchema(intentSchema)),
      required = Set("intentsJson")
    )
  )

  val ReadIntentDefinition: FunctionToolDefinition = FunctionToolDefinition(
    name = ReadIntent,
    description =
      "Read up to 100 unique existing intent IDs, including archived intents. Returns simplified intents without timestamps in request order. Unknown IDs fail explicitly; empty input returns an empty list.",
    inputSchema = JsonSchema.ObjectSchema(
      Map("intentIds" -> JsonSchema.ArraySchema(JsonSchema.StringSchema())),
      required = Set("intentIds")
    )
  )

  val ListDefinition: FunctionToolDefinition = FunctionToolDefinition(
    name = List,
    description =
      "Discover tasks and intents by ID, Markdown text, or task step content. Returns compact summaries, not replacement content; ReadTask/ReadIntent before editing. By default excludes archived records. limit defaults to 50 (max 100); continue with nextAfterId as afterId using the same query/filter.",
    inputSchema = JsonSchema.ObjectSchema(
      Map(
        "query" -> JsonSchema.StringSchema(),
        "includeArchived" -> JsonSchema.BooleanSchema(),
        "limit" -> JsonSchema.IntegerSchema(),
        "afterId" -> JsonSchema.StringSchema()
      )
    )
  )

  override def name: ToolKey = ToolKey.Sdlc
  override def functions: Set[FunctionToolDefinition] =
    Set(SetDefinition, ReadTaskDefinition, SetIntentsDefinition, ReadIntentDefinition, ListDefinition)
}
