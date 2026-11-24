package fmgp.yallmo.skill

import fmgp.yallmo.orchestration.*
import fmgp.yallmo.tool.*
import munit.ZSuite
import zio.*
import zio.json.*
import zio.json.ast.Json

final class ReadSkillToolSuite extends ZSuite {
  private val allowed =
    Skill(
      "allowed",
      "metadata",
      "MAIN <body>",
      sections = Map("z.md" -> "OTHER", "refs/a.md" -> "SECTION"),
      metadata = SkillMetadata(Json.Obj("author" -> Json.Str("PRIVATE AUTHOR")))
    )
  private val denied = Skill("denied", "secret metadata", "SECRET", sections = Map("refs/a.md" -> "SECRET SECTION"))
  private val reader = ReadSkillTool(Seq(allowed))
  private def call(json: String) = ExternalFunctionCall(SkillsToolDefinition.ReadSkill, json)

  testZ("main and exact section results contain only selected content and identity") {
    for {
      main <- reader.execute(call("""{"name":"allowed"}"""))
      section <- reader.execute(call("""{"name":"allowed","section":"refs/a.md"}"""))
    } yield {
      assertEquals(
        main.text.fromJson[ReadSkillTool.MainResult],
        Right(ReadSkillTool.MainResult("allowed", "MAIN <body>", Seq("refs/a.md", "z.md")))
      )
      assertEquals(
        section.text.fromJson[ReadSkillTool.SectionResult],
        Right(ReadSkillTool.SectionResult("allowed", "refs/a.md", "SECTION"))
      )
      assert(!main.text.contains("PRIVATE AUTHOR"))
      assert(!section.text.contains("PRIVATE AUTHOR"))
      assert(!main.text.contains("SECTION"))
      assert(!section.text.contains("MAIN"))
      assert(!section.text.contains("OTHER"))
      assert(!section.text.contains("z.md"))
    }
  }

  for (json, error) <- List(
      "{}" -> "InvalidToolArguments",
      "null" -> "InvalidToolArguments",
      "[]" -> "InvalidToolArguments",
      """{"name":null}""" -> "InvalidToolArguments",
      """{"name":42}""" -> "InvalidToolArguments",
      """{"name":" "}""" -> "InvalidToolArguments",
      """{"name":"allowed","section":null}""" -> "InvalidToolArguments",
      """{"name":"allowed","section":1}""" -> "InvalidToolArguments",
      """{"name":"allowed","section":" "}""" -> "InvalidToolArguments",
      """{"name":"allowed","path":"refs/a.md"}""" -> "InvalidToolArguments",
      """{"name":"unknown"}""" -> "UnknownSkill",
      """{"name":"denied"}""" -> "UnknownSkill",
      """{"name":"denied","section":"refs/a.md"}""" -> "UnknownSkill",
      """{"name":"allowed","section":"./refs/a.md"}""" -> "UnknownSkillSection",
      """{"name":"allowed","section":"refs/../refs/a.md"}""" -> "UnknownSkillSection",
      """{"name":"allowed","section":"/refs/a.md"}""" -> "UnknownSkillSection",
      """{"name":"allowed","section":"missing"}""" -> "UnknownSkillSection"
    )
  do
    testZ(s"direct execution rejects $json") {
      reader.execute(call(json)).either.map { result =>
        assertEquals(result.swap.toOption.map(_.getClass.getSimpleName), Some(error))
      }
    }

  testZ("independent bindings and repeated narrowing cannot widen permissions; hiding is not permission") {
    val other = ReadSkillTool(Seq(denied))
    val hidden = allowed.copy(disableModelInvocation = true)
    for {
      left <- reader.execute(call("""{"name":"denied"}""")).either
      right <- other.execute(call("""{"name":"allowed","section":"refs/a.md"}""")).either
      narrowed <- reader.narrow(Seq(denied)).narrow(Seq(allowed)).execute(call("""{"name":"allowed"}""")).either
      readable <- ReadSkillTool(Seq(hidden)).execute(call("""{"name":"allowed"}"""))
    } yield {
      assert(left.isLeft && right.isLeft && narrowed.isLeft)
      assert(readable.text.contains("MAIN"))
    }
  }
}
