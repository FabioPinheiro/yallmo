package fmgp.yallmo.fs

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import zio.*
import zio.json.*
import zio.stream.ZStream

final class FileSystemSuite extends FunSuite {
  test("the configured directory is the logical root for narrow read/write interfaces") {
    withRoot { (root, outside) =>
      val relative = Path.of("").toAbsolutePath.normalize().relativize(root).toString
      val fs = run(FileSystem.open(relative))
      val reader: FsRead = fs
      val writer: FsWrite = fs
      val text = "héllo 🌍\r\n世界\n"
      run(writer.writeUtf8("notes.txt", text, WriteMode.CreateNew))
      assertEquals(run(reader.readUtf8("notes.txt")), text)
      assertEquals(Files.readString(root.resolve("notes.txt"), UTF_8), text)
      assertEquals(run(reader.list(".")), Chunk("notes.txt"))
      assert(!Files.exists(outside.resolve("notes.txt")))
    }
  }

  test("initialization requires an existing directory without symlink ancestors") {
    withRoot { (root, outside) =>
      val missing = root.resolve("missing").toString
      assertEquals(run(FileSystem.open(missing).either), Left(FsError.NotFound(missing)))
      val file = Files.writeString(root.resolve("file"), "file", UTF_8).toString
      assertEquals(run(FileSystem.open(file).either), Left(FsError.WrongType(file, "directory")))
      for path <- List("", "bad\u0000root") do assertInvalid(run(FileSystem.open(path).either))
      val link = Files.createSymbolicLink(root.resolve("link"), outside)
      Files.createDirectory(outside.resolve("nested"))
      for path <- List(link, link.resolve("nested")) do assertInvalid(run(FileSystem.open(path.toString).either))
    }
  }

  test("byte streams round trip binary data without text conversion") {
    withRoot { (root, _) =>
      val fs = run(FileSystem.open(root.toString))
      val bytes = Chunk.fromIterable((0 to 255).map(_.toByte))
      run(fs.writeBytes("data.bin", ZStream.fromChunk(bytes).rechunk(7), WriteMode.CreateNew))
      assertEquals(run(fs.readBytes("data.bin").runCollect), bytes)
    }
  }

  test("directory creation is recursive and idempotent; listing returns sorted direct names") {
    withRoot { (root, _) =>
      val fs = run(FileSystem.open(root.toString))
      run(fs.createDirectories("docs/nested"))
      run(fs.createDirectories("docs/nested"))
      run(fs.createDirectories("."))
      for name <- List("z.txt", "a.txt") do run(fs.writeUtf8(s"docs/$name", name, WriteMode.CreateNew))
      assertEquals(run(fs.list("docs")), Chunk("a.txt", "nested", "z.txt"))
      assertEquals(run(fs.list("docs/nested")), Chunk.empty[String])
      for path <- List("docs/a.txt", "docs/a.txt/nested") do
        assertEquals(run(fs.createDirectories(path).either), Left(FsError.WrongType(path, "directory")))
    }
  }

  test("create-new, replace and append are explicit, including for empty streams") {
    withRoot { (root, _) =>
      val fs = run(FileSystem.open(root.toString))
      run(fs.writeBytes("empty.bin", ZStream.empty, WriteMode.CreateNew))
      assertEquals(Files.size(root.resolve("empty.bin")), 0L)
      run(fs.writeUtf8("note.txt", "long original", WriteMode.CreateNew))
      assertEquals(
        run(fs.writeUtf8("note.txt", "lost", WriteMode.CreateNew).either),
        Left(FsError.AlreadyExists("note.txt"))
      )
      assertEquals(run(fs.readUtf8("note.txt")), "long original")
      run(fs.writeUtf8("note.txt", "new", WriteMode.Replace))
      run(fs.writeUtf8("note.txt", " tail", WriteMode.Append))
      run(fs.writeBytes("note.txt", ZStream.empty, WriteMode.Append))
      assertEquals(run(fs.readUtf8("note.txt")), "new tail")
      run(fs.writeBytes("note.txt", ZStream.empty, WriteMode.Replace))
      assertEquals(run(fs.readUtf8("note.txt")), "")
      run(fs.writeUtf8("created.txt", "created", WriteMode.Append))
      assertEquals(run(fs.readUtf8("created.txt")), "created")
    }
  }

