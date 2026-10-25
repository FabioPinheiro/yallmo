package fmgp.yallmo.repl

import fmgp.yallmo.server.application.ProviderCatalog
import fmgp.yallmo.model.{Agent, AgentId, YallmoError}
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.server.application.ApplicationRuntime
import fmgp.yallmo.repl.voice.Transcriber
import fmgp.yallmo.provider.openai.OpenAiCodexAuth
import java.nio.file.Path
import zio.*
import zio.http.Client

/** Configured startup and lazy agent selection, sharing runtime caches and session storage across selections. */
private[repl] final class ReplStartup private (
    runtime: ApplicationRuntime,
    val userAgent: Agent[Any],
    val selected: Option[ReplSelectedAgent],
    val providerCatalog: ProviderCatalog,
    val providerLogin: ProviderLoginService,
    options: ReplLaunchOptions
) {
  val browserAudioPort: Int = runtime.browserAudioPort

  val instances: RuntimeInstances[Any] = runtime.instances

  /** Builds storage and a session. Unselected sessions expose commands but reject prompts without running tools. */
  def application: ZIO[Scope, YallmoError, ReplApplication[Any]] =
    for {
      opened <- runtime.openWorkspace(Path.of("").toAbsolutePath.normalize)
      (assembled, availability) = opened
      context = assembled.context(None)
      orchestrator = assembled.orchestrator
      voice <- ReplStartup.voice(runtime, options.transcriberAgentId, context, orchestrator, userAgent.id)
      application <- {
        val choices = runtime.agentSummaries.map(summary =>
          ReplAgentChoice(summary.id, summary.agentType, summary.providerIds, summary.model)
        )
        def build(
            selection: Option[ReplSelectedAgent]
        ): IO[YallmoError, ReplApplication[Any]] =
          ZIO.succeed {
            val submission = assembled.submission(userAgent.id, selection.map(_.binding))
            val replOrchestrator = ReplOrchestratorLive.fromApplication(orchestrator, submission)
            new ReplApplication[Any](
              ReplSession.fromApplication(submission),
              availability,
              replOrchestrator,
              initialState = ReplState(logLevel = options.logLevel),
              activeAgent = selection.map(_.feedback),
              providerCatalog = Some(providerCatalog),
              providerLogin = Some(providerLogin),
              agentChoices = choices,
              agentSelector =
                Some(id => runtime.resolve(id).map(ReplSelectedAgent(_)).flatMap(value => build(Some(value)))),
              transcriber = voice,
              userId = userAgent.id
            )
          }
        build(selected)
      }
    } yield application
}

private[repl] object ReplStartup {

  val DefaultSystemPrompt = ApplicationRuntime.DefaultSystemPrompt

  /** Explicit trusted in-process audio selection; no default or provider/auth probe. */
  private[repl] def voice[R](
      runtime: ApplicationRuntime,
      transcriberAgentId: Option[AgentId],
      available: StaticContext[R],
      orchestrator: Orchestrator[Any],
      humanId: AgentId
  ): IO[YallmoError, Option[Transcriber]] =
    ZIO.foreach(transcriberAgentId)(id =>
      runtime.resolveTranscriber(id, available).map { case (agent, settings) =>
        Transcriber.make(agent, settings, orchestrator, humanId)
      }
    )

  /** The server loader owns the default file. Register the supplied local human before any assembly. */
  def make(options: ReplLaunchOptions): ZIO[Client, YallmoError, ReplStartup] =
    for {
      humanId <- identity(options)
      channel <- HumanTurnChannel.make
      human = HumanAgent.make(humanId, channel)
      loaded <- ApplicationRuntime.load(Chunk(human))
      (runtime, catalog) = loaded
      startup <- fromRuntime(runtime, catalog, human, options)
    } yield startup

  private def identity(options: ReplLaunchOptions): IO[YallmoError, AgentId] =
    ZIO.fromEither(
      AgentId.from(options.userName).left.map(_ => YallmoError.InvalidConfig("Invalid --user-name identity"))
    )

  /** Trusted deterministic fixture seam, never a config/path or remote transport boundary. */
  private[repl] def fromRuntime(
      runtime: ApplicationRuntime,
      catalog: ProviderCatalog,
      human: Agent[Any],
      options: ReplLaunchOptions
  ): ZIO[Client, YallmoError, ReplStartup] =
    for {
      humanId <- identity(options)
      _ <- ZIO.fail(YallmoError.InvalidConfig("Local human identity mismatch")).unless(human.id == humanId)
      selected <- ZIO.foreach(options.agentId)(id => runtime.resolve(id).map(ReplSelectedAgent(_)))
      client <- ZIO.service[Client]
      login = new ProviderLoginService(catalog, OpenAiCodexAuth.oauthFlow(client))
    } yield new ReplStartup(runtime, human, selected, catalog, login, options)
}
