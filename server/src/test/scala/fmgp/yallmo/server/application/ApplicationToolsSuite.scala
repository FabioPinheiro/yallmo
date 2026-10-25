package fmgp.yallmo.server.application

import fmgp.yallmo.orchestration.ExternalToolCall
import fmgp.yallmo.tool.*
import java.nio.file.Path
import munit.FunSuite
import zio.*

final class ApplicationToolsSuite extends FunSuite {
  private val workspace = Path.of("/explicit/application-workspace")
  private val allKeys = Chunk("readFile", "listFiles", "bash", "repositoryChanges", "scala")

  test("startup selects only tools whose requirements pass without executing tools") {
    val (tools, calls) = run {
      for {
        services <- probes(git = false)
        tools <- ApplicationTools.evaluate(workspace).provideLayer(services.layer)
        calls <- services.calls.get
      } yield (tools, calls)
    }
    assertEquals(tools.available.map(_.definition.key), Chunk("readFile", "listFiles", "bash", "scala"))
    assertEquals(
      tools.availability,
      ToolAvailability(
        Chunk("readFile", "listFiles", "bash", "scala"),
        Chunk("repositoryChanges" -> Chunk("git is not available on PATH"))
      )
    )
    assertEquals(calls, Vector(s"directory:$workspace", "bash.available", "git.available", "scala.available"))
  }

  test("explicit workspace reaches directory and work-tree probes in startup order") {
    val otherWorkspace = Path.of("/another/application-workspace")
    val (first, second, calls) = run {
      for {
        services <- probes()
        first <- ApplicationTools.evaluate(workspace).provideLayer(services.layer)
        second <- ApplicationTools.evaluate(otherWorkspace).provideLayer(services.layer)
        calls <- services.calls.get
      } yield (first, second, calls)
    }
    assertEquals(first.available.map(_.definition.key), allKeys)
    assertEquals(first.availability, ToolAvailability(allKeys, Chunk.empty))
    assertEquals(second.availability, first.availability)
    assertEquals(
      calls,
      Vector(workspace, otherWorkspace).flatMap(path =>
        Vector(s"directory:$path", "bash.available", "git.available", s"git.worktree:$path", "scala.available")
      )
    )
  }

  test("unavailable directory and executables retain ordered availability reasons") {
    val (tools, calls) = run {
      for {
        services <- probes(directory = false, bash = false, git = false, scala = false)
        tools <- ApplicationTools.evaluate(workspace).provideLayer(services.layer)
        calls <- services.calls.get
      } yield (tools, calls)
    }
    assertEquals(tools.available, Chunk.empty)
    assertEquals(
      tools.availability,
      ToolAvailability(
        Chunk.empty,
        Chunk(
          "readFile" -> Chunk("workspace is not an accessible directory"),
          "listFiles" -> Chunk("workspace is not an accessible directory"),
          "bash" -> Chunk("bash is not available on PATH"),
          "repositoryChanges" -> Chunk("git is not available on PATH"),
          "scala" -> Chunk("Scala CLI (scala-cli) is not available on PATH")
        )
      )
    )
    assertEquals(calls, Vector(s"directory:$workspace", "bash.available", "git.available", "scala.available"))
  }

  test("installed git outside a work tree is omitted with its specific reason") {
    val tools = run {
      for {
        services <- probes(worktree = false)
        tools <- ApplicationTools.evaluate(workspace).provideLayer(services.layer)
      } yield tools
    }
    assertEquals(tools.available.map(_.definition.key), Chunk("readFile", "listFiles", "bash", "scala"))
    assertEquals(
      tools.availability.unavailable,
      Chunk("repositoryChanges" -> Chunk("workspace is not a Git work tree"))
    )
  }

  test("constructed workspace tools use the supplied workspace with fake services only") {
    val calls = run {
      for {
        services <- probes(allowExecution = true)
        tools <- ApplicationTools.evaluate(workspace).provideLayer(services.layer)
        _ <- services.calls.set(Vector.empty)
        _ <- ZIO
          .foreachDiscard(
            Chunk(
              ExternalToolCall("readFile", """{"path":"notes.txt"}"""),
              ExternalToolCall("listFiles", """{"path":"src"}"""),
              ExternalToolCall("bash", """{"command":"echo fake"}"""),
              ExternalToolCall("repositoryChanges", """{"view":"status"}""")
            )
          )(call => tools.available.find(_.definition.key == call.toolKey).get.execute(call))
          .provideLayer(services.layer)
        calls <- services.calls.get
      } yield calls
    }
    assertEquals(
      calls,
      Vector(
        s"read:${workspace.resolve("notes.txt")}",
        s"list:${workspace.resolve("src")}",
        s"bash:$workspace",
        s"git.status:$workspace"
      )
    )
  }

  private final case class Probes(calls: Ref[Vector[String]], layer: ULayer[ApplicationTools.Environment])

  private def probes(
      directory: Boolean = true,
      bash: Boolean = true,
      git: Boolean = true,
      worktree: Boolean = true,
      scala: Boolean = true,
      allowExecution: Boolean = false
  ): UIO[Probes] = Ref.make(Vector.empty[String]).map { calls =>
    def record(value: String): UIO[Unit] = calls.update(_ :+ value)
    def execution(value: String): UIO[CommandOutput] =
      if allowExecution then record(value).as(CommandOutput(0, false, "fake output", false))
      else ZIO.dieMessage(s"Tool discovery must not execute $value")

    val filesystem = new WorkspaceFileSystem {
      def isDirectory(path: Path): UIO[Boolean] = record(s"directory:$path").as(directory)
      def readUtf8(path: Path): Task[String] = execution(s"read:$path").map(_.output)
      def list(path: Path): Task[Chunk[String]] = execution(s"list:$path").as(Chunk("fake.txt"))
    }
    val shell = new BashExecutor {
      def isAvailable: UIO[Boolean] = record("bash.available").as(bash)
      def execute(command: String, path: Path, timeout: Duration, maxOutputBytes: Int): Task[CommandOutput] = {
        val _ = (command, timeout, maxOutputBytes)
        execution(s"bash:$path")
      }
    }
    val repository = new GitClient {
      def isAvailable: UIO[Boolean] = record("git.available").as(git)
      def isWorkTree(path: Path): UIO[Boolean] = record(s"git.worktree:$path").as(worktree)
      def status(path: Path): IO[GitError, CommandOutput] = execution(s"git.status:$path")
      def unstagedDiff(path: Path): IO[GitError, CommandOutput] = execution(s"git.unstaged:$path")
      def stagedDiff(path: Path): IO[GitError, CommandOutput] = execution(s"git.staged:$path")
    }
    val compiler = new ScalaCodeExecutor {
      def isAvailable: UIO[Boolean] = record("scala.available").as(scala)
      def execute(code: String, timeout: Duration, maxOutputBytes: Int): Task[CommandOutput] = {
        val _ = (code, timeout, maxOutputBytes)
        ZIO.dieMessage("Tool discovery must not execute Scala")
      }
    }
    Probes(
      calls,
      ZLayer.succeed(filesystem) ++ ZLayer.succeed(shell) ++ ZLayer.succeed(repository) ++ ZLayer.succeed(compiler)
    )
  }

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
