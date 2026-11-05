package fmgp.yallmo.view.web

import munit.FunSuite
import zio.json.*
import zio.json.ast.Json

final class SdlcRecordSuite extends FunSuite {
  test("title is the first line, handles CRLF and uses a fallback for empty first lines") {
    assertEquals(SdlcRecord.title("First line\nDetails"), "First line")
    assertEquals(SdlcRecord.title("  First line  \r\nDetails"), "First line")
    assertEquals(SdlcRecord.title("First\rDetails"), "First")
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
