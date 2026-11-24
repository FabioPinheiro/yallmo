package fmgp.yallmo.server.application

import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.{AgentFunction, ExternalFunctionCall, ExternalFunctionResult}
import fmgp.yallmo.orchestration.config.{AgentCapabilities, AgentConfig, Config, ProviderConfig, ProviderId}
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import fmgp.yallmo.server.jsonrpc.JsonRpc
import java.nio.charset.StandardCharsets.UTF_8
import fmgp.yallmo.skill.{Skill, SkillMetadata, SkillService}
import fmgp.yallmo.tool.*
import munit.ZSuite
import zio.{Config as _, *}
import zio.http.Client
import zio.json.*
import zio.json.ast.Json

final class SkillInspectionSuite extends ZSuite {
  private val inherited = AgentId("inherited")
  private val empty = AgentId("empty")
  private val noReader = AgentId("no-reader")
  private val broken = AgentId("broken")
  private val provider = ProviderId.unsafe("local")
  private val guide = Skill(
    "guide",
    "A guide",
    "MAIN",
    sections = Map("references/heat.md" -> "HEAT", "README.md" -> "README"),
    metadata = SkillMetadata(Json.Obj("author" -> Json.Str("Ada"), "custom" -> Json.Obj("key" -> Json.Str("value"))))
  )
  private val hidden = Skill("hidden", "Explicit only", "HIDDEN", disableModelInvocation = true)
  private val service = new SkillService {
    val catalog = Vector(hidden, guide)
    val diagnostics = Vector(
      ResourceDiagnostic("bad", "Cannot read SKILL.md: /private/storage/secret"),
      ResourceDiagnostic("/private/storage/skills", "Cannot discover skills: credential=secret")
    )
    def readSkill(name: String): IO[YallmoError, Skill] = ZIO.dieMessage("Inspection must use the cached catalog")
  }
  private def definition(capabilities: AgentCapabilities = AgentCapabilities()) =
    AgentConfig(
      "OAIAgent",
      Seq(provider),
      config = Json.Obj("model" -> Json.Str("private-model")),
      capabilities = capabilities
    )
  private val config = Config(
    agents = Map(
      inherited -> definition(),
      empty -> definition(AgentCapabilities(skills = Some(Set.empty))),
      noReader -> definition(AgentCapabilities(functions = Some(Set.empty))),
      broken -> AgentConfig("NotAnAgent", Seq.empty, config = Json.Obj("prompt" -> Json.Str("private-prompt")))
    ),
    providers = Map(provider -> ProviderConfig.OAI("http://127.0.0.1:1/v1"))
  )

  testZ("catalog inspection preserves hidden skills, exact cached sections and application-only metadata") {
    (for {
      application <- ServerApplication.make(config, Chunk.empty, skillService = Some(service))
      inspection = new SkillInspection(application)
      listed = inspection.list
      main <- inspection.read(SkillsRead.Params("guide"))
      section <- inspection.read(SkillsRead.Params("guide", Some("references/heat.md")))
      unknown <- inspection.read(SkillsRead.Params("unknown")).either
      traversals <- ZIO.foreach(List("../guide", "references/../README.md", "/private/file", "SKILL.md", "")) { path =>
        inspection.read(SkillsRead.Params("guide", Some(path))).either
      }
      explicit <- inspection.read(SkillsRead.Params("hidden"))
    } yield {
      assertEquals(listed.skills.map(_.name), Chunk("guide", "hidden"))
      assertEquals(listed.skills.head.documentCount, 3)
      assertEquals(listed.skills.head.author, Some("Ada"))
      assertEquals(
        listed.diagnostics,
        Chunk(SkillDiagnostic("bad", "Cannot read SKILL.md"), SkillDiagnostic("skills", "Skill discovery unavailable"))
      )
      assertEquals(main.content, "MAIN")
      assertEquals(main.sections, Chunk("README.md", "references/heat.md"))
      assertEquals(main.metadata, guide.metadata.data)
      assertEquals(section.content, "HEAT")
      assertEquals(explicit.content, "HIDDEN")
      assert(unknown.left.toOption.exists(_.code == ErrorCode.NotFound))
      assert(traversals.forall(_.left.toOption.exists(_.code == ErrorCode.NotFound)))
      assert(!listed.toJson.contains("/private"))
    }).provideLayer(Client.default)
  }

