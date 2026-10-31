package fmgp.yallmo.orchestration.config

import java.nio.file.Path
import zio.*

/** Offline, opt-in migration; does not start the server or initialize a missing source.
  *
  * sbt 'core/runMain fmgp.yallmo.orchestration.config.ConfigMigrationMain ./yallmo.json ./yallmo.yaml'
  */
object ConfigMigrationMain extends ZIOAppDefault {
  override val run: ZIO[ZIOAppArgs, Throwable, Unit] =
    for {
      args <- ZIOAppArgs.getArgs
      paths <- ZIO.attempt {
        require(
          args.size == 2,
          "Usage: core/runMain fmgp.yallmo.orchestration.config.ConfigMigrationMain <source.json> <destination.yaml>"
        )
        (Path.of(args(0)), Path.of(args(1)))
      }
      _ <- ConfigFile.migrateJsonToYaml(paths._1, paths._2)
      _ <- Console.printLine("YAML configuration created; source left unchanged.")
    } yield ()
}
