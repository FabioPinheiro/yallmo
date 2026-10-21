package fmgp.yallmo.repl

import java.nio.file.{Path, Paths}
import fmgp.yallmo.model.AgentId
import zio.{Chunk, LogLevel}

/** Validated startup options. A configuration file is mandatory; no provider/model CLI overrides exist.
  *
  * @param configPath
  *   required JSON file to load, or initialize privately if missing
  * @param agentId
  *   optional initial agent override; otherwise use defaultAgentId, or start unselected when neither is present
  * @param storageRoot
  *   optional durable conversation directory; absent means process-local session storage
  * @param logLevel
  *   initial terminal log threshold for startup and REPL turns; file logging remains independent
  */
private[repl] final case class ReplLaunchOptions(
    configPath: Path,
    agentId: Option[AgentId] = None,
    storageRoot: Option[Path] = None,
    logLevel: LogLevel = LogLevel.Warning
)

private[repl] object ReplLaunchOptions {
  val Usage =
    "Usage: yallmo --config <file> [--agent <id>] [--storage <directory>] [--log-level <trace|debug|info|warning|error|fatal|off>]"

  private final case class Parsed(
      configPath: Option[Path] = None,
      agentId: Option[AgentId] = None,
      storageRoot: Option[Path] = None,
      logLevel: LogLevel = LogLevel.Warning
  )

  def parse(arguments: Chunk[String]): Either[String, ReplLaunchOptions] = {
    def path(value: String, key: String): Either[String, Path] =
      scala.util.Try(Paths.get(value)).toEither.left.map(_ => s"Invalid $key path")

    def logLevel(value: String): Either[String, LogLevel] =
      value.trim.toLowerCase(java.util.Locale.ROOT) match {
        case "trace"   => Right(LogLevel.Trace)
        case "debug"   => Right(LogLevel.Debug)
        case "info"    => Right(LogLevel.Info)
        case "warning" => Right(LogLevel.Warning)
        case "error"   => Right(LogLevel.Error)
        case "fatal"   => Right(LogLevel.Fatal)
        case "off"     => Right(LogLevel.None)
        case _         => Left("Invalid --log-level value. Expected trace, debug, info, warning, error, fatal or off.")
      }

    def update(options: Parsed, key: String, value: String): Either[String, Parsed] =
      key match {
        case "--config" => path(value, key).map(file => options.copy(configPath = Some(file)))
        case "--agent"  =>
          AgentId
            .from(value.trim)
            .left
            .map(error => s"Invalid --agent value: $error")
            .map(id => options.copy(agentId = Some(id)))
        case "--storage"   => path(value, key).map(root => options.copy(storageRoot = Some(root)))
        case "--log-level" => logLevel(value).map(level => options.copy(logLevel = level))
        case _             => Left(s"Unknown option '$key'.")
      }

    def loop(rest: List[String], seen: Set[String], options: Parsed): Either[String, ReplLaunchOptions] =
      rest match {
        case Nil =>
          options.configPath
            .toRight("--config is required.")
            .map(file => ReplLaunchOptions(file, options.agentId, options.storageRoot, options.logLevel))
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
              Set("--config", "--agent", "--storage", "--log-level").contains(key),
              (),
              if key.startsWith("-") then s"Unknown option '$key'."
              else s"Unexpected argument '$key'. Expected an option starting with --."
            )
            _ <- Either.cond(!seen.contains(key), (), s"$key may appear only once")
            supplied <- value.filter(_.trim.nonEmpty).toRight(s"$key requires a value")
            next <- update(options, key, supplied)
            result <- loop(remaining, seen + key, next)
          } yield result
      }

    loop(arguments.toList, Set.empty, Parsed()).left.map(error => s"$error\n$Usage")
  }
}
