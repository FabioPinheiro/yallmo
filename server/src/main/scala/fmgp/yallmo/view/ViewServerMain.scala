package fmgp.yallmo.view

import java.nio.file.{Files, Path}
import fmgp.yallmo.model.Agent
import fmgp.yallmo.orchestration.{OrchestratorLive, RuntimeInstances}
import zio.*

/** Standalone viewer for filesystem-backed sessions; embedded applications should pass their existing orchestrator. */
object ViewServerMain extends ZIOAppDefault {
  private val Usage = "Usage: server/run --storage <existing-directory> [--port <1-65535>]"

  override val run: ZIO[ZIOAppArgs & Scope, Throwable, Unit] =
    for {
      args <- ZIOAppArgs.getArgs
      options <- ZIO.fromEither(parse(args.toList)).mapError(new IllegalArgumentException(_))
      (root, port) = options
      exists <- ZIO.attemptBlocking(Files.isDirectory(root))
      _ <- ZIO.fail(new IllegalArgumentException("Storage directory must already exist")).unless(exists)
      instances <- RuntimeInstances.fromAgents(Chunk.empty[Agent[Any]])
      orchestrator <- OrchestratorLive.filesystem(root, instances)
      bound <- new OrchestratorViewServer(orchestrator).start(port)
      _ <- Console.printLine(s"Orchestrator view: http://127.0.0.1:$bound")
      _ <- ZIO.never
    } yield ()

  private[view] def parse(args: List[String]): Either[String, (Path, Int)] = args match {
    case List("--storage", root)                 => Right((Path.of(root), 8080))
    case List("--storage", root, "--port", port) =>
      port.toIntOption
        .filter(value => value >= 1 && value <= 65535)
        .map(value => (Path.of(root), value))
        .toRight(Usage)
    case _ => Left(Usage)
  }
}
