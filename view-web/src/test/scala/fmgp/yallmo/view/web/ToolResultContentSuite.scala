package fmgp.yallmo.view.web

import munit.FunSuite
import zio.json.*
import zio.json.ast.Json

final class ToolResultContentSuite extends FunSuite {
  import ToolResultContent.{Field, Result}

  test("command metadata is compact and output JSON escapes become actual line breaks") {
    val json = """{"exitCode":0,"timedOut":false,"output":"first\nsecond\n","truncated":true}"""
    assertEquals(
      ToolResultContent.parse(json),
      Some(
        Result(
          List(Field("exitCode", "0"), Field("timedOut", "false"), Field("truncated", "true")),
          List(Field("output", "first\nsecond\n"))
        )
      )
    )
  }

  test("alternative field names, nulls and large numbers are retained without numeric rounding") {
    val json = """{"exit_code":1,"exit-code":2,"count":9007199254740993,"optional":null}"""
    assertEquals(
      ToolResultContent.parse(json).map(_.metadata),
      Some(
        List(
          Field("exit_code", "1"),
          Field("exit-code", "2"),
          Field("count", "9007199254740993"),
          Field("optional", "null")
        )
      )
    )
  }

  test("literal backslashes in paths and output are not decoded twice") {
    val json = """{"output":"C:\\new\\tools\\file.txt\\n"}"""
    assertEquals(
      ToolResultContent.parse(json).map(_.blocks),
      Some(List(Field("output", "C:\\new\\tools\\file.txt\\n")))
    )
  }

  test("unknown strings and structured fields remain available") {
    val json = """{"output":"<script>text only</script>","hint":"check the logs","nested":{"ok":true},"items":[1,2]}"""
    val blocks = ToolResultContent.parse(json).get.blocks
    assertEquals(blocks.map(_.name), List("output", "hint", "nested", "items"))
    assertEquals(blocks.head.text, "<script>text only</script>")
    assertEquals(blocks(1).text, "check the logs")
    assertEquals(blocks(2).text.fromJson[Json], """{"ok":true}""".fromJson[Json])
    assertEquals(blocks(3).text.fromJson[Json], "[1,2]".fromJson[Json])
  }

  test("a JSON string result is decoded as output") {
    assertEquals(
      ToolResultContent.parse("\"first\\nsecond\""),
      Some(Result(Nil, List(Field("output", "first\nsecond"))))
    )
  }

  test("invalid JSON, empty objects and other top-level values use the existing text view") {
    List("", "not JSON\n<unsafe>", "{broken}", "{}", "[]", "[1,2]", "1", "false", "null").foreach { text =>
      assertEquals(ToolResultContent.parse(text), None, text)
    }
  }

  test("collapse thresholds are evaluated on decoded output, not its single-line JSON representation") {
    val output = List.fill(17)("line").mkString("\n")
    val json = Json.Obj("exitCode" -> Json.Num(0), "output" -> Json.Str(output)).toJson
    val result = ToolResultContent.parse(json).get
    assertEquals(result.metadata, List(Field("exitCode", "0")))
    assert(MessageText.isLong(result.blocks.head.text))
  }
}
