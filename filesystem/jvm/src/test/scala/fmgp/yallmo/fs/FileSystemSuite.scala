package fmgp.yallmo.fs

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.ZSuite
import zio.*
import zio.json.*
import zio.stream.ZStream

final class FileSystemSuite extends ZSuite {
  testZ("the configured directory is the logical root for narrow read/write interfaces") {
    withRoot { (root, outside) =>
      val relative = Path.of("").toAbsolutePath.normalize().relativize(root).toString
      for {
        fs <- FileSystem.open(relative)
        reader: FsRead = fs
        writer: FsWrite = fs
        text = "héllo 🌍\r\n世界\n"
        _ <- writer.writeUtf8("notes.txt", text, WriteMode.CreateNew)
        actual <- reader.readUtf8("notes.txt")
        _ = assertEquals(actual, text)
        _ <- ZIO.attempt(assertEquals(Files.readString(root.resolve("notes.txt"), UTF_8), text))
        names <- reader.list(".")
      } yield {
        assertEquals(names, Chunk("notes.txt"))
        assert(!Files.exists(outside.resolve("notes.txt")))
      }
    }
  }

  testZ("initialization requires an existing directory without symlink ancestors") {
    withRoot { (root, outside) =>
      val missing = root.resolve("missing").toString
      for {
        missingResult <- FileSystem.open(missing).either
        _ = assertEquals(missingResult, Left(FsError.NotFound(missing)))
        file <- ZIO.attempt(Files.writeString(root.resolve("file"), "file", UTF_8).toString)
        fileResult <- FileSystem.open(file).either
        _ = assertEquals(fileResult, Left(FsError.WrongType(file, "directory")))
        _ <- ZIO.foreachDiscard(List("", "bad\u0000root"))(path => FileSystem.open(path).either.map(assertInvalid))
        link <- ZIO.attempt {
          val link = Files.createSymbolicLink(root.resolve("link"), outside)
          Files.createDirectory(outside.resolve("nested"))
          link
        }
        _ <- ZIO.foreachDiscard(List(link, link.resolve("nested"))) { path =>
          FileSystem.open(path.toString).either.map(assertInvalid)
        }
      } yield ()
    }
  }

  testZ("byte streams round trip binary data without text conversion") {
    withRoot { (root, _) =>
      for {
        fs <- FileSystem.open(root.toString)
        bytes = Chunk.fromIterable((0 to 255).map(_.toByte))
        _ <- fs.writeBytes("data.bin", ZStream.fromChunk(bytes).rechunk(7), WriteMode.CreateNew)
        actual <- fs.readBytes("data.bin").runCollect
      } yield assertEquals(actual, bytes)
    }
  }

  testZ("directory creation is recursive and idempotent; listing returns sorted direct names") {
    withRoot { (root, _) =>
      for {
        fs <- FileSystem.open(root.toString)
        _ <- fs.createDirectories("docs/nested")
        _ <- fs.createDirectories("docs/nested")
        _ <- fs.createDirectories(".")
        _ <- ZIO.foreachDiscard(List("z.txt", "a.txt"))(name => fs.writeUtf8(s"docs/$name", name, WriteMode.CreateNew))
        names <- fs.list("docs")
        _ = assertEquals(names, Chunk("a.txt", "nested", "z.txt"))
        nested <- fs.list("docs/nested")
        _ = assertEquals(nested, Chunk.empty[String])
        _ <- ZIO.foreachDiscard(List("docs/a.txt", "docs/a.txt/nested")) { path =>
          fs.createDirectories(path)
            .either
            .map(result => assertEquals(result, Left(FsError.WrongType(path, "directory"))))
        }
      } yield ()
    }
  }

