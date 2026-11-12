package fmgp.yallmo.model

import fmgp.yallmo.tool.{FunctionKey, ToolKey}

import fmgp.yallmo.model.tool.SdlcToolDefinition

import munit.FunSuite
import scala.compiletime.testing.typeChecks
import zio.json.*

final class ToolKeySuite extends FunSuite {
  test("tool identifiers have independent string codecs and support custom groups") {
    val keys =
      Set(ToolKey.Filesystem, ToolKey.Bash, ToolKey.RepositoryChanges, ToolKey.Scalamode, ToolKey.Sdlc, ToolKey.Coach)
    assertEquals(keys.size, 6)
    assertEquals(ToolKey.Sdlc.toJson, "\"sdlc\"")
    assertEquals(keys.toJson.fromJson[Set[ToolKey]], Right(keys))
    val custom = Map(ToolKey.unsafe("custom-package") -> "enabled")
    assertEquals(custom.toJson.fromJson[Map[ToolKey, String]], Right(custom))
    for value <- List("", " ", "\n\t") do {
      assert(ToolKey.from(value).isLeft)
      assert(value.toJson.fromJson[ToolKey].isLeft)
    }
    assert("""{" ":"enabled"}""".fromJson[Map[ToolKey, String]].isLeft)
  }

  test("tool keys cannot be substituted for function names or vice versa") {
    assert(!typeChecks("""val key: fmgp.yallmo.tool.ToolKey = fmgp.yallmo.model.tool.SdlcToolDefinition.SetTasks"""))
    assert(!typeChecks("""val key: fmgp.yallmo.tool.FunctionKey = fmgp.yallmo.tool.ToolKey.Sdlc"""))
    assert(!typeChecks("""fmgp.yallmo.orchestration.ExternalFunctionCall(fmgp.yallmo.tool.ToolKey.Sdlc, "{}")"""))
  }
}
