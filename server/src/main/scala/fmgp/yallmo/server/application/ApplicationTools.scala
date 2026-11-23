package fmgp.yallmo.server.application

import fmgp.yallmo.tool.scalamode.{ScalaCodeExecutor, ScalaCodeExecutors, ScalaTool, ScalamodeToolDefinition}

import fmgp.yallmo.skill.{ReadSkillTool, SkillService}
import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.tool.*

import fmgp.yallmo.fs.{FileSystem, FsRead, FsWrite}
import fmgp.yallmo.orchestration.{AgentFunction, AgentTool, ExternalFunctionCall, ExternalFunctionResult}
import fmgp.yallmo.tool.sdlc.{SdlcService, SdlcTools}
import zio.*

import java.nio.file.Path

/** Tools selected for one application startup.
  *
  * Only these tools are put in both the agent context and the runner. The availability value is retained for clients to
  * display.
  */
final case class StartupTools(
    functions: Chunk[AgentFunction[ApplicationTools.Environment]],
    availability: ToolAvailability,
    filesystem: Option[FsRead] = None,
    filesystemWrite: Option[FsWrite] = None
) {
  def tools: Chunk[AgentTool[ApplicationTools.Environment]] =
    AgentTool.group(ApplicationTools.ToolDefinitions, functions)
}

/** Client-neutral availability: individual functions retain their own failure reasons and explicit tool ownership. */
final case class ToolAvailability(
    availableFunctions: Chunk[FunctionKey],
    unavailableFunctions: Chunk[(FunctionKey, Chunk[String])],
    ownership: Map[FunctionKey, ToolKey] = Map.empty
) {
  def availableTools: Chunk[(ToolKey, Chunk[FunctionKey])] =
    availableFunctions.flatMap(ownership.get).distinct.map { toolKey =>
      toolKey -> availableFunctions.filter(key => ownership.get(key).contains(toolKey))
    }
}

object ToolAvailability {
  def from(
      available: Chunk[FunctionToolDefinition],
      unavailable: Chunk[(FunctionKey, Chunk[String])],
      catalog: Chunk[ToolDefinition] = Chunk.empty
  ): ToolAvailability = ToolAvailability(
    available.map(_.name),
    unavailable,
    catalog.flatMap(tool => tool.functions.map(function => function.name -> tool.name)).toMap
  )
}

/** Core tool composition: process/read tools use the workspace; SDLC uses shared server storage. */
object ApplicationTools {
  type Environment = BashExecutor & GitClient & ScalaCodeExecutor

  val Definitions: Chunk[FunctionToolDefinition] =
    Chunk(
      ReadFileTool.definition,
      ListFilesTool.definition,
      BashTool.definition,
      RepositoryChangesTool.definition,
      ScalaTool.definition
    ) ++ SdlcTools.Definitions :+ SkillsToolDefinition.ReadSkillDefinition

  val ToolDefinitions: Chunk[ToolDefinition] = Chunk(
    FilesystemToolDefinition,
    BashToolDefinition,
    RepositoryChangesToolDefinition,
    ScalamodeToolDefinition,
    SdlcToolDefinition,
    SkillsToolDefinition
  )

  val live: ULayer[Environment] =
    BashExecutor.live ++ GitClient.live ++ ScalaCodeExecutors.live

