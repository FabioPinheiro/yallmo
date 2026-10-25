package fmgp.yallmo.repl

import zio.*
import zio.http.Client
import fmgp.yallmo.model.AgentId
import fmgp.yallmo.server.OrchestratorWSClient
import fmgp.yallmo.repl.terminal.TerminalRepl
import fmgp.yallmo.repl.voice.AudioRecorder

/** Starts the local terminal */
object ReplMain extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> Runtime.addLogger(ReplLoggers.console)

  private type ApplicationEnvironment =
    Client & AudioRecorder

  private def replLayer: ZLayer[Scope, Throwable, ApplicationEnvironment] =
    ZLayer.make[ApplicationEnvironment](
      Client.default,
      AudioRecorder.live
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
      _ <- ZIO.scoped {
        ZIO.logLevelScoped(options.logLevel) *> launch(
          options,
          (for {
            startup <- ReplStartup.make(options)
            application <- startup.application
            _ <- ZIO.logInfo(s"REPL started selected=${startup.selected.nonEmpty}")
            _ <- Console.printLine(
              if startup.selected.isEmpty then "No agent selected. Press Esc, a to choose a configured agent."
              else "Configured agent selected."
            )
            _ <- ZIO.when(application.transcriber.isEmpty)(Console.printLine(voice.Transcriber.DisabledMessage))
            _ <- ZIO.serviceWithZIO[AudioRecorder](recorder =>
              TerminalRepl.runSystem(application, recorder, startup.browserAudioPort)
            )
          } yield ()).provideSomeLayer[Scope](replLayer)
        )
      }
    } yield ()
  }
    .as(ExitCode.success)
    .catchAll(error => Console.printLineError(s"REPL failed: ${error.getMessage}").as(ExitCode.failure))

  /** The explicit branch precedes every local service acquisition. Connection errors never enter localBootstrap. */
  private[repl] def launch(
      options: ReplLaunchOptions,
      localBootstrap: => ZIO[Scope, Throwable, Unit],
      terminal: ConnectedReplApplication => Task[Unit] = TerminalRepl.runConnectedSystem
  ): ZIO[Scope, Throwable, Unit] = options.server match {
    case None           => localBootstrap
    case Some(endpoint) =>
      for {
        _ <- ZIO
          .fail(new IllegalArgumentException("--transcriber-agent is unavailable with --server."))
          .when(options.transcriberAgentId.nonEmpty)
        user <- ZIO
          .fromEither(AgentId.from("user-" + options.userName))
          .mapError(_ => new IllegalArgumentException("Invalid --user-name."))
        client <- OrchestratorWSClient
          .connect(endpoint, humanId = Some(user), clientName = "yallmo-repl")
          .mapError(_ => new IllegalStateException("Connected REPL connection failed; no local fallback."))
        application <- ConnectedReplApplication.make(client, user, options.agentId, options.logLevel)
        _ <- Console.printLine(application.notice)
        _ <- terminal(application)
      } yield ()
  }

}
