package fmgp.yallmo.repl

import zio.*
import zio.http.Client
import fmgp.yallmo.orchestration.AgentTool
import fmgp.yallmo.orchestration.config.ConfigFile
import fmgp.yallmo.repl.terminal.TerminalRepl
import fmgp.yallmo.repl.voice.AudioRecorder
import fmgp.yallmo.tool.{BashExecutor, GitClient, ScalaCodeExecutor, WorkspaceFileSystem}

/** Starts the config-driven REPL. --config is required; --agent overrides its optional startup default. Missing files
  * are initialized privately with empty catalogs. Without a selected agent, commands remain available but prompts are
  * rejected. --storage persists conversations. --log-level sets the initial terminal log threshold. The JLine client
  * requires an interactive terminal.
  */
object ReplMain extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> Runtime.addLogger(ReplLoggers.console)

  private type ReplEnvironment = ReplTools.Environment
  private type ApplicationEnvironment =
    ReplEnvironment & Client & AudioRecorder

  private val replLayer: ZLayer[Scope, Throwable, ApplicationEnvironment] =
    ZLayer.make[ApplicationEnvironment](
      Client.default,
      AudioRecorder.live,
      WorkspaceFileSystem.live,
      BashExecutor.live,
      GitClient.live,
      ScalaCodeExecutor.live
    )

  override val run: ZIO[ZIOAppArgs & Scope, Any, ExitCode] = {
    for {
      _ <- Console.printLine(
        """  ██╗   ██╗ █████╗ ██╗     ██╗     ███╗   ███╗ ██████╗ 
          |  ╚██╗ ██╔╝██╔══██╗██║     ██║     ████╗ ████║██╔═══██╗
          |   ╚████╔╝ ███████║██║     ██║     ██╔████╔██║██║   ██║
          |    ╚██╔╝  ██╔══██║██║     ██║     ██║╚██╔╝██║██║   ██║
          |     ██║   ██║  ██║███████╗███████╗██║ ╚═╝ ██║╚██████╔╝
          |     ╚═╝   ╚═╝  ╚═╝╚══════╝╚══════╝╚═╝     ╚═╝ ╚═════╝ 
          | This REPL is another AI harness for the YALLMO project
          |""".stripMargin
      )
      arguments <- ZIOAppArgs.getArgs
      options <- ZIO.fromEither(ReplLaunchOptions.parse(arguments)).mapError(new IllegalArgumentException(_))
      userName <- System.property("user.name").map(_.filter(_.trim.nonEmpty).getOrElse("user"))
      config <- ConfigFile.loadOrInitialize(options.configPath, userName)
      _ <- ZIO.scoped {
        ZIO.logLevelScoped(options.logLevel) *> (for {
          startup <- ReplStartup.make(config, options)
          startupTools <- ReplTools.evaluate
          availableTools: Chunk[AgentTool[ReplEnvironment]] = startupTools.available
          application <- startup.application(availableTools, startupTools.availability)
          _ <- ZIO.foreachDiscard(options.storageRoot) { root =>
            ReplLoggers.initialize(root) *> Runtime.addLogger(ReplLoggers.filesAt(root)).build.unit
          }
          _ <- ZIO.logInfo(s"REPL started selected=${startup.selected.nonEmpty}")
          _ <- Console.printLine(
            if startup.selected.isEmpty then "No agent selected. Press Esc, a to choose a configured agent."
            else "Configured agent selected."
          )
          _ <- ZIO.when(application.transcriber.isEmpty)(Console.printLine(voice.Transcriber.DisabledMessage))
          _ <- ZIO.serviceWithZIO[AudioRecorder](recorder =>
            TerminalRepl.runSystem(application, recorder, config.browserAudioPort)
          )
        } yield ()).provideSomeLayer[Scope](replLayer)
      }
    } yield ()
  }
    .as(ExitCode.success)
    .catchAll(error => Console.printLineError(s"REPL failed: ${error.getMessage}").as(ExitCode.failure))

}
