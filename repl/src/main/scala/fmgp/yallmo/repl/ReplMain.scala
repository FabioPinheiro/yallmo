package fmgp.yallmo.repl

import zio.*
import fmgp.yallmo.model.UserId
import fmgp.yallmo.server.{OrchestratorClient, OrchestratorWSClient}
import fmgp.yallmo.repl.terminal.TerminalRepl

/** Starts the terminal client; configuration, storage and execution belong to the server. */
object ReplMain extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> Runtime.addLogger(ReplLoggers.console)

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
        ZIO.logLevelScoped(options.logLevel) *> launch(options)
      }
    } yield ()
  }
    .as(ExitCode.success)
    .catchAll(error => Console.printLineError(s"REPL failed: ${error.getMessage}").as(ExitCode.failure))

  /** Always connects, including the default endpoint. No embedded runtime or local-service fallback exists. */
  private[repl] def launch(
      options: ReplLaunchOptions,
      terminal: ConnectedReplApplication => Task[Unit] = TerminalRepl.runConnectedSystem,
      connect: (String, UserId) => ZIO[Scope, OrchestratorClient.Error, OrchestratorClient] = (endpoint, user) =>
        OrchestratorWSClient.connect(endpoint, userId = Some(user), clientName = "yallmo-repl")
  ): ZIO[Scope, Throwable, Unit] =
    for {
      user <- ZIO
        .fromEither(UserId.from(options.userName))
        .mapError(_ => new IllegalArgumentException("Invalid --user-name."))
      client <- connect(options.server, user)
        .mapError(_ => new IllegalStateException("Connected REPL connection failed; no local fallback."))
      application <- ConnectedReplApplication.make(client, UserId.chatAgent(user), options.agentId, options.logLevel)
      _ <- Console.printLine(application.notice)
      _ <- terminal(application)
    } yield ()

}
