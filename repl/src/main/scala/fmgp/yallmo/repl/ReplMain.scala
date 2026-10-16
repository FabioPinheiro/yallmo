package fmgp.yallmo.repl

import zio.*
import zio.http.Client
import fmgp.yallmo.model.*
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.provider.Transcriber
import fmgp.yallmo.repl.terminal.{LineTerminalRepl, BasicTerminalRepl, TerminalMode}
import fmgp.yallmo.repl.voice.AudioRecorder
import fmgp.yallmo.tool.{BashExecutor, GitClient, WorkspaceFileSystem}

/** Starts the native-agent REPL. OAI remains the default; `--provider=chatgpt` explicitly selects subscription OAuth.
  * Add `--model <id>` to override the model and `--storage <directory>` to persist conversations.
  */
object ReplMain extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> Runtime.addLogger(ReplLoggers.console)

  private type ReplEnvironment = Agent[Any] & ReplTools.Environment
  private type ApplicationEnvironment = ReplEnvironment & ReplProvider & AudioRecorder & Transcriber & SubscriptionLogin

  private val SystemPrompt =
    "You are Yallmo, a concise terminal assistant. Answer the user's request concisely. Use an advertised tool only when it is needed."

  private def replLayer(options: ReplLaunchOptions): ZLayer[Scope, Throwable, ApplicationEnvironment] =
    ZLayer.make[ApplicationEnvironment](
      Client.default,
      SubscriptionLogin.live,
      ReplProvider.layer(options),
      ZLayer.fromFunction((provider: ReplProvider) => provider.agent),
      ReplProvider.transcription,
      AudioRecorder.live,
      WorkspaceFileSystem.live,
      BashExecutor.live,
      GitClient.live
    )

  private def repl(
      availableTools: Chunk[AgentTool[ReplEnvironment]],
      toolAvailability: ReplToolAvailability,
      options: ReplLaunchOptions
  ): ZIO[ReplProvider & Scope, Throwable, ReplApplication[ReplEnvironment]] =
    for {
      provider <- ZIO.service[ReplProvider]
      model = provider.model
      client = provider.agent
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
      val agent = NativeFunctionAgent[ReplEnvironment](client.id, model)
      val runner =
        new AgentRunner[ReplEnvironment](availableTools.map(tool => tool.definition.key -> tool).toMap, Map.empty)
      val persist: (ReplState, ReplTurn) => IO[YallmoError, ReplTurn] =
        replOrchestrator.persistTurn

      new ReplApplication[ReplEnvironment](
        new ReplSession[ReplEnvironment](runner, agent, context, afterSubmit = persist),
        toolAvailability,
        replOrchestrator,
        activeAgent = Some(ReplActiveAgent.fromNative(provider.selection, agent))
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
      arguments <- ZIOAppArgs.getArgs
      options <- ZIO.fromEither(ReplLaunchOptions.parse(arguments)).mapError(new IllegalArgumentException(_))
      _ <- ZIO.scoped {
        (for {
          startupTools <- ReplTools.evaluate
          availableTools: Chunk[AgentTool[ReplEnvironment]] = startupTools.available
          application <- repl(availableTools, startupTools.availability, options)
          _ <- ZIO.logInfo(s"REPL started mode=${options.mode} provider=${options.provider}")
          login <- ZIO.service[SubscriptionLogin]
          _ <- options.mode match {
            case TerminalMode.Plain => new BasicTerminalRepl(application, login).run
            case TerminalMode.Line  =>
              ZIO.serviceWithZIO[AudioRecorder](recorder =>
                ZIO.serviceWithZIO[Transcriber](transcriber =>
                  LineTerminalRepl.runSystem(application, fallbackToPlain = false, recorder, transcriber, login)
                )
              )
            case TerminalMode.Auto =>
              ZIO.serviceWithZIO[AudioRecorder](recorder =>
                ZIO.serviceWithZIO[Transcriber](transcriber =>
                  LineTerminalRepl.runSystem(application, fallbackToPlain = true, recorder, transcriber, login)
                )
              )
            case TerminalMode.Tui =>
              ZIO.fail(UnsupportedOperationException("The full TUI is not implemented yet; use --ui=line."))
          }
        } yield ()).provideSomeLayer[Scope](replLayer(options))
      }
    } yield ()
  }
    .as(ExitCode.success)
    .catchAll(error => Console.printLineError(s"REPL failed: ${error.getMessage}").as(ExitCode.failure))

}
