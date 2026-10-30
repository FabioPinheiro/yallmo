package fmgp.yallmo.server.application

import fmgp.yallmo.fs.{FileSystem, FsRead}
import fmgp.yallmo.model.{ToolKey, YallmoError}
import fmgp.yallmo.orchestration.{AgentTool, AgentToolDefinition, ExternalToolCall, ExternalToolResult}
import fmgp.yallmo.tool.*
import zio.*

import java.nio.file.Path

/** Tools selected for one application startup.
  *
  * Only these tools are put in both the agent context and the runner. The availability value is retained for clients to
  * display.
  */
final case class StartupTools(
    available: Chunk[AgentTool[ApplicationTools.Environment]],
    availability: ToolAvailability,
    filesystem: Option[FsRead] = None
)

/** Client-neutral tool availability retained from application startup. */
final case class ToolAvailability(
    available: Chunk[ToolKey],
    unavailable: Chunk[(ToolKey, Chunk[String])]
)

/** Shared core tool composition for an explicitly supplied workspace. */
object ApplicationTools {
  type Environment = BashExecutor & GitClient & ScalaCodeExecutor

  val live: ULayer[Environment] =
    BashExecutor.live ++ GitClient.live ++ ScalaCodeExecutor.live

  /** Probes requirements without executing tools and logs availability once at startup. */
  def evaluate(workspace: Path): URIO[Environment, StartupTools] =
    for {
      filesystem <- FileSystem.open(workspace.toString).option
      hasWorkspace = filesystem.nonEmpty
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
          Option.unless(hasWorkspace)(ToolKey.ReadFile -> Chunk("workspace is not an accessible directory")),
          Option.unless(hasWorkspace)(ToolKey.ListFiles -> Chunk("workspace is not an accessible directory")),
          Option.unless(hasBash)(ToolKey.Bash -> Chunk("bash is not available on PATH")),
          gitStatus.left.toOption.map(reason => ToolKey.RepositoryChanges -> Chunk(reason)),
          Option.unless(hasScala)(ToolKey.Scalamode -> Chunk("Scala CLI (scala-cli) is not available on PATH")),
        ).flatten
      )
      available = Chunk.fromIterable(
        List[Option[AgentTool[Environment]]](
          filesystem.map(fs => bindFilesystem(new ReadFileTool, fs)),
          filesystem.map(fs => bindFilesystem(new ListFilesTool, fs)),
          Option.when(hasBash)(new BashTool(workspace)),
          gitStatus.toOption.map(_ => new RepositoryChangesTool(workspace)),
          Option.when(hasScala)(new ScalaTool()),
        ).flatten
      )
      startupTools = StartupTools(available, ToolAvailability(available.map(_.definition.key), unavailable), filesystem)
      _ <- ZIO.logDebug(s"Startup tool availability: ${startupTools.availability}")
      _ <- ZIO.logInfo(
        s"Available tools (${available.size}): ${available.map(_.definition.key).mkString(", ")}"
      )
    } yield startupTools

  /** Bind a read-only capability once; callers cannot substitute a different workspace at invocation time. */
  private[application] def bindFilesystem(tool: AgentTool[FsRead], fs: FsRead): AgentTool[Any] =
    new AgentTool[Any] {
      override val definition: AgentToolDefinition = tool.definition
      override def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
        tool.execute(call).provideEnvironment(ZEnvironment[FsRead](fs))
    }
}
