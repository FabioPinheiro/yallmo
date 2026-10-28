package fmgp.yallmo.server.application

import fmgp.yallmo.orchestration.AgentTool
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
    availability: ToolAvailability
)

/** Client-neutral tool availability retained from application startup. */
final case class ToolAvailability(
    available: Chunk[String],
    unavailable: Chunk[(String, Chunk[String])]
)

/** Shared core tool composition for an explicitly supplied workspace. */
object ApplicationTools {
  type Environment = WorkspaceFileSystem & BashExecutor & GitClient & ScalaCodeExecutor

  val live: ULayer[Environment] =
    WorkspaceFileSystem.live ++ BashExecutor.live ++ GitClient.live ++ ScalaCodeExecutor.live

  /** Probes requirements without executing tools and logs availability once at startup. */
  def evaluate(workspace: Path): URIO[Environment, StartupTools] =
    for {
      hasWorkspace <- WorkspaceFileSystem.isDirectory(workspace)
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
          Option.unless(hasWorkspace)("readFile" -> Chunk("workspace is not an accessible directory")),
          Option.unless(hasWorkspace)("listFiles" -> Chunk("workspace is not an accessible directory")),
          Option.unless(hasBash)("bash" -> Chunk("bash is not available on PATH")),
          gitStatus.left.toOption.map(reason => "repositoryChanges" -> Chunk(reason)),
          Option.unless(hasScala)("scalamode" -> Chunk("Scala CLI (scala-cli) is not available on PATH"))
        ).flatten
      )
      available = Chunk.fromIterable(
        List[Option[AgentTool[Environment]]](
          Option.when(hasWorkspace)(new ReadFileTool(workspace)),
          Option.when(hasWorkspace)(new ListFilesTool(workspace)),
          Option.when(hasBash)(new BashTool(workspace)),
          gitStatus.toOption.map(_ => new RepositoryChangesTool(workspace)),
          Option.when(hasScala)(new ScalaTool)
        ).flatten
      )
      startupTools = StartupTools(available, ToolAvailability(available.map(_.definition.key), unavailable))
      _ <- ZIO.logDebug(s"Startup tool availability: ${startupTools.availability}")
      _ <- ZIO.logInfo(
        s"Available tools (${available.size}): ${available.map(_.definition.key).mkString(", ")}"
      )
    } yield startupTools
}
