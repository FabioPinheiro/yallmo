package fmgp.yallmo.repl

import fmgp.yallmo.server.application.ProviderCatalog
import fmgp.yallmo.model.{Agent, AgentId, YallmoError}
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.server.application.ServerApplication
import fmgp.yallmo.repl.voice.Transcriber
import fmgp.yallmo.provider.openai.OpenAiCodexAuth
import zio.*
import zio.http.Client

/** Retained embedded application adapter for feature integration and tests; not a terminal startup path. */
private[repl] final class ReplStartup private (
    applicationOwner: ServerApplication,
    val userAgent: Agent[Any],
    val selected: Option[ReplSelectedAgent],
    val providerCatalog: ProviderCatalog,
    val providerLogin: ProviderLoginService,
    options: ReplLaunchOptions,
    transcriberAgentId: Option[AgentId]
) {
  val browserAudioPort: Int = applicationOwner.browserAudioPort

  val catalog: AgentCatalog[Any] = applicationOwner.catalog

  /** Adapts the already-open application and its session storage. Unselected sessions expose commands but reject
    * prompts without running tools.
    */
  def application: ZIO[Scope, YallmoError, ReplApplication[Any]] =
    for {
      context = applicationOwner.context(None)
      orchestrator = applicationOwner.master
      voice <- ReplStartup.voice(applicationOwner, transcriberAgentId, context, orchestrator, userAgent.id)
      application <- {
        val choices = applicationOwner.agentSummaries.map(summary =>
          ReplAgentChoice(summary.id, summary.agentType, summary.providerIds, summary.model)
        )
        def build(
            selection: Option[ReplSelectedAgent]
        ): IO[YallmoError, ReplApplication[Any]] =
          ZIO.succeed {
            val replOrchestrator = ReplOrchestratorLive.make(orchestrator, userAgent, selection.map(_.adapter))
            new ReplApplication[Any](
              ReplSession.resolved(selection.map(_.binding)),
              applicationOwner.availability,
              replOrchestrator,
              initialState = ReplState(logLevel = options.logLevel),
              activeAgent = selection.map(_.feedback),
              providerCatalog = Some(providerCatalog),
              providerLogin = Some(providerLogin),
              agentChoices = choices,
              agentSelector = Some(id =>
                applicationOwner.catalog.resolve(id).map(ReplSelectedAgent(_)).flatMap(value => build(Some(value)))
              ),
              transcriber = voice,
              userId = userAgent.id
            )
          }
        build(selected)
      }
    } yield application
}

private[repl] object ReplStartup {

  val DefaultSystemPrompt = ServerApplication.DefaultSystemPrompt

  /** Explicit trusted in-process audio selection; no default or provider/auth probe. */
  private[repl] def voice[R](
      applicationOwner: ServerApplication,
      transcriberAgentId: Option[AgentId],
      available: StaticContext[R],
      orchestrator: Orchestrator[Any],
      userId: AgentId
  ): IO[YallmoError, Option[Transcriber]] =
    ZIO.foreach(transcriberAgentId)(id =>
      applicationOwner.resolveTranscriber(id, available).map { case (agent, settings) =>
        Transcriber.make(agent, settings, orchestrator, userId)
      }
    )

  private def identity(options: ReplLaunchOptions): IO[YallmoError, AgentId] =
    ZIO.fromEither(
      AgentId.from(options.userName).left.map(_ => YallmoError.InvalidConfig("Invalid --user-name identity"))
    )

  /** Trusted deterministic fixture seam, never a config/path or remote transport boundary. */
  private[repl] def fromApplication(
      applicationOwner: ServerApplication,
      catalog: ProviderCatalog,
      human: Agent[Any],
      options: ReplLaunchOptions,
      transcriberAgentId: Option[AgentId] = None
  ): ZIO[Client, YallmoError, ReplStartup] =
    for {
      userId <- identity(options)
      _ <- ZIO.fail(YallmoError.InvalidConfig("Local human identity mismatch")).unless(human.id == userId)
      selected <- ZIO.foreach(options.agentId)(id => applicationOwner.resolve(id).map(ReplSelectedAgent(_)))
      client <- ZIO.service[Client]
      login = new ProviderLoginService(catalog, OpenAiCodexAuth.oauthFlow(client))
    } yield new ReplStartup(applicationOwner, human, selected, catalog, login, options, transcriberAgentId)
}
