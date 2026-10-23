package fmgp.yallmo.tool

import zio.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** Filesystem operations required by the read-only workspace tools. */
trait WorkspaceFileSystem {
  def isDirectory(path: Path): UIO[Boolean]
  def readUtf8(path: Path): Task[String]
  def list(path: Path): Task[Chunk[String]]
}

object WorkspaceFileSystem {
  def isDirectory(path: Path): URIO[WorkspaceFileSystem, Boolean] =
    ZIO.serviceWithZIO(_.isDirectory(path))

  def readUtf8(path: Path): ZIO[WorkspaceFileSystem, Throwable, String] =
    ZIO.serviceWithZIO(_.readUtf8(path))

  def list(path: Path): ZIO[WorkspaceFileSystem, Throwable, Chunk[String]] =
    ZIO.serviceWithZIO(_.list(path))

  val live: ULayer[WorkspaceFileSystem] = ZLayer.succeed(new WorkspaceFileSystem {
    override def isDirectory(path: Path): UIO[Boolean] =
      ZIO.attemptBlocking(Files.isDirectory(path)).orElseSucceed(false)

    override def readUtf8(path: Path): Task[String] =
      ZIO.attemptBlocking(Files.readString(path, UTF_8))

    override def list(path: Path): Task[Chunk[String]] =
      ZIO.attemptBlocking {
        val stream = Files.list(path)
        try Chunk.fromIterable(stream.limit(200).map(_.getFileName.toString).sorted.toList.asScala)
        finally stream.close()
      }
  })
}

/** Bounded result of a local process invocation. */
final case class CommandOutput(
    exitCode: Int,
    timedOut: Boolean,
    output: String,
    truncated: Boolean
)

/** Executes a Bash command in a workspace. */
trait BashExecutor {
  def isAvailable: UIO[Boolean]
  def execute(command: String, workspace: Path, timeout: Duration, maxOutputBytes: Int): Task[CommandOutput]
}

object BashExecutor {
  def execute(
      command: String,
      workspace: Path,
      timeout: Duration,
      maxOutputBytes: Int
  ): ZIO[BashExecutor, Throwable, CommandOutput] =
    ZIO.serviceWithZIO(_.execute(command, workspace, timeout, maxOutputBytes))

  val live: ULayer[BashExecutor] = ZLayer.succeed(new BashExecutor {
    override val isAvailable: UIO[Boolean] = LocalProcess.isAvailable("bash")

    override def execute(
        command: String,
        workspace: Path,
        timeout: Duration,
        maxOutputBytes: Int
    ): Task[CommandOutput] =
      LocalProcess.run(Seq("bash", "-c", command), workspace, timeout, maxOutputBytes)
  })
}

sealed trait GitError extends RuntimeException
object GitError {
  final case class Unavailable(cause: Throwable)
      extends RuntimeException(s"Could not start git: ${cause.getMessage}", cause)
      with GitError

  final case class CommandFailed(message: String) extends RuntimeException(message) with GitError
}

/** Read-only Git operations used by repository-inspection tools. */
trait GitClient {
  def isAvailable: UIO[Boolean]
  def isWorkTree(workspace: Path): UIO[Boolean]
  def status(workspace: Path): IO[GitError, CommandOutput]
  def unstagedDiff(workspace: Path): IO[GitError, CommandOutput]
  def stagedDiff(workspace: Path): IO[GitError, CommandOutput]
}

object GitClient {
  val live: ULayer[GitClient] = ZLayer.succeed(new GitClient {
    private val timeout = 30.seconds
    private val maxOutputBytes = 64 * 1024

    override val isAvailable: UIO[Boolean] = LocalProcess.isAvailable("git")

    override def isWorkTree(workspace: Path): UIO[Boolean] =
      LocalProcess
        .run(Seq("git", "-C", workspace.toString, "rev-parse", "--is-inside-work-tree"), workspace, timeout, 1024)
        .map(result => result.exitCode == 0 && !result.timedOut && result.output.trim == "true")
        .catchAll(_ => ZIO.succeed(false))

    override def status(workspace: Path): IO[GitError, CommandOutput] =
      runGit(Seq("status", "--short", "--branch"), workspace)

    override def unstagedDiff(workspace: Path): IO[GitError, CommandOutput] =
      runGit(Seq("diff", "--no-ext-diff", "--no-textconv", "--"), workspace)

    override def stagedDiff(workspace: Path): IO[GitError, CommandOutput] =
      runGit(Seq("diff", "--staged", "--no-ext-diff", "--no-textconv", "--"), workspace)

    private def runGit(arguments: Seq[String], workspace: Path): IO[GitError, CommandOutput] =
      LocalProcess
        .run(Seq("git", "-C", workspace.toString, "--no-pager") ++ arguments, workspace, timeout, maxOutputBytes)
        .mapError(GitError.Unavailable.apply)
        .flatMap { result =>
          if result.timedOut then ZIO.fail(GitError.CommandFailed("git command timed out"))
          else if result.exitCode != 0 then
            ZIO.fail(GitError.CommandFailed(s"git command exited with ${result.exitCode}: ${result.output}"))
          else ZIO.succeed(result)
        }
  })
}

private[tool] object LocalProcess {
  def isAvailable(command: String): UIO[Boolean] =
    run(Seq(command, "--version"), Path.of("").toAbsolutePath.normalize, 2.seconds, 1024)
      .map(result => result.exitCode == 0 && !result.timedOut)
      .catchAll(_ => ZIO.succeed(false))

  def run(arguments: Seq[String], workspace: Path, timeout: Duration, maxOutputBytes: Int): Task[CommandOutput] =
    ZIO.attemptBlockingInterrupt {
      val outputFile = Files.createTempFile("yallmo-command-", ".log")
      var process: Option[Process] = None

      try {
        val started = new ProcessBuilder(arguments*)
          .directory(workspace.toFile)
          .redirectErrorStream(true)
          .redirectOutput(outputFile.toFile)
          .start()
        process = Some(started)

        val completed = started.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS)
        if !completed then {
          started.destroyForcibly()
          started.waitFor()
        }

        val (output, truncated) = readOutput(outputFile, maxOutputBytes)
        CommandOutput(started.exitValue(), timedOut = !completed, output, truncated)
      } finally {
        process.filter(_.isAlive).foreach(_.destroyForcibly())
        Files.deleteIfExists(outputFile)
      }
    }

  private def readOutput(outputFile: Path, maxOutputBytes: Int): (String, Boolean) = {
    val input = Files.newInputStream(outputFile)
    try {
      val bytes = input.readNBytes(maxOutputBytes + 1)
      val truncated = bytes.length > maxOutputBytes
      (new String(bytes.take(maxOutputBytes), UTF_8), truncated)
    } finally input.close()
  }
}
