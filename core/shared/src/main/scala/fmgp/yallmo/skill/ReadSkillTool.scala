package fmgp.yallmo.skill

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.orchestration.{AgentFunction, ExternalFunctionCall, ExternalFunctionResult, StaticContext}
import fmgp.yallmo.tool.{FunctionToolDefinition, SkillsToolDefinition, ToolDefinition}
import zio.*
import zio.json.*

/** Captures only the selected agent's authorized immutable catalog. Narrowing never grants new names or contents. */
final class ReadSkillTool private (catalog: Seq[Skill]) extends AgentFunction[Any] {
  val definition: FunctionToolDefinition = SkillsToolDefinition.ReadSkillDefinition

  private[yallmo] def readableSkills(skills: Seq[Skill]): Seq[Skill] = skills.filter(catalog.contains)

  def narrow(skills: Seq[Skill]): ReadSkillTool =
    new ReadSkillTool(readableSkills(skills))

  def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
    for {
      _ <- ZIO
        .fromEither(FunctionToolDefinition.validateArgument(call.argumentsJson, definition.inputSchema))
        .mapError(YallmoError.InvalidToolArguments(definition.name, _))
      args <- ZIO
        .fromEither(call.argumentsJson.fromJson[ReadSkillTool.Arguments])
        .mapError(YallmoError.InvalidToolArguments(definition.name, _))
      _ <- ZIO
        .fail(YallmoError.InvalidToolArguments(definition.name, "name and section must not be blank"))
        .when(args.name.trim.isEmpty || args.section.exists(_.trim.isEmpty))
      skill <- ZIO.fromOption(catalog.find(_.name == args.name)).orElseFail(YallmoError.UnknownSkill(args.name))
      result <- args.section match {
        case None =>
          ZIO.succeed(ReadSkillTool.MainResult(skill.name, skill.instructions, skill.sections.keys.toSeq.sorted).toJson)
        case Some(section) =>
          ZIO
            .fromOption(skill.sections.get(section))
            .orElseFail(YallmoError.UnknownSkillSection(skill.name, section))
            .map(content => ReadSkillTool.SectionResult(skill.name, section, content).toJson)
      }
    } yield ExternalFunctionResult(result)
}

object ReadSkillTool {
  @jsonNoExtraFields
  private final case class Arguments(name: String, section: Option[String] = None) derives JsonCodec
  final case class MainResult(name: String, content: String, availableSections: Seq[String]) derives JsonCodec
  final case class SectionResult(name: String, section: String, content: String) derives JsonCodec

  def apply(authorizedSkills: Seq[Skill]): ReadSkillTool = new ReadSkillTool(authorizedSkills)

  /** A definition alone is insufficient to advertise activation. Preserve skill IDs for separate policy validation. */
  private[yallmo] def executableContext[R](
      context: StaticContext[R],
      functions: Iterable[AgentFunction[R]]
  ): StaticContext[R] = {
    val reader = functions.collectFirst { case reader: ReadSkillTool => reader }
    reader match {
      case Some(value) => context.copy(skills = Chunk.fromIterable(value.readableSkills(context.skills)))
      case None        =>
        val permitted = context.functions.filterNot(_.name == SkillsToolDefinition.ReadSkill)
        context.copy(functions = permitted, tools = ToolDefinition.available(context.tools, permitted))
    }
  }
}
