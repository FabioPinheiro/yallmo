package fmgp.yallmo.skill

import zio.json.ast.Json

/** Free-form, application-only data from an optional SKILL.metadata.yaml sidecar.
  *
  * The root is an object, but no fields are required. YAML comments are not represented in this parsed value; multiline
  * values remain strings. Metadata is descriptive, not instructions or authorization, and must not be included in
  * model-facing catalogs, skill reads or attachments.
  *
  * @param data
  *   JSON-compatible structured metadata, retaining unknown fields for application-specific consumers.
  */
final case class SkillMetadata(data: Json.Obj) {

  /** Looks up exact object keys without interpreting dots or coercing values. An empty path returns the root. Missing
    * keys or traversal through a non-object return None; arrays can be inspected through the returned AST.
    */
  def get(path: String*): Option[Json] =
    path.foldLeft(Option[Json](data)) { (current, key) =>
      current.flatMap {
        case Json.Obj(fields) => fields.find(_._1 == key).map(_._2)
        case _                => None
      }
    }

  /** Returns a string only when the selected value is a string; missing, null and other types return None. */
  def getString(path: String*): Option[String] =
    get(path*).collect { case Json.Str(value) => value }

  /** Optional conventional author field; no alternate spelling or nested author schema is inferred. */
  def author: Option[String] = getString("author")

  /** Optional conventional version field; numeric values are not converted to strings. */
  def version: Option[String] = getString("version")
}

object SkillMetadata {
  val empty: SkillMetadata = SkillMetadata(Json.Obj())
}
