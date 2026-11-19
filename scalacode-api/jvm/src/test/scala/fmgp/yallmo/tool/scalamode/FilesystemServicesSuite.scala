package fmgp.yallmo.tool.scalamode

import fmgp.rpc.{RpcWebSocketClient, RpcConnection, RpcWebSocket}
import fmgp.yallmo.fs.*
import java.nio.file.Files
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import zio.*
import zio.http.Server

final class FilesystemServicesSuite extends FunSuite {
  private def run[A](effect: ZIO[Any, Any, A]): A =
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def withConnection[A](
      make: (FileSystem, ScalamodePlatform.RpcRoute, String) => ScalamodeService
  )(body: (ScalamodeService, RpcConnection) => ZIO[Any, Any, A]): A = run(ZIO.scoped(for {
    root <- ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("fs-service-")))(path =>
      ZIO.attempt {
        val paths = Files.walk(path)
        try paths.iterator().asScala.toList.reverse.foreach(Files.delete(_))
        finally paths.close()
      }.orDie
    )
    _ <- ZIO.attempt(Files.writeString(root.resolve("note.txt"), "héllo 🌍"))
    _ <- ZIO.attempt(Files.write(root.resolve("bytes.bin"), (0 to 255).map(_.toByte).toArray))
    fs <- FileSystem.open(root.toString)
    route <- RpcWebSocket.route()
    server <- Server.defaultWith(_.binding("127.0.0.1", 0)).build
    port <- Server.install(route.routes).provideEnvironment(server)
    service = make(fs, route, s"ws://127.0.0.1:$port/rpc")
    ticket <- route.registerScoped(service.rpcBinding.get.dispatcher)
    connection <- RpcWebSocketClient.connect(port, ticket)
    result <- body(service, connection)
  } yield result))

  test(
    "FsRead service preserves UTF-8, structured listings, binary streams and typed failures without write authority"
  ) {
    withConnection(FilesystemService.read) { (service, connection) =>
      val read = FilesystemRuntime.readContract.client(connection)
      val write = FilesystemRuntime.writeContract.client(connection)
      for {
        text <- read.readUtf8("note.txt")
        names <- read.list(".")
        bytes <- read.readBytes("bytes.bin").runCollect
        missing <- read.readUtf8("missing").either
        traversal <- read.readBytes("../outside").runDrain.either
        denied <- write.deleteFile("note.txt").either
        retained <- read.readUtf8("note.txt")
      } yield {
        assertEquals(text, "héllo 🌍")
        assertEquals(retained, text)
        assertEquals(names, Chunk("bytes.bin", "note.txt"))
        assertEquals(bytes, Chunk.fromIterable((0 to 255).map(_.toByte)))
        assertEquals(missing, Left(FsError.NotFound("missing")))
        assert(traversal.left.toOption.exists(_.isInstanceOf[FsError.InvalidPath]))
        assertEquals(denied, Left(FsError.IoFailure(".", "RPC UnknownService")))
        assertEquals(service.binding.apiTypes, Seq("fmgp.yallmo.fs.FsRead"))
        assert(!service.scalaApi.get.contains("def writeUtf8"))
      }
    }
  }

  test("FsWrite service retains explicit modes and typed failures, and binds no read contract") {
    withConnection(FilesystemService.write) { (service, connection) =>
      val write = FilesystemRuntime.writeContract.client(connection)
      for {
        _ <- write.createDirectories("docs/nested")
        _ <- write.writeUtf8("docs/new.txt", "first", WriteMode.CreateNew)
        duplicate <- write.writeUtf8("docs/new.txt", "lost", WriteMode.CreateNew).either
        _ <- write.writeUtf8("docs/new.txt", "replacement", WriteMode.Replace)
        _ <- write.writeUtf8("docs/new.txt", " tail", WriteMode.Append)
        missingMove <- write.move("missing.txt", "docs/moved.txt", MoveMode.FailIfExists).either
        nonempty <- write.deleteDirectory("docs", DirectoryDeleteMode.EmptyOnly).either
        _ <- write.deleteFile("docs/new.txt")
        _ <- write.deleteDirectory("docs", DirectoryDeleteMode.Recursive)
        missing <- write.deleteFile("docs/new.txt").either
        denied <- FilesystemRuntime.readContract.client(connection).list(".").either
        traversal <- write.writeUtf8("../outside", "forbidden", WriteMode.Replace).either
      } yield {
        assertEquals(duplicate, Left(FsError.AlreadyExists("docs/new.txt")))
        assertEquals(missingMove, Left(FsError.NotFound("missing.txt")))
        assertEquals(nonempty, Left(FsError.DirectoryNotEmpty("docs")))
        assertEquals(missing, Left(FsError.NotFound("docs/new.txt")))
        assertEquals(denied, Left(FsError.IoFailure(".", "RPC UnknownService")))
        assert(traversal.left.toOption.exists(_.isInstanceOf[FsError.InvalidPath]))
        assertEquals(service.binding.apiTypes, Seq("fmgp.yallmo.tool.scalamode.FsWriteApi"))
        assert(!service.scalaApi.get.contains("def readUtf8"))
        assert(!service.scalaApi.get.contains("def writeBytes"))
      }
    }
  }

  test("combined service explicitly shares both capabilities and inspector documentation contains no transport") {
    withConnection((fs, route, endpoint) => FilesystemService.readWrite(fs, fs, route, endpoint)) {
      (service, connection) =>
        val fs = FilesystemRuntime.contract.client(connection)
        for {
          _ <- fs.writeUtf8("combined.txt", "first", WriteMode.CreateNew)
          _ <- fs.writeUtf8("combined.txt", " second", WriteMode.Append)
          text <- fs.readUtf8("combined.txt")
          names <- fs.list(".")
        } yield {
          assertEquals(text, "first second")
          assert(names.contains("combined.txt"))
          val definition = ScalaTool.withServices(service).definition
          assertEquals(definition.inputSchema, ScalaTool.definition.inputSchema)
          assertEquals(definition.scalamode.get.services, List(service.inspection))
          assert(service.scalaApi.get.contains("def readBytes"))
          assert(service.scalaApi.get.contains("def writeUtf8"))
          assert(!definition.description.contains("ws://"))
          assert(!definition.description.contains("FilesystemRuntime"))
        }
    }
  }
}
