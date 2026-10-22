package fmgp.yallmo.fs

import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import zio.*
import zio.stream.ZStream

final class FsDefaultsSuite extends FunSuite {
  test("UTF-8 reads handle chunk boundaries, empty input, invalid encoding and underlying errors") {
    for text <- List("", "héllo 🌍\r\n世界\n") do {
      val bytes = ZStream.fromIterable(text.getBytes(UTF_8)).rechunk(1)
      assertEquals(run(new TestRead(bytes).readUtf8("notes.txt")), text)
    }
    for bytes <- List(Chunk(0xc3.toByte, 0x28.toByte), Chunk(0xf0.toByte, 0x9f.toByte)) do {
      val result = run(new TestRead(ZStream.fromChunk(bytes).rechunk(1)).readUtf8("invalid.txt").either)
      result match {
        case Left(FsError.IoFailure(path, _)) => assertEquals(path, "invalid.txt")
        case other                            => fail(s"Expected a UTF-8 I/O error, got $other")
      }
    }
    val error = FsError.NotFound("missing.txt")
    assertEquals(run(new TestRead(ZStream.fail(error)).readUtf8("missing.txt").either), Left(error))
    val partial = ZStream.fromIterable("partial".getBytes(UTF_8)) ++ ZStream.fail(error)
    assertEquals(run(new TestRead(partial).readUtf8("missing.txt").either), Left(error))
  }

  test("UTF-8 writes and writeLine preserve encoding and primitive write errors") {
    run(new TestWrite(None).writeUtf8("notes.txt", 0xd800.toChar.toString, WriteMode.CreateNew).either) match {
      case Left(FsError.IoFailure(path, _)) => assertEquals(path, "notes.txt")
      case other                            => fail(s"Expected a UTF-8 encoding error, got $other")
    }
    val error = FsError.AlreadyExists("notes.txt")
    assertEquals(
      run(new TestWrite(Some(error)).writeUtf8("notes.txt", "hello", WriteMode.CreateNew).either),
      Left(error)
    )
    val writer = new FsUtf8Writer {
      override def write(text: String): IO[FsError, Unit] = ZIO.fail(error)
      override def flush: IO[FsError, Unit] = ZIO.unit
      override def sync: IO[FsError, Unit] = ZIO.unit
    }
    assertEquals(run(writer.writeLine("hello").either), Left(error))
  }

  private final class TestRead(bytes: ZStream[Any, FsError, Byte]) extends FsRead {
    override def readBytes(path: String): ZStream[Any, FsError, Byte] = bytes
    override def list(path: String): IO[FsError, Chunk[String]] = unused
  }

  private final class TestWrite(error: Option[FsError]) extends FsWrite {
    override def writeBytes[R, E](
        path: String,
        bytes: ZStream[R, E, Byte],
        mode: WriteMode
    ): ZIO[R, E | FsError, Unit] =
      error match {
        case Some(value) => ZIO.fail(value)
        case None        => bytes.runDrain
      }
    override def openUtf8Writer(path: String, mode: WriteMode): ZIO[Scope, FsError, FsUtf8Writer] = unused
    override def createDirectories(path: String): IO[FsError, Unit] = unused
    override def deleteFile(path: String): IO[FsError, Unit] = unused
    override def deleteDirectory(path: String, mode: DirectoryDeleteMode): IO[FsError, Unit] = unused
    override def move(source: String, destination: String, mode: MoveMode): IO[FsError, Unit] = unused
  }

  private def unused: IO[FsError, Nothing] = ZIO.fail(FsError.InvalidArgument("Unexpected test operation"))

  private def run[E, A](effect: ZIO[Any, E, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
}