  testZ("create-new, replace and append are explicit, including for empty streams") {
    withRoot { (root, _) =>
      for {
        fs <- FileSystem.open(root.toString)
        _ <- fs.writeBytes("empty.bin", ZStream.empty, WriteMode.CreateNew)
        _ <- ZIO.attempt(assertEquals(Files.size(root.resolve("empty.bin")), 0L))
        _ <- fs.writeUtf8("note.txt", "long original", WriteMode.CreateNew)
        existing <- fs.writeUtf8("note.txt", "lost", WriteMode.CreateNew).either
        _ = assertEquals(existing, Left(FsError.AlreadyExists("note.txt")))
        original <- fs.readUtf8("note.txt")
        _ = assertEquals(original, "long original")
        _ <- fs.writeUtf8("note.txt", "new", WriteMode.Replace)
        _ <- fs.writeUtf8("note.txt", " tail", WriteMode.Append)
        _ <- fs.writeBytes("note.txt", ZStream.empty, WriteMode.Append)
        appended <- fs.readUtf8("note.txt")
        _ = assertEquals(appended, "new tail")
        _ <- fs.writeBytes("note.txt", ZStream.empty, WriteMode.Replace)
        empty <- fs.readUtf8("note.txt")
        _ = assertEquals(empty, "")
        _ <- fs.writeUtf8("created.txt", "created", WriteMode.Append)
        created <- fs.readUtf8("created.txt")
      } yield assertEquals(created, "created")
    }
  }

  testZ("missing paths and wrong entry types produce typed errors") {
    withRoot { (root, _) =>
      for {
        fs <- FileSystem.open(root.toString)
        missing = List(
          fs.readBytes("missing").runDrain,
          fs.list("missing").unit,
          fs.deleteFile("missing"),
          fs.deleteDirectory("missing", DirectoryDeleteMode.Recursive),
          fs.move("missing", "destination", MoveMode.FailIfExists)
        )
        _ <- ZIO.foreachDiscard(missing)(
          _.either.map(result => assertEquals(result, Left(FsError.NotFound("missing"))))
        )
        writeMissing <- fs.writeUtf8("missing/note.txt", "note", WriteMode.Append).either
        _ = assertEquals(writeMissing, Left(FsError.NotFound("missing/note.txt")))
        readRoot <- fs.readUtf8(".").either
        _ = assertEquals(readRoot, Left(FsError.WrongType(".", "regular file")))
        writeRoot <- fs.writeUtf8(".", "no", WriteMode.Replace).either
      } yield assertEquals(writeRoot, Left(FsError.WrongType(".", "regular file")))
    }
  }

