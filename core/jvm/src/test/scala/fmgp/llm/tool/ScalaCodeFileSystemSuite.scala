package fmgp.yallmo.tool

import java.nio.file.Path
import munit.FunSuite
import zio.*

final class ScalaCodeFileSystemSuite extends FunSuite {
  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  test("script adapter delegates to the same stateful host service") {
    val path = Path.of("example.txt")
    val result = run {
      for {
        reads <- Ref.make(0)
        host = new WorkspaceFileSystem {
          def isDirectory(actual: Path): UIO[Boolean] = ZIO.succeed(actual == path)
          def readUtf8(actual: Path): Task[String] = reads.updateAndGet(_ + 1).map(count => s"$actual:$count")
          def list(actual: Path): Task[Chunk[String]] = ZIO.succeed(Chunk(actual.toString))
        }
        adapter = new ScalaCodeFileSystem(host)
        directory <- adapter.isDirectory(path.toString)
        first <- adapter.readUtf8(path.toString)
        second <- adapter.readUtf8(path.toString)
        entries <- adapter.list(path.toString)
        count <- reads.get
      } yield (directory, first, second, entries, count)
    }
    assertEquals(result, (true, "example.txt:1", "example.txt:2", Chunk("example.txt"), 2))
  }

  test("invalid JVM paths are handled inside effects without calling the host") {
    val host = new WorkspaceFileSystem {
      def isDirectory(path: Path): UIO[Boolean] = ZIO.dieMessage(s"Unexpected directory probe: $path")
      def readUtf8(path: Path): Task[String] = ZIO.dieMessage(s"Unexpected read: $path")
      def list(path: Path): Task[Chunk[String]] = ZIO.dieMessage(s"Unexpected listing: $path")
    }
    val adapter = new ScalaCodeFileSystem(host)
    val invalid = "invalid" + 0.toChar
    assert(!run(adapter.isDirectory(invalid)))
    assert(
      run(adapter.readUtf8(invalid).either).left.toOption.exists(_.isInstanceOf[java.nio.file.InvalidPathException])
    )
    assert(run(adapter.list(invalid).either).left.toOption.exists(_.isInstanceOf[java.nio.file.InvalidPathException]))
  }

  test("script adapter preserves host failures") {
    val failure = new RuntimeException("host failure")
    val host = new WorkspaceFileSystem {
      def isDirectory(path: Path): UIO[Boolean] = ZIO.succeed(path.isAbsolute)
      def readUtf8(path: Path): Task[String] = ZIO.fail(failure).as(path.toString)
      def list(path: Path): Task[Chunk[String]] = ZIO.fail(failure).as(Chunk(path.toString))
    }
    val adapter = new ScalaCodeFileSystem(host)
    assertEquals(run(adapter.readUtf8("missing").either), Left(failure))
    assertEquals(run(adapter.list("missing").either), Left(failure))
  }
}
