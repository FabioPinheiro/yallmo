package fmgp.yallmo.server

import fmgp.yallmo.fs.FileSystem
import fmgp.yallmo.server.application.ServerApplication
import fmgp.yallmo.tool.sdlc.SdlcService
import java.nio.file.Path
import zio.*

/** Offline checkpoint command. The normal server-root lease prevents racing a running standalone server. */
object SdlcMaintenanceMain extends ZIOAppDefault {
  override val run: ZIO[ZIOAppArgs & Scope, Throwable, Unit] =
    for {
      args <- ZIOAppArgs.getArgs
      _ <- ZIO
        .fail(new IllegalArgumentException("Usage: SdlcMaintenanceMain <existing-server-storage>"))
        .unless(args.size == 1)
      root <- ZIO.attemptBlocking(Path.of(args.head).toRealPath())
      _ <- ZIO
        .scoped {
          for {
            _ <- ServerApplication.lease(root)
            filesystem <- FileSystem.open(root.toString).mapError(error => new java.io.IOException(error.toString))
            service <- SdlcService.make(filesystem, filesystem)
            _ <- service.checkpoint.mapError(error => new java.io.IOException(error.toString))
            _ <- Console.printLine(
              "SDLC checkpoint complete. Current records are unchanged; history backups are retained."
            )
          } yield ()
        }
    } yield ()
}
