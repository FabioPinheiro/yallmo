package fmgp.yallmo.server

import java.nio.file.{Files, Path}
import java.util.Locale
import scala.util.Try
import fmgp.yallmo.model.Agent
import fmgp.yallmo.orchestration.{OrchestratorLive, RuntimeInstances}
import zio.*

/** Standalone read-only WebSocket/SPA host. Embedded applications should supply their existing master instead. */
object OrchestratorServerMain extends ZIOAppDefault {
  private val Levels = "trace|debug|info|warning|error|fatal|off"
  private val Usage = s"Usage: server/run --storage <existing-directory> [--port <1-65535>] [--log-level <$Levels>]"

  private[server] final case class Options(storage: Path, port: Int = 8080, logLevel: LogLevel = LogLevel.Info)

  override val run: ZIO[ZIOAppArgs & Scope, Throwable, Unit] =
    for {
      args <- ZIOAppArgs.getArgs
      options <- ZIO.fromEither(parse(args.toList)).mapError(new IllegalArgumentException(_))
      // Install before creating the HTTP runtime so its connection fibers inherit the configured logger.
      _ <- configureLogging(options.logLevel).build
      _ <- ZIO.logInfo(s"Starting Orchestrator server (log-level=${options.logLevel.label})")
      _ <- ZIO.addFinalizer(ZIO.logInfo("Orchestrator server stopped"))
      _ <- ZIO
        .logWarning(
          "TRACE logs include full RPC payloads, transcripts and attachment data. Use only for private local debugging."
        )
        .when(options.logLevel == LogLevel.Trace)
      _ <- ZIO.logDebug(s"Opening storage ${options.storage.toAbsolutePath.normalize()}")
      exists <- ZIO.attemptBlocking(Files.isDirectory(options.storage))
      _ <- ZIO.fail(new IllegalArgumentException("Storage directory must already exist")).unless(exists)
      instances <- RuntimeInstances.fromAgents(Chunk.empty[Agent[Any]])
      master <- OrchestratorLive.filesystem(options.storage, instances)
      bound <- new OrchestratorWSServer(master).start(options.port)
      _ <- ZIO.logInfo(s"Orchestrator listening at http://127.0.0.1:$bound (JSON-RPC WebSocket: /rpc)")
      _ <- ZIO.never
    } yield ()

  /** Replace ZIO's INFO-filtered default console logger, rather than changing individual statements' severity. */
  private[server] def configureLogging(
      minimum: LogLevel,
      logger: ZLogger[String, Any] = ZLogger.default.map(line => scala.Console.println(line))
  ): ULayer[Unit] =
    Runtime.removeDefaultLoggers >>> Runtime.addLogger(
      logger.filterLogLevel(level => minimum != LogLevel.None && level >= minimum)
    )

  private[server] def parse(args: List[String]): Either[String, Options] = {
    def collect(rest: List[String], values: Map[String, String]): Either[String, Map[String, String]] = rest match {
      case Nil              => Right(values)
      case argument :: tail =>
        val (key, value, remaining) = argument.split("=", 2).toList match {
          case key :: value :: Nil => (key, Some(value), tail)
          case _                   =>
            tail match {
              case value :: more if !value.startsWith("--") => (argument, Some(value), more)
              case _                                        => (argument, None, tail)
            }
        }
        for {
          _ <- Either.cond(Set("--storage", "--port", "--log-level").contains(key), (), "Unknown server option")
          _ <- Either.cond(!values.contains(key), (), s"$key may appear only once")
          supplied <- value.filter(_.trim.nonEmpty).toRight(s"$key requires a value")
          parsed <- collect(remaining, values.updated(key, supplied))
        } yield parsed
    }

    (for {
      values <- collect(args, Map.empty)
      storage <- values.get("--storage").toRight("--storage is required")
      root <- Try(Path.of(storage)).toEither.left.map(_ => "Invalid --storage path")
      port <- values.get("--port").fold[Either[String, Int]](Right(8080)) { value =>
        value.toIntOption.filter(port => port >= 1 && port <= 65535).toRight("Invalid --port; expected 1-65535")
      }
      level <- values.get("--log-level").fold[Either[String, LogLevel]](Right(LogLevel.Info)) { value =>
        value.trim.toLowerCase(Locale.ROOT) match {
          case "trace"   => Right(LogLevel.Trace)
          case "debug"   => Right(LogLevel.Debug)
          case "info"    => Right(LogLevel.Info)
          case "warning" => Right(LogLevel.Warning)
          case "error"   => Right(LogLevel.Error)
          case "fatal"   => Right(LogLevel.Fatal)
          case "off"     => Right(LogLevel.None)
          case _         => Left(s"Invalid --log-level; expected $Levels")
        }
      }
    } yield Options(root, port, level)).left.map(error => s"$error\n$Usage")
  }
}