  test("missing paths and wrong entry types produce typed errors") {
    withRoot { (root, _) =>
      val fs = run(FileSystem.open(root.toString))
      val missing = List(
        fs.readBytes("missing").runDrain,
        fs.list("missing").unit,
        fs.deleteFile("missing"),
        fs.deleteDirectory("missing", DirectoryDeleteMode.Recursive),
        fs.move("missing", "destination", MoveMode.FailIfExists)
      )
      missing.foreach(effect => assertEquals(run(effect.either), Left(FsError.NotFound("missing"))))
      assertEquals(
        run(fs.writeUtf8("missing/note.txt", "note", WriteMode.Append).either),
        Left(FsError.NotFound("missing/note.txt"))
      )
      assertEquals(run(fs.readUtf8(".").either), Left(FsError.WrongType(".", "regular file")))
      assertEquals(run(fs.writeUtf8(".", "no", WriteMode.Replace).either), Left(FsError.WrongType(".", "regular file")))
    }
  }

  test("all operations reject absolute paths, parent traversal, empty paths and invalid syntax") {
    withRoot { (root, outside) =>
      val fs = run(FileSystem.open(root.toString))
      val operations: List[String => IO[FsError, Unit]] = List(
        path => fs.readBytes(path).runDrain,
        path => fs.read[Int](path).runDrain,
        path => fs.list(path).unit,
        path => fs.writeUtf8(path, "no", WriteMode.Replace),
        path => ZIO.scoped(fs.openUtf8Writer(path, WriteMode.Replace)).unit,
        path => ZStream(1).run(fs.write[Int](path, WriteMode.Replace)),
        path => fs.createDirectories(path),
        path => fs.deleteFile(path),
        path => fs.deleteDirectory(path, DirectoryDeleteMode.Recursive),
        path => fs.move(path, "destination", MoveMode.FailIfExists),
        path => fs.move("source", path, MoveMode.FailIfExists)
      )
      for {
        path <- List(outside.resolve("escape.txt").toString, "../escape.txt", "a/../inside.txt", "", "bad\u0000path")
        operation <- operations
      } assertInvalid(run(operation(path).either))
      assert(!Files.exists(outside.resolve("escape.txt")))
    }
  }

  test("file, directory, internal and dangling symlinks are rejected without touching their targets") {
    withRoot { (root, outside) =>
      Files.writeString(outside.resolve("secret.txt"), "secret", UTF_8)
      Files.createSymbolicLink(root.resolve("file-link"), outside.resolve("secret.txt"))
      Files.createSymbolicLink(root.resolve("dir-link"), outside)
      Files.writeString(root.resolve("real.txt"), "real", UTF_8)
      Files.createSymbolicLink(root.resolve("internal-link"), Path.of("real.txt"))
      Files.createSymbolicLink(root.resolve("dangling-link"), Path.of("missing"))
      val fs = run(FileSystem.open(root.toString))
      for path <- List("file-link", "dir-link/secret.txt", "internal-link") do
        assertInvalid(run(fs.readUtf8(path).either))
      for path <- List("file-link", "dangling-link", "dir-link/new.txt") do
        assertInvalid(run(fs.writeUtf8(path, "no", WriteMode.Replace).either))
      assertInvalid(run(fs.createDirectories("dir-link/new-directory").either))
      assertInvalid(run(fs.deleteFile("file-link").either))
      assertInvalid(run(fs.deleteDirectory("dir-link", DirectoryDeleteMode.Recursive).either))
      assertInvalid(run(fs.move("file-link", "moved", MoveMode.FailIfExists).either))
      assertInvalid(run(fs.list(".").either))
      assertEquals(Files.readString(outside.resolve("secret.txt"), UTF_8), "secret")
      assert(!Files.exists(outside.resolve("new.txt")))
      assert(!Files.exists(outside.resolve("new-directory")))
    }
  }

  test("a removed or symlink-replaced root is rejected after initialization") {
    withRoot { (root, outside) =>
      val fs = run(FileSystem.open(root.toString))
      Files.move(root, root.resolveSibling("original-root"))
      Files.createSymbolicLink(root, outside)
      assertInvalid(run(fs.writeUtf8("escape.txt", "no", WriteMode.CreateNew).either))
      assertInvalid(run(fs.createDirectories(".").either))
      assert(!Files.exists(outside.resolve("escape.txt")))
    }
    withRoot { (root, _) =>
      val fs = run(FileSystem.open(root.toString))
      Files.delete(root)
      assertEquals(run(fs.createDirectories("nested").either), Left(FsError.NotFound("nested")))
      assert(!Files.exists(root))
    }
  }

