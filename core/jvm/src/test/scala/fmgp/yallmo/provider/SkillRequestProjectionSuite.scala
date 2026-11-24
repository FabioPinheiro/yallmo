package fmgp.yallmo.provider

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.*
import fmgp.yallmo.orchestration.config.AgentCapabilities
import fmgp.yallmo.skill.{Skill, SkillMetadata}
import fmgp.yallmo.tool.*
import munit.FunSuite
import zio.Chunk
import zio.json.ast.Json

abstract class SkillRequestProjectionSuite extends FunSuite {
  protected def wire(request: AgentTurnRequest): ujson.Obj
  private val visible = Skill(
    "a&b",
    "VISIBLE META",
    "PRIVATE MAIN",
    sections = Map("secret.md" -> "PRIVATE SECTION"),
    metadata = SkillMetadata(Json.Obj("author" -> Json.Str("PRIVATE AUTHOR")))
  )
  private val denied = Skill("denied", "DENIED META", "DENIED MAIN")
  private val hidden = visible.copy(name = "hidden", description = "HIDDEN META", disableModelInvocation = true)
  private val continuation =
    ProviderContinuation(ProviderId.unsafe("openai-codex-responses"), Some("rs-1"), "opaque-content")
  private val request = AgentTurnRequest(
    "model",
    Vector(
      MessagePayload.Reasoning(Some("summary"), continuation),
      MessagePayload.User("hello"),
      MessagePayload.Instructions("system")
    ),
    tools = Chunk(SkillsToolDefinition.ReadSkillDefinition),
    toolGroups = Chunk(SkillsToolDefinition),
    skills = Seq(visible, hidden, denied)
  )

  for (label, policy, hasCatalog) <- List(
      ("skills-only allowlist", AgentCapabilities(skills = Some(Set(visible.name, hidden.name))), true),
      ("tool denied", AgentCapabilities(tools = Some(Set.empty)), false),
      ("function denied", AgentCapabilities(functions = Some(Set.empty)), false),
      ("skills denied", AgentCapabilities(skills = Some(Set.empty)), false)
    )
  do
    test(s"$label projects the final skill catalog only after policy narrowing") {
      val filtered = policy.filterRequest(request)
      val twice = policy.filterRequest(filtered.validate.toOption.get)
      val body = wire(twice)
      val json = body.toString
      assertEquals(body, wire(filtered))
      assertEquals(filtered.input, request.input)
      assertEquals(json.contains("<available_skills>"), hasCatalog)
      assertEquals(json.contains("call readSkill"), hasCatalog)
      assert(!json.contains("PRIVATE") && !json.contains("secret.md") && !json.contains("HIDDEN META"))
      if hasCatalog then {
        assert(json.contains("a&amp;b"))
        assert(!json.contains("DENIED META"))
        assertEquals(json.sliding("<available_skills>".length).count(_ == "<available_skills>"), 1)
        assert(json.contains("readSkill"))
      }
      assertEquals(filtered.input.head, request.input.head)
      assertEquals(filtered.modelInput.head, request.input.head)
      assertEquals(filtered.input.last, MessagePayload.Instructions("system"))
    }

  test("removed reader and all-hidden catalog cannot produce final-wire activation guidance") {
    List(request.copy(tools = Chunk.empty), request.copy(skills = Seq(hidden))).foreach { value =>
      val json = wire(value).toString
      assert(!json.contains("<available_skills>") && !json.contains("call readSkill"))
      assert(!json.contains("PRIVATE") && !json.contains("HIDDEN META"))
    }
  }
}
