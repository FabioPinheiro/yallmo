package fmgp.yallmo.repl.grammar

import zio.{Chunk, LogLevel}

/** A completion request expressed independently of any line-editor library. */
final case class CompletionRequest(text: String, cursor: Int)

/** A single semantic completion candidate. */
final case class CompletionCandidate(value: String, description: String)

/** Candidates replace the text between `replacementStart` and `replacementEnd`. */
final case class CompletionResult(
    replacementStart: Int,
    replacementEnd: Int,
    candidates: Chunk[CompletionCandidate]
)

/** Help derived from the registered command grammar. */
final case class CommandHelp(usage: String, description: String)

/** A syntax error reported before an input can reach the agent. */
final case class ReplSyntaxError(input: String, message: String, suggestions: Chunk[CompletionCandidate])

/** The single source of truth for the REPL command language.
  *
  * Parsing, completion, and help are all derived from [[definitions]]. Text beginning with `/` is a command; prefix a
  * prompt with `//` to send a literal leading slash to the agent.
  */
object ReplGrammar {
  private final case class CommandDefinition(
      canonicalName: String,
      aliases: Chunk[String],
      usage: String,
      description: String,
      parseTail: String => Either[String, ReplCommand],
      completions: Chunk[CompletionCandidate] = Chunk.empty
  ) {
    val names: Chunk[String] = canonicalName +: aliases

    def parses(name: String): Boolean = names.contains(name)

    def help: CommandHelp = CommandHelp(usage, description)
  }

  private final case class ArgumentDefinition(
      canonicalName: String,
      aliases: Chunk[String],
      description: String,
      command: ReplCommand
  ) {
    val names: Chunk[String] = canonicalName +: aliases

    def parses(name: String): Boolean = names.contains(name)
  }

  private val debugArguments = Chunk(
    ArgumentDefinition(
      "print",
      Chunk.empty,
      "Print the snapshot as JSONL.",
      ReplCommand.Debug(DebugTarget.Display)
    ),
    ArgumentDefinition(
      "export",
      Chunk.empty,
      "Export the snapshot through this client.",
      ReplCommand.Debug(DebugTarget.Export)
    ),
    ArgumentDefinition(
      "request",
      Chunk.empty,
      "Display the latest request payload for the current session.",
      ReplCommand.Debug(DebugTarget.ProviderRequest)
    )
  )

  private val logArguments = Chunk(
    ArgumentDefinition("trace", Chunk.empty, "Show all application logs.", ReplCommand.Log(LogLevel.Trace)),
    ArgumentDefinition(
      "debug",
      Chunk.empty,
      "Show debug and higher application logs.",
      ReplCommand.Log(LogLevel.Debug)
    ),
    ArgumentDefinition(
      "info",
      Chunk.empty,
      "Show informational and higher application logs.",
      ReplCommand.Log(LogLevel.Info)
    ),
    ArgumentDefinition(
      "warning",
      Chunk.empty,
      "Show warnings and higher application logs.",
      ReplCommand.Log(LogLevel.Warning)
    ),
    ArgumentDefinition(
      "error",
      Chunk.empty,
      "Show errors and fatal application logs.",
      ReplCommand.Log(LogLevel.Error)
    ),
    ArgumentDefinition("fatal", Chunk.empty, "Show fatal application logs only.", ReplCommand.Log(LogLevel.Fatal)),
    ArgumentDefinition("off", Chunk.empty, "Suppress application logs for REPL turns.", ReplCommand.Log(LogLevel.None))
  )

  private val definitions: Chunk[CommandDefinition] = Chunk(
    CommandDefinition(
      canonicalName = "/help",
      aliases = Chunk.empty,
      usage = "/help",
      description = "Show available commands.",
      parseTail = noArguments(ReplCommand.Help)
    ),
    CommandDefinition(
      canonicalName = "/debug",
      aliases = Chunk.empty,
      usage = "/debug <print|export|request>",
      description = "Display or export the transcript, or show the current session's latest provider request.",
      parseTail = parseDebug,
      completions = debugCandidates("")
    ),
    CommandDefinition(
      canonicalName = "/history",
      aliases = Chunk.empty,
      usage = "/history",
      description = "Show the agent transcript.",
      parseTail = noArguments(ReplCommand.History)
    ),
    CommandDefinition(
      canonicalName = "/tools",
      aliases = Chunk.empty,
      usage = "/tools",
      description = "Show startup tool availability.",
      parseTail = noArguments(ReplCommand.Tools)
    ),
    CommandDefinition(
      canonicalName = "/log",
      aliases = Chunk.empty,
      usage = "/log <trace|debug|info|warning|error|fatal|off>",
      description = "Set the minimum log level for subsequent REPL turns.",
      parseTail = parseLog,
      completions = logCandidates("")
    ),
    CommandDefinition(
      canonicalName = "/new",
      aliases = Chunk.empty,
      usage = "/new [name]",
      description = "Start a fresh durable session, optionally with a name.",
      parseTail = parseNew
    ),
    CommandDefinition(
      canonicalName = "/session",
      aliases = Chunk.empty,
      usage = "/session <list|resume <id>>",
      description = "List durable sessions or resume one by ID.",
      parseTail = parseSession,
      completions = sessionCandidates("")
    ),
    CommandDefinition(
      canonicalName = "/exit",
      aliases = Chunk.empty,
      usage = "/exit",
      description = "Exit this client.",
      parseTail = noArguments(ReplCommand.Exit)
    )
  )

