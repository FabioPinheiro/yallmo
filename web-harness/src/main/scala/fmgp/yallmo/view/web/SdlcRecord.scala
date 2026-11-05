package fmgp.yallmo.view.web

import zio.json.*
import zio.json.ast.Json

/** Browser projection for card identity only; the complete stored object remains authoritative for inspection. */
private[web] final case class SdlcRecord(id: String, text: String, json: Json.Obj) {
  def title: String = SdlcRecord.title(text)
  def prettyJson: String = json.toJsonPretty
}

private[web] object SdlcRecord {
  def title(text: String): String =
    text.takeWhile(character => character != '\n' && character != '\r').trim match {
      case ""    => "(Untitled)"
      case first => first
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
      def stringField(name: String): Either[String, String] = obj.fields.find(_._1 == name).map(_._2) match {
        case Some(Json.Str(value)) => Right(value)
        case Some(_)               => Left(s"'$name' must be a string.")
        case None                  => Left(s"Missing '$name' string.")
      }
      for {
        id <- stringField("id")
        _ <- Either.cond(id.trim.nonEmpty, (), "'id' must not be empty.")
        text <- stringField("text")
      } yield SdlcRecord(id, text, obj)
    case _ => Left("Expected a stored JSON object.")
  }
}