  /** Probes requirements without executing tools and logs availability once at startup. */
  def evaluate(
      workspace: Path,
      storageRoot: Option[Path],
      sharedSdlc: Option[SdlcService] = None,
      skills: SkillService = SkillService.empty
  ): URIO[Environment, StartupTools] =
    for {
      filesystem <- FileSystem.open(workspace.toString).option
      hasWorkspace = filesystem.nonEmpty
      storage <- ZIO.foreach(storageRoot)(root => FileSystem.open(root.toString).option).map(_.flatten)
      service <- sharedSdlc match {
        case Some(value) => ZIO.some(value)
        case None        => ZIO.foreach(storage)(fs => SdlcService.make(fs, fs))
      }
      sdlc = service.map(SdlcTools.make)
      storageReason =
        if storageRoot.isEmpty then "SDLC tools require server filesystem storage"
        else "server storage is not an accessible directory"
      hasBash <- ZIO.serviceWithZIO[BashExecutor](_.isAvailable)
      gitStatus <- ZIO.serviceWithZIO[GitClient] { git =>
        git.isAvailable.flatMap {
          case false => ZIO.succeed(Left("git is not available on PATH"))
          case true  =>
            git.isWorkTree(workspace).map(isWorkTree => Either.cond(isWorkTree, (), "workspace is not a Git work tree"))
        }
      }
      hasScala <- ZIO.serviceWithZIO[ScalaCodeExecutor](_.isAvailable)
      unavailable = Chunk.fromIterable(
        List(
          Option.unless(hasWorkspace)(
            FilesystemToolDefinition.ReadFile -> Chunk("workspace is not an accessible directory")
          ),
          Option.unless(hasWorkspace)(
            FilesystemToolDefinition.ListFiles -> Chunk("workspace is not an accessible directory")
          ),
          Option.unless(hasBash)(BashToolDefinition.Execute -> Chunk("bash is not available on PATH")),
          gitStatus.left.toOption.map(reason => RepositoryChangesToolDefinition.Inspect -> Chunk(reason)),
          Option.unless(hasScala)(
            ScalamodeToolDefinition.Execute -> Chunk("Scala CLI (scala-cli) is not available on PATH")
          ),
        ).flatten
      ) ++ (if service.isEmpty then SdlcTools.Definitions.map(definition => definition.name -> Chunk(storageReason))
            else Chunk.empty)
      available = Chunk.fromIterable(
        List[Option[AgentFunction[Environment]]](
          filesystem.map(fs => bindFilesystem(new ReadFileTool, fs)),
          filesystem.map(fs => bindFilesystem(new ListFilesTool, fs)),
          Option.when(hasBash)(new BashTool(workspace)),
          gitStatus.toOption.map(_ => new RepositoryChangesTool(workspace)),
          Option.when(hasScala)(new ScalaTool()),
        ).flatten
      )
      reader =
        if skills.catalog.exists(!_.disableModelInvocation) then Chunk(ReadSkillTool(skills.catalog)) else Chunk.empty
      allAvailable = available ++ sdlc.fold(Chunk.empty[AgentFunction[Any]])(_.functions) ++ reader
      startupTools = StartupTools(
        allAvailable,
        ToolAvailability.from(allAvailable.map(_.definition), unavailable, ToolDefinitions),
        filesystem,
        filesystem
      )
      summary = startupTools.availability.availableTools
        .map { case (key, functions) =>
          s"${key.value}[${functions.map(_.value).mkString(", ")}]"
        }
        .mkString(", ")
      _ <- ZIO.logDebug(s"Startup tool availability: ${startupTools.availability}")
      _ <- ZIO.logInfo(
        s"Available tools (${startupTools.tools.size}), functions (${allAvailable.size}): $summary"
      )
    } yield startupTools

  /** Reads use the selected private root. Process tools retain the workspace. */
  private[application] def selected(
      tools: Chunk[AgentFunction[Any]],
      filesystem: Option[FsRead]
  ): Chunk[AgentFunction[Any]] = tools.map { tool =>
    filesystem match {
      case Some(fs) if tool.definition.name == FilesystemToolDefinition.ReadFile => bindFilesystem(new ReadFileTool, fs)
      case Some(fs) if tool.definition.name == FilesystemToolDefinition.ListFiles =>
        bindFilesystem(new ListFilesTool, fs)
      case _ => tool
    }
  }

  /** Bind a read-only capability once; callers cannot substitute a different workspace at invocation time. */
  private[application] def bindFilesystem(tool: AgentFunction[FsRead], fs: FsRead): AgentFunction[Any] =
    new AgentFunction[Any] {
      override val definition: FunctionToolDefinition = tool.definition
      override def execute(call: ExternalFunctionCall): IO[YallmoError, ExternalFunctionResult] =
        tool.execute(call).provideEnvironment(ZEnvironment[FsRead](fs))
    }
}