  testZ("all operations reject absolute paths, parent traversal, empty paths and invalid syntax") {
    withRoot { (root, outside) =>
      for {
        fs <- FileSystem.open(root.toString)
        operations: List[String => IO[FsError, Unit]] = List(
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
        _ <- ZIO.foreachDiscard(
          List(outside.resolve("escape.txt").toString, "../escape.txt", "a/../inside.txt", "", "bad\u0000path")
        ) { path =>
          ZIO.foreachDiscard(operations)(operation => operation(path).either.map(assertInvalid))
        }
      } yield assert(!Files.exists(outside.resolve("escape.txt")))
    }
  }

  testZ("file, directory, internal and dangling symlinks are rejected without touching their targets") {
    withRoot { (root, outside) =>
      for {
        _ <- ZIO.attempt {
          Files.writeString(outside.resolve("secret.txt"), "secret", UTF_8)
          Files.createSymbolicLink(root.resolve("file-link"), outside.resolve("secret.txt"))
          Files.createSymbolicLink(root.resolve("dir-link"), outside)
          Files.writeString(root.resolve("real.txt"), "real", UTF_8)
          Files.createSymbolicLink(root.resolve("internal-link"), Path.of("real.txt"))
          Files.createSymbolicLink(root.resolve("dangling-link"), Path.of("missing"))
        }
        fs <- FileSystem.open(root.toString)
        _ <- ZIO.foreachDiscard(List("file-link", "dir-link/secret.txt", "internal-link")) { path =>
          fs.readUtf8(path).either.map(assertInvalid)
        }
        _ <- ZIO.foreachDiscard(List("file-link", "dangling-link", "dir-link/new.txt")) { path =>
          fs.writeUtf8(path, "no", WriteMode.Replace).either.map(assertInvalid)
        }
        _ <- fs.createDirectories("dir-link/new-directory").either.map(assertInvalid)
        _ <- fs.deleteFile("file-link").either.map(assertInvalid)
        _ <- fs.deleteDirectory("dir-link", DirectoryDeleteMode.Recursive).either.map(assertInvalid)
        _ <- fs.move("file-link", "moved", MoveMode.FailIfExists).either.map(assertInvalid)
        _ <- fs.list(".").either.map(assertInvalid)
      } yield {
        assertEquals(Files.readString(outside.resolve("secret.txt"), UTF_8), "secret")
        assert(!Files.exists(outside.resolve("new.txt")))
        assert(!Files.exists(outside.resolve("new-directory")))
      }
    }
  }

  testZ("a removed or symlink-replaced root is rejected after initialization") {
    for {
      _ <- withRoot { (root, outside) =>
        for {
          fs <- FileSystem.open(root.toString)
          _ <- ZIO.attempt {
            Files.move(root, root.resolveSibling("original-root"))
            Files.createSymbolicLink(root, outside)
          }
          _ <- fs.writeUtf8("escape.txt", "no", WriteMode.CreateNew).either.map(assertInvalid)
          _ <- fs.createDirectories(".").either.map(assertInvalid)
        } yield assert(!Files.exists(outside.resolve("escape.txt")))
      }
      _ <- withRoot { (root, _) =>
        for {
          fs <- FileSystem.open(root.toString)
          _ <- ZIO.attempt(Files.delete(root))
          result <- fs.createDirectories("nested").either
        } yield {
          assertEquals(result, Left(FsError.NotFound("nested")))
          assert(!Files.exists(root))
        }
      }
    } yield ()
  }

  testZ("deletion distinguishes files and directories and rejects symlink descendants") {
    withRoot { (root, outside) =>
      for {
        fs <- FileSystem.open(root.toString)
        _ <- fs.createDirectories("folder/nested")
        _ <- fs.writeUtf8("folder/nested/file.txt", "data", WriteMode.CreateNew)
        deleteFile <- fs.deleteFile("folder").either
        _ = assertEquals(deleteFile, Left(FsError.WrongType("folder", "regular file")))
        deleteDirectory <- fs.deleteDirectory("folder/nested/file.txt", DirectoryDeleteMode.EmptyOnly).either
        _ = assertEquals(deleteDirectory, Left(FsError.WrongType("folder/nested/file.txt", "directory")))
        deleteNonEmpty <- fs.deleteDirectory("folder", DirectoryDeleteMode.EmptyOnly).either
        _ = assertEquals(deleteNonEmpty, Left(FsError.DirectoryNotEmpty("folder")))
        _ <- fs.deleteFile("folder/nested/file.txt")
        _ <- fs.deleteDirectory("folder/nested", DirectoryDeleteMode.EmptyOnly)
        _ <- fs.createDirectories("folder/again/deeper")
        _ <- fs.writeUtf8("folder/again/deeper/file.txt", "data", WriteMode.CreateNew)
        _ <- fs.deleteDirectory("folder", DirectoryDeleteMode.Recursive)
        names <- fs.list(".")
        _ = assertEquals(names, Chunk.empty[String])
        _ <- fs.createDirectories("tree")
        _ <- ZIO.attempt {
          Files.writeString(outside.resolve("keep.txt"), "keep", UTF_8)
          Files.createSymbolicLink(root.resolve("tree/link"), outside)
        }
        _ <- fs.move("tree", "moved", MoveMode.FailIfExists).either.map(assertInvalid)
        _ <- fs.deleteDirectory("tree", DirectoryDeleteMode.Recursive).either.map(assertInvalid)
      } yield {
        assertEquals(Files.readString(outside.resolve("keep.txt"), UTF_8), "keep")
        assert(!Files.exists(root.resolve("moved")))
      }
    }
  }

  testZ("the logical root cannot be deleted or used as either move endpoint") {
    withRoot { (root, _) =>
      for {
        fs <- FileSystem.open(root.toString)
        _ <- fs.writeUtf8("note.txt", "note", WriteMode.CreateNew)
        _ <- ZIO.foreachDiscard(List(".", "./")) { path =>
          for {
            _ <- fs.deleteFile(path).either.map(assertInvalid)
            _ <- fs.deleteDirectory(path, DirectoryDeleteMode.Recursive).either.map(assertInvalid)
            _ <- fs.move(path, "new-root", MoveMode.FailIfExists).either.map(assertInvalid)
            _ <- fs.move("note.txt", path, MoveMode.ReplaceFile).either.map(assertInvalid)
          } yield ()
        }
      } yield ()
    }
  }

  testZ("move renames files and trees, with explicit replacement and valid endpoints") {
    withRoot { (root, _) =>
      for {
        fs <- FileSystem.open(root.toString)
        _ <- fs.createDirectories("source/nested")
        _ <- fs.writeUtf8("source/nested/a.txt", "a", WriteMode.CreateNew)
        _ <- fs.move("source/nested/a.txt", "source/nested/b.txt", MoveMode.FailIfExists)
        _ <- fs.move("source", "destination", MoveMode.FailIfExists)
        moved <- fs.readUtf8("destination/nested/b.txt")
        _ = {
          assertEquals(moved, "a")
          assert(!Files.exists(root.resolve("source")))
        }
        _ <- fs.writeUtf8("new.txt", "new", WriteMode.CreateNew)
        _ <- fs.writeUtf8("existing.txt", "old", WriteMode.CreateNew)
        existing <- fs.move("new.txt", "existing.txt", MoveMode.FailIfExists).either
        _ = assertEquals(existing, Left(FsError.AlreadyExists("existing.txt")))
        old <- fs.readUtf8("existing.txt")
        _ = assertEquals(old, "old")
        _ <- fs.move("new.txt", "existing.txt", MoveMode.ReplaceFile)
        replaced <- fs.readUtf8("existing.txt")
        _ = assertEquals(replaced, "new")
        replaceDirectory <- fs.move("existing.txt", "destination", MoveMode.ReplaceFile).either
        _ = assertEquals(replaceDirectory, Left(FsError.AlreadyExists("destination")))
        replaceWithDirectory <- fs.move("destination", "existing.txt", MoveMode.ReplaceFile).either
        _ = assertEquals(
          replaceWithDirectory,
          Left(FsError.WrongType("destination", "regular file when replacing a file"))
        )
        _ <- fs.move("existing.txt", "./existing.txt", MoveMode.FailIfExists).either.map(assertInvalid)
        _ <- fs.move("destination", "destination/nested/new", MoveMode.FailIfExists).either.map(assertInvalid)
        missing <- fs.move("existing.txt", "missing/note.txt", MoveMode.FailIfExists).either
      } yield assertEquals(missing, Left(FsError.NotFound("missing/note.txt")))
    }
  }

  testZ("JSONL appends typed records with real Unicode, escaped newlines and one LF per element") {
    withRoot { (root, _) =>
      for {
        fs <- FileSystem.open(root.toString)
        records = Chunk(Record(1, "hello\n世界"), Record(2, "🌍"), Record(3, "tail"))
        _ <- ZStream
          .fromChunk(records.take(2))
          .run(fs.write[Record]("events.jsonl", WriteMode.Append, CommitPolicy.Every(2, Durability.Sync)))
        _ <- ZStream.succeed(records(2)).run(fs.write[Record]("events.jsonl", WriteMode.Append))
        actual <- fs.read[Record]("events.jsonl").runCollect
      } yield {
        assertEquals(actual, records)
        val text = Files.readString(root.resolve("events.jsonl"), UTF_8)
        assertEquals(text.count(_ == '\n'), records.size)
        assert(text.contains("\\n"))
        assert(text.endsWith("\n"))
      }
    }
  }

  testZ("scoped writers serialize lines and flush buffered text on scope exit") {
    withRoot { (root, _) =>
      for {
        fs <- FileSystem.open(root.toString)
        lines = (1 to 10).map(index => s"$index:" + "x" * 1000).toVector
        writer <- ZIO.scoped {
          for {
            writer <- fs.openUtf8Writer("lines.txt", WriteMode.CreateNew)
            _ <- ZIO.foreachParDiscard(lines)(writer.writeLine)
            _ <- writer.flush
            _ <- writer.sync
            _ <- writer.writeLine("buffered tail")
          } yield writer
        }
        text <- fs.readUtf8("lines.txt")
        actual = text.linesIterator.toVector
        _ = {
          assertEquals(actual.init.sorted, lines.sorted)
          assertEquals(actual.last, "buffered tail")
        }
        closed <- writer.write("too late").either
      } yield closed match {
        case Left(FsError.IoFailure(path, _)) => assertEquals(path, "lines.txt")
        case other                            => fail(s"Expected a closed-writer error, got $other")
      }
    }
  }

  testZ("byte reads open lazily and support early consumption") {
    withRoot { (root, _) =>
      for {
        fs <- FileSystem.open(root.toString)
        stream = fs.readBytes("later.txt")
        _ <- fs.writeUtf8("later.txt", "later", WriteMode.CreateNew)
        actual <- stream.take(1).runCollect
      } yield assertEquals(actual, Chunk('l'.toByte))
    }
  }

  testZ("byte writes preserve upstream failure and partial output on failure or interruption") {
    withRoot { (root, _) =>
      for {
        fs <- FileSystem.open(root.toString)
        failed <- fs
          .writeBytes("partial.bin", ZStream.succeed(1.toByte) ++ ZStream.fail("upstream"), WriteMode.CreateNew)
          .either
        _ = assertEquals(failed, Left("upstream"))
        partial <- fs.readBytes("partial.bin").runCollect
        _ = assertEquals(partial, Chunk(1.toByte))
        waiting <- Promise.make[Nothing, Unit]
        bytes = ZStream.succeed(2.toByte) ++ ZStream.fromZIO(waiting.succeed(())).drain ++ ZStream.never
        fiber <- fs.writeBytes("interrupted.bin", bytes, WriteMode.CreateNew).fork
        _ <- waiting.await
        _ <- fiber.interrupt
        interrupted <- fs.readBytes("interrupted.bin").runCollect
      } yield assertEquals(interrupted, Chunk(2.toByte))
    }
  }

  private final case class Record(id: Int, text: String) derives JsonCodec

  private def assertInvalid[A](result: Either[FsError, A]): Unit = result match {
    case Left(_: FsError.InvalidPath) => ()
    case other                        => fail(s"Expected InvalidPath, got $other")
  }

  private def withRoot[E, A](test: (Path, Path) => ZIO[Any, E, A]): ZIO[Any, E | Throwable, A] =
    ZIO.acquireReleaseWith {
      ZIO.attempt {
        val parent = Files.createTempDirectory("yallmo-rooted-fs-").toRealPath()
        val root = Files.createDirectory(parent.resolve("workspace"))
        val outside = Files.createDirectory(parent.resolve("outside"))
        (parent, root, outside)
      }
    } { (parent, _, _) =>
      ZIO.attempt {
        val entries = Files.walk(parent)
        try entries.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete(_))
        finally entries.close()
      }.orDie
    } { (_, root, outside) => test(root, outside) }
}
