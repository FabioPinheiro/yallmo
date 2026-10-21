package fmgp.yallmo.repl

import fmgp.yallmo.model.{Agent, AgentId, YallmoError}
import fmgp.yallmo.model.llm.ProviderGenerateOptions
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.orchestration.config.{Config, ConfigFile, ConfiguredAgentSettings, ProviderId}
import fmgp.yallmo.repl.voice.Transcriber
import fmgp.yallmo.provider.openai.OpenAiCodexAuth
import zio.*
import zio.http.Client
import zio.json.ast.Json

private[repl] final case class ReplSelectedAgent(
    adapter: Agent[Any],
    providerId: ProviderId,
    program: NativeFunctionAgent[Agent[Any]],
    systemPrompt: String
) {
  def feedback: ReplActiveAgent = ReplActiveAgent.fromNative(providerId, program)
}

/** Configured startup and lazy agent selection, sharing runtime caches and session storage across selections. */
private[repl] final class ReplStartup private (
    val instances: RuntimeInstances[Any],
    val userAgent: Agent[Any],
    val selected: Option[ReplSelectedAgent],
    val providerCatalog: ReplProviderCatalog,
    val providerLogin: ProviderLoginService,
    options: ReplLaunchOptions,
    config: Config
) {

  /** Builds storage and a session. Unselected sessions expose commands but reject prompts without running tools. */
  def application(
      availableTools: Chunk[AgentTool[ReplTools.Environment]],
      availability: ReplToolAvailability
  ): IO[YallmoError, ReplApplication[ReplTools.Environment]] =
    for {
      context = StaticContext[ReplTools.Environment](
        ReplStartup.DefaultSystemPrompt,
        tools = availableTools.map(_.definition)
      )
      _ <- ZIO.fromEither(ConfigFile.validateCapabilities(config, context))
      orchestrator <- options.storageRoot match {
        case None       => OrchestratorLive.inMemory(instances)
        case Some(root) => OrchestratorLive.filesystem(root, instances)
      }
      voice <- ReplStartup.voice(config, instances, context, orchestrator, userAgent.id)
      application <- {
        val runner = new AgentRunner[ReplTools.Environment](
          availableTools.map(tool => tool.definition.key -> tool).toMap,
          Map.empty
        )
        val choices = config.agents.toVector
          .sortBy(pair => AgentId.value(pair._1))
          .map { case (id, definition) =>
            ReplAgentChoice(
              id,
              definition.agentType,
              definition.providerIds.map(ProviderId.value).toVector,
              model = definition.config.fields
                .collectFirst { case ("model", Json.Str(value)) => value }
                .getOrElse("<invalid model>")
            )
          }
        def build(selection: Option[ReplSelectedAgent]): IO[YallmoError, ReplApplication[ReplTools.Environment]] =
          ZIO.succeed {
            val replOrchestrator = ReplOrchestratorLive.make(orchestrator, userAgent, selection.map(_.adapter))
            val context = StaticContext[ReplTools.Environment](
              selection.fold(ReplStartup.DefaultSystemPrompt)(_.systemPrompt),
              tools = availableTools.map(_.definition)
            )
            new ReplApplication[ReplTools.Environment](
              new ReplSession[ReplTools.Environment](
                runner,
                selection.map(value => value.program.bind[ReplTools.Environment](value.adapter, orchestrator.readBlob)),
                context,
                afterSubmit = replOrchestrator.persistTurn
              ),
              availability,
              replOrchestrator,
              activeAgent = selection.map(_.feedback),
              providerCatalog = Some(providerCatalog),
              providerLogin = Some(providerLogin),
              agentChoices = choices,
              agentSelector =
                Some(id => ReplStartup.resolve(config, instances, id).flatMap(value => build(Some(value)))),
              transcriber = voice,
              userId = userAgent.id
            )
          }
        build(selected)
      }
    } yield application
}

