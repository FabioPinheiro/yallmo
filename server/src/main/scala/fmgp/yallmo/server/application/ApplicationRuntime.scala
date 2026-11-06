package fmgp.yallmo.server.application

import fmgp.yallmo.model.{Agent, AgentId, AudioAgent, Message, MessageId, YallmoError}
import fmgp.yallmo.model.llm.{LlmProvider, ProviderGenerateOptions}
import fmgp.yallmo.orchestration.*
import fmgp.yallmo.orchestration.config.{AgentConfig, Config, ConfigFile, ConfiguredAgentSettings, ProviderId}
import java.nio.file.Path
import zio.*
import fmgp.rpc.RpcWebSocket
import fmgp.yallmo.fs.FsRead
import fmgp.yallmo.tool.sdlc.SdlcService
import fmgp.yallmo.tool.{ScalaTool, ReadFileTool, ListFilesTool}
import fmgp.yallmo.model.ToolKey
import zio.http.Client
import zio.json.ast.Json

/** Application-owned configuration snapshot and lazy registry with explicit live ChatGPT credential replacement.
  * Construction does not open storage or run agents; other configuration changes still require a new runtime.
  */
final class ApplicationRuntime private (
    config: Config,
    val instances: RuntimeInstances[Any],
    storageRoot: Option[Path]
) {
  import ApplicationRuntime.*

  def registerUser(id: AgentId): IO[YallmoError, Unit] = instances.registerUser(id)
  def isUser(id: AgentId): UIO[Boolean] = instances.isUser(id)

  val browserAudioPort: Int = config.browserAudioPort

  /** Display projection only: no provider definitions, credentials, prompts or filesystem roots. */
  def agentSummaries: Vector[AgentSummary] =
    config.agents.toVector.sortBy(pair => AgentId.value(pair._1)).map { case (id, definition) =>
      AgentSummary(
        id,
        displayText(definition.agentType),
        definition.providerIds.map(value => displayText(ProviderId.value(value))).toVector,
        definition.config.fields.collectFirst {
          case ("model", Json.Str(value)) if value.trim.nonEmpty => displayText(value.trim)
        }
      )
    }

  /** Trusted in-process audio adapter only; this is not a wire value or remote execution API. */
  def resolveTranscriber[R](
      id: AgentId,
      available: StaticContext[R]
  ): IO[YallmoError, (AudioAgent[Any], ConfiguredAgentSettings)] = {
    val checked = for {
      definition <- ZIO
        .fromOption(config.agents.get(id))
        .orElseFail(YallmoError.InvalidConfig("Transcriber must reference a configured agent"))
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
      agent <- instances
        .getAudioAgent(id)
        .mapError(_ => YallmoError.InvalidConfig("Selected agent requires an audio-capable agent and provider"))
    } yield (agent, settings)
    checked.mapError(error =>
      YallmoError.InvalidConfig(s"Transcriber '${displayText(AgentId.value(id))}': ${displayText(error.getMessage)}")
    )
  }

  /** Resolves an explicit selection without changing other callers' selections or invoking a provider. Logs loading
    * failures here, before the RPC boundary replaces details with a sanitized client error.
    */
  def resolve(id: AgentId): IO[YallmoError, ConfiguredAgentBinding] =
    ZIO
      .fromOption(config.agents.get(id))
      .orElseFail(YallmoError.UnknownAgent(id))
      .flatMap { definition =>
        definition.agentType match {
          case "OAIAgent" | "ChatGPTAgent" | "CoachAgent" => resolveNative(definition, id)
          case "FolderStructureAgent"                     =>
            instances.getAgentWithFilesystem(id).map { case (adapter, fs) =>
              ConfiguredAgentBinding.Folder(adapter, definition.capabilities, fs)
            }
          case name =>
            ZIO.fail(
              YallmoError.InvalidConfig(
                s"Unsupported agentType ($name); expected OAIAgent, ChatGPTAgent, CoachAgent or FolderStructureAgent"
              )
            )
        }
      }
      .tapError(error =>
        ZIO.logError(s"Unable to load agent '${displayText(AgentId.value(id))}': ${displayText(error.getMessage)}")
      )

  /** Uses the server factory's storage choice, in memory by default, with the same workspace scope. */
  def openWorkspace(
      workspace: Path
  ): ZIO[Scope, YallmoError, (ApplicationRuntime.Assembly[Any], ToolAvailability)] =
    openWorkspace(storageRoot, workspace)

  /** Opens workspace tools in the application's scope. The returned assembly must not outlive that scope. */
  def openWorkspace(
      storageRoot: Option[Path],
      workspace: Path
  ): ZIO[Scope, YallmoError, (ApplicationRuntime.Assembly[Any], ToolAvailability)] =
    openWorkspace(storageRoot, workspace, ApplicationTools.live)

  private[application] def openWorkspace(
      storageRoot: Option[Path],
      workspace: Path,
      services: ULayer[ApplicationTools.Environment],
      sharedSdlc: Option[SdlcService] = None
  ): ZIO[Scope, YallmoError, (ApplicationRuntime.Assembly[Any], ToolAvailability)] =
    openWorkspaceConfigured(storageRoot, workspace, services, None, sharedSdlc)

  /** Enables filesystem effects only when the caller supplies its own live route and trusted endpoint. */
  private[application] def openWorkspaceRpc(
      storageRoot: Option[Path],
      workspace: Path,
      services: ULayer[ApplicationTools.Environment],
      endpoint: String,
      sharedSdlc: Option[SdlcService] = None
  ): ZIO[Scope & RpcWebSocket.RpcRoute, YallmoError, (ApplicationRuntime.Assembly[Any], ToolAvailability)] =
    ZIO.serviceWithZIO[RpcWebSocket.RpcRoute] { route =>
      openWorkspaceConfigured(storageRoot, workspace, services, Some(route -> endpoint), sharedSdlc)
    }

  private def openWorkspaceConfigured(
      storageRoot: Option[Path],
      workspace: Path,
      services: ULayer[ApplicationTools.Environment],
      rpc: Option[(RpcWebSocket.RpcRoute, String)],
      sharedSdlc: Option[SdlcService]
  ): ZIO[Scope, YallmoError, (ApplicationRuntime.Assembly[Any], ToolAvailability)] =
    for {
      environment <- services.build
      startup <- ApplicationTools.evaluate(workspace, storageRoot, sharedSdlc).provideEnvironment(environment)
      tools = startup.available.map { tool =>
        new AgentTool[Any] {
          override val definition: AgentToolDefinition = tool.definition
          override def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
            tool.execute(call).provideEnvironment(environment)
        }
      }
      assembled <- open[Any](storageRoot, tools)
    } yield (
      new Assembly(
        assembled.orchestrator,
        tools,
        StaticContext[Any](DefaultSystemPrompt, tools = tools.map(_.definition)),
        startup.filesystem,
        rpc.map { case (route, endpoint) => (environment.add[RpcWebSocket.RpcRoute](route), endpoint) }
      ),
      startup.availability
    )

  /** Validates available capabilities before opening the single storage owner and assembling its runner. */
  def open[R](
      storageRoot: Option[Path],
      availableTools: Chunk[AgentTool[R]]
  ): IO[YallmoError, ApplicationRuntime.Assembly[R]] =
    for {
      context = StaticContext[R](ApplicationRuntime.DefaultSystemPrompt, tools = availableTools.map(_.definition))
      _ <- ZIO.foreachDiscard(config.agents.toList) { case (id, definition) =>
        val catalog =
          if definition.agentType == "CoachAgent" then
            context.copy(tools = context.tools ++ fmgp.yallmo.agents.CoachApplicationTools.Definitions)
          else context
        ZIO.fromEither(definition.capabilities.resolve(id, catalog))
      }
      orchestrator <- storageRoot match {
        case None       => OrchestratorLive.inMemory(instances)
        case Some(root) => OrchestratorLive.filesystem(root, instances)
      }
    } yield new ApplicationRuntime.Assembly(
      orchestrator,
      availableTools,
      context
    )

  private def resolveNative(definition: AgentConfig, id: AgentId): IO[YallmoError, ConfiguredAgentBinding] =
    for {
      defaultModel <-
        if definition.agentType == "CoachAgent" && !definition.config.fields.exists(_._1 == "model") then
          definition.providerIds.toList match {
            case providerId :: Nil =>
              instances.getProvider(providerId).flatMap {
                case provider: LlmProvider => ZIO.succeed(Option(provider.defaultModel).filter(_.trim.nonEmpty))
                case _                     => ZIO.fail(YallmoError.InvalidConfig("CoachAgent requires an LLM provider"))
              }
            case _ => ZIO.fail(YallmoError.InvalidConfig("CoachAgent requires exactly one provider id"))
          }
        else ZIO.succeed(None)
      settings <- ZIO
        .fromEither(
          ConfiguredAgentSettings.decode(
            definition,
            defaultSystemPrompt = Some(ApplicationRuntime.DefaultSystemPrompt),
            defaultModel = defaultModel
          )
        )
        .mapError(error => YallmoError.InvalidConfig(s"Agent '${AgentId.value(id)}': ${error.getMessage}"))
      entry <- instances.getAgentWithFilesystem(id)
      (adapter, filesystem) = entry
      providerId <- ZIO
        .fromOption(definition.providerIds.headOption)
        .orElseFail(YallmoError.InvalidConfig("Selected agent requires one provider"))
    } yield ConfiguredAgentBinding.Native(
      adapter,
      providerId,
      NativeFunctionAgent[Agent[Any]](
        id,
        settings.model,
        ProviderGenerateOptions(responseMimeType = None, thinkingLevel = settings.thinkingLevel),
        settings.parallelToolCalls
      ),
      settings.systemPrompt,
      filesystem
    )
}

