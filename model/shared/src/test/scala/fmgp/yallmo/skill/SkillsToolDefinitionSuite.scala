package fmgp.yallmo.skill

import fmgp.yallmo.model.MessagePayload
import fmgp.yallmo.model.llm.AgentTurnRequest
import fmgp.yallmo.tool.{FunctionToolDefinition, SkillsToolDefinition, ToolKey}
import munit.FunSuite
import zio.Chunk

final class SkillsToolDefinitionSuite extends FunSuite {
  test("readSkill schema has exact required name and optional string section, without additional properties") {
    val definition = SkillsToolDefinition.ReadSkillDefinition
    assertEquals(definition.name.value, "readSkill")
    assertEquals(SkillsToolDefinition.name, ToolKey.Skills)
    assertEquals(SkillsToolDefinition.functions, Set(definition))
    assertEquals(definition.inputSchema.required, Set("name"))
    assertEquals(definition.inputSchema.properties.keySet, Set("name", "section"))
    assert(!definition.inputSchema.additionalProperties)
    assertEquals(definition.validate, Right(definition))
    List("{}", """{"name":null}""", """{"name":"a","section":null}""", """{"name":"a","extra":true}""").foreach {
      json =>
        assert(FunctionToolDefinition.validateArgument(json, definition.inputSchema).isLeft)
    }
    assert(
      FunctionToolDefinition.validateArgument("""{"name":"a","section":"refs/a.md"}""", definition.inputSchema).isRight
    )
  }

  test("final projection is metadata only, stable and does not modify canonical instructions or continuations") {
    val skill = Skill("a&b", "description", "PRIVATE MAIN", sections = Map("secret.md" -> "PRIVATE SECTION"))
    val request = AgentTurnRequest(
      "model",
      Chunk(MessagePayload.User("hello"), MessagePayload.Instructions("system")),
      tools = Chunk(SkillsToolDefinition.ReadSkillDefinition),
      skills = Seq(skill)
    )
    assertEquals(request.input.last, MessagePayload.Instructions("system"))
    assertEquals(request.modelInput, request.validate.toOption.get.validate.toOption.get.modelInput)
    assert(request.modelInput.last.contentStr.contains("<name>a&amp;b</name>"))
    assert(request.modelInput.last.contentStr.contains("call readSkill"))
    assert(!request.modelInput.toString.contains("PRIVATE"))
    assert(!request.modelInput.toString.contains("secret.md"))
    assertEquals(request.copy(tools = Chunk.empty).modelInput, request.input)
    assertEquals(request.copy(skills = Seq(skill.copy(disableModelInvocation = true))).modelInput, request.input)
  }
}
