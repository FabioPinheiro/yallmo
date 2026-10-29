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
  // Retain legacy service wiring without registering the deprecated, unsandboxed tool.
  @scala.annotation.nowarn("cat=deprecation&msg=UNSAFE with no sandbox")
  type Environment = WorkspaceFileSystem & BashExecutor & GitClient & ScalaCodeExecutor & ScalaCodeDynamicExecutor

  @scala.annotation.nowarn("cat=deprecation&msg=UNSAFE with no sandbox")
  val live: ULayer[Environment] =
    WorkspaceFileSystem.live ++ BashExecutor.live ++ GitClient.live ++ ScalaCodeExecutor.live ++ ScalaCodeDynamicExecutor.live

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
      // UNSAFE hasDynamicScala <- ZIO.serviceWithZIO[ScalaCodeDynamicExecutor](_.isAvailable)
      unavailable = Chunk.fromIterable(
        List(
          Option.unless(hasWorkspace)("readFile" -> Chunk("workspace is not an accessible directory")),
          Option.unless(hasWorkspace)("listFiles" -> Chunk("workspace is not an accessible directory")),
          Option.unless(hasBash)("bash" -> Chunk("bash is not available on PATH")),
          gitStatus.left.toOption.map(reason => "repositoryChanges" -> Chunk(reason)),
          Option.unless(hasScala)("scalamode" -> Chunk("Scala CLI (scala-cli) is not available on PATH")),
          // UNSAFE Option.unless(hasDynamicScala)("scalaunsafe" -> Chunk("Scala CLI (scala-cli) is not available on PATH"))
        ).flatten
      )
      available = Chunk.fromIterable(
        List[Option[AgentTool[Environment]]](
          Option.when(hasWorkspace)(new ReadFileTool(workspace)),
          Option.when(hasWorkspace)(new ListFilesTool(workspace)),
          Option.when(hasBash)(new BashTool(workspace)),
          gitStatus.toOption.map(_ => new RepositoryChangesTool(workspace)),
          Option.when(hasScala)(new ScalaTool()),
          // UNSAFE Option.when(hasDynamicScala)(dynamicScalaTool(workspace))
        ).flatten
      )
      startupTools = StartupTools(available, ToolAvailability(available.map(_.definition.key), unavailable))
      _ <- ZIO.logDebug(s"Startup tool availability: ${startupTools.availability}")
      _ <- ZIO.logInfo(
        s"Available tools (${available.size}): ${available.map(_.definition.key).mkString(", ")}"
      )
    } yield startupTools

  // UNSAFE /** Adapts the current caller's filesystem at execution time without capturing services during discovery. */
  // private def dynamicScalaTool(workspace: Path): AgentTool[Environment] = {
  //   val path = workspace.toAbsolutePath.normalize.toString
  //   val config = ScalaCodeDynamicExecutor.Config(
  //     apiDescription = s"""fmgp.yallmo.scalacode.FileSystem:
  //       |  def isDirectory(path: String): zio.UIO[Boolean]
  //       |  def readUtf8(path: String): zio.Task[String]
  //       |  def list(path: String): zio.Task[zio.Chunk[String]]
  //       |Workspace: $path
  //       |Use absolute paths. Relative paths use the server working directory, not the workspace.
  //       |Example: ZIO.serviceWithZIO[fmgp.yallmo.scalacode.FileSystem](_.list(${ujson.write(path)}))
  //       |""".stripMargin
  //   )
  //   val tool = new ScalaDynamicTool[ScriptFileSystem](config)
  //   new AgentTool[Environment] {
  //     override val definition: AgentToolDefinition = tool.definition

  //     override def execute(call: ExternalToolCall): ZIO[Environment, YallmoError, ExternalToolResult] =
  //       tool.execute(call).provideSomeEnvironment[Environment] { environment =>
  //         ZEnvironment[ScalaCodeDynamicExecutor](environment.get[ScalaCodeDynamicExecutor])
  //           .add[ScriptFileSystem](new ScalaCodeFileSystem(environment.get[WorkspaceFileSystem]))
  //       }
  //   }
  // }
}
