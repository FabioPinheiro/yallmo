package fmgp.yallmo.tool.sdlc

import fmgp.yallmo.fs.{FsError, FsRead, FsWrite}
import fmgp.yallmo.model.{ToolKey, YallmoError}
import fmgp.yallmo.model.llm.JsonSchema
import fmgp.yallmo.orchestration.{
  AgentTool,
  AgentToolDefinition,
  ExternalToolCall,
  ExternalToolResult,
  ToolArgumentsValidator
}
import fmgp.yallmo.tool.sdlc.llm.{PlanIntentSimplifiedModel, PlanTaskSimplifiedModel}
import zio.*
import zio.json.*

/** Agent-facing planning tools over the same server-owned service used by the HTTP inspector. */
object SdlcTools {
  private[sdlc] val MaxArgumentBytes = 128 * 1024
  private val taskSchema = JsonSchema.ObjectSchema(
    properties = Map(
      "id" -> JsonSchema.StringSchema(Some("Existing stable task ID, or empty string to allocate a new ID")),
      "text" -> JsonSchema.StringSchema(Some("Work description and completion conditions; at most 8000 characters")),
      "state" -> JsonSchema.StringSchema(allowedValues = Chunk("open", "doing", "done", "dropped")),
      "subtasks" -> JsonSchema.ArraySchema(JsonSchema.StringSchema()),
      "dependsOn" -> JsonSchema.ArraySchema(JsonSchema.StringSchema()),
      "leftoverOf" -> JsonSchema.ArraySchema(JsonSchema.StringSchema()),
      "contributesTo" -> JsonSchema.ArraySchema(JsonSchema.StringSchema()),
      "assumptions" -> JsonSchema.ArraySchema(JsonSchema.StringSchema()),
      "archived" -> JsonSchema.BooleanSchema(),
      "expectedRevision" -> JsonSchema.IntegerSchema(
        Some("Echo the version returned by Read; 0 only when id is empty for creation")
      )
    ),
    required = Set("id", "text")
  )

  val SetDefinition: AgentToolDefinition = AgentToolDefinition(
    key = ToolKey.SdlcSet,
    description =
      "Set up to 100 complete planning tasks in shared server storage. tasksJson is a JSON array, not an encoded string. Use id=\"\" to create; otherwise ReadTask first and preserve all fields. Omitted fields reset to defaults; other tasks remain unchanged. Returns simplified tasks with allocated IDs. Timestamps are runtime-owned. Echo expectedRevision from ReadTask when updating; stale revisions fail without writes. Creates are not retry-idempotent.",
    inputSchema = JsonSchema.ObjectSchema(
      Map("tasksJson" -> JsonSchema.ArraySchema(taskSchema)),
      required = Set("tasksJson")
    )
  )

