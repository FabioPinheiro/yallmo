package fmgp.yallmo.repl

import java.nio.file.{Path, Paths}
import zio.*
import zio.http.Client
import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.provider.oai.{OAIAgent, OAILlmProvider}
import fmgp.yallmo.provider.Transcriber
import fmgp.yallmo.repl.terminal.{LineTerminalRepl, BasicTerminalRepl, TerminalMode}
import fmgp.yallmo.repl.voice.AudioRecorder
import fmgp.yallmo.tool.{BashExecutor, GitClient, WorkspaceFileSystem}

/** Starts an interactive LLM REPL using the OpenAI-compatible (OAI) adapter.
  *
  * Set `MY_MODEL_URL` and `MY_MODEL_NAME`, then run `sbt "runMain fmgp.yallmo.repl.ReplMain --ui=line"`. Add
  * `--storage <directory>` to persist named conversations in local storage.
  */
object ReplMain extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> Runtime.addLogger(ReplLoggers.console)

  private type ReplEnvironment = Agent[Any] & ReplTools.Environment
  private type ApplicationEnvironment = ReplEnvironment & OAIAgent & OAILlmProvider & AudioRecorder & Transcriber
  private final case class LaunchOptions(mode: TerminalMode, storageRoot: Option[Path])

  private val SystemPrompt =
    "You are Yallmo, a concise terminal assistant. Answer the user's request concisely. Use an advertised tool only when it is needed."

  private val replLayer: ZLayer[Scope, Throwable, ApplicationEnvironment] =
    ZLayer.make[ApplicationEnvironment](
      Client.default,
      OAILlmProvider.live,
      OAIAgent.live(AgentId("Gamma")),
      Transcriber.live,
      AudioRecorder.live,
      WorkspaceFileSystem.live,
      BashExecutor.live,
      GitClient.live
    )

  private def repl(
      availableTools: Chunk[AgentTool[ReplEnvironment]],
      toolAvailability: ReplToolAvailability,
      options: LaunchOptions
  ): ZIO[OAIAgent & OAILlmProvider & Scope, Throwable, ReplApplication[ReplEnvironment]] =
    for {
      model <- ZIO.serviceWith[OAILlmProvider](_.defaultModel)
      client <- ZIO.service[OAIAgent]
      humanChannel <- HumanTurnChannel.make
      userAgent = HumanAgent.make(AgentId("fabio"), humanChannel)
      agents = Chunk(userAgent, client)
      orchestrator <- options.storageRoot match
        case None           => OrchestratorLive.inMemory(agents)
        case Some(rootPath) => OrchestratorLive.filesystem(rootPath, agents)
      _ <- ZIO.foreachDiscard(options.storageRoot) { root =>
        ReplLoggers.initialize(root) *> Runtime.addLogger(ReplLoggers.filesAt(root)).build.unit
      }
      replOrchestrator = ReplOrchestratorLive.make(orchestrator, userAgent, client)
    } yield {
      val context = StaticContext[ReplEnvironment](SystemPrompt, tools = availableTools.map(_.definition))
      val agent: AgentInstance[ReplEnvironment] = NativeFunctionAgent[ReplEnvironment](client.id, model)
      val runner =
        new AgentRunner[ReplEnvironment](availableTools.map(tool => tool.definition.key -> tool).toMap, Map.empty)
      val persist: (ReplState, ReplTurn) => IO[YallmoError, ReplTurn] =
        replOrchestrator.persistTurn

      new ReplApplication[ReplEnvironment](
        new ReplSession[ReplEnvironment](runner, agent, context, afterSubmit = persist),
        toolAvailability,
        replOrchestrator
      )
    }

  override val run: ZIO[ZIOAppArgs & Scope, Any, ExitCode] = {
    for {
      _ <- Console.printLine(
        """ ██╗   ██╗ █████╗ ███╗   ███╗███╗   ███╗██╗      ██████╗ 
          | ╚██╗ ██╔╝██╔══██╗████╗ ████║████╗ ████║██║     ██╔═══██╗
          |  ╚████╔╝ ███████║██╔████╔██║██╔████╔██║██║     ██║   ██║
          |   ╚██╔╝  ██╔══██║██║╚██╔╝██║██║╚██╔╝██║██║     ██║   ██║
          |    ██║   ██║  ██║██║ ╚═╝ ██║██║ ╚═╝ ██║███████╗╚██████╔╝
          |    ╚═╝   ╚═╝  ╚═╝╚═╝     ╚═╝╚═╝     ╚═╝╚══════╝ ╚═════╝ 
          | This REPL is another AI harness project
          |""".stripMargin
      )
      options <-
        parseLaunchOptions // ZIO.fromEither(parseLaunchOptions(arguments)
      _ <- ZIO.scoped {
        (for {
          startupTools <- ReplTools.evaluate
          availableTools: Chunk[AgentTool[ReplEnvironment]] = startupTools.available
          application <- repl(availableTools, startupTools.availability, options)
          _ <- ZIO.logInfo(s"REPL started mode=$options.mode")
          _ <- options.mode match {
            case TerminalMode.Plain => new BasicTerminalRepl(application).run
            case TerminalMode.Line  =>
              ZIO.serviceWithZIO[AudioRecorder](recorder =>
                ZIO.serviceWithZIO[Transcriber](transcriber =>
                  LineTerminalRepl.runSystem(application, fallbackToPlain = false, recorder, transcriber)
                )
              )
            case TerminalMode.Auto =>
              ZIO.serviceWithZIO[AudioRecorder](recorder =>
                ZIO.serviceWithZIO[Transcriber](transcriber =>
                  LineTerminalRepl.runSystem(application, fallbackToPlain = true, recorder, transcriber)
                )
              )
            case TerminalMode.Tui =>
              ZIO.fail(UnsupportedOperationException("The full TUI is not implemented yet; use --ui=line."))
          }
        } yield ()).provideSomeLayer[Scope](replLayer)
      }
    } yield ()
  }
    .as(ExitCode.success)
    .catchAll(error => Console.printLineError(s"REPL failed: ${error.getMessage}").as(ExitCode.failure))

  private def parseLaunchOptions: ZIO[ZIOAppArgs, IllegalArgumentException, LaunchOptions] =
    for {
      arguments: Chunk[String] <- ZIOAppArgs.getArgs
      values = arguments.toList
      separate = values.zipWithIndex.collect { case ("--storage", index) => index }
      inline = values.zipWithIndex.collect { case (value, index) if value.startsWith("--storage=") => index }
      ret = (separate ++ inline) match
        case Nil => TerminalMode.parse(arguments).map(LaunchOptions(_, None))
        case index :: Nil if separate.contains(index) && index + 1 < values.size =>
          val root = Paths.get(values(index + 1))
          val ui = values.patch(index, Nil, 2)
          TerminalMode.parse(Chunk.fromIterable(ui)).map(LaunchOptions(_, Some(root)))
        case index :: Nil if inline.contains(index) =>
          val root = Paths.get(values(index).stripPrefix("--storage="))
          val ui = values.patch(index, Nil, 1)
          TerminalMode.parse(Chunk.fromIterable(ui)).map(LaunchOptions(_, Some(root)))
        case index :: Nil if separate.contains(index) => Left("--storage requires a directory")
        case _                                        => Left("--storage may appear only once")
      launchOptions <- ZIO.fromEither(ret).mapError(error => new IllegalArgumentException(error))
    } yield launchOptions

}