private[repl] object ReplStartup {

  val DefaultSystemPrompt =
    "You are Yallmo, a concise terminal assistant. Answer the user's request concisely. Use an advertised tool only when it is needed."

  /** Resolves the ordinary configured audio agent and applies REPL voice policy without inference or auth probes. */
  private[repl] def voice[R](
      config: Config,
      instances: RuntimeInstances[Any],
      available: StaticContext[R],
      orchestrator: Orchestrator[Any],
      humanId: AgentId
  ): IO[YallmoError, Option[Transcriber]] =
    ZIO.foreach(config.transcriberAgent) { id =>
      val checked = for {
        definition <- ZIO
          .fromOption(config.agents.get(id))
          .orElseFail(YallmoError.InvalidConfig("transcriberAgent must reference a configured agent"))
        settings <- ZIO.fromEither(ConfiguredAgentSettings.decode(definition))
        context <- ZIO.fromEither(definition.capabilities.resolve(id, available))
        _ <- ZIO
          .fail(
            YallmoError.InvalidConfig(
              "Transcription requires effectively empty capabilities.tools/retrievers/skills/extensions; explicitly set each allowlist to [] to disable inherited capabilities"
            )
          )
          .unless(
            context.tools.isEmpty && context.retrievers.isEmpty && context.skills.isEmpty && context.extensions.isEmpty
          )
        agent <- instances.getAudioAgent(id)
      } yield Transcriber.make(agent, settings, orchestrator, humanId)
      checked.mapError(error =>
        YallmoError.InvalidConfig(s"transcriberAgent '${AgentId.value(id)}': ${error.getMessage}")
      )
    }

  /** Registers the configured human identity and resolves only the CLI/default agent, without inference or auth.
    *
    * @param config
    *   loaded immutable catalog, including the human name and optional startup preference
    * @param options
    *   CLI agent selection takes precedence over defaultAgentId; absent selections remain unselected
    */
  def make(config: Config, options: ReplLaunchOptions): ZIO[Client, YallmoError, ReplStartup] =
    for {
      humanId <- ZIO.fromEither(
        AgentId.from(config.userName).left.map(_ => YallmoError.InvalidConfig("userName must not be blank"))
      )
      channel <- HumanTurnChannel.make
      human = HumanAgent.make(humanId, channel)
      instances <- RuntimeInstances.make(config, Chunk(human))
      selected <- ZIO.foreach(options.agentId.orElse(config.defaultAgentId))(resolve(config, instances, _))
      catalog <- ReplProviderCatalog.make(options.configPath)
      client <- ZIO.service[Client]
      login = new ProviderLoginService(catalog, OpenAiCodexAuth.oauthFlow(client))
    } yield new ReplStartup(instances, human, selected, catalog, login, options, config)

  private def resolve(
      config: Config,
      instances: RuntimeInstances[Any],
      id: AgentId
  ): IO[YallmoError, ReplSelectedAgent] =
    for {
      definition <- ZIO.fromOption(config.agents.get(id)).orElseFail(YallmoError.UnknownAgent(id))
      settings <- ZIO
        .fromEither(ConfiguredAgentSettings.decode(definition, defaultSystemPrompt = Some(DefaultSystemPrompt)))
        .mapError(error => YallmoError.InvalidConfig(s"Agent '${AgentId.value(id)}': ${error.getMessage}"))
      adapter <- instances.getAgent(id)
      providerId <- ZIO
        .fromOption(definition.providerIds.headOption)
        .orElseFail(YallmoError.InvalidConfig("Selected agent requires one provider"))
    } yield ReplSelectedAgent(
      adapter,
      providerId,
      NativeFunctionAgent[Agent[Any]](
        id,
        settings.model,
        ProviderGenerateOptions(responseMimeType = None, thinkingLevel = settings.thinkingLevel),
        settings.parallelToolCalls
      ),
      settings.systemPrompt
    )

}
