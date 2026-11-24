package fmgp.yallmo.skill

import fmgp.yallmo.orchestration.StaticContext
import munit.FunSuite
import zio.Chunk
import zio.json.ast.Json

final class SkilSuite extends FunSuite {
  test("renderCatalogEntry escapes XML metadata once and never serializes skill contents") {
    val skill = Skill(
      "grill&<>\"'",
      "Use <heat> & \"care\"; it's &amp; safe",
      "PRIVATE BODY",
      sections = Map("private/path.md" -> "PRIVATE SECTION"),
      metadata = SkillMetadata(Json.Obj("author" -> Json.Str("PRIVATE AUTHOR")))
    )
    assertEquals(
      skill.renderCatalogEntry,
      """<skill>
        |  <name>grill&amp;&lt;&gt;&quot;&apos;</name>
        |  <description>Use &lt;heat&gt; &amp; &quot;care&quot;; it&apos;s &amp;amp; safe</description>
        |</skill>""".stripMargin
    )
  }

  test("renderCatalog preserves order and omits disabled skills and empty wrappers") {
    val last = Skill("z", "Last alphabetically", "PRIVATE BODY")
    val first = Skill("a", "First alphabetically", "PRIVATE BODY")
    val disabled = Skill("hidden", "PRIVATE METADATA", "PRIVATE BODY", disableModelInvocation = true)
    assertEquals(
      Skill.renderCatalog(Seq(last, disabled, first)),
      """<available_skills>
        |  <skill>
        |    <name>z</name>
        |    <description>Last alphabetically</description>
        |  </skill>
        |  <skill>
        |    <name>a</name>
        |    <description>First alphabetically</description>
        |  </skill>
        |</available_skills>""".stripMargin
    )
    assertEquals(Skill.renderCatalog(Seq.empty), "")
    assertEquals(Skill.renderCatalog(Seq(disabled)), "")
  }

  test("one skill model retains instructions, invocation policy and relative sections") {
    val skill = Skill(
      name = "grilling",
      description = "Grill safely",
      instructions = "# Grill\nUse heat.\n",
      disableModelInvocation = true,
      sections = Map("references/temperature.md" -> "Use a thermometer.")
    )
    val context = StaticContext[Any]("system", skills = Chunk(skill))

    assertEquals(skill.copy(name = "renamed").name, "renamed")
    assertEquals(context.skills.head, skill)
    assertEquals(
      skill.productElementNames.toVector,
      Vector("name", "description", "instructions", "disableModelInvocation", "sections", "metadata")
    )
    assertEquals(Skill("brief", "Be brief", "Use few words").sections, Map.empty[String, String])
    assert(!Skill("brief", "Be brief", "Use few words").disableModelInvocation)
  }
}
