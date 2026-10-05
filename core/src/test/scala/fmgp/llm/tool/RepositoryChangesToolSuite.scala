package fmgp.yallmo.tool

import fmgp.yallmo.model.YallmoError
import fmgp.yallmo.orchestration.{ExternalToolCall}
import munit.FunSuite
import zio.*

import java.nio.file.Path
import scala.collection.mutable.ArrayBuffer

final class RepositoryChangesToolSuite extends FunSuite {
  private val workspace = Path.of("/workspace")

  test("repository changes selects only the requested read-only Git view") {
    val calls = ArrayBuffer.empty[String]
    val tool = new RepositoryChangesTool(workspace)
    val git = recordingGitClient(calls)

    val unstaged = run(execute(tool, "unstaged", git))
    val staged = run(execute(tool, "staged", git))
    val status = run(execute(tool, "status", git))

    assertEquals(calls.toList, List("unstaged", "staged", "status"))
    assertEquals(ujson.read(unstaged.text)("output").str, "unstaged output")
    assertEquals(ujson.read(staged.text)("view").str, "staged")
    assertEquals(ujson.read(status.text)("view").str, "status")
  }

  test("repository changes reports a missing Git executable as unavailable") {
    val tool = new RepositoryChangesTool(workspace)
    val unavailable = new GitClient {
      override val isAvailable: UIO[Boolean] = ZIO.succeed(false)
      override def isWorkTree(_workspace: Path): UIO[Boolean] = ZIO.succeed(false)
      override def status(_workspace: Path): IO[GitError, CommandOutput] =
        ZIO.fail(GitError.Unavailable(new RuntimeException("git missing")))
      override def unstagedDiff(_workspace: Path): IO[GitError, CommandOutput] =
        ZIO.fail(GitError.Unavailable(new RuntimeException("git missing")))
      override def stagedDiff(_workspace: Path): IO[GitError, CommandOutput] =
        ZIO.fail(GitError.Unavailable(new RuntimeException("git missing")))
    }

    val result = run(execute(tool, "status", unavailable).either)

    assertEquals(result.left.toOption.map(_.getClass), Some(classOf[YallmoError.ToolUnavailable]))
  }

  private def execute(
      tool: RepositoryChangesTool,
      view: String,
      git: GitClient
  ): ZIO[Any, YallmoError, fmgp.yallmo.orchestration.ExternalToolResult] =
    tool.execute(ExternalToolCall("repositoryChanges", s"""{"view":"$view"}""")).provide(ZLayer.succeed(git))

  private def recordingGitClient(calls: ArrayBuffer[String]): GitClient = new GitClient {
    override val isAvailable: UIO[Boolean] = ZIO.succeed(true)
    override def isWorkTree(_workspace: Path): UIO[Boolean] = ZIO.succeed(true)
    override def status(_workspace: Path): IO[GitError, CommandOutput] = result("status", calls)
    override def unstagedDiff(_workspace: Path): IO[GitError, CommandOutput] = result("unstaged", calls)
    override def stagedDiff(_workspace: Path): IO[GitError, CommandOutput] = result("staged", calls)

    private def result(view: String, calls: ArrayBuffer[String]): IO[GitError, CommandOutput] =
      ZIO.succeed {
        calls += view
        CommandOutput(0, timedOut = false, s"$view output", truncated = false)
      }
  }

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }
}
