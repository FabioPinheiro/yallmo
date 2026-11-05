package fmgp.yallmo.view.web

import zio.json.*
import zio.json.ast.Json

/** Read-only checklist projection. The browser deliberately does not depend on core planning models. */
private[web] final case class SdlcStep(done: Boolean, title: String, description: String = "") derives JsonCodec {
  def markdown: String = {
    val escaped = title.flatMap(c => if "\\`*_{}[]<>!|&#~".contains(c) then s"\\$c" else c.toString)
    val checkbox = s"- [${if done then "x" else " "}] $escaped"
    if description.trim.isEmpty then checkbox
    else {
      val nested = description.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1).map("  " + _).mkString("\n")
      s"$checkbox\n\n$nested"
    }
  }
}

/** Browser presentation projection; the complete stored JSON remains authoritative and retains unknown fields. */
private[web] final case class SdlcRecord(
    id: String,
    description: String,
    json: Json.Obj,
    state: Option[String] = None,
    archived: Boolean = false,
    steps: List[SdlcStep] = Nil,
    refs: List[(String, String)] = Nil,
    contributesTo: List[String] = Nil,
    dependsOn: List[String] = Nil,
    assumptions: List[String] = Nil,
    explicitTitle: Option[String] = None
) {
  def title: String = explicitTitle.getOrElse(SdlcRecord.title(description))
  def prettyJson: String = json.toJsonPretty
  def completedSteps: Int = steps.count(_.done)

  /** Same narrative/checklist export contract as PlanTask.toMarkdown, without storing a duplicate JSON field. */
  def markdown: String = {
    val narrative = explicitTitle.fold(description) { value =>
      val escaped = value.flatMap(c => if "\\`*_{}[]<>!|&#~".contains(c) then s"\\$c" else c.toString)
      if description.isEmpty then s"# $escaped" else s"# $escaped\n\n$description"
    }
    if steps.isEmpty then narrative else s"$narrative\n\n## Steps\n\n${steps.map(_.markdown).mkString("\n\n")}"
  }

  def relationships: List[(String, String)] =
    refs ++ contributesTo.map("contributesTo" -> _) ++ dependsOn.map("dependsOn" -> _)

  def matches(query: String): Boolean = {
    val needle = query.trim.toLowerCase
    (List(id, title, description, state.getOrElse("")) ++ steps.flatMap(step => List(step.title, step.description)) ++
      relationships.flatMap { case (relationship, target) => List(relationship, target) } ++ assumptions)
      .exists(_.toLowerCase.contains(needle))
  }
}

private[web] object SdlcRecord {
  def title(text: String): String =
    text.takeWhile(character => character != '\n' && character != '\r').trim.replaceFirst("^#{1,6}\\s+", "") match {
      case ""    => "(Untitled)"
      case first => first
    }

  def visible(records: List[SdlcRecord], query: String, includeArchived: Boolean, state: String): List[SdlcRecord] =
    records.filter(record =>
      (includeArchived || !record.archived) && record.matches(query) && (state == "all" || record.state.contains(state))
    )

  def relationshipLabel(relationship: String): String = relationship match {
    case "subtask"       => "Subtask"
    case "dependsOn"     => "Depends on"
    case "leftoverOf"    => "Left over from"
    case "contributesTo" => "Contributes to"
    case other           => other
  }

  def decode(body: String): Either[String, List[SdlcRecord]] =
    body.fromJson[Json].left.map(error => s"Invalid JSON: $error").flatMap {
      case Json.Arr(items) =>
        items.zipWithIndex
          .foldLeft[Either[String, List[SdlcRecord]]](Right(Nil)) { case (records, (item, index)) =>
            for {
              previous <- records
              record <- decodeRecord(item).left.map(error => s"Item ${index + 1}: $error")
            } yield record :: previous
          }
          .map(_.reverse)
      case _ => Left("Expected a JSON array of stored objects.")
    }

  private def decodeRecord(value: Json): Either[String, SdlcRecord] = value match {
    case obj: Json.Obj =>
      val fields = obj.fields.toMap
      def stringField(name: String): Either[String, String] = fields.get(name) match {
        case Some(Json.Str(value)) => Right(value)
        case Some(_)               => Left(s"'$name' must be a string.")
        case None                  => Left(s"Missing '$name' string.")
      }
      def optional[A: JsonDecoder](name: String, default: A): Either[String, A] =
        fields.get(name).fold[Either[String, A]](Right(default))(_.as[A].left.map(error => s"'$name': $error"))
      for {
        id <- stringField("id")
        _ <- Either.cond(id.trim.nonEmpty, (), "'id' must not be empty.")
        content <-
          if fields.contains("title") then {
            for {
              title <- stringField("title")
              _ <- Either.cond(
                title.trim.nonEmpty && !title.exists(c => c == '\n' || c == '\r'),
                (),
                "'title' must be nonblank and single-line."
              )
              description <- optional("description", "")
              _ <- Either.cond(!fields.contains("text"), (), "Cannot mix legacy text with title/description.")
            } yield (Some(title), description)
          } else if fields.contains("description") then Left("Missing 'title' string.")
          else stringField("text").map(text => (None, text))
        archived <- optional("archived", false)
        steps <- optional[List[SdlcStep]]("steps", Nil)
        refs <- optional[List[(String, String)]]("refs", Nil)
        contributions <- optional[List[String]]("contributesTo", Nil)
        dependencies <- optional[List[String]]("dependsOn", Nil)
        assumptions <- optional[List[String]]("assumptions", Nil)
      } yield SdlcRecord(
        id,
        content._2,
        obj,
        fields.get("state").collect { case Json.Str(state) => state },
        archived,
        steps,
        refs,
        contributions,
        dependencies,
        assumptions,
        content._1
      )
    case _ => Left("Expected a stored JSON object.")
  }
}
