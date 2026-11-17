package fmgp.yallmo.webharness

import fmgp.yallmo.tool.{BashToolDefinition, FunctionKey}
import munit.FunSuite
import zio.json.*
import zio.json.ast.Json

final class ToolCallContentSuite extends FunSuite {
  private def source(arguments: String): Option[String] =
    ToolCallContent.scalaSource("tool_call", Some(FunctionKey.scalamode), arguments)

  test("Scalamode arguments decode once into literal source with whitespace, paths and embedded fences intact") {
    val code = "\n  val path = \"C:\\\\tmp\"\nval example = \"\"\"```mermaid\nflowchart LR\n A-->B\n```\"\"\"\n"
    assertEquals(source(Json.Obj("code" -> Json.Str(code)).toJson), Some(code))
    assertEquals(source("""{"code":""}"""), Some(""))
  }

  test("only a Scalamode tool call uses the source presentation") {
    val arguments = """{"code":"val n = 42"}"""
    assertEquals(source(arguments), Some("val n = 42"))
    assertEquals(ToolCallContent.scalaSource("tool_call", Some(FunctionKey.unsafe("other")), arguments), None)
    assertEquals(ToolCallContent.scalaSource("tool_call", None, arguments), None)
    List("assistant", "user", "tool_result").foreach { role =>
      assertEquals(ToolCallContent.scalaSource(role, Some(FunctionKey.scalamode), arguments), None)
    }
  }

  test("Bash commands decode once for literal terminal presentation") {
    val command = "printf '%s\\n' '<img src=x onerror=alert(1)>'\ncat <<'EOF'\n```mermaid\nEOF\n"
    val arguments = Json.Obj("command" -> Json.Str(command)).toJson
    assertEquals(ToolCallContent.bashCommand("tool_call", Some(BashToolDefinition.Execute), arguments), Some(command))
    assertEquals(
      ToolCallContent.bashCommand("tool_call", Some(BashToolDefinition.Execute), """{"command":""}"""),
      Some("")
    )
    List("assistant", "user", "tool_result").foreach { role =>
      assertEquals(ToolCallContent.bashCommand(role, Some(BashToolDefinition.Execute), arguments), None)
    }
    List(None, Some(FunctionKey.scalamode), Some(FunctionKey.unsafe("other"))).foreach { tool =>
      assertEquals(ToolCallContent.bashCommand("tool_call", tool, arguments), None)
    }
    assertEquals(source(arguments), None)
  }

  test("malformed or unknown Bash arguments keep their JSON display") {
    List(
      "not JSON",
      "{\"command\":",
      "{}",
      "[]",
      "null",
      """{"command":null}""",
      """{"command":42}""",
      """{"command":"ls","timeout":10}""",
      """{"command":"ls","command":"pwd"}""",
      """{"code":"ls"}"""
    ).foreach { arguments =>
      assertEquals(
        ToolCallContent.bashCommand("tool_call", Some(BashToolDefinition.Execute), arguments),
        None,
        arguments
      )
    }
  }

  test("malformed and unknown argument shapes fall back without hiding any fields") {
    List(
      "not JSON",
      "{\"code\":",
      "{}",
      "[]",
      "null",
      """{"code":null}""",
      """{"code":42}""",
      """{"code":{"nested":"value"}}""",
      """{"code":"42","mode":"legacy"}""",
      """{"code":"42","code":"43"}"""
    ).foreach(arguments => assertEquals(source(arguments), None, arguments))
  }
}