  val ReadTaskDefinition: AgentToolDefinition = AgentToolDefinition(
    key = ToolKey.SdlcReadTask,
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
      "text" -> JsonSchema.StringSchema(Some("Desired outcome and success conditions; at most 8000 characters")),
      "dependsOn" -> JsonSchema.ArraySchema(JsonSchema.StringSchema()),
      "archived" -> JsonSchema.BooleanSchema(),
      "expectedRevision" -> JsonSchema.IntegerSchema(
        Some("Echo the version returned by Read; 0 only when id is empty for creation")
      )
    ),
    required = Set("id", "text")
  )

  val SetIntentsDefinition: AgentToolDefinition = AgentToolDefinition(
    key = ToolKey.SdlcSetIntents,
    description =
      "Set up to 100 complete intent snapshots in shared server storage. intentsJson is an array, not an encoded string. Empty id creates an I-n ID; nonempty IDs must exist. Read before editing; omitted fields reset to defaults. dependsOn contains existing task IDs. Echo expectedRevision from the read result on updates; stale revisions fail. Timestamps are runtime-owned; creates are not retry-idempotent.",
    inputSchema = JsonSchema.ObjectSchema(
      Map("intentsJson" -> JsonSchema.ArraySchema(intentSchema)),
      required = Set("intentsJson")
    )
  )

  val ReadIntentDefinition: AgentToolDefinition = AgentToolDefinition(
    key = ToolKey.SdlcReadIntent,
    description =
      "Read up to 100 unique existing intent IDs, including archived intents. Returns simplified intents without timestamps in request order. Unknown IDs fail explicitly; empty input returns an empty list.",
    inputSchema = JsonSchema.ObjectSchema(
      Map("intentIds" -> JsonSchema.ArraySchema(JsonSchema.StringSchema())),
      required = Set("intentIds")
    )
  )

  val ListDefinition: AgentToolDefinition = AgentToolDefinition(
    key = ToolKey.SdlcList,
    description =
      "Discover tasks and intents by ID or text. Returns compact summaries, not replacement content; ReadTask/ReadIntent before editing. By default excludes archived records. limit defaults to 50 (max 100); continue with nextAfterId as afterId using the same query/filter.",
    inputSchema = JsonSchema.ObjectSchema(
      Map(
        "query" -> JsonSchema.StringSchema(),
        "includeArchived" -> JsonSchema.BooleanSchema(),
        "limit" -> JsonSchema.IntegerSchema(),
        "afterId" -> JsonSchema.StringSchema()
      )
    )
  )

  val Definitions: Chunk[AgentToolDefinition] =
    Chunk(SetDefinition, ReadTaskDefinition, SetIntentsDefinition, ReadIntentDefinition, ListDefinition)

  /** Convenience for embedded users; the standalone server passes its already-created shared service instead. */
  def make(read: FsRead, write: FsWrite): UIO[Chunk[AgentTool[Any]]] =
    SdlcService.make(read, write).map(service => make(service))

  /** Bind the same service to tools and HTTP routes; this does not create another storage owner. */
  def make(service: SdlcService): Chunk[AgentTool[Any]] =
    Definitions.map { toolDefinition =>
      new AgentTool[Any] {
        override val definition: AgentToolDefinition = toolDefinition

        override def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] = {
          def invalid(message: String) = YallmoError.InvalidToolArguments(definition.key, message)
          for {
            _ <- ZIO.fail(invalid("Mismatched tool name")).unless(call.toolKey == definition.key)
            _ <- ZIO
              .fail(invalid(s"Arguments must not exceed $MaxArgumentBytes UTF-8 bytes"))
              .when(
                call.argumentsJson.length > MaxArgumentBytes || call.argumentsJson
                  .getBytes("UTF-8")
                  .length > MaxArgumentBytes
              )
            _ <- ZIO
              .fromEither(ToolArgumentsValidator.validate(call.argumentsJson, definition.inputSchema))
              .mapError(invalid)
            arguments <- ZIO.attempt(ujson.read(call.argumentsJson)).mapError(error => invalid(error.getMessage))
            result <- SdlcTools.execute(service, definition.key, arguments).mapError {
              case FsError.InvalidArgument(message) => invalid(message)
              case error                            =>
                YallmoError.ToolExecutionFailed(definition.key, error.toString, new java.io.IOException(error.toString))
            }
          } yield ExternalToolResult(result)
        }
      }
    }

  private def execute(service: SdlcService, key: ToolKey, arguments: ujson.Value): IO[FsError, String] = {
    def decode[A: JsonDecoder](field: String, default: ujson.Value = ujson.Null): IO[FsError, A] =
      ZIO
        .fromEither(ujson.write(arguments.obj.getOrElse(field, default)).fromJson[A])
        .mapError(FsError.InvalidArgument.apply)

    key match {
      case ToolKey.SdlcSet => decode[List[PlanTaskSimplifiedModel]]("tasksJson").flatMap(service.setTasks).map(_.toJson)
      case ToolKey.SdlcReadTask   => decode[List[String]]("taskIds").flatMap(service.readTasks).map(_.toJson)
      case ToolKey.SdlcSetIntents =>
        decode[List[PlanIntentSimplifiedModel]]("intentsJson").flatMap(service.setIntents).map(_.toJson)
      case ToolKey.SdlcReadIntent => decode[List[String]]("intentIds").flatMap(service.readIntents).map(_.toJson)
      case ToolKey.SdlcList       =>
        for {
          query <- decode[String]("query", ujson.Str(""))
          archived <- decode[Boolean]("includeArchived", ujson.Bool(false))
          limit <- decode[Int]("limit", ujson.Num(50))
          after <- decode[Option[String]]("afterId")
          page <- service.discover(query, archived, limit, after)
        } yield page.toJson
      case _ => ZIO.fail(FsError.InvalidArgument("Unsupported SDLC operation"))
    }
  }
}
