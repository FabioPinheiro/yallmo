package fmgp.yallmo.tool

import fmgp.yallmo.fs.{FileSystem, FsRead}
import fmgp.yallmo.model.{ToolKey, YallmoError}
import fmgp.yallmo.orchestration.ExternalToolCall
import java.nio.file.{Files, Path}
import java.util.Comparator
import munit.FunSuite
import zio.*

final class FileSystemToolsSuite extends FunSuite {
  test("workspace tools read and list relative to the configured root") {
    withWorkspace { (workspace, fs) =>
      Files.writeString(workspace.resolve("note.txt"), "hello")
      Files.createDirectory(workspace.resolve("docs"))
      assertEquals(run(read(fs, "note.txt")).text, "hello")
      assertEquals(run(list(fs, ".")).text, "docs\nnote.txt")
    }
  }

  test("workspace tools reject traversal, absolute paths, empty paths and symlinks") {
    withWorkspace { (workspace, fs) =>
      val outside = Files.writeString(workspace.getParent.resolve("outside.txt"), "outside")
      Files.createSymbolicLink(workspace.resolve("link.txt"), outside)
      for path <- List("../outside.txt", outside.toString, "", "link.txt", "bad\u0000path") do {
        assert(run(read(fs, path).either).swap.toOption.get.isInstanceOf[YallmoError.ToolExecutionFailed])
        assert(run(list(fs, path).either).isLeft)
      }
      // Listing must not silently follow or expose a symlink entry either.
      assert(run(list(fs, ".").either).isLeft)
    }
  }

  test("missing files and invalid arguments remain tool failures rather than defects") {
    withWorkspace { (_, fs) =>
      val missing = run(read(fs, "missing.txt").either).swap.toOption.get
      assert(missing.isInstanceOf[YallmoError.ToolExecutionFailed])
      assert(missing.getMessage.contains("NotFound"))
      for arguments <- List("{}", "{\"path\":42}", "not json") do {
        val result = run(
          new ReadFileTool()
            .execute(ExternalToolCall(ToolKey.ReadFile, arguments))
            .provideEnvironment(ZEnvironment[FsRead](fs))
            .either
        )
        assert(result.swap.toOption.get.isInstanceOf[YallmoError.InvalidToolArguments])
        val listing = run(
          new ListFilesTool()
            .execute(ExternalToolCall(ToolKey.ListFiles, arguments))
            .provideEnvironment(ZEnvironment[FsRead](fs))
            .either
        )
        assert(listing.swap.toOption.get.isInstanceOf[YallmoError.InvalidToolArguments])
      }
    }
  }

  test("wrong entry types and invalid UTF-8 are mapped to tool execution failures") {
    withWorkspace { (workspace, fs) =>
      Files.write(workspace.resolve("invalid.txt"), Array[Byte](0xc3.toByte, 0x28.toByte))
      for effect <- List(read(fs, "."), list(fs, "invalid.txt"), read(fs, "invalid.txt")) do
        assert(run(effect.either).swap.toOption.get.isInstanceOf[YallmoError.ToolExecutionFailed])
    }
  }

  private def read(fs: FsRead, path: String) =
    new ReadFileTool()
      .execute(ExternalToolCall(ToolKey.ReadFile, ujson.Obj("path" -> path).render()))
      .provideEnvironment(ZEnvironment[FsRead](fs))

  private def list(fs: FsRead, path: String) =
    new ListFilesTool()
      .execute(ExternalToolCall(ToolKey.ListFiles, ujson.Obj("path" -> path).render()))
      .provideEnvironment(ZEnvironment[FsRead](fs))

  private def withWorkspace(test: (Path, FsRead) => Unit): Unit = {
    val parent = Files.createTempDirectory("yallmo-tools-").toRealPath()
    val workspace = Files.createDirectory(parent.resolve("workspace"))
    try test(workspace, run(FileSystem.open(workspace.toString)))
    finally {
      val paths = Files.walk(parent)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
    }
  }

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