object ApplicationRuntime {
  val DefaultConfigPath: Path = Path.of("yallmo.json")

  /** IDs retain lookup identity; callers must sanitize IDs when rendering, as with catalog entries. Model reports only
    * a non-blank explicit setting; None does not imply a default, validation result or applicability.
    */
  final case class AgentSummary(id: AgentId, agentType: String, providerIds: Vector[String], model: Option[String])

  /** Server-owned file boundary. The captured HTTP client must outlive the returned runtime. */
  def load(
      registeredAgents: Chunk[Agent[Any]],
      configPath: Path = DefaultConfigPath,
      storageRoot: Option[Path] = None
  ): ZIO[Client, YallmoError, (ApplicationRuntime, ProviderCatalog)] =
    for {
      config <- ConfigFile.loadOrInitialize(configPath)
      runtime <- make(config, registeredAgents, storageRoot)
      catalog <- ProviderCatalog.make(configPath)
    } yield (runtime, catalog)

  private def displayText(value: String): String = value.map { char =>
    if Character.isISOControl(char) || Set(
        Character.FORMAT.toInt,
        Character.LINE_SEPARATOR.toInt,
        Character.PARAGRAPH_SEPARATOR.toInt
      ).contains(Character.getType(char))
    then '?'
    else char
  }