  def parse(line: String): Either[ReplSyntaxError, ReplInput] = {
    val trimmed = line.trim
    if trimmed.isEmpty then Right(ReplInput.Empty)
    else if trimmed.startsWith("//") then prompt(trimmed.drop(1), line)
    else if trimmed.startsWith("/") then parseCommand(trimmed, line)
    else prompt(trimmed, line)
  }

  def complete(request: CompletionRequest): CompletionResult = {
    val cursor = request.cursor.max(0).min(request.text.length)
    val prefix = request.text.take(cursor)
    val trimmed = prefix.trim

    if trimmed.startsWith("//") || !trimmed.startsWith("/") then CompletionResult(cursor, cursor, Chunk.empty)
    else {
      val words = trimmed.split("\\s+", -1).toList
      val hasTrailingWhitespace = prefix.lastOption.exists(_.isWhitespace)
      words match {
        case "/debug" :: Nil if hasTrailingWhitespace =>
          CompletionResult(cursor, cursor, debugCandidates(""))
        case "/log" :: Nil if hasTrailingWhitespace =>
          CompletionResult(cursor, cursor, logCandidates(""))
        case "/session" :: Nil if hasTrailingWhitespace =>
          CompletionResult(cursor, cursor, sessionCandidates(""))
        case command :: Nil =>
          val start = prefix.lastIndexOf(command)
          CompletionResult(start, cursor, commandCandidates(command))
        case command :: argument :: Nil if command == "/debug" || command == "/log" || command == "/session" =>
          val start = if hasTrailingWhitespace then cursor else prefix.lastIndexOf(argument)
          val fragment = if hasTrailingWhitespace then "" else argument
          val candidates =
            if command == "/debug" then debugCandidates(fragment)
            else if command == "/log" then logCandidates(fragment)
            else sessionCandidates(fragment)
          CompletionResult(start, cursor, candidates)
        case _ => CompletionResult(cursor, cursor, Chunk.empty)
      }
    }
  }

  def help(): Chunk[CommandHelp] = definitions.map(_.help)

  private def parseCommand(
      commandLine: String,
      originalInput: String
  ): Either[ReplSyntaxError, ReplInput] = {
    val withoutSlash = commandLine.drop(1)
    val pieces = withoutSlash.split("\\s+", 2)
    val commandName = pieces.headOption.fold("/")(name => s"/$name")
    val tail = if pieces.length > 1 then pieces(1).trim else ""

    definitions.find(_.parses(commandName)) match {
      case Some(definition) =>
        definition
          .parseTail(tail)
          .map(command => ReplInput.Command(command))
          .left
          .map(message => ReplSyntaxError(originalInput, message, definition.completions))
      case None =>
        Left(
          ReplSyntaxError(
            originalInput,
            s"Unknown command: $commandName",
            commandCandidates(commandName)
          )
        )
    }
  }

  private def prompt(value: String, originalInput: String): Either[ReplSyntaxError, ReplInput] =
    UserPrompt
      .from(value)
      .map(ReplInput.Prompt.apply)
      .toRight(ReplSyntaxError(originalInput, "Expected a non-empty prompt.", Chunk.empty))

  private def noArguments(command: ReplCommand)(tail: String): Either[String, ReplCommand] =
    Either.cond(tail.isEmpty, command, "This command does not accept arguments.")

  private def parseNew(tail: String): Either[String, ReplCommand] =
    tail.split("\\s+", -1).toList match
      case Nil | "" :: Nil => Right(ReplCommand.NewSession(None))
      case name :: Nil     => Right(ReplCommand.NewSession(Some(name)))
      case _               => Left("Expected zero or one session name.")

  private def parseDebug(tail: String): Either[String, ReplCommand] =
    debugArguments
      .find(_.parses(tail))
      .map(_.command)
      .toRight(s"Expected one of: ${debugArguments.map(_.canonicalName).mkString(", ")}.")

  private def parseLog(tail: String): Either[String, ReplCommand] =
    logArguments
      .find(_.parses(tail))
      .map(_.command)
      .toRight(s"Expected one of: ${logArguments.map(_.canonicalName).mkString(", ")}.")

  private def parseSession(tail: String): Either[String, ReplCommand] =
    tail.split("\\s+").toList match
      case "list" :: Nil         => Right(ReplCommand.Session(SessionCommand.List))
      case "resume" :: id :: Nil => Right(ReplCommand.Session(SessionCommand.Resume(id)))
      case _                     => Left("Expected /session list or /session resume <id>.")

  private def commandCandidates(fragment: String): Chunk[CompletionCandidate] =
    definitions
      .flatMap(definition => definition.names.map(name => CompletionCandidate(name, definition.description)))
      .filter(_.value.startsWith(fragment))

  private def debugCandidates(fragment: String): Chunk[CompletionCandidate] =
    debugArguments
      .flatMap(argument => argument.names.map(name => CompletionCandidate(name, argument.description)))
      .filter(_.value.startsWith(fragment))

  private def logCandidates(fragment: String): Chunk[CompletionCandidate] =
    logArguments
      .flatMap(argument => argument.names.map(name => CompletionCandidate(name, argument.description)))
      .filter(_.value.startsWith(fragment))

  private def sessionCandidates(fragment: String): Chunk[CompletionCandidate] =
    Chunk(
      CompletionCandidate("list", "List durable sessions."),
      CompletionCandidate("resume", "Resume a session by ID.")
    ).filter(_.value.startsWith(fragment))
}