  test("deletion distinguishes files and directories and rejects symlink descendants") {
    withRoot { (root, outside) =>
      val fs = run(FileSystem.open(root.toString))
      run(fs.createDirectories("folder/nested"))
      run(fs.writeUtf8("folder/nested/file.txt", "data", WriteMode.CreateNew))
      assertEquals(run(fs.deleteFile("folder").either), Left(FsError.WrongType("folder", "regular file")))
      assertEquals(
        run(fs.deleteDirectory("folder/nested/file.txt", DirectoryDeleteMode.EmptyOnly).either),
        Left(FsError.WrongType("folder/nested/file.txt", "directory"))
      )
      assertEquals(
        run(fs.deleteDirectory("folder", DirectoryDeleteMode.EmptyOnly).either),
        Left(FsError.DirectoryNotEmpty("folder"))
      )
      run(fs.deleteFile("folder/nested/file.txt"))
      run(fs.deleteDirectory("folder/nested", DirectoryDeleteMode.EmptyOnly))
      run(fs.createDirectories("folder/again/deeper"))
      run(fs.writeUtf8("folder/again/deeper/file.txt", "data", WriteMode.CreateNew))
      run(fs.deleteDirectory("folder", DirectoryDeleteMode.Recursive))
      assertEquals(run(fs.list(".")), Chunk.empty[String])
      run(fs.createDirectories("tree"))
      Files.writeString(outside.resolve("keep.txt"), "keep", UTF_8)
      Files.createSymbolicLink(root.resolve("tree/link"), outside)
      assertInvalid(run(fs.move("tree", "moved", MoveMode.FailIfExists).either))
      assertInvalid(run(fs.deleteDirectory("tree", DirectoryDeleteMode.Recursive).either))
      assertEquals(Files.readString(outside.resolve("keep.txt"), UTF_8), "keep")
      assert(!Files.exists(root.resolve("moved")))
    }
  }

  test("the logical root cannot be deleted or used as either move endpoint") {
    withRoot { (root, _) =>
      val fs = run(FileSystem.open(root.toString))
      run(fs.writeUtf8("note.txt", "note", WriteMode.CreateNew))
      for path <- List(".", "./") do {
        assertInvalid(run(fs.deleteFile(path).either))
        assertInvalid(run(fs.deleteDirectory(path, DirectoryDeleteMode.Recursive).either))
        assertInvalid(run(fs.move(path, "new-root", MoveMode.FailIfExists).either))
        assertInvalid(run(fs.move("note.txt", path, MoveMode.ReplaceFile).either))
      }
    }
  }

  test("move renames files and trees, with explicit replacement and valid endpoints") {
    withRoot { (root, _) =>
      val fs = run(FileSystem.open(root.toString))
      run(fs.createDirectories("source/nested"))
      run(fs.writeUtf8("source/nested/a.txt", "a", WriteMode.CreateNew))
      run(fs.move("source/nested/a.txt", "source/nested/b.txt", MoveMode.FailIfExists))
      run(fs.move("source", "destination", MoveMode.FailIfExists))
      assertEquals(run(fs.readUtf8("destination/nested/b.txt")), "a")
      assert(!Files.exists(root.resolve("source")))
      run(fs.writeUtf8("new.txt", "new", WriteMode.CreateNew))
      run(fs.writeUtf8("existing.txt", "old", WriteMode.CreateNew))
      assertEquals(
        run(fs.move("new.txt", "existing.txt", MoveMode.FailIfExists).either),
        Left(FsError.AlreadyExists("existing.txt"))
      )
      assertEquals(run(fs.readUtf8("existing.txt")), "old")
      run(fs.move("new.txt", "existing.txt", MoveMode.ReplaceFile))
      assertEquals(run(fs.readUtf8("existing.txt")), "new")
      assertEquals(
        run(fs.move("existing.txt", "destination", MoveMode.ReplaceFile).either),
        Left(FsError.AlreadyExists("destination"))
      )
      assertEquals(
        run(fs.move("destination", "existing.txt", MoveMode.ReplaceFile).either),
        Left(FsError.WrongType("destination", "regular file when replacing a file"))
      )
      assertInvalid(run(fs.move("existing.txt", "./existing.txt", MoveMode.FailIfExists).either))
      assertInvalid(run(fs.move("destination", "destination/nested/new", MoveMode.FailIfExists).either))
      assertEquals(
        run(fs.move("existing.txt", "missing/note.txt", MoveMode.FailIfExists).either),
        Left(FsError.NotFound("missing/note.txt"))
      )
    }
  }