  val DefaultSystemPrompt =
    "You are Yallmo, a concise terminal assistant. Answer the user's request concisely. Use an advertised tool only when it is needed."

  /** Registers trusted in-process agents, such as the legacy local human adapter. The HTTP client must outlive use. */
  def make(
      config: Config,
      registeredAgents: Chunk[Agent[Any]],
      storageRoot: Option[Path] = None
  ): ZIO[Client, YallmoError, ApplicationRuntime] =
    RuntimeInstances
      .make(config, registeredAgents)
      .map(instances => new ApplicationRuntime(config, instances, storageRoot))

  /** One opened runtime, reused across agent selections. Neither selecting nor deriving context opens storage again. */
  final class Assembly[R] private[application] (
      val orchestrator: OrchestratorLive[Any],
      availableTools: Chunk[AgentTool[R]],
      availableContext: StaticContext[R],
      workspaceFilesystem: Option[FsRead] = None,
      scalaRpc: Option[(ZEnvironment[ApplicationTools.Environment & RpcWebSocket.RpcRoute], String)] = None
  ) {
    def context(selection: Option[ConfiguredAgentBinding]): StaticContext[R] =
      availableContext.copy(
        systemPrompt = selection.fold(DefaultSystemPrompt)(_.systemPrompt),
        tools = selectedTools(selection).map(_.definition)
      )

    private def selectedTools(selection: Option[ConfiguredAgentBinding]): Chunk[AgentTool[R]] = {
      val base = scalaRpc match {
        case None                          => availableTools
        case Some((environment, endpoint)) =>
          val filesystem = selection.flatMap(_.filesystem).orElse(workspaceFilesystem)
          availableTools.map { tool =>
            filesystem match {
              case Some(fs) if tool.definition.key == ToolKey.ReadFile =>
                ApplicationTools.bindFilesystem(new ReadFileTool, fs)
              case Some(fs) if tool.definition.key == ToolKey.ListFiles =>
                ApplicationTools.bindFilesystem(new ListFilesTool, fs)
              case Some(fs) if tool.definition.key == ToolKey.Scalamode =>
                val selected = ScalaTool.filesystem(endpoint)
                new AgentTool[Any] {
                  override val definition = selected.definition
                  override def execute(call: ExternalToolCall): IO[YallmoError, ExternalToolResult] =
                    selected.execute(call).provideEnvironment(environment.add[FsRead](fs))
                }
              case _ => tool
            }
          }
      }
      // Local tools replace matching keys; the context and runner share exactly one definition per key.
      val local = localTools(selection)
      base.filterNot(tool => local.exists(_.definition.key == tool.definition.key)) ++ local
    }

    private def localTools(selection: Option[ConfiguredAgentBinding]): Chunk[AgentTool[Any]] =
      selection.fold(Chunk.empty[AgentTool[Any]]) { binding =>
        binding.adapter match {
          case scoped: HasLocalAgentTools => scoped.localTools
          case _                          => Chunk.empty
        }
      }

    /** Invokes one message, never an agent loop; model-requested actions require a session submission. */
    def invoke(input: Message, selection: ConfiguredAgentBinding): ZIO[R, YallmoError, Message] = {
      val selected = Some(selection)
      orchestrator.invoke(
        input,
        runner(selected).invocation(selection.bind[R](id => orchestrator.readBlob(id)), context(selected))
      )
    }

    def submitBranch(
        author: AgentId,
        parent: MessageId,
        prompt: String,
        selection: ConfiguredAgentBinding
    ): ZIO[R, BranchSubmitFailure, BranchTurn] = {
      val selected = Some(selection)
      orchestrator.submitBranch(
        parent,
        author,
        AgentTask(prompt),
        SessionSubmission.branchExecution(
          runner(selected),
          selection.bind[R](id => orchestrator.readBlob(id)),
          context(selected)
        )
      )
    }

    private def runner(selection: Option[ConfiguredAgentBinding]): AgentRunner[R] =
      new AgentRunner[R](
        selectedTools(selection).map(tool => tool.definition.key -> tool).toMap,
        Map.empty
      )

    /** Builds an immutable selection over this same registry, storage and tool runner. */
    def submission(authorId: AgentId, selection: Option[ConfiguredAgentBinding]): SessionSubmission[R] =
      SessionSubmission.configured(
        orchestrator,
        authorId,
        runner(selection),
        selection.map(_.bind[R](id => orchestrator.readBlob(id))),
        context(selection)
      )
  }
}
