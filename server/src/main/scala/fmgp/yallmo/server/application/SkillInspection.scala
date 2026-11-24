package fmgp.yallmo.server.application

import fmgp.yallmo.model.{AgentId, YallmoError}
import fmgp.yallmo.server.jsonrpc.JsonRpc
import fmgp.yallmo.server.protocol.YallmoProtocol.*
import fmgp.yallmo.skill.Skill
import fmgp.yallmo.tool.SkillsToolDefinition
import zio.*

/** Read-only projections of the startup catalog and effective agent contexts. Never executes tools or rereads skills.
  * Like tools.list, effective contexts use the existing lazy resolver, which may validate configured workspace roots.
  * Resolver failures are deliberately generic: configuration/provider errors may contain private filesystem paths.
  */
private[server] final class SkillInspection(application: ServerApplication) {
  private val service = application.skillService

  // Budget UTF-16 units conservatively: each can occupy up to six JSON bytes. Together with at most 256 skills
  // and 512 discovery warnings, these display bounds keep a filesystem catalog below the 1 MiB wire limit.
  // Complete descriptions are returned by read; complete metadata remains available in read.metadata.
  private def abbreviated(value: String, limit: Int): String =
    if value.length <= limit then value
    else {
      val prefix = value.take(limit - 1)
      val complete = if prefix.lastOption.exists(Character.isHighSurrogate) then prefix.dropRight(1) else prefix
      complete + "…"
    }

  private def summary(skill: Skill): SkillSummary = SkillSummary(
    skill.name,
    abbreviated(skill.description, 256),
    skill.disableModelInvocation,
    1 + skill.sections.size,
    skill.metadata.author.map(abbreviated(_, 64)),
    skill.metadata.version.map(abbreviated(_, 64))
  )

  def list: SkillsList.Result = SkillsList.Result(
    Chunk.fromIterable(service.catalog.sortBy(_.name).map(summary)),
    // Discovery diagnostics can embed OS errors and absolute storage roots. Expose only safe resource identifiers
    // and a stable category, retaining detailed diagnostics in the startup logs rather than the wire response.
    Chunk.fromIterable(service.diagnostics.map { diagnostic =>
      val message =
        if diagnostic.reason.startsWith("Invalid SKILL.metadata.yaml") then "Invalid SKILL.metadata.yaml"
        else if diagnostic.reason.startsWith("Invalid SKILL.md frontmatter") then "Invalid SKILL.md frontmatter"
        else if diagnostic.reason.startsWith("Duplicate name") then "Duplicate skill name"
        else if diagnostic.reason.startsWith("Cannot load sections") then "Cannot load skill sections"
        else if diagnostic.reason.startsWith("Cannot read SKILL.md") then "Cannot read SKILL.md"
        else "Skill discovery unavailable"
      val resource = diagnostic.entry match {
        case value if value.nonEmpty && value != "." && value != ".." && !value.exists(c => c == '/' || c == '\\') =>
          value
        case _ => "skills"
      }
      SkillDiagnostic(abbreviated(resource, 64), message)
    })
  )

  private def find(name: String): IO[JsonRpc.Error, Skill] =
    ZIO
      .fromOption(service.catalog.find(_.name == name))
      .orElseFail(JsonRpc.Error(ErrorCode.NotFound, "Skill not found"))

  def read(request: SkillsRead.Params): IO[JsonRpc.Error, SkillsRead.Result] =
    for {
      skill <- find(request.name)
      content <- request.section match {
        case None          => ZIO.succeed(skill.instructions)
        case Some(section) =>
          ZIO
            .fromOption(skill.sections.get(section))
            .orElseFail(JsonRpc.Error(ErrorCode.NotFound, "Skill section not found"))
      }
    } yield SkillsRead.Result(
      summary(skill).copy(description = skill.description),
      content,
      Chunk.fromIterable(skill.sections.keys.toVector.sorted),
      skill.metadata.data
    )

  def agentsSkills(id: AgentId): IO[JsonRpc.Error, AgentsSkills.Result] =
    application
      .resolve(id)
      .mapError {
        case _: YallmoError.UnknownAgent =>
          JsonRpc.Error(ErrorCode.NotFound, "No configured skill context for this agent")
        case _ => JsonRpc.Error(ErrorCode.Rejected, "Agent skill context unavailable")
      }
      .map { selection =>
        AgentsSkills.Result(
          Chunk.fromIterable(selection.context.skills.sortBy(_.name).map(summary)),
          selection.functions.exists(_.definition.name == SkillsToolDefinition.ReadSkill)
        )
      }

  def availability(name: String): IO[JsonRpc.Error, SkillsAvailability.Result] =
    find(name) *> ZIO
      .foreach(application.agentSummaries) { agent =>
        application
          .resolve(agent.id)
          .fold(
            _ => SkillAgentAvailability(agent.id, false, Some("Agent skill context unavailable")),
            selection => SkillAgentAvailability(agent.id, selection.context.skills.exists(_.name == name), None)
          )
      }
      .map(values => SkillsAvailability.Result(Chunk.fromIterable(values)))
}
