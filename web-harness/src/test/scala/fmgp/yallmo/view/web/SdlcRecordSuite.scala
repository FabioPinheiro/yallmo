package fmgp.yallmo.view.web

import munit.FunSuite
import zio.json.*
import zio.json.ast.Json

final class SdlcRecordSuite extends FunSuite {
  test("title is the first line, handles CRLF and uses a fallback for empty first lines") {
    assertEquals(SdlcRecord.title("First line\nDetails"), "First line")
    assertEquals(SdlcRecord.title("  First line  \r\nDetails"), "First line")
    assertEquals(SdlcRecord.title("First\rDetails"), "First")
    assertEquals(SdlcRecord.title("# Improve authentication\nDetails"), "Improve authentication")
    assertEquals(SdlcRecord.title("### A smaller heading"), "A smaller heading")
    List("", "   ", "\nDetails", " \r\nDetails").foreach { text =>
      assertEquals(SdlcRecord.title(text), "(Untitled)")
    }
    assertEquals(SdlcRecord.title("<script>alert('text')</script>"), "<script>alert('text')</script>")
  }

  test("full stored task JSON retains timestamps, relationships, state, archived and unknown fields") {
    val body =
      """[{"id":"task-1","text":"Title\r\nFull text <b>not HTML</b>","createdAt":"2026-01-01T00:00:00.123Z","updatedAt":"2026-02-02T03:04:05Z","relationships":{"intents":["intent-1"]},"state":{"Done":{}},"archived":true,"futureField":{"nested":[1,null,false,"value"]}}]"""
    val records = SdlcRecord.decode(body).toOption.get
    assertEquals(records.size, 1)
    assertEquals(records.head.id, "task-1")
    assertEquals(records.head.title, "Title")
    assert(records.head.prettyJson.contains("\n"))
    assertEquals(s"[${records.head.prettyJson}]".fromJson[Json], body.fromJson[Json])
  }

  test("intent objects need no state and preserve all fields without core model codecs") {
    val body =
      """[{"id":"intent-1","text":"","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-02T00:00:00Z","relationships":[],"archived":false}]"""
    val record = SdlcRecord.decode(body).toOption.get.head
    assertEquals(record.title, "(Untitled)")
    assertEquals(s"[${record.prettyJson}]".fromJson[Json], body.fromJson[Json])
    assertEquals(SdlcRecord.decode("[]"), Right(Nil))
  }

  test("planning cards project steps and refs while retaining unknown JSON fields") {
    val body =
      """[{"id":"T-1","text":"# Authentication\n\nReliable renewal.","state":"doing","archived":false,"steps":[{"done":true,"title":"Reproduce"},{"done":false,"title":"Fix renewal","description":"Add **regression tests**."}],"refs":[["subtask","T-2"],["leftoverOf","T-3"]],"contributesTo":["I-1"],"assumptions":["Trusted clients"],"future":{"nested":true}}]"""
    val record = SdlcRecord.decode(body).toOption.get.head
    assertEquals(record.title, "Authentication")
    assertEquals(record.state, Some("doing"))
    assertEquals(record.completedSteps, 1)
    assertEquals(record.steps.size, 2)
    assertEquals(record.relationships, List("subtask" -> "T-2", "leftoverOf" -> "T-3", "contributesTo" -> "I-1"))
    assertEquals(record.assumptions, List("Trusted clients"))
    assert(record.prettyJson.contains("future"))
    assertEquals(
      record.markdown,
      "# Authentication\n\nReliable renewal.\n\n## Steps\n\n- [x] Reproduce\n\n- [ ] Fix renewal\n\n  Add **regression tests**."
    )
    List("T-1", "RENEWAL", "regression", "T-2", "I-1", "trusted", "doing").foreach(query =>
      assert(record.matches(query), clues(query))
    )
    assert(!record.matches("missing text"))
  }