  test("JSONL appends typed records with real Unicode, escaped newlines and one LF per element") {
    withRoot { (root, _) =>
      val fs = run(FileSystem.open(root.toString))
      val records = Chunk(Record(1, "hello\n世界"), Record(2, "🌍"), Record(3, "tail"))
      run(
        ZStream
          .fromChunk(records.take(2))
          .run(fs.write[Record]("events.jsonl", WriteMode.Append, CommitPolicy.Every(2, Durability.Sync)))
      )
      run(ZStream.succeed(records(2)).run(fs.write[Record]("events.jsonl", WriteMode.Append)))
      assertEquals(run(fs.read[Record]("events.jsonl").runCollect), records)
      val text = Files.readString(root.resolve("events.jsonl"), UTF_8)
      assertEquals(text.count(_ == '\n'), records.size)
      assert(text.contains("\\n"))
      assert(text.endsWith("\n"))
    }
  }

  test("scoped writers serialize lines and flush buffered text on scope exit") {
    withRoot { (root, _) =>
      val fs = run(FileSystem.open(root.toString))
      val lines = (1 to 10).map(index => s"$index:" + "x" * 1000).toVector
      val writer = run(ZIO.scoped {
        for {
          writer <- fs.openUtf8Writer("lines.txt", WriteMode.CreateNew)
          _ <- ZIO.foreachParDiscard(lines)(writer.writeLine)
          _ <- writer.flush
          _ <- writer.sync
          _ <- writer.writeLine("buffered tail")
        } yield writer
      })
      val actual = run(fs.readUtf8("lines.txt")).linesIterator.toVector
      assertEquals(actual.init.sorted, lines.sorted)
      assertEquals(actual.last, "buffered tail")
      run(writer.write("too late").either) match {
        case Left(FsError.IoFailure(path, _)) => assertEquals(path, "lines.txt")
        case other                            => fail(s"Expected a closed-writer error, got $other")
      }
    }
  }

  test("byte reads open lazily and support early consumption") {
    withRoot { (root, _) =>
      val fs = run(FileSystem.open(root.toString))
      val stream = fs.readBytes("later.txt")
      run(fs.writeUtf8("later.txt", "later", WriteMode.CreateNew))
      assertEquals(run(stream.take(1).runCollect), Chunk('l'.toByte))
    }
  }

  test("byte writes preserve upstream failure and partial output on failure or interruption") {
    withRoot { (root, _) =>
      val fs = run(FileSystem.open(root.toString))
      assertEquals(
        run(
          fs.writeBytes("partial.bin", ZStream.succeed(1.toByte) ++ ZStream.fail("upstream"), WriteMode.CreateNew)
            .either
        ),
        Left("upstream")
      )
      assertEquals(run(fs.readBytes("partial.bin").runCollect), Chunk(1.toByte))
      run {
        for {
          waiting <- Promise.make[Nothing, Unit]
          bytes = ZStream.succeed(2.toByte) ++ ZStream.fromZIO(waiting.succeed(())).drain ++ ZStream.never
          fiber <- fs.writeBytes("interrupted.bin", bytes, WriteMode.CreateNew).fork
          _ <- waiting.await
          _ <- fiber.interrupt
        } yield ()
      }
      assertEquals(run(fs.readBytes("interrupted.bin").runCollect), Chunk(2.toByte))
    }
  }

  private final case class Record(id: Int, text: String) derives JsonCodec

  private def assertInvalid[A](result: Either[FsError, A]): Unit = result match {
    case Left(_: FsError.InvalidPath) => ()
    case other                        => fail(s"Expected InvalidPath, got $other")
  }

  private def withRoot[A](test: (Path, Path) => A): A = {
    val parent = Files.createTempDirectory("yallmo-rooted-fs-").toRealPath()
    val root = Files.createDirectory(parent.resolve("workspace"))
    val outside = Files.createDirectory(parent.resolve("outside"))
    try test(root, outside)
    finally {
      val entries = Files.walk(parent)
      try entries.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete(_))
      finally entries.close()
    }
  }

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