  testZ("agent inspection uses effective contexts, reader binding and isolates configured-agent resolution errors") {
    (for {
      application <- ServerApplication.make(config, Chunk.empty, skillService = Some(service))
      _ <- application.registerUser(AgentId("user-test"))
      inspection = new SkillInspection(application)
      inheritedSkills <- inspection.agentsSkills(inherited)
      emptySkills <- inspection.agentsSkills(empty)
      noReaderSkills <- inspection.agentsSkills(noReader)
      unavailable <- inspection.agentsSkills(broken).either
      unknown <- inspection.agentsSkills(AgentId("unknown")).either
      availability <- inspection.availability("guide")
      hiddenAvailability <- inspection.availability("hidden")
      missing <- inspection.availability("missing").either
      selection <- application.resolve(inherited)
    } yield {
      assertEquals(inheritedSkills.skills.map(_.name), Chunk("guide", "hidden"))
      assert(inheritedSkills.modelDiscoveryAvailable)
      assertEquals(emptySkills.skills, Chunk.empty)
      assertEquals(noReaderSkills.skills.map(_.name), Chunk("guide", "hidden"))
      assert(!noReaderSkills.modelDiscoveryAvailable)
      assert(unavailable.left.toOption.exists(_.code == ErrorCode.Rejected))
      assert(unknown.left.toOption.exists(_.code == ErrorCode.NotFound))
      assertEquals(availability.agents.map(_.agentId), Chunk(broken, empty, inherited, noReader))
      assertEquals(
        availability.agents.find(_.agentId == broken),
        Some(SkillAgentAvailability(broken, false, Some("Agent skill context unavailable")))
      )
      assertEquals(availability.agents.find(_.agentId == empty), Some(SkillAgentAvailability(empty, false, None)))
      assertEquals(availability.agents.find(_.agentId == noReader), Some(SkillAgentAvailability(noReader, true, None)))
      assertEquals(hiddenAvailability.agents, availability.agents)
      assert(missing.left.toOption.exists(_.code == ErrorCode.NotFound))
      assertEquals(inheritedSkills.skills.map(_.name), selection.context.skills.sortBy(_.name).map(_.name))
      assert(!availability.toJson.contains("private-prompt"))
      assert(!availability.toJson.contains("private-model"))
    }).provideLayer(Client.default)
  }

  testZ("maximum catalogs bound JSON-escaped display fields while preserving full skill details") {
    val description = "\u0001" * 1023 + "X"
    val metadataValue = "\u0001" * 1000
    val metadata = SkillMetadata(Json.Obj("author" -> Json.Str(metadataValue), "version" -> Json.Str(metadataValue)))
    val entries = (1 to 256).map(index => Skill(s"guide-$index", description, "MAIN", metadata = metadata)).toVector
    val largeCatalog = new SkillService {
      val catalog = entries
      // Over-approximate the filesystem's maximum: invalid sidecar plus duplicate-name warning per entry.
      val diagnostics = Vector.fill(512)(ResourceDiagnostic("\u0001" * 255, "Invalid SKILL.metadata.yaml"))
      def readSkill(name: String): IO[YallmoError, Skill] = ZIO.dieMessage("Inspection uses the cached catalog")
    }
    (for {
      application <- ServerApplication.make(config, Chunk.empty, skillService = Some(largeCatalog))
      inspection = new SkillInspection(application)
      effective <- inspection.agentsSkills(inherited)
      detail <- inspection.read(SkillsRead.Params(entries.head.name))
    } yield {
      val listed = inspection.list
      val catalogEnvelope = JsonRpc.Success(Json.Str("catalog"), listed).toJson.getBytes(UTF_8)
      val agentEnvelope = JsonRpc.Success(Json.Str("agent"), effective).toJson.getBytes(UTF_8)
      assert(catalogEnvelope.length < 1024 * 1024, clues(catalogEnvelope.length))
      assert(agentEnvelope.length < 1024 * 1024, clues(agentEnvelope.length))
      assertEquals(listed.skills.size, 256)
      assertEquals(listed.diagnostics.size, 512)
      assert(listed.skills.forall(_.description.length <= 256))
      assert(listed.skills.forall(_.author.exists(_.length <= 64)))
      assertEquals(detail.skill.description, description)
      assertEquals(detail.metadata, metadata.data)
    }).provideLayer(Client.default)
  }

  testZ("application-selected function removal controls model discovery, not raw capability allowlists") {
    val file = new AgentFunction[Any] {
      val definition = FilesystemToolDefinition.ReadFileDefinition
      def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
        ZIO.dieMessage("Inspection cannot execute tools")
    }
    (for {
      application <- ServerApplication.make(
        config,
        Chunk.empty,
        availableFunctions = Chunk(file),
        selectedFunctions = _ => Chunk(file),
        skillService = Some(service)
      )
      skills <- new SkillInspection(application).agentsSkills(inherited)
    } yield {
      assertEquals(skills.skills.map(_.name), Chunk("guide", "hidden"))
      assert(!skills.modelDiscoveryAvailable)
    }).provideLayer(Client.default)
  }
}
