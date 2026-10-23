package fmgp.yallmo.tool

import fmgp.yallmo.orchestration.ExternalToolCall
import munit.FunSuite
import zio.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

final class RepositoryChangesToolIntegrationSuite extends FunSuite {
  test("repository changes separates unstaged and staged diffs in a temporary Git work tree") {
    val repository = Files.createTempDirectory("yallmo-git-tool-")
    val file = repository.resolve("note.txt")

    try {
      git(repository, "init", "--quiet")
      Files.writeString(file, "before\n", UTF_8)
      git(repository, "add", "note.txt")
      git(
        repository,
        "-c",
        "user.name=Yallmo Test",
        "-c",
        "user.email=test@yallmo.local",
        "commit",
        "--quiet",
        "-m",
        "initial"
      )

      Files.writeString(file, "after\n", UTF_8)
      val tool = new RepositoryChangesTool(repository)
      val unstaged = run(execute(tool, "unstaged"))
      assert(ujson.read(unstaged.text)("output").str.contains("-before"))
      assert(ujson.read(unstaged.text)("output").str.contains("+after"))

      git(repository, "add", "note.txt")
      val staged = run(execute(tool, "staged"))
      assert(ujson.read(staged.text)("output").str.contains("-before"))
      assert(ujson.read(staged.text)("output").str.contains("+after"))
      assertEquals(ujson.read(run(execute(tool, "unstaged")).text)("output").str, "")
    } finally deleteRecursively(repository)
  }

  private def execute(tool: RepositoryChangesTool, view: String) =
    tool.execute(ExternalToolCall("repositoryChanges", s"""{"view":"$view"}""")).provide(GitClient.live)

  private def git(workspace: Path, arguments: String*): Unit = {
    val process = new ProcessBuilder((Seq("git", "-C", workspace.toString) ++ arguments)*)
      .redirectErrorStream(true)
      .start()
    val output = new String(process.getInputStream.readAllBytes(), UTF_8)
    assertEquals(process.waitFor(), 0, clues(output))
  }

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then {
      val paths = Files.walk(path)
      try paths.iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }
}
