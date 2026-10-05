package fmgp.yallmo.repl.terminal

import zio.Chunk

/** Requested local presentation mode. `Auto` may fall back; explicit modes never do. */
enum TerminalMode {
  case Auto
  case Plain
  case Line
  case Tui
}

object TerminalMode {
  def parse(arguments: Chunk[String]): Either[String, TerminalMode] =
    arguments.toList match {
      case Nil                    => Right(TerminalMode.Auto)
      case "--ui=auto" :: Nil     => Right(TerminalMode.Auto)
      case "--ui=plain" :: Nil    => Right(TerminalMode.Plain)
      case "--ui=line" :: Nil     => Right(TerminalMode.Line)
      case "--ui=tui" :: Nil      => Right(TerminalMode.Tui)
      case "--ui" :: value :: Nil => parseValue(value)
      case _                      => Left("Usage: yallmo [--ui=auto|plain|line|tui]")
    }

  private def parseValue(value: String): Either[String, TerminalMode] =
    value match {
      case "auto"  => Right(TerminalMode.Auto)
      case "plain" => Right(TerminalMode.Plain)
      case "line"  => Right(TerminalMode.Line)
      case "tui"   => Right(TerminalMode.Tui)
      case _       => Left(s"Unknown UI mode: $value. Expected auto, plain, line, or tui.")
    }
}
