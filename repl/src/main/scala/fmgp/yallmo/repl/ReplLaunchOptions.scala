package fmgp.yallmo.repl

import java.nio.file.{Path, Paths}
import fmgp.yallmo.repl.terminal.TerminalMode
import zio.Chunk

/** Explicit generation backend; subscription failures never select a different provider. */
private[repl] enum ReplProviderSelection {
  case Oai, ChatGpt
}

/** Validated startup options, parsed before any provider layers are constructed. */
private[repl] final case class ReplLaunchOptions(
    mode: TerminalMode = TerminalMode.Auto,
    storageRoot: Option[Path] = None,
    provider: ReplProviderSelection = ReplProviderSelection.Oai,
    model: Option[String] = None
)

private[repl] object ReplLaunchOptions {
  val Usage = "Usage: yallmo [--ui=auto|plain|line|tui] [--storage <directory>] " +
    "[--provider=oai|chatgpt] [--model <id>]"

  def parse(arguments: Chunk[String]): Either[String, ReplLaunchOptions] = {
    def update(options: ReplLaunchOptions, key: String, value: String): Either[String, ReplLaunchOptions] =
      key match {
        case "--ui"      => TerminalMode.parse(Chunk(s"--ui=$value")).map(mode => options.copy(mode = mode))
        case "--storage" =>
          scala.util
            .Try(Paths.get(value))
            .toEither
            .left
            .map(_ => "Invalid --storage directory")
            .map(path => options.copy(storageRoot = Some(path)))
        case "--model"    => Right(options.copy(model = Some(value)))
        case "--provider" =>
          value match {
            case "oai"                      => Right(options.copy(provider = ReplProviderSelection.Oai))
            case "chatgpt" | "openai-codex" => Right(options.copy(provider = ReplProviderSelection.ChatGpt))
            case _                          => Left("Unknown provider. Expected oai or chatgpt (alias openai-codex).")
          }
        case _ => Left(Usage)
      }

    def loop(rest: List[String], seen: Set[String], options: ReplLaunchOptions): Either[String, ReplLaunchOptions] =
      rest match {
        case Nil              => Right(options)
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
            _ <- Either.cond(Set("--ui", "--storage", "--provider", "--model").contains(key), (), Usage)
            _ <- Either.cond(!seen.contains(key), (), s"$key may appear only once")
            supplied <- value.filter(_.trim.nonEmpty).toRight(s"$key requires a value")
            next <- update(options, key, supplied)
            result <- loop(remaining, seen + key, next)
          } yield result
      }

    loop(arguments.toList, Set.empty, ReplLaunchOptions())
  }
}
