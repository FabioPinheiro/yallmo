package fmgp.yallmo.repl

import fmgp.yallmo.model.AgentId
import zio.{Chunk, LogLevel}

/** CLI-only local identity and explicit selections; server configuration is not a REPL option. */
private[repl] final case class ReplLaunchOptions(
    userName: String,
    agentId: Option[AgentId] = None,
    transcriberAgentId: Option[AgentId] = None,
    logLevel: LogLevel = LogLevel.Warning,
    server: Option[String] = None
)

private[repl] object ReplLaunchOptions {
  val Usage =
    "Usage: yallmo --user-name <id> [--server <ws(s)://host:port/path>] [--agent <id>] [--transcriber-agent <id>] [--log-level <trace|debug|info|warning|error|fatal|off>]"

  private final case class Parsed(
      userName: Option[String] = None,
      agentId: Option[AgentId] = None,
      transcriberAgentId: Option[AgentId] = None,
      logLevel: LogLevel = LogLevel.Warning,
      server: Option[String] = None
  )

  private def validateServer(value: String): Either[String, String] = {
    val valid = scala.util.Try(new java.net.URI(value)).toOption.exists { uri =>
      Set("ws", "wss").contains(uri.getScheme) && uri.getHost != null &&
      uri.getRawUserInfo == null && uri.getRawQuery == null && uri.getRawFragment == null &&
      uri.getPort >= -1 && uri.getPort <= 65535 && uri.getPort != 0
    }
    Either.cond(valid, value, "Invalid --server endpoint. Expected ws/wss URI without credentials, query or fragment.")
  }

  def parse(arguments: Chunk[String]): Either[String, ReplLaunchOptions] = {
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
        case "--user-name" =>
          AgentId
            .from(value)
            .left
            .map(error => s"Invalid --user-name value: $error")
            .map(id => options.copy(userName = Some(AgentId.value(id))))
        case "--transcriber-agent" =>
          AgentId
            .from(value)
            .left
            .map(error => s"Invalid --transcriber-agent value: $error")
            .map(id => options.copy(transcriberAgentId = Some(id)))
        case "--agent" =>
          AgentId
            .from(value)
            .left
            .map(error => s"Invalid --agent value: $error")
            .map(id => options.copy(agentId = Some(id)))
        case "--server"    => validateServer(value).map(endpoint => options.copy(server = Some(endpoint)))
        case "--log-level" => logLevel(value).map(level => options.copy(logLevel = level))
        case _             => Left(s"Unknown option '$key'.")
      }

    def loop(rest: List[String], seen: Set[String], options: Parsed): Either[String, ReplLaunchOptions] =
      rest match {
        case Nil =>
          Either
            .cond(
              options.server.isEmpty || options.transcriberAgentId.isEmpty,
              (),
              "--transcriber-agent is unavailable with --server (connected mode is read-only)."
            )
            .flatMap(_ =>
              options.userName
                .toRight("--user-name is required.")
                .map(name =>
                  ReplLaunchOptions(
                    name,
                    options.agentId,
                    options.transcriberAgentId,
                    options.logLevel,
                    options.server
                  )
                )
            )
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
              Set("--user-name", "--agent", "--transcriber-agent", "--log-level", "--server").contains(key),
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
