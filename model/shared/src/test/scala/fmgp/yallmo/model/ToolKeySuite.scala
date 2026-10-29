package fmgp.yallmo.model

import munit.FunSuite
import scala.compiletime.testing.typeChecks
import zio.json.*

final class ToolKeySuite extends FunSuite {
  private val builtins = List(
    ToolKey.ReadFile -> "readFile",
    ToolKey.ListFiles -> "listFiles",
    ToolKey.Bash -> "bash",
    ToolKey.RepositoryChanges -> "repositoryChanges",
    ToolKey.Scalamode -> "scalamode",
    ToolKey.ScalaUnsafe -> "scalaunsafe",
    ToolKey.CoachListApplications -> "coach_list_applications",
    ToolKey.CoachGetApplication -> "coach_get_application",
    ToolKey.CoachSaveApplication -> "coach_save_application"
  )

  test("built-in keys are unique and retain their wire values") {
    assertEquals(builtins.map(_._1).distinct.size, 9)
    builtins.foreach { (key, wire) =>
      assertEquals(key.value, wire)
      assertEquals(key.toJson, s"\"$wire\"")
      assertEquals(s"\"$wire\"".fromJson[ToolKey], Right(key))
    }
  }

  test("custom keys are supported and preserved verbatim without accepting blank identifiers") {
    for value <- List("clock", "mcp__custom__search", " custom key ") do
      assertEquals(ToolKey.from(value).map(_.value), Right(value))
      assertEquals(value.toJson.fromJson[ToolKey].map(_.value), Right(value))
    for value <- List("", " ", "\n\t") do
      assertEquals(ToolKey.from(value), Left("tool key must not be blank"))
      assert(value.toJson.fromJson[ToolKey].isLeft)
  }

  test("collection and map-key codecs preserve strings and reject blank keys") {
    val keys = Set(ToolKey.ReadFile, ToolKey.Scalamode)
    assertEquals(keys.toJson.fromJson[Set[ToolKey]], Right(keys))
    assertEquals("[\"clock\"]".fromJson[Set[ToolKey]], Right(Set(ToolKey.unsafe("clock"))))
    val catalog = Map(ToolKey.Scalamode -> "available")
    assertEquals(catalog.toJson, """{"scalamode":"available"}""")
    assertEquals(catalog.toJson.fromJson[Map[ToolKey, String]], Right(catalog))
    assert("""{" ":"available"}""".fromJson[Map[ToolKey, String]].isLeft)
  }

  test("raw strings and other identifier types cannot be used as tool keys") {
    assert(!typeChecks("""val key: fmgp.yallmo.model.ToolKey = "scalamode""""))
    assert(!typeChecks("""val key: fmgp.yallmo.model.ToolKey = fmgp.yallmo.model.AgentId("scalamode")"""))
    assert(!typeChecks("""fmgp.yallmo.orchestration.ExternalToolCall("scalamode", "{}")"""))
    assert(!typeChecks("""fmgp.yallmo.model.ToolChoice.Function("scalamode")"""))
    assert(typeChecks("""fmgp.yallmo.orchestration.ExternalToolCall(fmgp.yallmo.model.ToolKey.Scalamode, "{}")"""))
  }

  test("tool calls and outputs retain their existing JSON shape") {
    val id = FunctionCallId.unsafe("call-1")
    val call = FunctionCall(id, ToolKey.Scalamode, "{}")
    val output = FunctionCallOutput(id, ToolKey.Scalamode, "42")
    assertEquals(call.toJson, """{"id":"call-1","name":"scalamode","argumentsJson":"{}"}""")
    assertEquals(output.toJson, """{"callId":"call-1","name":"scalamode","output":"42"}""")
    assertEquals(call.toJson.fromJson[FunctionCall], Right(call))
    assertEquals(output.toJson.fromJson[FunctionCallOutput], Right(output))
    assert("""{"id":"call-1","name":" ","argumentsJson":"{}"}""".fromJson[FunctionCall].isLeft)
    assert("""{"callId":"call-1","name":"","output":"42"}""".fromJson[FunctionCallOutput].isLeft)
  }
}
