package fmgp.yallmo.agents

import fmgp.yallmo.model.ToolKey
import fmgp.yallmo.fs.{FsError, FsRead, FsWrite, WriteMode}
import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.model.llm.JsonSchema
import fmgp.yallmo.orchestration.*
import java.time.{Instant, LocalDate, OffsetDateTime, ZoneId}
import zio.*
import zio.json.*
import zio.stream.ZPipeline

/** Local application notebook. There is deliberately no network, mail, browser or calendar capability.
  *
  * An append-only journal retains revisions; a semaphore serializes access within one agent instance. Independent
  * processes/instances must not share a root. Corrupt/oversize journals fail closed and are never silently reset.
  */
object CoachApplicationTools {
  val ListKey: ToolKey = ToolKey.CoachListApplications
  val GetKey: ToolKey = ToolKey.CoachGetApplication
  val SaveKey: ToolKey = ToolKey.CoachSaveApplication
  private val JournalPath = "applications.jsonl"
  private val MaxJournalBytes = 2 * 1024 * 1024
  private val MaxRecords = 2000
  private val MaxApplications = 100
  private val Stages = Chunk(
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
  private val InterviewStates = Chunk("proposed", "confirmed", "cancelled")

  /** Full replacement of one application; omitted optional fields are cleared. Read the previous record before editing.
    * Dates and confirmed outcomes must come from the user, not an inferred external action.
    */
  final case class Application(
      id: String,
      company: String,
      role: String,
      stage: String,
      contact: Option[String] = None,
      channel: Option[String] = None,
      jobUrl: Option[String] = None,
      nextAction: Option[String] = None,
      followUpDate: Option[String] = None,
      lastMessageSummary: Option[String] = None,
      replyDraft: Option[String] = None,
      notes: Option[String] = None,
      interviewStart: Option[String] = None,
      interviewTimeZone: Option[String] = None,
      interviewState: Option[String] = None,
      interviewDurationMinutes: Option[Int] = None
  ) derives JsonCodec

  final case class Record(revision: Int, updatedAt: String, application: Application) derives JsonCodec
  private final case class Save(expectedRevision: Int, application: Application) derives JsonCodec
  private final case class Get(id: String) derives JsonCodec
  private final case class Summary(
      id: String,
      revision: Int,
      company: String,
      role: String,
      stage: String,
      nextAction: Option[String],
      followUpDate: Option[String],
      interviewStart: Option[String],
      interviewTimeZone: Option[String],
      interviewState: Option[String]
  ) derives JsonEncoder

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

  val Definitions: Chunk[AgentToolDefinition] = Chunk(
    AgentToolDefinition(
      ListKey,
      "List application summaries, revisions, follow-ups and interview proposals/confirmations from the local notebook.",
      JsonSchema.ObjectSchema(Map.empty)
    ),
    AgentToolDefinition(
      GetKey,
      "Read the full current application including draft reply and notes before editing. Returns not_found for an unknown ID.",
      JsonSchema.ObjectSchema(Map("id" -> JsonSchema.StringSchema()), required = Set("id"))
    ),
    AgentToolDefinition(
      SaveKey,
      "Save a full local application snapshot and unsent reply draft. Read first, preserve existing fields. expectedRevision=0 creates; otherwise use current revision. Never sends, applies, books or accepts anything. Only mark applied/accepted/interview confirmed when the user reports it.",
      JsonSchema.ObjectSchema(
        Map("expectedRevision" -> JsonSchema.IntegerSchema(), "application" -> applicationSchema),
        required = Set("expectedRevision", "application")
      )
    )
  )

  def make(fs: FsRead & FsWrite): UIO[Chunk[AgentTool[Any]]] =
    Semaphore.make(1).map { lock =>
      Definitions.map { toolDefinition =>
        new AgentTool[Any] {
          val definition: AgentToolDefinition = toolDefinition
          def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
            lock.withPermit(executeCall(fs, definition, call))
        }
      }
    }

  private def executeCall(
      fs: FsRead & FsWrite,
      definition: AgentToolDefinition,
      call: ExternalToolCall
  ): IO[YallmoError, ExternalToolResult] = {
    def invalid(message: String) = YallmoError.InvalidToolArguments(definition.key, message)
    for {
      _ <- ZIO.fail(invalid("Mismatched tool name")).unless(call.toolKey == definition.key)
      _ <- ZIO.fromEither(ToolArgumentsValidator.validate(call.argumentsJson, definition.inputSchema)).mapError(invalid)
      notebook <- load(fs).mapError(error =>
        YallmoError.ToolExecutionFailed(definition.key, error.toString, new RuntimeException(error.toString))
      )
      (records, byteCount) = notebook
      current = records.foldLeft(Map.empty[String, Record])((state, record) =>
        state.updated(record.application.id, record)
      )
      output <- definition.key match {
        case ListKey =>
          val summaries = current.values.toList.sortBy(_.application.id).map { record =>
            val a = record.application
            Summary(
              a.id,
              record.revision,
              a.company,
              a.role,
              a.stage,
              a.nextAction,
              a.followUpDate,
              a.interviewStart,
              a.interviewTimeZone,
              a.interviewState
            )
          }
          ZIO.succeed(summaries.toJson)
        case GetKey =>
          for {
            get <- ZIO.fromEither(call.argumentsJson.fromJson[Get].left.map(_ => invalid("Invalid application ID")))
            _ <- ZIO.fail(invalid("Invalid application ID")).unless(validId(get.id))
          } yield current.get(get.id).fold("{\"not_found\":true}")(_.toJson)
        case SaveKey =>
          for {
            save <- ZIO.fromEither(
              call.argumentsJson.fromJson[Save].left.map(_ => invalid("Invalid application snapshot"))
            )
            _ <- ZIO.fromEither(validate(save.application).left.map(invalid))
            previous = current.get(save.application.id)
            revision = previous.fold(0)(_.revision)
            // A retried identical write is safe; a stale different snapshot cannot overwrite newer information.
            sameRetry = previous.exists(r =>
              r.revision == save.expectedRevision + 1 && r.application == save.application
            )
            _ <- ZIO
              .fail(invalid("Revision conflict: read the application again and preserve its latest fields"))
              .unless(save.expectedRevision >= 0 && (revision == save.expectedRevision || sameRetry))
            _ <- ZIO
              .fail(
                invalid(
                  s"Notebook is full ($MaxApplications applications or $MaxRecords revisions); archive it manually"
                )
              )
              .when(!sameRetry && (records.size >= MaxRecords || (previous.isEmpty && current.size >= MaxApplications)))
            result <-
              if sameRetry then ZIO.succeed(previous.get)
              else
                for {
                  now <- Clock.instant
                  record = Record(revision + 1, now.toString, save.application)
                  line = record.toJson + "\n"
                  _ <- ZIO
                    .fail(invalid("Notebook size limit reached; archive it manually"))
                    .when(byteCount + line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MaxJournalBytes)
                  _ <- ZIO
                    .scoped {
                      for {
                        writer <- fs.openUtf8Writer(JournalPath, WriteMode.Append)
                        _ <- writer.write(line)
                        _ <- writer.sync
                      } yield ()
                    }
                    .uninterruptible
                    .mapError(error =>
                      YallmoError
                        .ToolExecutionFailed(definition.key, error.toString, new RuntimeException(error.toString))
                    )
                } yield record
          } yield result.toJson
        case _ => ZIO.fail(invalid("Unknown Coach tool"))
      }
    } yield ExternalToolResult(output)
  }

  private def load(fs: FsRead): IO[FsError, (Chunk[Record], Int)] =
    (for {
      bytes <- fs.readBytes(JournalPath).take(MaxJournalBytes.toLong + 1).runCollect
      _ <- ZIO
        .fail(FsError.InvalidArgument("Application notebook exceeds its size limit"))
        .when(bytes.size > MaxJournalBytes)
      text <- zio.stream.ZStream
        .fromChunk(bytes)
        .via(ZPipeline.utf8Decode.mapError(error => FsError.IoFailure(JournalPath, error.getMessage)))
        .runCollect
      _ <- ZIO
        .fail(FsError.InvalidArgument("Incomplete application journal; recover it manually before writing"))
        .when(bytes.nonEmpty && bytes.last != '\n'.toByte)
      lines = if bytes.isEmpty then Nil else text.mkString.stripSuffix("\n").split("\n", -1).toList
      _ <- ZIO
        .fail(FsError.InvalidArgument("Application notebook exceeds its revision limit"))
        .when(lines.size > MaxRecords)
      records <- ZIO.foreach(lines.zipWithIndex) { case (line, index) =>
        // zio-json decoding permits trailing input; reject it explicitly before decoding the typed record.
        ZIO
          .attempt(ujson.read(line))
          .mapError(_ => FsError.InvalidJson(JournalPath, index.toLong + 1, "Invalid journal JSON")) *>
          ZIO
            .fromEither(line.fromJson[Record])
            .mapError(message => FsError.InvalidJson(JournalPath, index.toLong + 1, message))
      }
      _ <- ZIO.foldLeft(records)(Map.empty[String, Int]) { (revisions, record) =>
        for {
          _ <- ZIO.fromEither(validate(record.application)).mapError(FsError.InvalidArgument.apply)
          _ <- ZIO
            .attempt(Instant.parse(record.updatedAt))
            .mapError(_ => FsError.InvalidArgument("Invalid notebook timestamp"))
          _ <- ZIO
            .fail(FsError.InvalidArgument("Invalid notebook revision sequence"))
            .unless(record.revision == revisions.getOrElse(record.application.id, 0) + 1)
        } yield revisions.updated(record.application.id, record.revision)
      }
      _ <- ZIO
        .fail(FsError.InvalidArgument("Application notebook exceeds its application limit"))
        .when(records.map(_.application.id).distinct.size > MaxApplications)
    } yield (Chunk.fromIterable(records), bytes.size)).catchSome { case FsError.NotFound(JournalPath) =>
      ZIO.succeed((Chunk.empty, 0))
    }

  private def validId(id: String): Boolean = id.matches("[a-z0-9][a-z0-9-]{0,79}")

  private def validate(a: Application): Either[String, Unit] =
    for {
      _ <- Either.cond(validId(a.id), (), "Use a stable lowercase application slug (at most 80 characters)")
      _ <- Either.cond(
        a.company.trim.nonEmpty && a.role.trim.nonEmpty && a.company.length <= 200 && a.role.length <= 200,
        (),
        "Company and role are required and limited to 200 characters"
      )
      _ <- Either.cond(Stages.contains(a.stage), (), "Unknown application stage")
      _ <- Either.cond(
        List(a.contact, a.channel, a.jobUrl, a.nextAction, a.lastMessageSummary, a.replyDraft, a.notes).flatten
          .forall(_.length <= 4000),
        (),
        "Notebook fields must not exceed 4000 characters"
      )
      _ <- scala.util
        .Try {
          a.followUpDate.foreach(LocalDate.parse)
          a.jobUrl.foreach { value =>
            val uri = java.net.URI.create(value)
            require(Set("http", "https").contains(uri.getScheme) && uri.getHost != null)
          }
          require(a.interviewState.forall(InterviewStates.contains))
          require(a.interviewDurationMinutes.forall(n => n > 0 && n <= 480))
          if a.interviewStart.nonEmpty then {
            val time = OffsetDateTime.parse(a.interviewStart.get)
            val zone = ZoneId.of(a.interviewTimeZone.get)
            require(time.atZoneSameInstant(zone).getOffset == time.getOffset)
            require(a.interviewState.nonEmpty)
          } else require(a.interviewTimeZone.isEmpty && a.interviewState.isEmpty && a.interviewDurationMinutes.isEmpty)
        }
        .toEither
        .left
        .map(_ =>
          "Invalid date, URL or interview details: supply an offset datetime, matching IANA zone and proposed/confirmed/cancelled state"
        )
    } yield ()
}
