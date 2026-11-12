package fmgp.yallmo.model

import fmgp.yallmo.tool.FunctionKey

import fmgp.yallmo.model.tool.{
  BashToolDefinition,
  CoachToolDefinition,
  FilesystemToolDefinition,
  RepositoryChangesToolDefinition,
  ScalamodeToolDefinition,
  SdlcToolDefinition
}

import munit.FunSuite
import scala.compiletime.testing.typeChecks
import zio.json.*

final class FunctionKeySuite extends FunSuite {
  private val builtins = List(
    FilesystemToolDefinition.ReadFile -> "readFile",
    FilesystemToolDefinition.ListFiles -> "listFiles",
    BashToolDefinition.Execute -> "bash",
    RepositoryChangesToolDefinition.Inspect -> "repositoryChanges",
    ScalamodeToolDefinition.Execute -> "scalamode",
    SdlcToolDefinition.SetTasks -> "sdlc_set",
    SdlcToolDefinition.ReadTask -> "sdlc_read_task",
    SdlcToolDefinition.SetIntents -> "sdlc_set_intents",
    SdlcToolDefinition.ReadIntent -> "sdlc_read_intent",
    SdlcToolDefinition.List -> "sdlc_list",
    CoachToolDefinition.ListApplications -> "coach_list_applications",
    CoachToolDefinition.GetApplication -> "coach_get_application",
    CoachToolDefinition.SaveApplication -> "coach_save_application"
  )

  test("built-in keys are unique and retain their wire values") {
    assertEquals(builtins.map(_._1).distinct.size, 13)
    builtins.foreach { (key, wire) =>
      assertEquals(key.value, wire)
      assertEquals(key.toJson, s"\"$wire\"")
      assertEquals(s"\"$wire\"".fromJson[FunctionKey], Right(key))
    }
  }

  test("custom keys are supported and preserved verbatim without accepting blank identifiers") {
    for value <- List("clock", "mcp__custom__search", " custom key ") do
      assertEquals(FunctionKey.from(value).map(_.value), Right(value))
      assertEquals(value.toJson.fromJson[FunctionKey].map(_.value), Right(value))
    for value <- List("", " ", "\n\t") do
      assertEquals(FunctionKey.from(value), Left("function key must not be blank"))
      assert(value.toJson.fromJson[FunctionKey].isLeft)
  }

  test("collection and map-key codecs preserve strings and reject blank keys") {
    val keys = Set(FilesystemToolDefinition.ReadFile, ScalamodeToolDefinition.Execute)
    assertEquals(keys.toJson.fromJson[Set[FunctionKey]], Right(keys))
    assertEquals("[\"clock\"]".fromJson[Set[FunctionKey]], Right(Set(FunctionKey.unsafe("clock"))))
    val catalog = Map(ScalamodeToolDefinition.Execute -> "available")
    assertEquals(catalog.toJson, """{"scalamode":"available"}""")
    assertEquals(catalog.toJson.fromJson[Map[FunctionKey, String]], Right(catalog))
    assert("""{" ":"available"}""".fromJson[Map[FunctionKey, String]].isLeft)
  }

  test("raw strings and other identifier types cannot be used as tool keys") {
    assert(!typeChecks("""val key: fmgp.yallmo.tool.FunctionKey = "scalamode""""))
    assert(!typeChecks("""val key: fmgp.yallmo.tool.FunctionKey = fmgp.yallmo.model.AgentId("scalamode")"""))
    assert(!typeChecks("""fmgp.yallmo.orchestration.ExternalFunctionCall("scalamode", "{}")"""))
    assert(
      typeChecks(
        """fmgp.yallmo.orchestration.ExternalFunctionCall(fmgp.yallmo.model.tool.ScalamodeToolDefinition.Execute, "{}")"""
      )
    )
  }

  test("tool calls and outputs retain their existing JSON shape") {
    val id = FunctionCallId.unsafe("call-1")
    val call = FunctionCall(id, ScalamodeToolDefinition.Execute, "{}")
    val output = FunctionGenericOutput(id, BashToolDefinition.Execute, "42")
    assertEquals(call.toJson, """{"id":"call-1","name":"scalamode","argumentsJson":"{}"}""")
    assertEquals(output.toJson, """{"callId":"call-1","name":"bash","output":"42"}""")
    assertEquals(call.toJson.fromJson[FunctionCall], Right(call))
    assertEquals(output.toJson.fromJson[FunctionCallOutput], Right(output))
    assert("""{"id":"call-1","name":" ","argumentsJson":"{}"}""".fromJson[FunctionCall].isLeft)
    assert("""{"callId":"call-1","name":"","output":"42"}""".fromJson[FunctionCallOutput].isLeft)
  }
}
