package fmgp.yallmo.repl

import fmgp.yallmo.model.{Agent, AgentId, YallmoError}
import fmgp.yallmo.model.llm.{AudioInput, LlmProviderAudio, ProviderGenerateOptions, ThinkingLevel}
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.orchestration.config.{AgentConfig, Config, ProviderId}
import fmgp.yallmo.provider.Transcriber
import fmgp.yallmo.provider.openai.OpenAiCodexAuth
import zio.*
import zio.http.Client
import zio.json.*
import zio.json.ast.Json

/** Per-agent model settings. The same values drive native requests, the system context and footer metadata. */
private[repl] final case class ReplAgentSettings(
    model: String,
    systemPrompt: String,
    thinkingLevel: Option[ThinkingLevel],
    parallelToolCalls: Boolean
)

private[repl] object ReplAgentSettings {
  val DefaultSystemPrompt =
    "You are Yallmo, a concise terminal assistant. Answer the user's request concisely. Use an advertised tool only when it is needed."

  private final case class Fields(
      model: String,
      systemPrompt: String = DefaultSystemPrompt,
      thinkingLevel: Option[String] = None,
      parallelToolCalls: Boolean = false
  ) derives JsonCodec

  def decode(definition: AgentConfig): Either[YallmoError, ReplAgentSettings] =
    for {
      fields <- definition.config.toJson
        .fromJson[Fields]
        .left
        .map(_ =>
          YallmoError.InvalidConfig("Selected agent settings require a model and valid optional prompt/thinking fields")
        )
      _ <- Either.cond(
        fields.model.trim.nonEmpty && fields.systemPrompt.trim.nonEmpty,
        (),
        YallmoError.InvalidConfig("Selected agent model and systemPrompt must not be blank")
      )
      thinking <- fields.thinkingLevel match {
        case None        => Right(None)
        case Some(value) =>
          ThinkingLevel
            .parse(value)
            .map(Some(_))
            .left
            .map(_ => YallmoError.InvalidConfig("thinkingLevel must be minimal, low, medium or high"))
      }
    } yield ReplAgentSettings(fields.model.trim, fields.systemPrompt, thinking, fields.parallelToolCalls)
}

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

  /** Uses the selected connection for audio only if its adapter supports it, with the selected agent's explicit model.
    * There is no fallback to another provider or account.
    */
  def transcriber: IO[YallmoError, Transcriber] = transcriberFor(selected)

  private def transcriberFor(selection: Option[ReplSelectedAgent]): IO[YallmoError, Transcriber] =
    selection match {
      case None        => ZIO.succeed(ReplStartup.unavailableTranscriber)
      case Some(value) =>
        instances.getProvider(value.providerId).map {
          case audio: LlmProviderAudio =>
            new Transcriber(new LlmProviderAudio {
              override def inferenceWithAudio(
                  prompt: String,
                  input: AudioInput,
                  model: Option[String]
              ): IO[YallmoError, String] =
                audio.inferenceWithAudio(prompt, input, Some(model.getOrElse(value.program.model)))
            })
          case _ => ReplStartup.unavailableTranscriber
        }
    }

  /** Builds storage and a session. Unselected sessions expose commands but reject prompts without running tools. */
  def application(
      availableTools: Chunk[AgentTool[ReplTools.Environment]],
      availability: ReplToolAvailability
  ): IO[YallmoError, ReplApplication[ReplTools.Environment]] =
    for {
      orchestrator <- options.storageRoot match {
        case None       => OrchestratorLive.inMemory(instances)
        case Some(root) => OrchestratorLive.filesystem(root, instances)
      }
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
          transcriberFor(selection).map { audio =>
            val replOrchestrator = ReplOrchestratorLive.make(orchestrator, userAgent, selection.map(_.adapter))
            val context = StaticContext[ReplTools.Environment](
              selection.fold(ReplAgentSettings.DefaultSystemPrompt)(_.systemPrompt),
              tools = availableTools.map(_.definition)
            )
            new ReplApplication[ReplTools.Environment](
              new ReplSession[ReplTools.Environment](
                runner,
                selection.map(value => value.program.bind[ReplTools.Environment](value.adapter)),
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
              transcriber = Some(audio),
              userId = userAgent.id
            )
          }
        build(selected)
      }
    } yield application
}

private[repl] object ReplStartup {

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
      settings <- ZIO.fromEither(ReplAgentSettings.decode(definition))
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

  private[repl] val unavailableTranscriber: Transcriber = new Transcriber(new LlmProviderAudio {
    override def inferenceWithAudio(prompt: String, audio: AudioInput, model: Option[String]): IO[YallmoError, String] =
      ZIO.fail(
        YallmoError.InvalidConfig(
          "Voice transcription requires a selected audio-capable provider; no fallback is configured"
        )
      )
  })
}
