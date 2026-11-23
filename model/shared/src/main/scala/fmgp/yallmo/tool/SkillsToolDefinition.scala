package fmgp.yallmo.tool

import fmgp.yallmo.model.llm.JsonSchema
import fmgp.yallmo.skill.Skill

/** Skill activation is an in-memory read, not a filesystem capability. */
object SkillsToolDefinition extends ToolDefinition {
  val ReadSkill: FunctionKey = FunctionKey.unsafe("readSkill")
  val ReadSkillDefinition: FunctionToolDefinition = FunctionToolDefinition(
    ReadSkill,
    "Load a permitted skill's main instructions and available section names, or one section by its exact key. Name and section must not be blank.",
    JsonSchema.ObjectSchema(
      Map(
        "name" -> JsonSchema.StringSchema(Some("Exact available skill name; must not be blank")),
        "section" -> JsonSchema.StringSchema(Some("Optional exact skill-relative section key; must not be blank"))
      ),
      required = Set("name"),
      additionalProperties = false
    )
  )
  override val name: ToolKey = ToolKey.Skills
  override val functions: Set[FunctionToolDefinition] = Set(ReadSkillDefinition)

  /** Only call after execution availability has been established by the runner/resolver. */
  def renderCatalog(skills: Iterable[Skill], definitions: Iterable[FunctionToolDefinition]): String = {
    val catalog = if definitions.exists(_ == ReadSkillDefinition) then Skill.renderCatalog(skills) else ""
    if catalog.isEmpty then ""
    else s"$catalog\n\n$ActivationGuidance"
  }

  val ActivationGuidance: String =
    "When a task matches an available skill, call readSkill with its name to load its instructions. Load sections as needed using their exact section keys."
}
