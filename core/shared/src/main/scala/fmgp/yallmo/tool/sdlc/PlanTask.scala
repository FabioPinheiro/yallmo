package fmgp.yallmo.tool.sdlc

import fmgp.yallmo.tool.sdlc.llm.PlanTaskSimplifiedModel
import java.time.Instant
import zio.json.*
import zio.json.ast.Json

/** Complete task snapshot. The runtime owns timestamps and revision; IDs remain stable across updates. A zero revision
  * is a legacy decode default, inferred from journal history before the record is exposed.
  *
  * The title is plain text and the description is Markdown; steps are an ordered local checklist independent of state
  * and subtask relationships. Relationships contain (relationship, ID) tuples, not embedded records. Subtask order is
  * preferred order, not a prerequisite dependency. Decoding establishes JSON shape only; project-level reference
  * validation belongs to the tool implementation.
  */
final case class PlanTask(
    id: PlanTaskId,
    title: String,
    createdAt: Instant,
    updatedAt: Instant,
    description: String = "",
    state: TaskState = TaskState.Open,
    refs: List[(PlanTaskRelationship, PlanTaskId)] = Nil,
    contributesTo: List[String] = Nil,
    assumptions: List[String] = Nil,
    archived: Boolean = false,
    revision: Long = 0L,
    steps: List[PlanTaskStep] = Nil
) {

  /** Generates the narrative and checklist as Markdown without duplicating derived content in storage. */
  def toMarkdown: String = toSimplifiedModel.toMarkdown

  /** Removes runtime-maintained metadata without changing planning fields. */
  def toSimplifiedModel: PlanTaskSimplifiedModel =
    PlanTaskSimplifiedModel(
      id = id,
      title = title,
      description = description,
      state = state,
      refs = refs,
      contributesTo = contributesTo,
      assumptions = assumptions,
      archived = archived,
      expectedRevision = revision,
      steps = steps
    )
}

object PlanTask {
  private val currentDecoder: JsonDecoder[PlanTask] = DeriveJsonDecoder.gen[PlanTask]

  /** Writes title/description and refs. Legacy text and relationship lists migrate on decode without rewriting
    * historical bytes. Mixed old/new fields fail explicitly rather than discarding or guessing content.
    */
  given JsonCodec[PlanTask] = JsonCodec(
    DeriveJsonEncoder.gen[PlanTask],
    summon[JsonDecoder[Json]].mapOrFail(json =>
      migrateText(json).flatMap(migrateRelationships).flatMap(_.as[PlanTask](using currentDecoder))
    )
  )

  private def migrateText(json: Json): Either[String, Json] = json match {
    case obj: Json.Obj =>
      val fields = obj.fields.toMap
      fields.get("text") match {
        case None                                                                  => Right(obj)
        case Some(_) if fields.contains("title") || fields.contains("description") =>
          Left("Task cannot mix legacy text with title/description")
        case Some(value) =>
          for {
            text <- value.as[String]
            _ <- Either.cond(
              text.trim.nonEmpty && text.length <= 8000,
              (),
              "Legacy task text must contain 1–8000 characters"
            )
            title = text
              .replace('\r', '\n')
              .split("\n")
              .iterator
              .map(_.trim)
              .find(_.nonEmpty)
              .getOrElse("")
              .replaceFirst("^#{1,6}\\s+", "")
              .take(160)
          } yield Json.Obj(
            obj.fields.filterNot(_._1 == "text") ++ zio.Chunk(
              "title" -> Json.Str(title),
              "description" -> Json.Str(text)
            )
          )
      }
    case _ => Left("Expected a stored task object")
  }

  private def migrateRelationships(json: Json): Either[String, Json] = json match {
    case obj: Json.Obj =>
      val legacy = List("subtasks" -> "subtask", "dependsOn" -> "dependsOn", "leftoverOf" -> "leftoverOf")
      val names = legacy.map(_._1).toSet
      val fields = obj.fields.toMap
      if !names.exists(fields.contains) then Right(obj)
      else if fields.contains("refs") then Left("Task cannot mix refs with legacy relationship lists")
      else {
        val refs = legacy.foldLeft[Either[String, List[Json]]](Right(Nil)) { case (result, (name, relationship)) =>
          for {
            previous <- result
            ids <- fields.get(name).fold[Either[String, List[String]]](Right(Nil))(_.as[List[String]])
          } yield previous ++ ids.map(id => Json.Arr(Json.Str(relationship), Json.Str(id)))
        }
        refs.map(values =>
          Json.Obj(
            obj.fields
              .filterNot(field => names.contains(field._1)) :+ ("refs" -> Json.Arr(zio.Chunk.fromIterable(values)))
          )
        )
      }
    case _ => Left("Expected a stored task object")
  }
}
