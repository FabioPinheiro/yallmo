package fmgp.yallmo.repl

import fmgp.yallmo.orchestration.AgentTool
import fmgp.yallmo.tool.*
import zio.*

import java.nio.file.Path

/** Tools selected for one REPL startup.
  *
  * Only these tools are put in both the agent context and the runner. The availability value is retained solely for the
  * `/tools` command.
  */
final case class ReplStartupTools(
    available: Chunk[AgentTool[ReplTools.Environment]],
    availability: ReplToolAvailability
)

/** Client-neutral tool availability retained from REPL startup. */
final case class ReplToolAvailability(
    available: Chunk[String],
    unavailable: Chunk[(String, Chunk[String])]
)

/** REPL tools constrained to the process working directory. */
object ReplTools {
  type Environment = WorkspaceFileSystem & BashExecutor & GitClient

  private val workspace = Path.of("").toAbsolutePath.normalize
  private val readFile: AgentTool[Environment] = new ReadFileTool(workspace)
  private val listFiles: AgentTool[Environment] = new ListFilesTool(workspace)
  private val bash: AgentTool[Environment] = new BashTool(workspace)
  private val repositoryChanges: AgentTool[Environment] = new RepositoryChangesTool(workspace)

  /** Checks the few requirements of this REPL's fixed tool set once at startup. */
  def evaluate: URIO[Environment, ReplStartupTools] =
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
    } yield {
      val unavailable = Chunk.fromIterable(
        List(
          Option.unless(hasWorkspace)("readFile" -> Chunk("workspace is not an accessible directory")),
          Option.unless(hasWorkspace)("listFiles" -> Chunk("workspace is not an accessible directory")),
          Option.unless(hasBash)("bash" -> Chunk("bash is not available on PATH")),
          gitStatus.left.toOption.map(reason => "repositoryChanges" -> Chunk(reason))
        ).flatten
      )
      val available = Chunk.fromIterable(
        List(
          Option.when(hasWorkspace)(readFile),
          Option.when(hasWorkspace)(listFiles),
          Option.when(hasBash)(bash),
          gitStatus.toOption.map(_ => repositoryChanges)
        ).flatten
      )
      ReplStartupTools(available, ReplToolAvailability(available.map(_.definition.key), unavailable))
    }
}
