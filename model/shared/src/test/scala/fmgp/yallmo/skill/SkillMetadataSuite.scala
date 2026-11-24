package fmgp.yallmo.skill

import munit.FunSuite
import zio.json.ast.Json

final class SkillMetadataSuite extends FunSuite {
  private val review = Json.Obj("status" -> Json.Str("approved"))
  private val metadata = SkillMetadata(
    Json.Obj(
      "author" -> Json.Str("Fabio"),
      "version" -> Json.Str("1.0.0"),
      "notes" -> Json.Str("First line.\nSecond line.\n"),
      "custom" -> Json.Obj("review" -> review),
      "custom.review" -> Json.Str("literal key"),
      "tags" -> Json.Arr(Json.Str("style"), Json.Str("communication")),
      "nullable" -> Json.Null,
      "enabled" -> Json.Bool(true)
    )
  )

  test("conventional accessors and exact nested paths retain free-form metadata") {
    assertEquals(metadata.author, Some("Fabio"))
    assertEquals(metadata.version, Some("1.0.0"))
    assertEquals(metadata.getString("custom", "review", "status"), Some("approved"))
    assertEquals(metadata.get("custom", "review"), Some(review))
    assertEquals(metadata.getString("custom.review"), Some("literal key"))
    assertEquals(metadata.get(), Some(metadata.data))
  }

  test("multiline strings and arrays are preserved without interpretation") {
    assertEquals(metadata.getString("notes"), Some("First line.\nSecond line.\n"))
    assertEquals(metadata.get("tags"), Some(Json.Arr(Json.Str("style"), Json.Str("communication"))))
    assertEquals(metadata.getString("tags"), None)
    assertEquals(metadata.get("tags", "0"), None)
  }

  test("missing keys and traversal through non-objects return None") {
    assertEquals(metadata.get("missing"), None)
    assertEquals(metadata.get("custom", "missing"), None)
    assertEquals(metadata.get("author", "name"), None)
    assertEquals(metadata.get("nullable", "name"), None)
  }

  test("typed accessors do not coerce nulls, booleans or structured values") {
    assertEquals(metadata.get("nullable"), Some(Json.Null))
    assertEquals(metadata.getString("nullable"), None)
    assertEquals(metadata.getString("enabled"), None)
    assertEquals(metadata.getString("custom"), None)
    val structured = SkillMetadata(Json.Obj("author" -> review, "version" -> Json.Bool(true)))
    assertEquals(structured.author, None)
    assertEquals(structured.version, None)
  }

  test("existing skill constructors default to empty application metadata and copies retain it") {
    val skill = Skill("brief", "Be brief", "Use few words")
    assertEquals(skill.metadata, SkillMetadata.empty)
    assertEquals(skill.metadata.author, None)
    assertEquals(skill.metadata.get(), Some(Json.Obj()))
    val annotated = skill.copy(metadata = metadata)
    assertEquals(annotated.copy(name = "renamed").metadata, metadata)
    assertEquals(annotated.instructions, skill.instructions)
    assertEquals(annotated.disableModelInvocation, skill.disableModelInvocation)
    assertEquals(annotated.sections, skill.sections)
  }
}
