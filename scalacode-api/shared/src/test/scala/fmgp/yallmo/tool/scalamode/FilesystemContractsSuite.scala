package fmgp.yallmo.tool.scalamode

import fmgp.rpc.RpcConnection
import fmgp.yallmo.fs.{FsError, FsRead}
import munit.ZSuite
import zio.*
import zio.json.ast.Json
import zio.stream.ZStream

/** Contract derivation and typed errors remain portable even without a browser WebSocket transport. */
final class FilesystemContractsSuite extends ZSuite {
  testZ("FsRead contract preserves selected implementations and typed failures on JVM and JS")(ZIO.suspendSucceed {
    val read = new FsRead {
      def readBytes(path: String) = ZStream.dieMessage("Custom readUtf8 must not be replaced by its default")
      override def readUtf8(path: String) = ZIO.succeed(s"custom:$path")
      def list(path: String) = ZIO.fail(FsError.NotFound(path))
    }
    val dispatcher = FilesystemRuntime.readContract.bind(read)
    val client = FilesystemRuntime.readContract.client(new RpcConnection {
      def call(serviceId: String, version: Int, method: String, arguments: Chunk[Json]) =
        dispatcher.dispatch(serviceId, version, method, arguments)
      def close = ZIO.unit
    })
    for {
      text <- client.readUtf8("note.txt").orDieWith(error => new Exception(error.toString))
      missing <- client.list("missing").either
    } yield {
      assertEquals(text, "custom:note.txt")
      assertEquals(missing, Left(FsError.NotFound("missing")))
      assertEquals(FilesystemRuntime.readContract.descriptor.streamMethods, Map("readBytes" -> true))
    }
  })

  test("read-only and write-only contracts have disjoint authority") {
    val read = FilesystemRuntime.readContract.descriptor
    val write = FilesystemRuntime.writeContract.descriptor
    assertEquals(read.methods, Set("readBytes", "readUtf8", "list"))
    assertEquals(write.methods, Set("writeUtf8", "createDirectories", "deleteFile", "deleteDirectory", "move"))
    assert(read.methods.intersect(write.methods).isEmpty)
    assertEquals(write.streamMethods, Map.empty[String, Boolean])
    assertEquals(FilesystemRuntime.contract.descriptor.methods, read.methods ++ write.methods)
  }
}
