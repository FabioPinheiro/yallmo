package fmgp.yallmo.server

import java.nio.file.Path
import java.util.Locale
import scala.util.Try
import fmgp.yallmo.server.application.ServerApplication
import zio.*
import fmgp.rpc.RpcWebSocket
import zio.http.Server

/** Standalone trusted-environment WebSocket/SPA host; execution requires a configured backend and claimed human ID.
  * Embedded applications should supply their existing master instead.
  */
object OrchestratorServerMain extends ZIOAppDefault {
  private val Levels = "trace|debug|info|warning|error|fatal|off"
  private val Usage =
    s"Usage: server/run --storage <existing-directory> [--config <path> [--workspace <existing-directory>]] [--port <1-65535>] [--log-level <$Levels>]"

  private[server] final case class Options(
      storage: Path,
      port: Int = 8080,
      logLevel: LogLevel = LogLevel.Info,
      config: Option[Path] = None,
      workspace: Option[Path] = None
  )

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
      _ <- ZIO
        .scoped(for {
          application <- options.config match {
            case None    => ServerApplication.open(options.storage, options.config, options.workspace)
            case Some(_) =>
              for {
                route <- RpcWebSocket.route()
                http <- Server.defaultWith(_.binding("127.0.0.1", 0)).build
                port <- Server.install(route.routes).provideEnvironment(http)
                application <- ServerApplication
                  .openRpc(
                    options.storage,
                    options.config,
                    options.workspace,
                    s"ws://127.0.0.1:$port/rpc"
                  )
                  .provideSomeEnvironment[Scope & ServerApplication.Environment](_.add[RpcWebSocket.RpcRoute](route))
              } yield application
          }
          bound <- new OrchestratorWSServer(application.master, application.configured)
            .start(options.port)
          _ <- ZIO.logInfo(s"Orchestrator listening at http://0.0.0.0:$bound (JSON-RPC WebSocket: /rpc)")
          _ <- ZIO.never
        } yield ())
        .provideLayer(ServerApplication.live)
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
          _ <- Either.cond(
            Set("--storage", "--port", "--log-level", "--config", "--workspace").contains(key),
            (),
            "Unknown server option"
          )
          _ <- Either.cond(!values.contains(key), (), s"$key may appear only once")
          supplied <- value.filter(_.trim.nonEmpty).toRight(s"$key requires a value")
          parsed <- collect(remaining, values.updated(key, supplied))
        } yield parsed
    }

    (for {
      values <- collect(args, Map.empty)
      storage <- values.get("--storage").toRight("--storage is required")
      root <- Try(Path.of(storage)).toEither.left.map(_ => "Invalid --storage path")
      config <- values.get("--config").traversePath("--config")
      workspace <- values.get("--workspace").traversePath("--workspace")
      _ <- Either.cond(workspace.isEmpty || config.nonEmpty, (), "--workspace requires --config")
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
    } yield Options(root, port, level, config, workspace)).left.map(error => s"$error\n$Usage")
  }

  extension (value: Option[String])
    private def traversePath(option: String): Either[String, Option[Path]] =
      value match {
        case None       => Right(None)
        case Some(path) => Try(Path.of(path)).toEither.left.map(_ => s"Invalid $option path").map(Some(_))
      }
}
