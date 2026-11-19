package fmgp.yallmo.tool.scalamode

import fmgp.rpc.RpcWebSocket
import fmgp.yallmo.fs.FileSystem
import fmgp.yallmo.tool.ScalaModeResult
import java.nio.file.Files
import munit.FunSuite
import zio.*
import zio.http.Server

/** Opt-in real compiler and child-process RPC checks, matching ScalamodeServiceSuite's executable convention. */
final class FilesystemServiceCompilerSuite extends FunSuite {
  override val munitTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(10).minutes

  test("real snippets read streams, write through explicit services and cannot expand a read-only view") {
    val executable = sys.env.get("YALLMO_TEST_SCALA_CLI")
    assume(executable.nonEmpty, "Set YALLMO_TEST_SCALA_CLI for real pinned compiler / child RPC checks")
    val effect = ZIO
      .scoped(for {
        root <- ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("fs-service-compiler-")))(path =>
          ZIO.attempt {
            Files.deleteIfExists(path.resolve("note.txt"))
            Files.delete(path)
          }.orDie
        )
        fs <- FileSystem.open(root.toString).mapError(error => new Exception(error.toString))
        route <- RpcWebSocket.route()
        server <- Server.defaultWith(_.binding("127.0.0.1", 0)).build
        port <- Server.install(route.routes).provideEnvironment(server)
        endpoint = s"ws://127.0.0.1:$port/rpc"
        executor <- ZIO.service[ScalaCodeExecutor]
        both = FilesystemService.default(fs, Some(fs))
        written <- executor.executeWithServices(
          "import fmgp.yallmo.tool.scalamode.FilesystemModes\n" +
            "for { _ <- fs.writeUtf8(\"note.txt\", \"hello\", FilesystemModes.createNew); " +
            "text <- fs.readUtf8(\"note.txt\"); names <- fs.list(\".\") } yield hello.helloWorld + \"|\" + text + \"|\" + names.mkString(\",\")",
          both,
          120.seconds,
          16384
        )
        read = FilesystemService.read(fs, route, endpoint)
        streamed <- executor
          .executeWithServices("fs.readBytes(\"note.txt\").runCollect.map(_.size)", read, 120.seconds, 16384)
        denied <- executor.executeWithServices("fs.deleteFile(\"note.txt\")", read, 120.seconds, 16384)
        write = FilesystemService.write(fs, route, endpoint)
        deleted <- executor.executeWithServices("fs.deleteFile(\"note.txt\")", write, 120.seconds, 16384)
        missing <- executor.executeWithServices("fs.list(\".\")", write, 120.seconds, 16384)
      } yield {
        assertEquals(written.output, "Hello world|hello|note.txt\n", written.toString)
        assertEquals(both.inspections.map(_.parameter), List("fs", "hello"))
        assertEquals(streamed.output, "5\n", streamed.toString)
        assert(denied.result.isInstanceOf[ScalaModeResult.CompilationError], denied.toString)
        assertEquals(deleted.output, "()\n", deleted.toString)
        assert(missing.result.isInstanceOf[ScalaModeResult.CompilationError], missing.toString)
      })
      .provide(ScalaCodeExecutors.layer(executable.get))
    Unsafe.unsafe { unsafe ?=> Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }
  }
}
