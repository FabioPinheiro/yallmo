package fmgp.yallmo.server.application

import fmgp.yallmo.model.*
import fmgp.yallmo.model.llm.AgentTurnRequest
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.orchestration.config.{AgentConfig, Config, ProviderConfig, ProviderId}
import fmgp.yallmo.skill.{Skill, SkillService}
import fmgp.yallmo.tool.*
import munit.ZSuite
import zio.{Config as _, *}
import zio.http.Client
import zio.json.ast.Json

final class ReadSkillCompositionSuite extends ZSuite {
  private val id = AgentId("selected")
  private val provider = ProviderId.unsafe("local")
  private val config = Config(
    agents = Map(id -> AgentConfig("OAIAgent", Seq(provider), config = Json.Obj("model" -> Json.Str("model")))),
    providers = Map(provider -> ProviderConfig.OAI("http://127.0.0.1:1/v1"))
  )
  private val skill = Skill("guide", "metadata", "MAIN")
  private def service(skills: Seq[Skill]): SkillService = new SkillService {
    val catalog = skills
    val diagnostics = Seq.empty
    def readSkill(name: String): IO[YallmoError, Skill] =
      ZIO.fromOption(skills.find(_.name == name)).orElseFail(YallmoError.UnknownSkill(name))
  }
  private val file = new AgentFunction[Any] {
    val definition = FilesystemToolDefinition.ReadFileDefinition
    def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
      ZIO.dieMessage("composition does not execute files")
  }

  for (label, catalog, definitions, selectedFiles, hasReader) <- List(
      ("ordinary", Seq(skill), ApplicationTools.ToolDefinitions, false, true),
      ("selected removal", Seq(skill), ApplicationTools.ToolDefinitions, true, false),
      ("custom group removal", Seq(skill), Chunk[ToolDefinition](FilesystemToolDefinition), false, false),
      ("empty", Seq.empty[Skill], ApplicationTools.ToolDefinitions, false, false),
      ("all hidden", Seq(skill.copy(disableModelInvocation = true)), ApplicationTools.ToolDefinitions, false, false)
    )
  do
    testZ(s"$label application composition matches selected definitions and bound functions") {
      (for {
        application <- ServerApplication.make(
          config,
          Chunk.empty,
          availableFunctions = Chunk(file),
          selectedFunctions = _ => if selectedFiles then Chunk(file) else Chunk.empty,
          toolDefinitions = definitions,
          skillService = Some(service(catalog))
        )
        selected <- application.resolve(id)
      } yield {
        val request = AgentTurnRequest(
          "model",
          Chunk(MessagePayload.Instructions("system")),
          tools = selected.context.functions,
          toolGroups = selected.context.tools,
          skills = selected.context.skills
        )
        assertEquals(selected.context.functions.exists(_.name == SkillsToolDefinition.ReadSkill), hasReader)
        assertEquals(selected.functions.exists(_.definition.name == SkillsToolDefinition.ReadSkill), hasReader)
        assertEquals(request.modelInput.map(_.contentStr).mkString.contains("<available_skills>"), hasReader)
        assertEquals(application.context(None).skills.map(_.name), Chunk.fromIterable(catalog.map(_.name)))
        assertEquals(selected.context.skills.map(_.name), Chunk.fromIterable(catalog.map(_.name)))
      }).provideLayer(Client.default)
    }
}