  test("task cards use the explicit title and Markdown description independently") {
    val body =
      """[{"id":"T-1","title":"Explicit task title","description":"## Different heading\n\nDescription **details**.","steps":[{"done":false,"title":"Check"}]},{"id":"T-2","title":"Title only"}]"""
    val records = SdlcRecord.decode(body).toOption.get
    val record = records.head
    assertEquals(record.title, "Explicit task title")
    assertEquals(record.description, "## Different heading\n\nDescription **details**.")
    assertEquals(
      record.markdown,
      "# Explicit task title\n\n## Different heading\n\nDescription **details**.\n\n## Steps\n\n- [ ] Check"
    )
    assert(record.matches("explicit"))
    assert(record.matches("description"))
    assertEquals(records(1).description, "")
    assertEquals(records(1).markdown, "# Title only")
    List(
      """{"id":"T-1","title":""}""",
      """{"id":"T-1","title":"Task","description":42}""",
      """{"id":"T-1","description":"No title"}""",
      """{"id":"T-1","title":"New","text":"Old"}"""
    ).foreach(json => assert(SdlcRecord.decode(s"[$json]").isLeft, clues(json)))
  }

  test("search, archive and task-state filters compose without changing source records") {
    val body =
      """[{"id":"T-1","text":"First","state":"open"},{"id":"T-2","text":"Second","state":"done","archived":true},{"id":"I-1","text":"Outcome","dependsOn":["T-1"]}]"""
    val records = SdlcRecord.decode(body).toOption.get
    assertEquals(SdlcRecord.visible(records, "", false, "all").map(_.id), List("T-1", "I-1"))
    assertEquals(SdlcRecord.visible(records, "", true, "done").map(_.id), List("T-2"))
    assertEquals(SdlcRecord.visible(records, "second", false, "all"), Nil)
    assertEquals(SdlcRecord.visible(records, "T-1", true, "all").map(_.id), List("T-1", "I-1"))
    assertEquals(SdlcRecord.visible(records, "", true, "all"), records)
    assertEquals(records.last.relationships, List("dependsOn" -> "T-1"))
    assertEquals(records.head.markdown, records.head.description)
  }

  test("Markdown export escapes plain step titles and nests multiline descriptions") {
    assertEquals(
      SdlcStep(false, "Read [docs] &copy;", "One\r\n\rTwo").markdown,
      "- [ ] Read \\[docs\\] \\&copy;\n\n  One\n  \n  Two"
    )
    assertEquals(SdlcStep(true, "Complete", " \n ").markdown, "- [x] Complete")
  }

  test("malformed structured planning fields produce a useful error rather than hiding content") {
    List(
      "\"steps\":[{\"title\":\"Missing done\"}]",
      "\"refs\":[[\"subtask\"]]",
      "\"refs\":[[\"subtask\",42]]",
      "\"archived\":\"yes\"",
      "\"assumptions\":[42]"
    ).foreach { field =>
      assert(SdlcRecord.decode(s"""[{"id":"T-1","text":"Plan",$field}]""").isLeft, clues(field))
    }
  }

  test("the HTTP boundary requires a bare array of objects with string id and text") {
    val invalid = List(
      "not json" -> "Invalid JSON",
      "{\"tasks\":[]}" -> "Expected a JSON array",
      "null" -> "Expected a JSON array",
      "[1]" -> "Item 1: Expected a stored JSON object",
      "[{}]" -> "Item 1: Missing 'id'",
      "[{\"id\":3,\"text\":\"Title\"}]" -> "'id' must be a string",
      "[{\"id\":\"  \",\"text\":\"Title\"}]" -> "'id' must not be empty",
      "[{\"id\":\"task\"}]" -> "Missing 'text'",
      "[{\"id\":\"task\",\"text\":null}]" -> "'text' must be a string",
      "[{\"id\":\"task\",\"text\":\"Title\"},false]" -> "Item 2:"
    )
    invalid.foreach { (body, expected) =>
      assert(SdlcRecord.decode(body).left.toOption.exists(_.contains(expected)), s"$body should report $expected")
    }
  }
}
