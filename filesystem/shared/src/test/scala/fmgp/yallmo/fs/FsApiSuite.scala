package fmgp.yallmo.fs

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import zio.json.*
import zio.ZIO
import zio.stream.ZStream

final class FsApiSuite extends FunSuite {
  test("FsError JSON codec roundtrips every filesystem error case") {
    val errors: List[FsError] = List(
      FsError.InvalidPath("../secret", "outside root"),
      FsError.NotFound("missing"),
      FsError.AlreadyExists("present"),
      FsError.DirectoryNotEmpty("folder"),
      FsError.WrongType("file", "directory"),
      FsError.InvalidArgument("invalid mode"),
      FsError.InvalidJson("events.jsonl", Long.MaxValue, "invalid JSON: \"🙂\"\n"),
      FsError.IoFailure("file", "access denied")
    )
    errors.foreach(error => assertEquals(error.toJson.fromJson[FsError], Right(error)))
  }

  test("read interfaces exclude mutation and mutations require explicit modes") {
    val errors = typeCheckErrors("""
      import fmgp.yallmo.fs.*
      import zio.stream.ZStream

      def text(fs: FsRead) = fs.writeUtf8("file", "text", WriteMode.Replace)
      def bytes(fs: FsRead) = fs.writeBytes("file", ZStream.empty, WriteMode.Replace)
      def create(fs: FsRead) = fs.createDirectories("folder")
      def deleteFile(fs: FsRead) = fs.deleteFile("file")
      def deleteDirectory(fs: FsRead) = fs.deleteDirectory("folder", DirectoryDeleteMode.Recursive)
      def move(fs: FsRead) = fs.move("old", "new", MoveMode.FailIfExists)
      def recover(fs: FsRead) = fs.write
      def json(fs: FsJsonlRead) = fs.write[Int]("events.jsonl", WriteMode.Append)

      def missingByteMode(fs: FsWrite) = fs.writeBytes("file", ZStream.empty)
      def missingJsonMode(fs: FsJsonlWrite) = fs.write[Int]("events.jsonl")
      def missingDeleteMode(fs: FsWrite) = fs.deleteDirectory("folder")
      def missingMoveMode(fs: FsWrite) = fs.move("old", "new")
    """)
    for method <- List("writeUtf8", "writeBytes", "createDirectories", "deleteFile", "deleteDirectory", "move", "write")
    do assert(errors.exists(_.message.contains(method)), clues(method))
    for method <- List("writeBytes", "write", "deleteDirectory", "move") do
      assert(errors.exists(error => error.message.contains(method) && error.message.contains("mode")), clues(method))
  }

  // Ordinary compiled helpers retain generic environment/error checks without runtime tests of declarations.
  def writeBytes[R, E](fs: FsWrite, bytes: ZStream[R, E, Byte]): ZIO[R, E | FsError, Unit] =
    fs.writeBytes("data.bin", bytes, WriteMode.Append)

  def writeJsonl[R, E](fs: FsJsonlWrite, values: ZStream[R, E, Int]): ZIO[R, E | FsError, Unit] =
    values.run(fs.write[Int]("events.jsonl", WriteMode.Append))
}
