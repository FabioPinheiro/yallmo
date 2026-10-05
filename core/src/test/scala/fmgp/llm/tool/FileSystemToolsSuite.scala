package fmgp.yallmo.tool

import fmgp.yallmo.orchestration.ExternalToolCall
import munit.FunSuite
import zio.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

final class FileSystemToolsSuite extends FunSuite {
  test("workspace tools resolve paths relative to the workspace, including parent traversal") {
    val parent = Files.createTempDirectory("yallmo-tools-")
    val workspace = parent.resolve("workspace")
    val file = workspace.resolve("note.txt")
    val directory = workspace.resolve("docs")
    val parentFile = parent.resolve("outside.txt")
    Files.createDirectory(workspace)
    Files.writeString(file, "hello", UTF_8)
    Files.createDirectory(directory)
    Files.writeString(parentFile, "outside", UTF_8)

    try {
      val read = new ReadFileTool(workspace)
      val list = new ListFilesTool(workspace)

      assertEquals(
        run(
          read.execute(ExternalToolCall("readFile", """{"path":"note.txt"}""")).provide(WorkspaceFileSystem.live)
        ).text,
        "hello"
      )
      assertEquals(
        run(
          read.execute(ExternalToolCall("readFile", """{"path":"../outside.txt"}""")).provide(WorkspaceFileSystem.live)
        ).text,
        "outside"
      )
      assertEquals(
        run(list.execute(ExternalToolCall("listFiles", """{"path":"."}""")).provide(WorkspaceFileSystem.live)).text,
        "docs\nnote.txt"
      )
    } finally {
      Files.deleteIfExists(parentFile)
      Files.deleteIfExists(file)
      Files.deleteIfExists(directory)
      Files.deleteIfExists(workspace)
      Files.deleteIfExists(parent)
    }
  }

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }
}
